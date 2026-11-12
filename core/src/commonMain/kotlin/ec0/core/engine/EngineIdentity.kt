/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Device identity, capabilities, QR pairing URI codec, and ephemeral-bound
 * pairing_proof derivation (docs/engine/design.md §2.1, §3.1, §8.3).
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.kdeconnect.Sha256

/** Advertised service capabilities in mDNS TXT `caps=...` and presence beacons. */
enum class EngineCapability(val wireName: String) {
    CLIPBOARD("clip"),
    FILE("file"),
    INPUT("input"),
    NOTIFICATION("notif"),
    BATTERY("battery"),
    MIRROR("mirror");

    companion object {
        val NOTIF: EngineCapability = NOTIFICATION
        val PRESENCE: EngineCapability = BATTERY
        val SCRCPY: EngineCapability = MIRROR

        fun fromWireName(name: String): EngineCapability? = when (val token = name.trim().lowercase()) {
            "clip", "clipboard" -> CLIPBOARD
            "file", "files" -> FILE
            "input" -> INPUT
            "notif", "notification" -> NOTIFICATION
            "battery", "presence" -> BATTERY
            "mirror", "scrcpy" -> MIRROR
            else -> entries.firstOrNull { it.wireName == token }
        }

        fun parseCsv(csv: String): Set<EngineCapability> =
            csv.split(',')
                .mapNotNull { token -> fromWireName(token) }
                .toSet()

        fun encodeCsv(caps: Iterable<EngineCapability>): String =
            caps.map { it.wireName }.distinct().joinToString(",")

        fun formatCsv(caps: Iterable<EngineCapability>): String = encodeCsv(caps)
    }
}

/** Discovered or paired peer metadata advertised via mDNS (`_ec0._tcp.local.`). */
data class PeerInfo(
    val deviceId: String,
    val displayName: String,
    val role: String = EngineConstants.ROLE_DESKTOP,
    val protocolVersion: Int = EngineConstants.PROTOCOL_VERSION,
    val capabilities: Set<EngineCapability> = EngineCapability.entries.toSet(),
    val shortFingerprint: String = deviceId.take(12),
    val pairingOpen: Boolean = false,
    val addresses: List<String> = emptyList(),
    val port: Int = EngineConstants.PRIMARY_PORT,
    val lastSeenMs: Long = 0L,
)

/** Pure-Kotlin identity helpers shared by phone and desktop. */
object EngineIdentity {

    private val DEVICE_ID_HEX_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * Computes the canonical 64-character lowercase hex `deviceId` from a raw
     * 32-byte Ed25519 public key (`deviceId = sha256(pk_raw_32B)`).
     */
    fun computeDeviceId(ed25519PublicKeyRaw: ByteArray): String {
        require(ed25519PublicKeyRaw.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 raw public key must be 32 bytes (got ${ed25519PublicKeyRaw.size})"
        }
        return Sha256.digest(ed25519PublicKeyRaw).toHex()
    }

    /** Validates that [deviceId] is a 64-char lowercase hex string matching [ed25519PublicKeyRaw]. */
    fun verifyDeviceId(deviceId: String, ed25519PublicKeyRaw: ByteArray): Boolean {
        if (!DEVICE_ID_HEX_REGEX.matches(deviceId)) return false
        if (ed25519PublicKeyRaw.size != EngineConstants.ED25519_KEY_BYTES) return false
        val expected = computeDeviceId(ed25519PublicKeyRaw)
        return EngineEncoding.constantTimeEquals(
            deviceId.encodeToByteArray(),
            expected.encodeToByteArray(),
        )
    }

    /** First 16 hex chars of `deviceId`, used for mDNS instance prefix and TXT `fp=`. */
    fun shortFingerprint(deviceId: String): String {
        require(DEVICE_ID_HEX_REGEX.matches(deviceId)) { "Invalid deviceId: $deviceId" }
        return deviceId.substring(0, 16)
    }

    /**
     * Order-symmetric 8-character uppercase verification badge for TOFU manual
     * pairing (`SHA-256(sortedConcat(pk_A, pk_B))[0..8)`).
     */
    fun verificationBadge(pubARaw32: ByteArray, pubBRaw32: ByteArray): String {
        require(pubARaw32.size == EngineConstants.ED25519_KEY_BYTES) { "pubA must be 32 bytes" }
        require(pubBRaw32.size == EngineConstants.ED25519_KEY_BYTES) { "pubB must be 32 bytes" }
        val cmp = compareUnsignedLexicographical(pubARaw32, pubBRaw32)
        val combined = ByteArray(64)
        if (cmp <= 0) {
            pubARaw32.copyInto(combined, 0)
            pubBRaw32.copyInto(combined, 32)
        } else {
            pubBRaw32.copyInto(combined, 0)
            pubARaw32.copyInto(combined, 32)
        }
        return Sha256.digest(combined).toHex().substring(0, 8).uppercase()
    }

    /**
     * Computes the ephemeral-bound `pairing_proof` carried in `CLIENT_HELLO`
     * (`docs/engine/design.md` §3.1, Amendment 3):
     *   `HMAC-SHA256(key = nonce_qr_16B, data = "ec0-pair-v1" || pk_I_32B || pk_R_32B || epk_I_32B)`
     * Returns a 64-character lowercase hex string.
     */
    fun computePairingProof(
        nonceQr16: ByteArray,
        ikPubRaw32: ByteArray,
        rkPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
    ): String {
        require(nonceQr16.size == EngineConstants.HANDSHAKE_NONCE_BYTES) { "QR nonce must be 16 bytes" }
        require(ikPubRaw32.size == EngineConstants.ED25519_KEY_BYTES) { "ik_pub must be 32 bytes" }
        require(rkPubRaw32.size == EngineConstants.ED25519_KEY_BYTES) { "rk_pub must be 32 bytes" }
        require(epkIRaw32.size == EngineConstants.X25519_KEY_BYTES) { "epk_I must be 32 bytes" }

        val tag = EngineConstants.PAIR_PROOF_TAG.encodeToByteArray() // 11 bytes ("ec0-pair-v1")
        val input = ByteArray(tag.size + 96) // 107 bytes
        var offset = 0
        tag.copyInto(input, offset); offset += tag.size
        ikPubRaw32.copyInto(input, offset); offset += 32
        rkPubRaw32.copyInto(input, offset); offset += 32
        epkIRaw32.copyInto(input, offset)
        return HkdfSha256.hmacSha256(nonceQr16, input).toHex()
    }

    /** Constant-time verification of an incoming ephemeral-bound [proofHex]. */
    fun verifyPairingProof(
        proofHex: String,
        nonceQr16: ByteArray,
        ikPubRaw32: ByteArray,
        rkPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
    ): Boolean {
        if (proofHex.length != 64) return false
        val actualBytes = try {
            proofHex.decodeHex()
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expectedBytes = computePairingProof(nonceQr16, ikPubRaw32, rkPubRaw32, epkIRaw32).decodeHex()
        return EngineEncoding.constantTimeEquals(actualBytes, expectedBytes)
    }

    private fun compareUnsignedLexicographical(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val ua = a[i].toInt() and 0xff
            val ub = b[i].toInt() and 0xff
            if (ua != ub) return ua.compareTo(ub)
        }
        return a.size.compareTo(b.size)
    }
}

/**
 * QR / URI pairing payload (`ec0://pair?v=1&id=...&pk=...&name=...&nonce=...&port=...&ips=...`).
 * Validated on construction so an invalid or mismatched `id`/`pk` can never be instantiated.
 */
data class PairingQrPayload(
    val version: Int = EngineConstants.PROTOCOL_VERSION,
    val deviceId: String,
    val publicKeyRaw: ByteArray,
    val alias: String,
    val nonce: ByteArray,
    val port: Int = EngineConstants.PRIMARY_PORT,
    val ips: List<String> = emptyList(),
) {
    init {
        require(version == EngineConstants.PROTOCOL_VERSION) {
            "Unsupported pairing URI version: $version"
        }
        require(publicKeyRaw.size == EngineConstants.ED25519_KEY_BYTES) {
            "Pairing public key must be 32 bytes (got ${publicKeyRaw.size})"
        }
        require(EngineIdentity.verifyDeviceId(deviceId, publicKeyRaw)) {
            "Pairing deviceId does not match SHA-256(publicKeyRaw)"
        }
        require(alias.isNotBlank() && alias.encodeToByteArray().size <= 64) {
            "Pairing alias must be non-blank and <= 64 UTF-8 bytes"
        }
        require(nonce.size == EngineConstants.HANDSHAKE_NONCE_BYTES) {
            "Pairing nonce must be 16 bytes (got ${nonce.size})"
        }
        require(port in 1..65535) {
            "Pairing TCP port out of range: $port"
        }
    }

    fun toUri(): String = buildString {
        append(SCHEME_PREFIX)
        append("?v=").append(version)
        append("&id=").append(deviceId)
        append("&pk=").append(publicKeyRaw.toBase64UrlUnpadded())
        append("&name=").append(percentEncode(alias))
        append("&nonce=").append(nonce.toHex())
        append("&port=").append(port)
        if (ips.isNotEmpty()) {
            append("&ips=").append(ips.joinToString(",") { percentEncode(it) })
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingQrPayload) return false
        return version == other.version &&
            deviceId == other.deviceId &&
            publicKeyRaw.contentEquals(other.publicKeyRaw) &&
            alias == other.alias &&
            nonce.contentEquals(other.nonce) &&
            port == other.port &&
            ips == other.ips
    }

    override fun hashCode(): Int = deviceId.hashCode()

    companion object {
        const val SCHEME_PREFIX: String = "ec0://pair"

        fun parseUri(uri: String): PairingQrPayload {
            val trimmed = uri.trim()
            require(trimmed.startsWith("$SCHEME_PREFIX?")) {
                "Invalid pairing URI scheme (expected '$SCHEME_PREFIX?')"
            }
            val query = trimmed.substringAfter('?')
            val params = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                require(eq > 0) { "Malformed query parameter in pairing URI: $part" }
                val k = part.substring(0, eq)
                val v = part.substring(eq + 1)
                params[k] = v
            }
            val version = params["v"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Missing or non-integer 'v' in pairing URI")
            val id = params["id"]
                ?: throw IllegalArgumentException("Missing 'id' in pairing URI")
            val pkB64 = params["pk"]
                ?: throw IllegalArgumentException("Missing 'pk' in pairing URI")
            val nameRaw = params["name"]
                ?: throw IllegalArgumentException("Missing 'name' in pairing URI")
            val nonceHex = params["nonce"]
                ?: throw IllegalArgumentException("Missing 'nonce' in pairing URI")
            val port = params["port"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Missing or non-integer 'port' in pairing URI")
            val ips = params["ips"]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.map { percentDecode(it) }
                ?: emptyList()

            return PairingQrPayload(
                version = version,
                deviceId = id,
                publicKeyRaw = pkB64.decodeBase64Url(),
                alias = percentDecode(nameRaw),
                nonce = nonceHex.decodeHex(),
                port = port,
                ips = ips,
            )
        }

        private fun percentEncode(value: String): String {
            val bytes = value.encodeToByteArray()
            val sb = StringBuilder(bytes.size)
            for (b in bytes) {
                val c = b.toInt() and 0xff
                val isUnreserved = (c in 'a'.code..'z'.code) ||
                    (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
                if (isUnreserved) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    val hex = "0123456789ABCDEF"
                    sb.append(hex[c ushr 4])
                    sb.append(hex[c and 0x0f])
                }
            }
            return sb.toString()
        }

        private fun percentDecode(encoded: String): String {
            val out = ByteArray(encoded.length)
            var outIdx = 0
            var i = 0
            while (i < encoded.length) {
                val c = encoded[i]
                if (c == '%') {
                    require(i + 2 < encoded.length) { "Incomplete percent-encoding in '$encoded'" }
                    val hi = hexVal(encoded[i + 1])
                    val lo = hexVal(encoded[i + 2])
                    out[outIdx++] = ((hi shl 4) or lo).toByte()
                    i += 3
                } else if (c == '+') {
                    out[outIdx++] = ' '.code.toByte()
                    i++
                } else {
                    val bytes = c.toString().encodeToByteArray()
                    for (b in bytes) out[outIdx++] = b
                    i++
                }
            }
            return out.decodeToString(0, outIdx)
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c.code - '0'.code
            in 'a'..'f' -> c.code - 'a'.code + 10
            in 'A'..'F' -> c.code - 'A'.code + 10
            else -> throw IllegalArgumentException("Invalid percent-hex char: '$c'")
        }
    }
}
