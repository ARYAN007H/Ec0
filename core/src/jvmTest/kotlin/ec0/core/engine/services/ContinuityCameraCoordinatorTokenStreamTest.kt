/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM tests for the ContinuityCameraCoordinator's ec0-native token stream path:
 * token generation, receiver-driven streaming status, live control routing,
 * honest failure surfacing, and legacy-path compatibility (token == null).
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.services.camdroid.CamDroidCapabilities
import ec0.core.engine.services.camdroid.CamDroidSessionMessages
import ec0.core.engine.services.camdroid.CamDroidV2
import ec0.core.engine.protocol.CameraStreamState
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContinuityCameraCoordinatorTokenStreamTest {

    private class RecordingSink : CamDroidSinkHandle {
        val written = ByteArrayOutputStream()
        val closedFlag = AtomicBoolean(false)
        override val stdin: java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = written.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = written.write(b, off, len)
        }
        override val isAlive: Boolean = true
        override fun exitCode(): Int? = null
        override fun close() {
            closedFlag.set(true)
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

    @Test
    fun streamTokenIsHex128BitAndUnique() {
        val token = ContinuityCameraCoordinator.newStreamToken()
        assertEquals(32, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(token, ContinuityCameraCoordinator.newStreamToken())
    }

    @Test
    fun tokenPathStreamsThroughReceiverWithActualConfig() {
        val sink = RecordingSink()
        val frame = ByteArray(24) { 0x5a }
        val server = ServerSocket(0).apply { reuseAddress = true }
        val controlPackets = CopyOnWriteArrayList<String>()
        val port = server.localPort
        val serverThread = Thread {
            runCatching {
                val client = server.accept()
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                // v2 session: hello carries the token in the client identity.
                val hello = CamDroidV2.readPacket(input)
                assertTrue(hello.payload.decodeToString().contains("ec0|abcd"))
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
                val start = CamDroidV2.readPacket(input)
                assertTrue(start.payload.decodeToString().contains("\"type\":\"start\""))
                // Control drain
                Thread {
                    runCatching {
                        while (!client.isClosed) {
                            val p = CamDroidV2.readPacket(input)
                            if (p.kind == CamDroidV2.KIND_CONTROL) {
                                controlPackets.add(p.payload.decodeToString())
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()
                CamDroidV2.Packet(
                    kind = CamDroidV2.KIND_METADATA,
                    flags = 0,
                    sessionId = 7,
                    sequence = 0,
                    captureTimestampNs = 0,
                    payload = CamDroidSessionMessages.streamAccepted(
                        sessionId = 7,
                        codec = "h264",
                        mode = ec0.core.engine.services.camdroid.CamDroidVideoMode(1920, 1080, 30, 1_000_000L, 50_000_000L),
                        bitrateBps = 8_000_000L,
                    ).toString().encodeToByteArray(),
                ).writeTo(client.getOutputStream())
                Thread.sleep(200)
                CamDroidV2.Packet(
                    kind = CamDroidV2.KIND_VIDEO,
                    flags = 0,
                    sessionId = 7,
                    sequence = 1,
                    captureTimestampNs = 0,
                    payload = frame,
                ).writeTo(client.getOutputStream())
                Thread.sleep(300)
                runCatching { client.close() }
            }
            runCatching { server.close() }
        }.apply { isDaemon = true }
        serverThread.start()

        val coordinator = ContinuityCameraCoordinator(
            camDroidReceiverFactory = {
                CamDroidStreamReceiver(
                    sinkLauncher = { sink },
                    v4l2DevicePath = "/dev/video10",
                    configAwaitMs = 2_000,
                )
            },
        )
        val status = coordinator.startCameraStream(
            host = "127.0.0.1",
            port = port,
            backend = CameraBackend.CAMDROID_NATIVE,
            fps = 30,
            streamToken = "abcd",
        )

        assertEquals(CameraStreamState.STREAMING, status.state)
        assertEquals("1920x1080", status.resolution)
        assertEquals(30, status.fps)
        assertEquals("h264", status.codec)
        assertEquals(CameraBackend.CAMDROID_NATIVE, status.backend)
        assertTrue(
            await { sink.written.size() == frame.size },
            "frame must reach the v4l2 sink through the coordinator path",
        )

        // Live control rides the receiver socket.
        coordinator.sendControlCommand(ec0.core.engine.protocol.CamDroidControlCommand.SetZoom(250L))
        assertTrue(await { controlPackets.any { it.contains("set_zoom") } })

        // Stop must tear down receiver + sink regardless of the peer.
        val stopStatus = coordinator.stopCameraStream()
        assertEquals(CameraStreamState.STOPPED, stopStatus.state)
        assertTrue(sink.closedFlag.get())
        serverThread.join(2_000)
    }

    @Test
    fun receiverFailureSurfacesHonestReason() {
        val coordinator = ContinuityCameraCoordinator(
            camDroidReceiverFactory = {
                CamDroidStreamReceiver(sinkLauncher = { RecordingSink() })
            },
        )
        val status = coordinator.startCameraStream(
            host = "127.0.0.1",
            port = 1, // closed
            streamToken = "abcd",
        )
        assertEquals(CameraStreamState.ERROR, status.state)
        assertTrue(status.reason?.contains("unreachable") == true)
    }

    @Test
    fun legacyPathStillUsedWhenTokenIsNull() {
        // token == null must keep the legacy external-binary transport contract.
        val fakeTransport = object : CamDroidControlTransport {
            val packets = java.util.concurrent.CopyOnWriteArrayList<CamDroidV1Packet>()
            var connectedTo: String? = null
            override val isConnected: Boolean = true
            override fun connect(host: String, port: Int, timeoutMs: Int): Boolean {
                connectedTo = "$host:$port"
                return true
            }

            override fun sendPacket(packet: CamDroidV1Packet): Boolean {
                packets.add(packet)
                return true
            }

            override fun close() {}
        }
        val coordinator = ContinuityCameraCoordinator(
            camDroidTransportFactory = { fakeTransport },
            v4l2DeviceCheck = { true },
            camdroidBinaryPath = "/nonexistent/camdroid", // spawn failure tolerated
        )
        val status = coordinator.startCameraStream(
            host = "192.0.2.77",
            backend = CameraBackend.CAMDROID_NATIVE,
            spawnDesktopReceiver = true,
        )
        assertEquals(CameraStreamState.STREAMING, status.state)
        assertEquals("192.0.2.77:4747", fakeTransport.connectedTo)
        assertEquals(1, fakeTransport.packets.size)
        assertEquals(CamDroidMediaKind.CONTROL, fakeTransport.packets[0].kind)
    }

    private fun readPacket(input: DataInputStream): CamDroidV1Packet {
        val kindByte = input.readByte()
        val length = Integer.reverseBytes(input.readInt())
        val payload = ByteArray(length)
        input.readFully(payload)
        return CamDroidV1Packet(CamDroidMediaKind.fromWireValue(kindByte), payload)
    }
}
