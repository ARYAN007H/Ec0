/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM tests for the desktop-side CamDroid stream receiver: token handshake on
 * the wire, config-driven ffmpeg sink launch, pre-config frame drops, silence
 * watchdog auto-stop, stop-always-works semantics, and honest failure reasons.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
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
        private val script: (FakePhoneSession) -> Unit,
    ) {
        val server = ServerSocket(0).apply { reuseAddress = true }
        val handshakeSeen = AtomicReference<String?>(null)
        val startSeen = AtomicReference<String?>(null)
        val controlPackets = CopyOnWriteArrayList<String>()
        val clientClosed = CountDownLatch(1)
        private val thread = Thread {
            runCatching {
                val client = server.accept()
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                val handshake = readPacket(input)
                handshakeSeen.set(handshake.payload.decodeToString())
                val start = readPacket(input)
                startSeen.set(start.payload.decodeToString())
                // Background drain: capture the receiver's live control packets.
                val controlReader = Thread {
                    runCatching {
                        while (!client.isClosed) {
                            val extra = readPacket(input)
                            if (extra.kind == CamDroidMediaKind.CONTROL) {
                                controlPackets.add(extra.payload.decodeToString())
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
            fun send(packet: CamDroidV1Packet) {
                socket.getOutputStream().write(CamDroidV1Codec.encode(packet))
                socket.getOutputStream().flush()
            }

            fun sendConfig(width: Int = 640, height: Int = 480, fps: Int = 30) {
                send(
                    CamDroidV1Packet(
                        CamDroidMediaKind.METADATA,
                        CamDroidStreamConfig(width, height, fps, "mjpeg").toJsonObject().toString()
                            .encodeToByteArray(),
                    ),
                )
            }
        }

        companion object {
            fun readPacket(input: DataInputStream): CamDroidV1Packet {
                val kindByte = input.readByte()
                val length = Integer.reverseBytes(input.readInt())
                val payload = ByteArray(length)
                input.readFully(payload)
                return CamDroidV1Packet(CamDroidMediaKind.fromWireValue(kindByte), payload)
            }
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
    ) = CamDroidStreamReceiver(
        sinkLauncher = { sink },
        v4l2DevicePath = "/dev/video10",
        silenceTimeoutMs = silenceMs,
        configAwaitMs = configAwaitMs,
    )

    // ---- happy path --------------------------------------------------------

    @Test
    fun pipesConfiguredMjpegIntoSinkWithTokenHandshake() {
        val sink = RecordingSink()
        val frame1 = ByteArray(32) { 0x11 }
        val frame2 = ByteArray(32) { 0x22 }
        val phone = FakePhoneServer(expectedToken = "tok123") { session ->
            session.sendConfig()
            // Give start() a beat to attach the sink after the config latch.
            Thread.sleep(150)
            session.send(CamDroidV1Packet(CamDroidMediaKind.VIDEO, frame1))
            session.send(CamDroidV1Packet(CamDroidMediaKind.VIDEO, frame2))
        }
        phone.start()
        val receiver = newReceiver(phone.port, sink)
        val snapshot = receiver.start("127.0.0.1", phone.port, streamToken = "tok123", requestedFps = 30)

        assertEquals(CamDroidReceiverState.STREAMING, snapshot.state)
        assertEquals(640, snapshot.config?.width)
        assertEquals(30, snapshot.config?.fps)

        // The phone saw the arm token in the TCP handshake and the mjpeg Start.
        val handshake = phone.handshakeSeen.get()
        assertTrue(handshake?.contains("\"token\":\"tok123\"") == true, "handshake must carry the arm token: $handshake")
        assertTrue(phone.startSeen.get()?.contains("\"cmd\":\"start\"") == true)
        assertTrue(phone.startSeen.get()?.contains("mjpeg") == true)

        // Every video byte reached the "ffmpeg" stdin.
        assertTrue(
            await { sink.written.size() == frame1.size + frame2.size },
            "sink stdin must receive both frames, got ${sink.written.size()}",
        )
        assertEquals(2L, receiver.snapshot.value.framesReceived)
        receiver.stop()
        phone.close()
    }

    // ---- argv ----------------------------------------------------------------

    @Test
    fun ffmpegArgvTargetsLoopbackWithYuyv() {
        val receiver = CamDroidStreamReceiver(v4l2DevicePath = "/dev/video10")
        val argv = receiver.buildFfmpegArgv(CamDroidStreamConfig(640, 480, 30, "mjpeg"))
        assertEquals("ffmpeg", argv.first())
        assertTrue(argv.contains("-f") && argv.contains("mjpeg"))
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
            // Two frames BEFORE the config: bounded policy must drop them.
            session.send(CamDroidV1Packet(CamDroidMediaKind.VIDEO, early))
            session.send(CamDroidV1Packet(CamDroidMediaKind.VIDEO, early))
            session.sendConfig()
            // Post-sink frame, after start() has attached the sink.
            Thread.sleep(150)
            session.send(CamDroidV1Packet(CamDroidMediaKind.VIDEO, late))
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
}
