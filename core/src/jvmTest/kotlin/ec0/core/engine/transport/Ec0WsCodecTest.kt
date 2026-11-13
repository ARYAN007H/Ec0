/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.transport

import ec0.core.kdeconnect.Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ec0WsCodecTest {

    @Test
    fun rfc6455AcceptKeyVectorMatchesSection1_3() {
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        assertEquals(expectedAccept, Ec0WsCodec.acceptKey(clientKey))
    }

    @Test
    fun httpUpgradeRequestAndResponseRoundtrip() {
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val rawReq = Ec0WsCodec.buildClientUpgradeRequest(
            host = "192.168.1.42",
            port = 49880,
            path = "/ec0/v1",
            clientKey = clientKey,
        )
        val parsedReq = Ec0WsCodec.parseClientUpgradeRequest(
            Ec0WsCodec.readHttpHeaderBlock(ByteArrayInputStream(rawReq.toByteArray(Charsets.US_ASCII))),
        )
        assertEquals("GET", parsedReq.method)
        assertEquals("/ec0/v1", parsedReq.path)
        assertEquals(clientKey, parsedReq.clientKey)

        val rawResp = Ec0WsCodec.buildServerUpgradeResponse(parsedReq.clientKey)
        val (statusCode, headers) = Ec0WsCodec.parseServerUpgradeResponse(
            Ec0WsCodec.readHttpHeaderBlock(ByteArrayInputStream(rawResp.toByteArray(Charsets.US_ASCII))),
        )
        assertEquals(101, statusCode)
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers["sec-websocket-accept"])
    }

    @Test
    fun frameCodecHandlesSmallMediumAnd64BitExtendedPayloads() {
        val sizes = listOf(0, 125, 126, 1024, 65_535, 70_000)
        for (size in sizes) {
            val payload = ByteArray(size) { (it * 31 + 7).toByte() }

            val clientWire = Ec0WsCodec.encodeClientFrame(Ec0WsOpcode.BINARY, payload)
            val decodedClient = Ec0WsCodec.readFrame(DataInputStream(ByteArrayInputStream(clientWire)))
            assertTrue(decodedClient.fin)
            assertTrue(decodedClient.masked)
            assertEquals(Ec0WsOpcode.BINARY, decodedClient.opcode)
            assertContentEquals(payload, decodedClient.payload)

            val serverWire = Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.BINARY, payload)
            val decodedServer = Ec0WsCodec.readFrame(DataInputStream(ByteArrayInputStream(serverWire)))
            assertTrue(decodedServer.fin)
            assertFalse(decodedServer.masked)
            assertEquals(Ec0WsOpcode.BINARY, decodedServer.opcode)
            assertContentEquals(payload, decodedServer.payload)
        }
    }

    @Test
    fun readMessageReassemblesFragmentsAndHandlesInterleavedPingAndClose() {
        val stream = ByteArrayOutputStream()
        val part1 = "Hello, ".encodeToByteArray()
        val part2 = "ec0-engine ".encodeToByteArray()
        val part3 = "continuity!".encodeToByteArray()
        val pingPayload = "hb-1".encodeToByteArray()

        stream.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.TEXT, part1, fin = false))
        stream.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.PING, pingPayload, fin = true))
        stream.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.CONTINUATION, part2, fin = false))
        stream.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.CONTINUATION, part3, fin = true))
        stream.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.CLOSE, byteArrayOf(0x03, 0xE8.toByte()), fin = true))

        val input = DataInputStream(ByteArrayInputStream(stream.toByteArray()))
        val receivedPings = mutableListOf<ByteArray>()
        val receivedCloses = mutableListOf<ByteArray>()

        val msg = Ec0WsCodec.readMessage(
            input = input,
            onPing = { receivedPings.add(it) },
            onClose = { receivedCloses.add(it) },
        )
        assertNotNull(msg)
        assertEquals(Ec0WsOpcode.TEXT, msg.opcode)
        assertEquals("Hello, ec0-engine continuity!", msg.asUtf8String())
        assertEquals(1, receivedPings.size)
        assertContentEquals(pingPayload, receivedPings[0])

        val eofClose = Ec0WsCodec.readMessage(
            input = input,
            onPing = { receivedPings.add(it) },
            onClose = { receivedCloses.add(it) },
        )
        assertNull(eofClose)
        assertEquals(1, receivedCloses.size)
    }

    @Test
    fun bulkStreamFrameHeaderAndPayloadRoundtrip() {
        val data = "chunk-payload-bytes-0123456789".encodeToByteArray()
        val digest = Sha256.digest(data)
        val frame = BulkStreamFrame(
            streamId = 42,
            chunkIndex = 7,
            flags = BulkStreamFrame.FLAG_FINAL_CHUNK,
            sha256 = digest,
            payload = data,
        )
        val wire = frame.toBytes()
        assertEquals(BulkStreamFrame.HEADER_BYTES + data.size, wire.size)

        val decoded = BulkStreamFrame.fromBytes(wire)
        assertEquals(42, decoded.streamId)
        assertEquals(7, decoded.chunkIndex)
        assertEquals(BulkStreamFrame.FLAG_FINAL_CHUNK, decoded.flags)
        assertContentEquals(digest, decoded.sha256)
        assertContentEquals(data, decoded.payload)
    }
}
