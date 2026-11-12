/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * RFC 6455 codec tests — masking, length encoding, accept-key derivation
 * (RFC 6455 §1.3 example vector), upgrade request/response parsing.
 */
package ec0.core.synergy

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Rfc6455Test {

    @Test
    fun acceptKeyMatchesRfcExample() {
        // RFC 6455 §1.3: key "dGhlIHNhbXBsZSBub25jZQ==" →
        // "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun clientFrameIsMaskedAndDecodable() {
        val payload = "hello synergy".toByteArray()
        val frame = WsFrames.encodeClientFrame(WsOpcode.TEXT, payload)

        // Mask bit set in the second header byte
        assertEquals(0x80, frame[1].toInt() and 0x80)

        // Roundtrip through the reader (server frames are unmasked; the decoder
        // handles both — verify masking was applied by checking the payload
        // differs pre-mask for a distinct mask… simply assert decode succeeds
        // and content matches, since readFrame un-unmasks with the embedded key).
        val decoded = WsFrames.readFrame(DataInputStream(ByteArrayInputStream(frame)))
        assertTrue(decoded.fin)
        assertEquals(WsOpcode.TEXT, decoded.opcode)
        assertEquals("hello synergy", String(decoded.payload))
    }

    @Test
    fun extendedPayloadLength126() {
        val payload = ByteArray(300) { 'x'.code.toByte() }
        val frame = WsFrames.encodeClientFrame(WsOpcode.BINARY, payload)
        val decoded = WsFrames.readFrame(DataInputStream(ByteArrayInputStream(frame)))
        assertEquals(300, decoded.payload.size)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun largePayloadLength127() {
        val payload = ByteArray(70_000) { 'y'.code.toByte() }
        val frame = WsFrames.encodeClientFrame(WsOpcode.BINARY, payload)
        val decoded = WsFrames.readFrame(DataInputStream(ByteArrayInputStream(frame)))
        assertEquals(70_000, decoded.payload.size)
    }

    @Test
    fun upgradeRequestShape() {
        val request = WsFrames.buildUpgradeRequest("192.168.49.1", 49888, "/", "SAMPLEKEY")
        assertTrue(request.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: 192.168.49.1:49888\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
        assertTrue(request.contains("Connection: Upgrade\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Key: SAMPLEKEY\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun upgradeResponseParsing() {
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
            "\r\n"
        val (code, headers) = WsFrames.parseUpgradeResponse(response)
        assertEquals(101, code)
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers["sec-websocket-accept"])
    }

    @Test
    fun upgradeResponseRejection() {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        val (code, _) = WsFrames.parseUpgradeResponse(response)
        assertEquals(404, code)
    }

    @Test
    fun acceptKeyMismatchIsDetectable() {
        val key = WsFrames.newClientKey()
        val bogusAccept = WsFrames.acceptKey("different-key")
        assertTrue(bogusAccept != WsFrames.acceptKey(key))
    }

    @Test
    fun closeAndPingRoundtrip() {
        val close = WsFrames.encodeClientFrame(WsOpcode.CLOSE, ByteArray(0))
        val decodedClose = WsFrames.readFrame(DataInputStream(ByteArrayInputStream(close)))
        assertEquals(WsOpcode.CLOSE, decodedClose.opcode)

        val pingBody = "hb".toByteArray()
        val ping = WsFrames.encodeClientFrame(WsOpcode.PING, pingBody)
        val decodedPing = WsFrames.readFrame(DataInputStream(ByteArrayInputStream(ping)))
        assertEquals(WsOpcode.PING, decodedPing.opcode)
        assertTrue(pingBody.contentEquals(decodedPing.payload))
    }

    @Test
    fun fragmentedMessageReassembles() {
        // Server → client: FIN=0 TEXT "hel", FIN=0 CONT "lo ", FIN=1 CONT "world"
        val stream = byteArrayOf(
            0x01.toByte(), 0x03.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), // unmasked, len 3
            0x00.toByte(), 0x03.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), ' '.code.toByte(),
            0x80.toByte(), 0x05.toByte(), 'w'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'l'.code.toByte(), 'd'.code.toByte(),
        )
        val message = WsFrames.readTextMessage(DataInputStream(ByteArrayInputStream(stream)))
        assertEquals("hello world", message)
    }

    @Test
    fun textMessageWithPingInterleaved() {
        val stream = byteArrayOf(
            0x81.toByte(), 0x03.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte(),
            0x89.toByte(), 0x00.toByte(), // unmasked ping, empty
        )
        // readTextMessage consumes the ping and returns the prior completed message
        val message = WsFrames.readTextMessage(DataInputStream(ByteArrayInputStream(stream)))
        assertEquals("hi!", message)
    }

    @Test
    fun closeFrameReturnsNull() {
        val stream = byteArrayOf(0x88.toByte(), 0x00.toByte())
        val message = WsFrames.readTextMessage(DataInputStream(ByteArrayInputStream(stream)))
        assertEquals(null, message)
    }

    @Test
    fun assertFailsOnOversizedFrame() {
        // 127-length header claiming 3 GiB with no payload → read fails with EOF
        val stream = byteArrayOf(
            0x81.toByte(), 127,
            0, 0, 0, 0xC0.toByte(), 0, 0, 0, 0, // 3 GiB
        )
        assertFailsWith<Exception> {
            WsFrames.readFrame(DataInputStream(ByteArrayInputStream(stream)))
        }
    }
}
