/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Normative constants, domain-separation tags, and pure-Kotlin binary/hex/base64url
 * codecs for ec0-engine (see docs/engine/design.md §1–§4).
 */
package ec0.core.engine

object EngineConstants {
    /** Wire protocol version advertised in mDNS TXT (`v=1`), QR URIs, and handshakes. */
    const val PROTOCOL_VERSION: Int = 1

    /** Default `src_role` carried in every Ec0Envelope (`docs/engine/design.md` §4.1). */
    const val ENGINE_SRC_ROLE: String = "ec0-engine"

    // mDNS / DNS-SD service discovery (RFC 6762 / RFC 6763)
    const val MDNS_MULTICAST_IPV4: String = "224.0.0.251"
    const val MDNS_PORT: Int = 5353
    const val MDNS_SERVICE_TYPE: String = "_ec0._tcp.local."
    const val MDNS_DEFAULT_TTL_SECONDS: Int = 120
    val MDNS_BURST_DELAYS_MS: List<Long> = listOf(100L, 500L, 2_000L)

    // Peer roles advertised in mDNS TXT (`role=...`)
    const val ROLE_PHONE: String = "phone"
    const val ROLE_DESKTOP: String = "desktop"
    const val ROLE_TABLET: String = "tablet"

    // WebSocket upgrade path
    const val WS_PATH: String = "/ec0/v1"

    /**
     * Dedicated primary listener port for ec0-engine (`49880`), deliberately distinct
     * from O+ Synergy (`8080`/`8792`), LocalSend (`53317`), and KDE Connect (`1716`)
     * so all four protocol stacks can run concurrently on the same host without
     * bind conflicts, while sharing the high-port fallback tier (`39888, 49888, 59888`).
     */
    const val PRIMARY_PORT: Int = 49880
    val DEFAULT_PORT_LIST: List<Int> = listOf(49880, 39888, 49888, 59888)

    /** Hotspot / P2P gateway fallback addresses when multicast UDP is filtered. */
    val GATEWAY_FALLBACK_HOSTS: List<String> = listOf(
        "192.168.137.1", // Android / Windows SoftAP hotspot gateway
        "192.168.49.1",  // Android Wi-Fi Direct (P2P) group owner
        "192.168.43.1",  // Legacy Android tether gateway
    )
    val FALLBACK_GATEWAY_IPS: List<String> = GATEWAY_FALLBACK_HOSTS

    // Cryptographic domain-separation ASCII tags (docs/engine/design.md §3–§4)
    const val HS_RESP_TAG: String = "ec0-hs-resp-v1"
    const val HS_INIT_TAG: String = "ec0-hs-init-v1"
    const val SESSION_INFO_TAG: String = "ec0-v1-session"
    const val PAIR_PROOF_TAG: String = "ec0-pair-v1"
    const val ENVELOPE_SIG_TAG: String = "ec0-sig-v1"

    // Key & nonce byte sizes
    const val ED25519_KEY_BYTES: Int = 32
    const val ED25519_SIG_BYTES: Int = 64
    const val X25519_KEY_BYTES: Int = 32
    const val HANDSHAKE_NONCE_BYTES: Int = 16
    const val SESSION_KEY_BYTES: Int = 32
    const val SESSION_ID_BYTES: Int = 32
    const val AEAD_NONCE_BYTES: Int = 12
    const val AEAD_TAG_BYTES: Int = 16
    const val AEAD_HEADER_BYTES: Int = 9 // 1B frameType + 8B big-endian counter
    const val AEAD_AAD_BYTES: Int = 41   // 32B sessionId + 1B frameType + 8B counter

    // AEAD session frameType discriminators (docs/engine/design.md §3.5)
    const val FRAME_AUTH_CONFIRM: Byte = 0x01
    const val FRAME_ENVELOPE_JSON: Byte = 0x02
    const val FRAME_BULK_CHUNK: Byte = 0x03

    // Frame & chunk size limits (DoS protection, docs/engine/design.md §7)
    const val MAX_ENVELOPE_JSON_BYTES: Int = 128 * 1024
    const val DEFAULT_FILE_CHUNK_BYTES: Int = 256 * 1024
    const val MAX_WS_FRAME_BYTES: Int = 512 * 1024
    const val MAX_AEAD_FRAME_BYTES: Int = MAX_WS_FRAME_BYTES

    // Replay window & offline deduplication constants (docs/engine/design.md §4.4)
    const val LIVE_CLOCK_SKEW_MS: Long = 300_000L
    const val OFFLINE_TTL_MS: Long = 3_600_000L
    const val REPLAY_WINDOW_SIZE: Int = 64
    const val OFFLINE_DEDUPE_CAPACITY: Int = 512
    const val QR_NONCE_TTL_MS: Long = 120_000L
}

/** Pure-Kotlin hex and unpadded Base64url codecs + constant-time byte equality. */
object EngineEncoding {
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val B64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val B64URL_DECODE_TABLE: IntArray = IntArray(128) { -1 }.apply {
        for (i in B64URL_ALPHABET.indices) {
            this[B64URL_ALPHABET[i].code] = i
        }
        // Also accept standard '+' and '/' for leniency if a peer emits standard Base64
        this['+'.code] = 62
        this['/'.code] = 63
    }

    fun toHex(bytes: ByteArray): String = bytes.toHex()

    fun fromHex(hex: String): ByteArray = hex.decodeHex()

    fun toBase64UrlUnpadded(bytes: ByteArray): String = bytes.toBase64UrlUnpadded()

    fun decodeBase64Url(encoded: String): ByteArray = encoded.decodeBase64Url()

    fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xff
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return out.concatToString()
    }

    fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length (got $length)" }
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = hexNibble(this[i * 2])
            val lo = hexNibble(this[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> throw IllegalArgumentException("Invalid hex character: '$c'")
    }

    /** RFC 4648 §5 Base64url encoding without '=' padding. */
    fun ByteArray.toBase64UrlUnpadded(): String {
        if (isEmpty()) return ""
        val sb = StringBuilder((size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= size) {
            val triple = ((this[i].toInt() and 0xff) shl 16) or
                ((this[i + 1].toInt() and 0xff) shl 8) or
                (this[i + 2].toInt() and 0xff)
            sb.append(B64URL_ALPHABET[(triple ushr 18) and 0x3f])
            sb.append(B64URL_ALPHABET[(triple ushr 12) and 0x3f])
            sb.append(B64URL_ALPHABET[(triple ushr 6) and 0x3f])
            sb.append(B64URL_ALPHABET[triple and 0x3f])
            i += 3
        }
        val rem = size - i
        if (rem == 1) {
            val b0 = this[i].toInt() and 0xff
            sb.append(B64URL_ALPHABET[(b0 ushr 2) and 0x3f])
            sb.append(B64URL_ALPHABET[(b0 and 0x03) shl 4])
        } else if (rem == 2) {
            val b0 = this[i].toInt() and 0xff
            val b1 = this[i + 1].toInt() and 0xff
            val pair = (b0 shl 8) or b1
            sb.append(B64URL_ALPHABET[(pair ushr 10) and 0x3f])
            sb.append(B64URL_ALPHABET[(pair ushr 4) and 0x3f])
            sb.append(B64URL_ALPHABET[(pair and 0x0f) shl 2])
        }
        return sb.toString()
    }

    /** Decodes RFC 4648 §5 Base64url (with or without '=' padding). */
    fun String.decodeBase64Url(): ByteArray {
        val cleaned = trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        val mod = cleaned.length % 4
        require(mod != 1) { "Invalid Base64url length: ${cleaned.length}" }
        val outLen = (cleaned.length * 3) / 4
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i + 4 <= cleaned.length) {
            val c0 = b64Val(cleaned[i])
            val c1 = b64Val(cleaned[i + 1])
            val c2 = b64Val(cleaned[i + 2])
            val c3 = b64Val(cleaned[i + 3])
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[outIdx++] = (triple ushr 16).toByte()
            out[outIdx++] = (triple ushr 8).toByte()
            out[outIdx++] = triple.toByte()
            i += 4
        }
        val rem = cleaned.length - i
        if (rem == 2) {
            val c0 = b64Val(cleaned[i])
            val c1 = b64Val(cleaned[i + 1])
            require((c1 and 0x0f) == 0) { "Non-canonical Base64url trailing bits" }
            out[outIdx] = ((c0 shl 2) or (c1 ushr 4)).toByte()
        } else if (rem == 3) {
            val c0 = b64Val(cleaned[i])
            val c1 = b64Val(cleaned[i + 1])
            val c2 = b64Val(cleaned[i + 2])
            require((c2 and 0x03) == 0) { "Non-canonical Base64url trailing bits" }
            val pair = (c0 shl 10) or (c1 shl 4) or (c2 ushr 2)
            out[outIdx++] = (pair ushr 8).toByte()
            out[outIdx] = pair.toByte()
        }
        return out
    }

    private fun b64Val(c: Char): Int {
        val code = c.code
        val v = if (code in 0..127) B64URL_DECODE_TABLE[code] else -1
        require(v >= 0) { "Invalid Base64url character: '$c'" }
        return v
    }

    /** Constant-time byte array comparison to prevent timing side channels on MACs/tags/keys. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
