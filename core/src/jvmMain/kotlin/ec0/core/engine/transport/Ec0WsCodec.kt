/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Full client + server RFC 6455 WebSocket HTTP upgrade and frame codec
 * for ec0-engine. Supports both masked client frames and unmasked server
 * frames, TEXT and BINARY opcodes, CONTINUATION reassembly, and PING/PONG/CLOSE.
 */
package ec0.core.engine.transport

import ec0.core.engine.EngineConstants
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Ec0WsOpcode {
    const val CONTINUATION: Int = 0x0
    const val TEXT: Int = 0x1
    const val BINARY: Int = 0x2
    const val CLOSE: Int = 0x8
    const val PING: Int = 0x9
    const val PONG: Int = 0xA
}

data class Ec0WsFrame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray,
    val masked: Boolean = false,
)

data class Ec0WsMessage(
    val opcode: Int,
    val payload: ByteArray,
) {
    fun asUtf8String(): String = String(payload, Charsets.UTF_8)
}

data class Ec0WsUpgradeRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
) {
    val clientKey: String
        get() = headers["sec-websocket-key"]
            ?: throw IOException("Missing Sec-WebSocket-Key header")
}

/**
 * Structured 41-byte header + payload for binary stream frames (`frameType = 0x03`):
 * `[streamId: 4B][chunkIndex: 4B][flags: 1B][sha256: 32B][payload: N bytes]`
 */
data class BulkStreamFrame(
    val streamId: Int,
    val chunkIndex: Int,
    val flags: Int,
    val sha256: ByteArray,
    val payload: ByteArray,
) {
    init {
        require(sha256.size == 32) { "BulkStreamFrame sha256 must be 32 bytes" }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        writeIntBigEndian(out, 0, streamId)
        writeIntBigEndian(out, 4, chunkIndex)
        out[8] = (flags and 0xFF).toByte()
        sha256.copyInto(out, destinationOffset = 9)
        payload.copyInto(out, destinationOffset = HEADER_BYTES)
        return out
    }

    companion object {
        const val HEADER_BYTES: Int = 41
        const val FLAG_DATA: Int = 0x00
        const val FLAG_FINAL_CHUNK: Int = 0x01
        const val FLAG_STREAM_ABORT: Int = 0x02

        fun fromBytes(bytes: ByteArray): BulkStreamFrame {
            require(bytes.size >= HEADER_BYTES) {
                "BulkStreamFrame requires at least $HEADER_BYTES bytes (got ${bytes.size})"
            }
            val streamId = readIntBigEndian(bytes, 0)
            val chunkIndex = readIntBigEndian(bytes, 4)
            val flags = bytes[8].toInt() and 0xFF
            val sha256 = bytes.copyOfRange(9, 41)
            val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
            return BulkStreamFrame(
                streamId = streamId,
                chunkIndex = chunkIndex,
                flags = flags,
                sha256 = sha256,
                payload = payload,
            )
        }

        private fun writeIntBigEndian(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = ((value ushr 24) and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }

        private fun readIntBigEndian(buf: ByteArray, offset: Int): Int {
            val b0 = buf[offset].toInt() and 0xFF
            val b1 = buf[offset + 1].toInt() and 0xFF
            val b2 = buf[offset + 2].toInt() and 0xFF
            val b3 = buf[offset + 3].toInt() and 0xFF
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }
}

object Ec0WsCodec {
    const val WS_GUID: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val maskRandom = SecureRandom()

    fun newClientKey(): String {
        val bytes = ByteArray(16)
        maskRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Computes the RFC 6455 §4.2.2 `Sec-WebSocket-Accept` value for [clientKey].
     */
    fun acceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest((clientKey.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
    }

    /**
     * Encodes a client-to-server WebSocket frame (ALWAYS masked per RFC 6455 §5.3).
     */
    fun encodeClientFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        explicitMaskKey: ByteArray? = null,
    ): ByteArray {
        val mask = explicitMaskKey ?: ByteArray(4).also { maskRandom.nextBytes(it) }
        require(mask.size == 4) { "WebSocket mask key must be 4 bytes" }
        return encodeFrameInternal(opcode = opcode, payload = payload, fin = fin, mask = mask)
    }

    /**
     * Encodes a server-to-client WebSocket frame (unmasked per RFC 6455 §5.1).
     */
    fun encodeServerFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
    ): ByteArray = encodeFrameInternal(opcode = opcode, payload = payload, fin = fin, mask = null)

    private fun encodeFrameInternal(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean,
        mask: ByteArray?,
    ): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        val b0 = (if (fin) 0x80 else 0x00) or (opcode and 0x0F)
        out.write(b0)

        val maskBit = if (mask != null) 0x80 else 0x00
        when {
            payload.size <= 125 -> {
                out.write(maskBit or payload.size)
            }
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                val lenLong = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((lenLong ushr shift) and 0xFFL).toInt())
                }
            }
        }

        if (mask != null) {
            out.write(mask)
            val maskedPayload = ByteArray(payload.size)
            for (i in payload.indices) {
                maskedPayload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            out.write(maskedPayload)
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    /**
     * Reads a single RFC 6455 frame from [input], enforcing [maxPayloadBytes].
     */
    fun readFrame(
        input: DataInputStream,
        maxPayloadBytes: Int = EngineConstants.MAX_AEAD_FRAME_BYTES + 65_536,
    ): Ec0WsFrame {
        val b0 = input.readUnsignedByte()
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val b1 = input.readUnsignedByte()
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        when (len.toInt()) {
            126 -> {
                len = (input.readUnsignedByte().toLong() shl 8) or input.readUnsignedByte().toLong()
            }
            127 -> {
                len = 0L
                repeat(8) {
                    len = (len shl 8) or input.readUnsignedByte().toLong()
                }
            }
        }
        if (len < 0L || len > maxPayloadBytes.toLong()) {
            throw IOException("WebSocket frame length $len exceeds cap $maxPayloadBytes")
        }
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        if (payload.isNotEmpty()) {
            input.readFully(payload)
        }
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
        }
        return Ec0WsFrame(fin = fin, opcode = opcode, payload = payload, masked = masked)
    }

    /**
     * Reads one complete WebSocket message (reassembling `CONTINUATION` fragments),
     * automatically replying to `PING` via [onPing] and echoing `CLOSE` via [onClose].
     * Returns `null` when a `CLOSE` frame is received.
     */
    fun readMessage(
        input: DataInputStream,
        onPing: (ByteArray) -> Unit = {},
        onClose: (ByteArray) -> Unit = {},
        maxMessageBytes: Int = EngineConstants.MAX_AEAD_FRAME_BYTES + 65_536,
    ): Ec0WsMessage? {
        var initialOpcode = -1
        var buffer: ByteArrayOutputStream? = null

        while (true) {
            val frame = readFrame(input, maxMessageBytes)
            when (frame.opcode) {
                Ec0WsOpcode.TEXT, Ec0WsOpcode.BINARY -> {
                    if (buffer != null) {
                        throw IOException("Received new data frame (${frame.opcode}) before previous fragmented message completed")
                    }
                    if (frame.fin) {
                        return Ec0WsMessage(frame.opcode, frame.payload)
                    }
                    initialOpcode = frame.opcode
                    buffer = ByteArrayOutputStream(frame.payload.size * 2)
                    buffer.write(frame.payload)
                }
                Ec0WsOpcode.CONTINUATION -> {
                    val activeBuf = buffer
                        ?: throw IOException("Received CONTINUATION frame without initial TEXT/BINARY frame")
                    if (activeBuf.size() + frame.payload.size > maxMessageBytes) {
                        throw IOException("Reassembled WebSocket message exceeds cap $maxMessageBytes")
                    }
                    activeBuf.write(frame.payload)
                    if (frame.fin) {
                        return Ec0WsMessage(initialOpcode, activeBuf.toByteArray())
                    }
                }
                Ec0WsOpcode.PING -> onPing(frame.payload)
                Ec0WsOpcode.PONG -> Unit
                Ec0WsOpcode.CLOSE -> {
                    onClose(frame.payload)
                    return null
                }
                else -> throw IOException("Unsupported WebSocket opcode: 0x${frame.opcode.toString(16)}")
            }
        }
    }

    fun buildClientUpgradeRequest(
        host: String,
        port: Int,
        path: String = EngineConstants.WS_PATH,
        clientKey: String,
    ): String = buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append(':').append(port).append("\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ").append(clientKey).append("\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("User-Agent: ec0-engine/1.0\r\n")
        append("\r\n")
    }

    fun buildServerUpgradeResponse(clientKey: String): String = buildString {
        val accept = acceptKey(clientKey)
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
        append("Server: ec0-engine/1.0\r\n")
        append("\r\n")
    }

    fun parseServerUpgradeResponse(raw: String): Pair<Int, Map<String, String>> {
        val lines = raw.split("\r\n")
        val statusLine = lines.firstOrNull() ?: throw IOException("Empty HTTP upgrade response")
        val code = Regex("HTTP/1\\.[01] (\\d{3})").find(statusLine)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Malformed HTTP status line: $statusLine")
        val headers = parseHeaders(lines.drop(1))
        return code to headers
    }

    fun parseClientUpgradeRequest(raw: String): Ec0WsUpgradeRequest {
        val lines = raw.split("\r\n")
        val requestLine = lines.firstOrNull() ?: throw IOException("Empty HTTP upgrade request")
        val parts = requestLine.split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) {
            throw IOException("Malformed HTTP request line: $requestLine")
        }
        val method = parts[0].uppercase()
        val path = parts[1]
        if (method != "GET") {
            throw IOException("WebSocket upgrade requires GET (got $method)")
        }
        val headers = parseHeaders(lines.drop(1))
        val upgrade = headers["upgrade"]?.lowercase()
        if (upgrade != "websocket") {
            throw IOException("Missing or invalid Upgrade header: $upgrade")
        }
        val version = headers["sec-websocket-version"]
        if (version != null && version != "13") {
            throw IOException("Unsupported Sec-WebSocket-Version: $version")
        }
        if (headers["sec-websocket-key"].isNullOrBlank()) {
            throw IOException("Missing Sec-WebSocket-Key header")
        }
        return Ec0WsUpgradeRequest(method = method, path = path, headers = headers)
    }

    /**
     * Reads HTTP headers byte-by-byte until `\r\n\r\n` so zero WebSocket frame bytes
     * are consumed into a buffered reader.
     */
    fun readHttpHeaderBlock(input: InputStream, maxHeaderBytes: Int = 16_384): String {
        val out = ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < maxHeaderBytes) {
            val b = input.read()
            if (b == -1) throw IOException("EOF while reading HTTP upgrade headers")
            out.write(b)
            val c = b.toChar()
            state = when {
                c == '\r' && (state == 0 || state == 2) -> state + 1
                c == '\n' && (state == 1 || state == 3) -> state + 1
                c == '\r' -> 1
                else -> 0
            }
            if (state == 4) {
                return out.toString(Charsets.US_ASCII)
            }
        }
        throw IOException("HTTP upgrade headers exceeded $maxHeaderBytes bytes")
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return map
    }
}
