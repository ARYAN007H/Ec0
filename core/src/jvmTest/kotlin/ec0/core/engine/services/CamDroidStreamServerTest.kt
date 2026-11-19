/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM tests for the phone-side CamDroid v2 stream server: token-gated arming,
 * v2 session negotiation (hello → capabilities → start → accepted), frame
 * round-trip over real sockets, bounded-slot drop policy, stop-on-silence,
 * arm TTL expiry, and clean stop/disarm.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.services.camdroid.CamDroidSessionMessages
import ec0.core.engine.services.camdroid.CamDroidV2
import ec0.core.engine.services.camdroid.CamDroidVideoMode
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CamDroidStreamServerTest {

    /** Deterministic H.264 frame source the tests drive manually. */
    private class FakeFrameSource : CamDroidH264FrameSource {
        val closed = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val modes = listOf(
            CamDroidVideoMode(1920, 1080, 30, 1_000_000L, 50_000_000L),
            CamDroidVideoMode(1280, 720, 30, 800_000L, 30_000_000L),
        )
        private var sink: CamDroidH264Sink? = null

        override fun supportedModes(): List<CamDroidVideoMode> = modes

        override fun start(mode: CamDroidVideoMode, bitrateBps: Long, sink: CamDroidH264Sink, onError: (String) -> Unit) {
            started.set(true)
            this.sink = sink
        }

        fun push(data: ByteArray, isConfig: Boolean = false, isKey: Boolean = false) {
            sink?.onEncoded(data, isConfig, isKey, 0L)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun readPacket(input: DataInputStream): CamDroidV2.Packet = CamDroidV2.readPacket(input)

    /** Reads packets until one of [kind] arrives (heartbeats interleave freely). */
    private fun readUntil(input: DataInputStream, kind: Int, timeoutMs: Long = 3_000): CamDroidV2.Packet? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val packet = runCatching { readPacket(input) }.getOrNull() ?: return null
            if (packet.kind == kind) return packet
        }
        return null
    }

    /** Polls [predicate] until true or [timeoutMs] elapses. */
    private fun await(timeoutMs: Long = 4_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    /**
     * Full v2 client session: connect → hello → capabilities → start →
     * accepted. [token] null = the hello lacks the ec0| prefix (rejection path);
     * [fps] deliberately un-advertised (60) to prove the server clamps.
     */
    private fun connectClient(
        port: Int,
        token: String?,
        fps: Int = 60,
    ): Triple<Socket, DataInputStream, CamDroidVideoMode?> {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        val out = socket.getOutputStream()
        val clientField = if (token != null) "ec0|$token" else "camdroid-desktop"
        CamDroidV2.Packet(
            kind = CamDroidV2.KIND_METADATA,
            flags = 0,
            sessionId = 0,
            sequence = 0,
            captureTimestampNs = 0,
            payload = CamDroidSessionMessages.hello(clientField).toString().encodeToByteArray(),
        ).writeTo(out)
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val caps = readUntil(input, CamDroidV2.KIND_METADATA) ?: return Triple(socket, input, null)
        val capabilities = CamDroidCapabilities.fromJson(
            CamDroidSessionMessages.parseType(caps.payload)!!.second,
        )
        val mode = CamDroidModeResolver.resolve(capabilities, "h264", 1920, 1080, fps)
            ?: capabilities.videoModes.firstOrNull()
        if (mode != null) {
            CamDroidV2.Packet(
                kind = CamDroidV2.KIND_METADATA,
                flags = 0,
                sessionId = 0,
                sequence = 0,
                captureTimestampNs = 0,
                payload = CamDroidSessionMessages.start("h264", mode.width, mode.height, mode.fps, 8_000_000L, false)
                    .toString().encodeToByteArray(),
            ).writeTo(out)
        }
        return Triple(socket, input, mode)
    }

    private fun newServer(
        source: FakeFrameSource,
        port: Int = 0,
        armTtlMs: Long = 1_500,
        silenceMs: Long = 700,
        heartbeatMs: Long = 200,
    ): CamDroidStreamServer = CamDroidStreamServer(
        frameSourceFactory = { source },
        port = port,
        armTtlMs = armTtlMs,
        clientSilenceTimeoutMs = silenceMs,
        heartbeatIntervalMs = heartbeatMs,
    )

    // ---- happy path --------------------------------------------------------

    @Test
    fun streamsTokenAuthenticatedH264ToClient() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("aabbccdd11223344")
        assertTrue(armed is CamDroidArmResult.Armed)
        assertEquals(CamDroidServerState.ARMED, server.snapshot.state)

        val (client, input, mode) = connectClient((armed as CamDroidArmResult.Armed).port, "aabbccdd11223344")
        assertTrue(mode != null, "the phone's capabilities must include a startable mode")
        // THE A03 LESSON: the client asked for 60fps, the phone advertises 30 —
        // the accepted mode must be clamped to an advertised one.
        assertEquals(30, mode!!.fps, "the accepted mode must be clamped to the advertised fps")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })

        // A config bundle (SPS/PPS) then one paced NAL make it through.
        val spsPps = ByteArray(32) { 0x67 }
        source.push(spsPps, isConfig = true)
        val vconfig = readUntil(input, CamDroidV2.KIND_VIDEO_CONFIG)
        assertTrue(vconfig?.flags?.and(CamDroidV2.FLAG_CONFIGURATION) != 0, "config must ride the CONFIGURATION flag")

        val nal = ByteArray(64) { it.toByte() }
        source.push(nal, isKey = true)
        val video = readUntil(input, CamDroidV2.KIND_VIDEO)
        assertTrue(video?.flags?.and(CamDroidV2.FLAG_KEY_FRAME) != 0, "key frames must ride the KEY_FRAME flag")
        assertTrue(video?.payload?.contentEquals(nal) == true)

        // Heartbeats keep the desktop watchdog fed between frames.
        source.push(ByteArray(16))
        val sawHeartbeat = await {
            var hb = false
            runCatching { if (readPacket(input).kind == CamDroidV2.KIND_HEARTBEAT) hb = true }
            hb
        }
        assertTrue(sawHeartbeat, "expected at least one HEARTBEAT packet")

        assertTrue(server.snapshot.framesSent >= 1)
        client.close()
        server.close()
    }

    // ---- auth ----------------------------------------------------------------

    @Test
    fun rejectsWrongTokenAndDisarms() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("correct-token")
        assertTrue(armed is CamDroidArmResult.Armed)

        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "evil-token")

        assertTrue(
            await {
                server.snapshot.reason?.contains("token rejected") == true ||
                    server.snapshot.reason?.contains("handshake failed") == true
            },
            "server should record an honest rejection reason, got: ${server.snapshot.reason}",
        )
        // The socket is closed without any media: reading hits EOF.
        val eof = runCatching { readPacket(DataInputStream(client.getInputStream().buffered())) }
        assertTrue(eof.isFailure, "bad-token client must receive no stream data")
        // Armed window is gone after a rejected handshake.
        assertEquals(CamDroidServerState.IDLE, server.snapshot.state)
        client.close()
        server.close()
    }

    @Test
    fun refusesToArmWithoutToken() {
        val server = newServer(FakeFrameSource())
        val result = server.arm("   ")
        assertTrue(result is CamDroidArmResult.Failed)
        assertTrue((result as CamDroidArmResult.Failed).reason.contains("token"))
    }

    // ---- stop semantics --------------------------------------------------------

    @Test
    fun clientSilenceAutoStopsAndClosesCapture() {
        val source = FakeFrameSource()
        val server = newServer(source, silenceMs = 500)
        val armed = server.arm("tok")
        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")

        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })
        // Send nothing at all: the silence watchdog must end the session.
        assertTrue(
            await(3_000) { server.snapshot.state == CamDroidServerState.ERROR },
            "silent client must trip the watchdog",
        )
        assertTrue(server.snapshot.reason?.contains("silent") == true)
        assertTrue(source.closed.get(), "capture must stop when the client goes silent")
        client.close()
        server.close()
    }

    @Test
    fun desktopVanishingEndsSessionToo() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("tok")
        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })
        client.close() // vanish mid-stream
        assertTrue(
            await(3_000) { source.closed.get() },
            "a vanished desktop must stop the capture",
        )
        server.close()
    }

    // ---- arming lifecycle --------------------------------------------------------

    @Test
    fun armTtlExpiresWithoutClient() {
        val source = FakeFrameSource()
        val server = newServer(source, armTtlMs = 400)
        val armed = server.arm("tok")
        val port = (armed as CamDroidArmResult.Armed).port

        assertTrue(await(3_000) { server.snapshot.state == CamDroidServerState.IDLE })
        // The listening socket is gone: a fresh connection must be refused.
        val refused = runCatching {
            val probe = Socket()
            probe.connect(InetSocketAddress("127.0.0.1", port), 500)
            probe.close()
            true
        }
        assertTrue(refused.isFailure || !refused.getOrDefault(true), "socket must be closed after TTL expiry")
        assertEquals(CamDroidServerState.IDLE, server.snapshot.state)
        server.close()
    }

    @Test
    fun disarmClosesLiveSessionAndSocket() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("tok")
        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })

        server.disarm()
        assertTrue(await { source.closed.get() })
        assertEquals(CamDroidServerState.IDLE, server.snapshot.state)
        client.close()
        server.close()
    }

    // ---- bounded slot --------------------------------------------------------

    @Test
    fun boundedSlotDropsFramesSlowConsumersNeverQueue() {
        val source = FakeFrameSource()
        // 5 fps pacing (200ms interval) vs a producer pushing 40 NALs at once.
        val server = CamDroidStreamServer(
            frameSourceFactory = { source },
            port = 0,
            armTtlMs = 1_500,
            clientSilenceTimeoutMs = 700,
            heartbeatIntervalMs = 150,
        )
        val armed = server.arm("tok")
        val (client, input, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })

        val pushed = 40
        repeat(pushed) { i -> source.push(byteArrayOf(i.toByte())) }

        var received = 0
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && received < pushed) {
            val packet = runCatching { readPacket(input) }.getOrNull() ?: break
            if (packet.kind == CamDroidV2.KIND_VIDEO) received++
            if (server.droppedFrameCount() > 0 && received >= 1) break
        }
        assertTrue(server.droppedFrameCount() > 0, "over-pushed frames must be dropped, not queued")
        assertTrue(received < pushed, "consumer must receive far fewer frames than pushed")
        client.close()
        server.close()
    }

    // ---- payload bounds --------------------------------------------------------

    @Test
    fun oversizedFramesAreDroppedNotStreamed() {
        val source = FakeFrameSource()
        val server = CamDroidStreamServer(
            frameSourceFactory = { source },
            port = 0,
            maxFrameBytes = 128,
            heartbeatIntervalMs = 150,
        )
        val armed = server.arm("tok")
        val (client, input, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })

        source.push(ByteArray(256)) // over the 128-byte guard
        assertEquals(1L, server.droppedFrameCount())

        val small = ByteArray(8)
        source.push(small)
        val video = readUntil(input, CamDroidV2.KIND_VIDEO)
        assertTrue(video?.payload?.contentEquals(small) == true)
        client.close()
        server.close()
    }

    // ---- legacy handshake codec (coordinator's transport) remains valid -----

    @Test
    fun wireControlJsonRoundTripsThroughWireParser() {
        // The legacy TCP control plane speaks CamDroid wire JSON — the phone
        // server must parse it, not the engine-envelope format.
        val zoomPacket = CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.SetZoom(250L))
        val parsedZoom = CamDroidControlCommand.fromCamDroidWireJson(zoomPacket.payload.decodeToString())
        assertEquals(CamDroidControlCommand.SetZoom(250L), parsedZoom)

        val stop = CamDroidControlCommand.fromCamDroidWireJson("""{"cmd":"stop"}""")
        assertEquals(CamDroidControlCommand.Stop, stop)

        val switch = CamDroidControlCommand.fromCamDroidWireJson("""{"cmd":"switch_camera"}""")
        assertEquals(CamDroidControlCommand.SwitchCamera, switch)

        // Garbage never crashes the stream.
        assertEquals(null, CamDroidControlCommand.fromCamDroidWireJson("not json"))
        assertEquals(null, CamDroidControlCommand.fromCamDroidWireJson("""{"cmd":"launch_missiles"}"""))
    }
}
