/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Minimal RFC 6455 client-side WebSocket plumbing: HTTP Upgrade handshake +
 * frame codec, implemented over raw TLS sockets. Rationale for not using the
 * ktor-client CIO engine here: the pinned-trust + permissive-hostname +
 * per-attempt port-walk combination isn't cleanly expressible through the
 * CIO TLS settings surface of the pinned ktor version, and upstream achieves
 * exactly this shape with OkHttp + custom TrustManager + permissive verifier
 * (WSCM:447-466). Wire behavior is identical; see docs/re/synergy-transport.md
 * §Deviations.
 */
package ec0.core.synergy

import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 6455 frame opcodes used by the Synergy channel. */
object WsOpcode {
    const val CONTINUATION: Int = 0x0
    const val TEXT: Int = 0x1
    const val BINARY: Int = 0x2
    const val CLOSE: Int = 0x8
    const val PING: Int = 0x9
    const val PONG: Int = 0xA
}

data class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

object WsFrames {

    private val maskRandom = SecureRandom()

    /** Encodes a client frame — client frames are ALWAYS masked (RFC 6455 §5.3). */
    fun encodeClientFrame(opcode: Int, payload: ByteArray): ByteArray {
        val masked = ByteArray(payload.size)
        val mask = ByteArray(4)
        maskRandom.nextBytes(mask)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        val header = mutableListOf<Byte>()
        header.add((0x80 or opcode).toByte()) // FIN + opcode
        when {
            payload.size <= 125 -> header.add((0x80 or payload.size).toByte()) // MASK bit + len
            payload.size <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add((payload.size ushr 8).toByte())
                header.add(payload.size.toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (shift in 56 downTo 0 step 8) {
                    header.add(((payload.size.toLong() ushr shift) and 0xffL).toByte())
                }
            }
        }
        header.addAll(mask.toList())
        return header.toByteArray() + masked
    }

    /** Reads one frame (server→client frames are unmasked per RFC 6455 §5.1). */
    fun readFrame(input: DataInputStream): WsFrame {
        val b0 = input.readUnsignedByte()
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val b1 = input.readUnsignedByte()
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        when (len.toInt()) {
            126 -> len = (input.readUnsignedByte().toLong() shl 8) or input.readUnsignedByte().toLong()
            127 -> {
                len = 0
                repeat(8) { len = (len shl 8) or input.readUnsignedByte().toLong() }
            }
        }
        if (len < 0 || len > Int.MAX_VALUE) throw IOException("Frame too large: $len")
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        if (len > 0) input.readFully(payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(fin, opcode, payload)
    }

    /**
     * Reads one complete TEXT message (fragments reassembled), transparently
     * consuming ping/pong frames. Returns null on a close frame.
     */
    fun readTextMessage(input: DataInputStream): String? {
        val buffer = mutableListOf<Byte>()
        var messageStarted = false
        while (true) {
            val frame = readFrame(input)
            when (frame.opcode) {
                WsOpcode.TEXT, WsOpcode.BINARY -> {
                    buffer.clear()
                    buffer.addAll(frame.payload.toList())
                    messageStarted = true
                }
                WsOpcode.CONTINUATION -> if (messageStarted) buffer.addAll(frame.payload.toList())
                WsOpcode.PING, WsOpcode.PONG -> Unit // consumed; session layer may reply to pings
                WsOpcode.CLOSE -> return null
                else -> throw IOException("Unsupported opcode ${frame.opcode}")
            }
            if (frame.fin && messageStarted) {
                return String(buffer.toByteArray(), Charsets.UTF_8)
            }
        }
    }

    const val WS_GUID: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** RFC 6455 §4.2.2 Sec-WebSocket-Accept value for a given client key. */
    fun acceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey + WS_GUID).toByteArray())
        return Base64.getEncoder().encodeToString(sha1)
    }

    fun buildUpgradeRequest(host: String, port: Int, path: String, key: String): String = buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append(':').append(port).append("\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("User-Agent: ec0/1.0 synergy-transport\r\n")
        append("\r\n")
    }

    /** Parses an HTTP/1.x handshake response; returns (statusCode, headers). */
    fun parseUpgradeResponse(raw: String): Pair<Int, Map<String, String>> {
        val lines = raw.split("\r\n")
        val status = lines.firstOrNull() ?: throw IOException("Empty handshake response")
        val code = Regex("HTTP/1\\.[01] (\\d{3})").find(status)
            ?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Malformed status line: $status")
        val headers = lines.drop(1)
            .takeWhile { it.isNotEmpty() }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }
            .toMap()
        return code to headers
    }

    fun newClientKey(): String {
        val bytes = ByteArray(16)
        maskRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }
}
