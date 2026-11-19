/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM tests for the desktop-side CamDroid v2 stream receiver: token handshake
 * on the wire (v2 Hello with `ec0|<token>`), capability negotiation →
 * StreamAccepted config, ffmpeg H.264 sink launch, pre-handshake drops,
 * silence watchdog auto-stop, stop-always-works semantics, honest failures,
 * and the upstream heartbeats the phone's watchdog depends on.
 */
package ec0.core.engine.services

import ec0.core.engine.services.camdroid.CamDroidCapabilities
import ec0.core.engine.services.camdroid.CamDroidCapabilities
import ec0.core.engine.services.camdroid.CamDroidSessionMessages
import ec0.core.engine.services.camdroid.CamDroidV2
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CamDroidStreamReceiverTest {

    /** ffmpeg stand-in that records argv and every byte written to "stdin". */
    private class RecordingSink : CamDroidSinkHandle {
        val written = ByteArrayOutputStream()
        val closedFlag = AtomicBoolean(false)
        override val stdin: OutputStream = object : OutputStream() {
            override fun write(b: Int) = written.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = written.write(b, off, len)
        }
        override val isAlive: Boolean = true
        override fun exitCode(): Int? = null
        override fun close() {
            closedFlag.set(true)
        }
    }

    /** Scripted fake phone: validates the handshake, then plays packets. */
    private class FakePhoneServer(
        private val expectedToken: String?,
        /** How long the socket stays open after the script finishes. */
        private val holdOpenMs: Long = 400,
        /** 0 = pick an ephemeral port; non-zero binds THAT port (arm→bind race tests). */
        bindPort: Int = 0,
        private val script: (FakePhoneSession) -> Unit,
    ) {
        val server = ServerSocket(bindPort).apply { reuseAddress = true }
        val handshakeSeen = AtomicReference<String?>(null)
        val startSeen = AtomicReference<String?>(null)
        val controlPackets = CopyOnWriteArrayList<String>()
        val heartbeatsSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val clientClosed = CountDownLatch(1)
        private val thread = Thread {
            runCatching {
                val client = server.accept()
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                // v2 session: hello (token) → capabilities → start → accepted.
                val hello = readPacket(input)
                handshakeSeen.set(hello.payload.decodeToString())
                val helloData = CamDroidSessionMessages.parseType(hello.payload)!!.second
                val clientField = helloData["client"]!!.toString().trim('"')
                CamDroidV2.Packet(
                    kind = CamDroidV2.KIND_METADATA,
                    flags = 0,
                    sessionId = 0,
                    sequence = 0,
                    captureTimestampNs = 0,
                    payload = CamDroidSessionMessages.capabilities(
                        CamDroidCapabilities(
                            codecs = listOf("h264"),
                            videoModes = listOf(
                                ec0.core.engine.services.camdroid.CamDroidVideoMode(1920, 1080, 30, 1_000_000L, 50_000_000L),
                            ),
                        ),
                    ).toString().encodeToByteArray(),
                ).writeTo(client.getOutputStream())
                val start = readPacket(input)
                startSeen.set(start.payload.decodeToString())
                // Background drain: capture the receiver's live control packets.
                val controlReader = Thread {
                    runCatching {
                        while (!client.isClosed) {
                            val extra = readPacket(input)
                            if (extra.kind == CamDroidV2.KIND_CONTROL) {
                                controlPackets.add(extra.payload.decodeToString())
                            }
                            if (extra.kind == CamDroidV2.KIND_HEARTBEAT) {
                                heartbeatsSeen.incrementAndGet()
                            }
                        }
                    }
                }.apply { isDaemon = true }
                controlReader.start()
                val session = FakePhoneSession(client)
                runCatching { script(session) }
                Thread.sleep(holdOpenMs)
                runCatching { client.close() }
                clientClosed.countDown()
            }
            runCatching { server.close() }
        }.apply { isDaemon = true }

        val port: Int get() = server.localPort

        fun start() {
            thread.start()
        }

        fun close() {
            runCatching { server.close() }
        }

        inner class FakePhoneSession(val socket: Socket) {
            fun send(packet: CamDroidV2.Packet) {
                packet.writeTo(socket.getOutputStream())
            }

            /** The v2 StreamAccepted: carries the negotiated capture config. */
            fun sendConfig(width: Int = 1920, height: Int = 1080, fps: Int = 30) {
                send(
                    CamDroidV2.Packet(
                        kind = CamDroidV2.KIND_METADATA,
                        flags = 0,
                        sessionId = 42,
                        sequence = 0,
                        captureTimestampNs = 0,
                        payload = CamDroidSessionMessages.streamAccepted(
                            sessionId = 42,
                            codec = "h264",
                            mode = ec0.core.engine.services.camdroid.CamDroidVideoMode(
                                width, height, fps, 1_000_000L, 50_000_000L,
                            ),
                            bitrateBps = 8_000_000L,
                        ).toString().encodeToByteArray(),
                    ),
                )
            }

            fun sendVideo(data: ByteArray) {
                send(
                    CamDroidV2.Packet(
                        kind = CamDroidV2.KIND_VIDEO,
                        flags = 0,
                        sessionId = 42,
                        sequence = 1,
                        captureTimestampNs = 0,
                        payload = data,
                    ),
                )
            }
        }

        companion object {
            fun readPacket(input: DataInputStream): CamDroidV2.Packet = CamDroidV2.readPacket(input)
        }
    }

    private fun await(timeoutMs: Long = 4_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private fun newReceiver(
        port: Int,
        sink: RecordingSink,
        silenceMs: Long = 700,
        configAwaitMs: Long = 2_000,
        heartbeatMs: Long = 2_000,
    ) = CamDroidStreamReceiver(
        sinkLauncher = { sink },
        v4l2DevicePath = "/dev/video10",
        silenceTimeoutMs = silenceMs,
        configAwaitMs = configAwaitMs,
        heartbeatIntervalMs = heartbeatMs,
    )

    // ---- happy path --------------------------------------------------------

    @Test
    fun pipesConfiguredH264IntoSinkWithTokenHandshake() {
        val sink = RecordingSink()
        val nal1 = ByteArray(32) { 0x11 }
        val nal2 = ByteArray(32) { 0x22 }
        val phone = FakePhoneServer(expectedToken = "tok123") { session ->
            session.sendConfig()
            // Give start() a beat to attach the sink after the accepted latch.
            Thread.sleep(150)
            session.sendVideo(nal1)
            session.sendVideo(nal2)
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok123", requestedFps = 30)

        assertEquals(CamDroidReceiverState.STREAMING, snapshot.state)
        assertEquals(1920, snapshot.config?.width)
        assertEquals(30, snapshot.config?.fps)
        assertEquals("h264", snapshot.config?.codec)

        // The phone saw the arm token inside the v2 hello's client identity.
        val handshake = phone.handshakeSeen.get()
        assertTrue(handshake?.contains("ec0|tok123") == true, "hello must carry the arm token: $handshake")
        assertTrue(phone.startSeen.get()?.contains("\"type\":\"start\"") == true)

        // Every video byte reached the "ffmpeg" stdin.
        assertTrue(
            await { sink.written.size() == nal1.size + nal2.size },
            "sink stdin must receive both NAL units, got ${sink.written.size()}",
        )
        assertEquals(2L, receiver.snapshot.value.framesReceived)
        receiver.stop()
        phone.close()
    }

    // ---- argv ----------------------------------------------------------------

    @Test
    fun ffmpegArgvTargetsLoopbackWithYuyv() {
        val receiver = CamDroidStreamReceiver(v4l2DevicePath = "/dev/video10")
        val argv = receiver.buildFfmpegArgv(CamDroidStreamConfig("h264", 1920, 1080, 30))
        assertEquals("ffmpeg", argv.first())
        assertTrue(argv.contains("-f") && argv.contains("h264"), "the v2 stream is hardware H.264")
        assertTrue(argv.contains("-pix_fmt") && argv.contains("yuyv422"))
        assertEquals("/dev/video10", argv.last())
    }

    // ---- drop policy --------------------------------------------------------

    @Test
    fun framesBeforeConfigAreDroppedNeverQueued() {
        val sink = RecordingSink()
        val early = ByteArray(16) { 0x33 }
        val late = ByteArray(16) { 0x44 }
        val phone = FakePhoneServer(expectedToken = "tok") { session ->
            // Two frames BEFORE the StreamAccepted: bounded policy must drop them.
            session.sendVideo(early)
            session.sendVideo(early)
            session.sendConfig()
            // Post-sink frame, after start() has attached the sink.
            Thread.sleep(150)
            session.sendVideo(late)
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok")

        assertEquals(CamDroidReceiverState.STREAMING, snapshot.state)
        assertTrue(
            await { receiver.snapshot.value.framesDropped >= 2L },
            "pre-config frames must be counted as dropped",
        )
        assertTrue(
            await { sink.written.size() == late.size },
            "only post-config frames reach the sink",
        )
        receiver.stop()
        phone.close()
    }

    // ---- watchdogs --------------------------------------------------------

    @Test
    fun silentPhoneAutoStopsWithHonestReason() {
        val sink = RecordingSink()
        // Socket stays open well past the 500ms watchdog so the SILENCE path
        // trips (not the EOF path) — that is the honest reason we assert.
        val phone = FakePhoneServer(expectedToken = "tok", holdOpenMs = 3_000) { session ->
            session.sendConfig()
            // Then nothing at all.
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink, silenceMs = 500)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok")

        assertEquals(CamDroidReceiverState.STREAMING, snapshot.state)
        assertTrue(
            await(3_000) { receiver.snapshot.value.state == CamDroidReceiverState.ERROR },
            "silence watchdog must trip",
        )
        assertTrue(receiver.snapshot.value.reason?.contains("silent") == true)
        assertTrue(sink.closedFlag.get(), "sink must be destroyed on auto-stop")
        receiver.stop()
        phone.close()
    }

    @Test
    fun phoneClosingSocketStopsStream() {
        val sink = RecordingSink()
        val phone = FakePhoneServer(expectedToken = "tok") { session ->
            session.sendConfig()
            session.socket.close() // vanish immediately
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        receiver.start("127.0.0.1", phone.port, streamToken = "tok")

        assertTrue(
            await(3_000) { receiver.snapshot.value.state == CamDroidReceiverState.ERROR },
            "a vanished phone must end the stream with an honest reason",
        )
        receiver.stop()
        phone.close()
    }

    // ---- stop-always --------------------------------------------------------

    @Test
    fun stopAlwaysWorksEvenAfterPhoneVanished() {
        val sink = RecordingSink()
        val phone = FakePhoneServer(expectedToken = "tok") { session ->
            session.sendConfig()
            session.socket.close()
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        receiver.start("127.0.0.1", phone.port, streamToken = "tok")
        await(3_000) { receiver.snapshot.value.state != CamDroidReceiverState.STREAMING }

        // stop() is local-only: completes even though the peer is gone.
        receiver.stop("user stop")
        assertEquals(CamDroidReceiverState.STOPPED, receiver.snapshot.value.state)
        assertTrue(sink.closedFlag.get())
        // Idempotent.
        receiver.stop()
        assertEquals(CamDroidReceiverState.STOPPED, receiver.snapshot.value.state)
        phone.close()
    }

    @Test
    fun stopWithoutStartIsSafe() {
        val receiver = CamDroidStreamReceiver(sinkLauncher = { RecordingSink() })
        receiver.stop()
        assertEquals(CamDroidReceiverState.STOPPED, receiver.snapshot.value.state)
    }

    // ---- failure honesty --------------------------------------------------------

    @Test
    fun unreachablePhoneFailsWithReason() {
        val receiver = CamDroidStreamReceiver(sinkLauncher = { RecordingSink() })
        // Port 1 on loopback is closed in practice.
        val snapshot = receiver.start("127.0.0.1", 1, streamToken = "tok", requestedFps = 30)
        assertEquals(CamDroidReceiverState.ERROR, snapshot.state)
        assertTrue(snapshot.reason?.contains("unreachable") == true)
    }

    @Test
    fun emptyTokenRefusesToStart() {
        val receiver = CamDroidStreamReceiver(sinkLauncher = { RecordingSink() })
        val snapshot = receiver.start("127.0.0.1", 4747, streamToken = "  ")
        assertEquals(CamDroidReceiverState.ERROR, snapshot.state)
        assertTrue(snapshot.reason?.contains("token") == true)
    }

    @Test
    fun missingConfigTimesOutHonestly() {
        val sink = RecordingSink()
        val phone = FakePhoneServer(expectedToken = "tok") { /* sends NOTHING */ }
        phone.start()
        val receiver = newReceiver(phone.port, sink, configAwaitMs = 400)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok")
        assertEquals(CamDroidReceiverState.ERROR, snapshot.state)
        assertTrue(snapshot.reason?.contains("capture config") == true)
        receiver.stop()
        phone.close()
    }

    // ---- live control --------------------------------------------------------

    @Test
    fun sendControlDeliversZoomOnLiveSocket() {
        val sink = RecordingSink()
        val phone = FakePhoneServer(expectedToken = "tok") { session ->
            session.sendConfig()
            // Keep the socket open long enough for a control packet to arrive.
            Thread.sleep(300)
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        receiver.start("127.0.0.1", phone.port, streamToken = "tok")
        assertTrue(receiver.sendControl(ec0.core.engine.protocol.CamDroidControlCommand.SetZoom(250L)))
        assertTrue(
            await { phone.controlPackets.any { it.contains("set_zoom") } },
            "zoom must reach the phone on the stream socket",
        )
        receiver.stop()
        phone.close()
    }

    @Test
    fun sendControlWithoutSessionReturnsFalse() {
        val receiver = CamDroidStreamReceiver(sinkLauncher = { RecordingSink() })
        assertEquals(false, receiver.sendControl(ec0.core.engine.protocol.CamDroidControlCommand.Stop))
    }

    // ---- arm→bind race (dial retry) ---------------------------------------

    /**
     * The desktop arms the phone over the engine session, then dials :4747 —
     * but the phone's stream server only OPENS after it processes that arm, a
     * few milliseconds later. A single immediate dial loses the race every
     * time (observed live on the A03: 'Connection refused' while the phone
     * logged 'armed on :4747 — awaiting token handshake'). The bounded retry
     * must reach a server that appears mid-window.
     */
    @Test
    fun dialRetryReachesAPhoneThatBindsLate() {
        val sink = RecordingSink()
        // Reserve a port, then RELEASE it: for the first ~450ms nothing listens
        // (the receiver's attempts at 0ms and 300ms refuse), then the phone's
        // server appears on the same port and the ~600ms attempt connects.
        val port = ServerSocket(0).also { it.close() }.localPort
        var phone: FakePhoneServer? = null
        val lateBind = Thread {
            Thread.sleep(450L)
            phone = FakePhoneServer(
                expectedToken = "tok-late",
                bindPort = port,
            ) { session ->
                session.sendConfig()
                Thread.sleep(150)
            }.also { it.start() }
        }.apply { isDaemon = true }
        lateBind.start()
        try {
            val receiver = newReceiver(port, sink)
            val snapshot = receiver.start("127.0.0.1", port, streamToken = "tok-late", requestedFps = 30)
            assertEquals(
                CamDroidReceiverState.STREAMING,
                snapshot.state,
                "the bounded dial retry must reach a late-binding phone, got: ${snapshot.reason}",
            )
            receiver.close()
        } finally {
            phone?.close()
        }
    }

    @Test
    fun permanentlyClosedPortFailsHonestlyAfterBoundedRetries() {
        val sink = RecordingSink()
        val port = ServerSocket(0).also { it.close() }.localPort
        val receiver = CamDroidStreamReceiver(
            sinkLauncher = { sink },
            v4l2DevicePath = "/dev/video10",
            connectTimeoutMs = 500,
            silenceTimeoutMs = 700,
            configAwaitMs = 500,
            bindRetryAttempts = 3,
            bindRetryIntervalMs = 60,
        )
        val startedAt = System.currentTimeMillis()
        val snapshot = receiver.start("127.0.0.1", port, streamToken = "tok-x", requestedFps = 30)
        val elapsed = System.currentTimeMillis() - startedAt
        assertEquals(CamDroidReceiverState.ERROR, snapshot.state)
        assertTrue(
            snapshot.reason.orEmpty().contains("unreachable"),
            "the failure must stay honest about the unreachable phone, got: ${snapshot.reason}",
        )
        assertTrue(
            elapsed < 3_000,
            "the bounded retry must not hang the caller ($elapsed ms)",
        )
    }

    // ---- upstream heartbeats (the phone's client-silence watchdog) ---------

    /**
     * The phone's server auto-stops when its CLIENT is silent for 5s — and the
     * desktop only READS the stream, so without upstream heartbeats every
     * session died ~5s after connect (observed live: banner went live, then
     * the sink died and OBS showed black). The wire doc promised 'HEARTBEAT
     * keepalive in BOTH directions'; the downstream leg existed, this pins the
     * upstream one.
     */
    @Test
    fun sendsUpstreamHeartbeatsToFeedThePhonesWatchdog() {
        val sink = RecordingSink()
        val phone = FakePhoneServer(expectedToken = "tok-hb") { session ->
            session.sendConfig()
            Thread.sleep(1_200) // hold the session across several HB intervals
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink, heartbeatMs = 300)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok-hb", requestedFps = 30)
        assertEquals(CamDroidReceiverState.STREAMING, snapshot.state)
        assertTrue(
            await(3_000L) { phone.heartbeatsSeen.get() >= 2 },
            "the desktop must send periodic heartbeats upstream, saw ${phone.heartbeatsSeen.get()}",
        )
        receiver.close()
    }
}
