/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM tests for the phone-side CamDroid stream server: token-gated arming,
 * handshake rejection, frame round-trip over real sockets, bounded-slot drop
 * policy, stop-on-silence, arm TTL expiry, and clean stop/disarm.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CamDroidStreamServerTest {

    /** Deterministic frame source the tests drive manually. */
    private class FakeFrameSource : CamDroidFrameSource {
        val closed = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        private var sink: ((ByteArray) -> Unit)? = null

        override fun start(sinkFn: (ByteArray) -> Unit, onError: (String) -> Unit) {
            started.set(true)
            sink = sinkFn
        }

        fun push(frame: ByteArray) {
            sink?.invoke(frame)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun readPacket(input: DataInputStream): CamDroidV1Packet {
        val kindByte = input.readByte()
        val length = Integer.reverseBytes(input.readInt())
        val payload = ByteArray(length)
        input.readFully(payload)
        return CamDroidV1Packet(CamDroidMediaKind.fromWireValue(kindByte), payload)
    }

    private fun writePacket(out: java.io.OutputStream, packet: CamDroidV1Packet) {
        out.write(CamDroidV1Codec.encode(packet))
        out.flush()
    }

    /** Reads packets until one of [kind] arrives (heartbeats interleave freely). */
    private fun readUntil(input: DataInputStream, kind: CamDroidMediaKind, timeoutMs: Long = 3_000): CamDroidV1Packet? {
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

    private fun connectClient(port: Int, token: String?): Pair<Socket, DataInputStream> {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        val out = socket.getOutputStream()
        val handshake = if (token != null) {
            """{"version":"1.0","client":"ec0-test","token":"$token"}"""
        } else {
            """{"version":"1.0","client":"ec0-test"}"""
        }
        writePacket(out, CamDroidV1Packet(CamDroidMediaKind.METADATA, handshake.encodeToByteArray()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        return socket to input
    }

    private fun newServer(
        source: FakeFrameSource,
        port: Int = 0,
        targetFps: Int = 30,
        armTtlMs: Long = 1_500,
        silenceMs: Long = 700,
        heartbeatMs: Long = 200,
    ): CamDroidStreamServer = CamDroidStreamServer(
        frameSourceFactory = { source },
        port = port,
        targetFps = targetFps,
        armTtlMs = armTtlMs,
        clientSilenceTimeoutMs = silenceMs,
        heartbeatIntervalMs = heartbeatMs,
    )

    // ---- happy path --------------------------------------------------------

    @Test
    fun streamsTokenAuthenticatedFramesToClient() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("aabbccdd11223344")
        assertTrue(armed is CamDroidArmResult.Armed)
        assertEquals(CamDroidServerState.ARMED, server.snapshot.state)

        val (client, input) = connectClient((armed as CamDroidArmResult.Armed).port, "aabbccdd11223344")

        // Config metadata first — the desktop sink depends on it.
        val configPacket = readPacket(input)
        assertEquals(CamDroidMediaKind.METADATA, configPacket.kind)
        val config = CamDroidStreamConfig.fromJson(configPacket.payload.decodeToString())
        assertEquals(640, config?.width)
        assertEquals(480, config?.height)
        assertEquals("mjpeg", config?.codec)

        // One paced frame makes it through the single-slot handoff.
        val frame1 = ByteArray(64) { it.toByte() }
        source.push(frame1)
        val video = readUntil(input, CamDroidMediaKind.VIDEO)
        assertEquals(CamDroidMediaKind.VIDEO, video?.kind)
        assertTrue(video?.payload?.contentEquals(frame1) == true)

        // Heartbeats keep the desktop watchdog fed between frames.
        source.push(ByteArray(16))
        val sawHeartbeat = await {
            var hb = false
            runCatching { if (readPacket(input).kind == CamDroidMediaKind.HEARTBEAT) hb = true }
            hb
        }
        assertTrue(sawHeartbeat, "expected at least one HEARTBEAT packet")

        assertTrue(await { server.snapshot.state == CamDroidServerState.STREAMING })
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
            await { server.snapshot.reason?.contains("Rejected") == true },
            "server should record an honest rejection reason",
        )
        // The socket is closed without any config: reading hits EOF.
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
    fun stopCommandEndsSessionAndClosesFrameSource() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("tok")
        val port = (armed as CamDroidArmResult.Armed).port
        val (client, _) = connectClient(port, "tok")

        assertTrue(await { source.started.get() })

        val out = client.getOutputStream()
        writePacket(out, CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.Stop))

        assertTrue(
            await { server.snapshot.state == CamDroidServerState.STOPPED },
            "stop control must end the session",
        )
        assertTrue(await { source.closed.get() }, "frame source must be closed on stop")
        client.close()
        server.close()
    }

    @Test
    fun clientSilenceAutoStopsAndClosesCapture() {
        val source = FakeFrameSource()
        val server = newServer(source, silenceMs = 500)
        val armed = server.arm("tok")
        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")

        assertTrue(await { source.started.get() })
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
        assertTrue(await { source.started.get() })
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

        assertTrue(
            await(3_000) { server.snapshot.reason?.contains("Arm window expired") == true },
        )
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
        assertTrue(await { source.started.get() })

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
        // 5 fps pacing (200ms interval) vs a producer pushing 40 frames at once.
        val server = newServer(source, targetFps = 5, heartbeatMs = 150)
        val armed = server.arm("tok")
        val (client, input) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")

        // Drain the config packet so the slot pipeline is live.
        readPacket(input)
        val pushed = 40
        repeat(pushed) { i -> source.push(byteArrayOf(i.toByte())) }

        var received = 0
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && received < pushed) {
            val packet = runCatching { readPacket(input) }.getOrNull() ?: break
            if (packet.kind == CamDroidMediaKind.VIDEO) received++
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
        val (client, input) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")
        readPacket(input) // config

        source.push(ByteArray(256)) // over the 128-byte guard
        assertEquals(1L, server.droppedFrameCount())

        val small = ByteArray(8)
        source.push(small)
        val video = readUntil(input, CamDroidMediaKind.VIDEO)
        assertEquals(CamDroidMediaKind.VIDEO, video?.kind)
        assertTrue(video?.payload?.contentEquals(small) == true)
        client.close()
        server.close()
    }

    // ---- config codec --------------------------------------------------------

    @Test
    fun configRoundTripAndBounds() {
        val json = CamDroidStreamConfig(width = 640, height = 480, fps = 30, codec = "mjpeg")
            .toJsonObject().toString()
        val parsed = CamDroidStreamConfig.fromJson(json)
        assertEquals(CamDroidStreamConfig(640, 480, 30, "mjpeg"), parsed)
        // Absurd values rejected: the sink must never trust a hostile config.
        assertEquals(null, CamDroidStreamConfig.fromJson("""{"width":99999,"height":480,"fps":30}"""))
        assertEquals(null, CamDroidStreamConfig.fromJson("""{"width":640,"height":480,"fps":120}"""))
        assertEquals(null, CamDroidStreamConfig.fromJson("not json at all"))
    }

    // ---- live control routing --------------------------------------------------------

    @Test
    fun controlCommandsReachOwnerHookExceptStop() {
        val source = FakeFrameSource()
        val server = newServer(source)
        val armed = server.arm("tok")
        val (client, _) = connectClient((armed as CamDroidArmResult.Armed).port, "tok")

        val seen = mutableListOf<String>()
        server.onControlCommand = { cmd -> seen.add(cmd.cmdName) }

        val out = client.getOutputStream()
        writePacket(out, CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.SetZoom(250L)))
        writePacket(out, CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.Stop))

        assertTrue(await { seen.contains("set_zoom") }, "live control must reach the owner hook")
        assertTrue(await { server.snapshot.state == CamDroidServerState.STOPPED })
        client.close()
        server.close()
    }

    // ---- legacy handshake codec remains wire-compatible ------------------------------

    @Test
    fun clientHandshakeCodecCarriesOptionalToken() {
        val withoutToken = CamDroidV1Codec.clientHandshakePacket()
        val parsedWithout = Json.decodeFromString(
            JsonObject.serializer(),
            withoutToken.payload.decodeToString(),
        )
        assertEquals("ec0-desktop", parsedWithout["client"]?.toString()?.trim('"'))
        assertTrue(parsedWithout["token"] == null)

        val withToken = CamDroidV1Codec.clientHandshakePacket(streamToken = "ff0011")
        val parsedWith = Json.decodeFromString(
            JsonObject.serializer(),
            withToken.payload.decodeToString(),
        )
        assertEquals("ff0011", parsedWith["token"]?.toString()?.trim('"'))
    }
}
