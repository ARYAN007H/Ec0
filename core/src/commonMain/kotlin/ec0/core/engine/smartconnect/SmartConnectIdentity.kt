package ec0.core.engine.smartconnect

import ec0.core.engine.QrCodeMatrix
import ec0.core.kdeconnect.Sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Smart Connect device identity, salted-hash `nonceId` rotation/matching, serial number (`sn`)
 * non-empty guard, 180s multi-use invite token manager, and `motorolardpconnection{...}` QR
 * code generator.
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §3-S-A.
 */
@OptIn(ExperimentalEncodingApi::class)
object SmartConnectIdentity {

    private val compactJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private const val ALPHANUMERIC_CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * Generates a persistent 16-byte raw `realDeviceId` encoded as standard 24-character Base64.
     */
    fun generateRealDeviceIdBase64(random: Random = Random.Default): String {
        val raw16 = ByteArray(16)
        random.nextBytes(raw16)
        return Base64.Default.encode(raw16)
    }

    /**
     * Decodes a Base64 device ID (`realDeviceId` or `nonceId`) and enforces exact 16-byte length.
     */
    fun decodeId16(idBase64: String): ByteArray {
        val trimmed = idBase64.trim()
        require(trimmed.isNotEmpty()) { "Device ID Base64 must not be blank" }
        val decoded = Base64.Default.decode(trimmed)
        require(decoded.size == 16) {
            "Expected 16-byte decoded device ID, got ${decoded.size} bytes"
        }
        return decoded
    }

    /**
     * Computes a rotating 16-byte salted-hash `nonceId` from a 16-byte `realDeviceId` and an
     * 8-byte random `salt8`:
     * ```
     * digest32 = SHA-256(realDeviceIdRaw16 || salt8)
     * nonceIdRaw16 = salt8[0..8) || digest32[0..8)
     * ```
     * Golden vector (§3-S-A):
     * - `realDeviceIdRaw16 = 000102030405060708090a0b0c0d0e0f`
     * - `salt8 = 1011121314151617`
     * - `nonceIdRaw16 = 10111213141516171d64add2a6388367` (`"EBESExQVFhcdZK3SpjiDZw=="`)
     */
    fun generateNonceIdRaw(
        realDeviceIdRaw16: ByteArray,
        salt8: ByteArray? = null,
        random: Random = Random.Default
    ): ByteArray {
        require(realDeviceIdRaw16.size == 16) {
            "realDeviceIdRaw16 must be 16 bytes (got ${realDeviceIdRaw16.size})"
        }
        val actualSalt = salt8 ?: ByteArray(8).also { random.nextBytes(it) }
        require(actualSalt.size == 8) {
            "salt8 must be 8 bytes (got ${actualSalt.size})"
        }
        val input = ByteArray(24)
        realDeviceIdRaw16.copyInto(input, destinationOffset = 0, startIndex = 0, endIndex = 16)
        actualSalt.copyInto(input, destinationOffset = 16, startIndex = 0, endIndex = 8)
        val digest = Sha256.hash(input)
        val nonceId = ByteArray(16)
        actualSalt.copyInto(nonceId, destinationOffset = 0, startIndex = 0, endIndex = 8)
        digest.copyInto(nonceId, destinationOffset = 8, startIndex = 0, endIndex = 8)
        return nonceId
    }

    /**
     * Generates a Base64-encoded 16-byte `nonceId` from a Base64-encoded `realDeviceId`.
     */
    fun generateNonceIdBase64(
        realDeviceIdBase64: String,
        salt8: ByteArray? = null,
        random: Random = Random.Default
    ): String {
        val realRaw = decodeId16(realDeviceIdBase64)
        val nonceRaw = generateNonceIdRaw(realRaw, salt8, random)
        return Base64.Default.encode(nonceRaw)
    }

    /**
     * Verifies whether a 16-byte `candidateNonceIdRaw16` matches a known 16-byte
     * `pairedRealDeviceIdRaw16` using constant-time 8-byte digest prefix comparison.
     * Also returns true if `candidateNonceIdRaw16` is byte-for-byte identical to
     * `pairedRealDeviceIdRaw16` (some older APK paths pass `realDeviceId` directly).
     */
    fun verifyNonceIdAgainstRealId(
        candidateNonceIdRaw16: ByteArray,
        pairedRealDeviceIdRaw16: ByteArray
    ): Boolean {
        if (candidateNonceIdRaw16.size != 16 || pairedRealDeviceIdRaw16.size != 16) {
            return false
        }
        if (constantTimeEquals(candidateNonceIdRaw16, pairedRealDeviceIdRaw16)) {
            return true
        }
        val salt8 = candidateNonceIdRaw16.copyOfRange(0, 8)
        val expectedNonce = generateNonceIdRaw(pairedRealDeviceIdRaw16, salt8)
        return constantTimeEquals(candidateNonceIdRaw16, expectedNonce)
    }

    /**
     * Matches an incoming 16-byte `nonceId` (from mDNS TXT `deviceId`, BLE `0x5246` `ServiceData`,
     * or a `SealedDiscoveryFrame` header) against a collection of paired peer records.
     */
    fun <T> matchNonceIdToPairedPeer(
        candidateNonceIdRaw16: ByteArray,
        pairedPeers: Iterable<T>,
        realDeviceIdSelector: (T) -> String
    ): T? {
        if (candidateNonceIdRaw16.size != 16) return null
        for (peer in pairedPeers) {
            val realIdB64 = realDeviceIdSelector(peer)
            val realIdRaw = runCatching { decodeId16(realIdB64) }.getOrNull() ?: continue
            if (verifyNonceIdAgainstRealId(candidateNonceIdRaw16, realIdRaw)) {
                return peer
            }
        }
        return null
    }

    /**
     * Matches a Base64-encoded `nonceId` against paired peer records.
     */
    fun <T> matchNonceIdBase64ToPairedPeer(
        candidateNonceIdBase64: String,
        pairedPeers: Iterable<T>,
        realDeviceIdSelector: (T) -> String
    ): T? {
        val candidateRaw = runCatching { decodeId16(candidateNonceIdBase64) }.getOrNull() ?: return null
        return matchNonceIdToPairedPeer(candidateRaw, pairedPeers, realDeviceIdSelector)
    }

    /**
     * Enforces the `sn` non-empty guard (§3-S-A):
     * The stock phone APK rejects `svc 0` verification if `sn` is empty (`TextUtils.isEmpty(sn)`
     * in `c0.o.w`). If `existingSn` is null or blank, generates a stable RFC 4122 v4 UUID string.
     */
    fun ensureNonEmptySerialNumber(existingSn: String?, random: Random = Random.Default): String {
        val trimmed = existingSn?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return generateUuidV4(random)
    }

    /**
     * Generates an RFC 4122 version 4 UUID string without requiring platform-specific APIs.
     */
    fun generateUuidV4(random: Random = Random.Default): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.toHexLower()
        return buildString(36) {
            append(hex.substring(0, 8))
            append('-')
            append(hex.substring(8, 12))
            append('-')
            append(hex.substring(12, 16))
            append('-')
            append(hex.substring(16, 20))
            append('-')
            append(hex.substring(20, 32))
        }
    }

    /**
     * Generates a random 32-byte per-pair symmetric AES-256 key encoded as Base64 (44 chars).
     */
    fun generatePerPairKeyBase64(random: Random = Random.Default): String {
        val key32 = ByteArray(32)
        random.nextBytes(key32)
        return Base64.Default.encode(key32)
    }

    /**
     * Generates a random `~44`-character alphanumeric invite token (`invitation`).
     */
    fun generateInviteToken(length: Int = 44, random: Random = Random.Default): String {
        require(length in 16..128) { "Invite token length must be between 16 and 128" }
        return buildString(length) {
            repeat(length) {
                append(ALPHANUMERIC_CHARS[random.nextInt(ALPHANUMERIC_CHARS.length)])
            }
        }
    }

    /**
     * Builds the `motorolardpconnection{"wsData":{...},"version":"3.0.0"}` QR payload string
     * per `docs/engine/smart-connect-design.md` §3-S-A.
     *
     * Note: `wsData.deviceId` is the current rotating `nonceId` (24-char Base64 of 16 bytes),
     * `wsData.fp` is the 64-char lowercase hex SHA-256 fingerprint of the PC's TLS leaf DER
     * certificate, and `wsData.invitation` is the 44-char alphanumeric invite token.
     */
    fun buildQrPayloadString(
        ips: List<String>,
        wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
        nonceIdBase64: String,
        deviceName: String,
        certFingerprintHex: String,
        invitation: String,
        timestampMs: Long
    ): String {
        require(ips.isNotEmpty()) { "At least one LAN IPv4 address is required for QR pairing" }
        require(wsPort in 1..65535) { "Invalid wsPort: $wsPort" }
        decodeId16(nonceIdBase64) // Validate 16-byte Base64 nonceId
        val normalizedFp = certFingerprintHex.trim().lowercase()
        require(normalizedFp.length == 64 && normalizedFp.all { it in '0'..'9' || it in 'a'..'f' }) {
            "certFingerprintHex must be 64 hex chars"
        }
        require(invitation.isNotBlank()) { "invitation token must not be blank" }

        val envelope = SmartConnectQrEnvelope(
            wsData = SmartConnectQrWsData(
                ips = ips,
                wsPort = wsPort,
                deviceId = nonceIdBase64.trim(),
                deviceName = deviceName.ifBlank { "EC0-PC" },
                type = SmartConnectConstants.PC_TYPE_LAPTOP,
                os = SmartConnectConstants.PC_OS_WINDOWS,
                appType = SmartConnectConstants.PC_APP_TYPE_DESKTOP,
                scType = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
                cap = SmartConnectConstants.PC_BASE_CAP,
                fp = normalizedFp,
                invitation = invitation,
                net = SmartConnectConstants.PC_NET_DEFAULT,
                timestamp = timestampMs
            ),
            version = SmartConnectConstants.ENVELOPE_VERSION
        )
        return SmartConnectConstants.QR_PREFIX + compactJson.encodeToString(envelope)
    }

    /**
     * Parses a `motorolardpconnection{...}` QR payload string back into [SmartConnectQrEnvelope].
     */
    fun parseQrPayloadString(qrPayload: String): SmartConnectQrEnvelope {
        val trimmed = qrPayload.trim()
        require(trimmed.startsWith(SmartConnectConstants.QR_PREFIX)) {
            "QR payload must start with '${SmartConnectConstants.QR_PREFIX}'"
        }
        val jsonPart = trimmed.removePrefix(SmartConnectConstants.QR_PREFIX)
        return compactJson.decodeFromString<SmartConnectQrEnvelope>(jsonPart)
    }

    /**
     * Encodes a `motorolardpconnection{...}` QR payload into a boolean matrix via [QrCodeMatrix].
     */
    fun buildQrMatrix(
        ips: List<String>,
        wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
        nonceIdBase64: String,
        deviceName: String,
        certFingerprintHex: String,
        invitation: String,
        timestampMs: Long
    ): QrCodeMatrix.Matrix {
        val payload = buildQrPayloadString(
            ips = ips,
            wsPort = wsPort,
            nonceIdBase64 = nonceIdBase64,
            deviceName = deviceName,
            certFingerprintHex = certFingerprintHex,
            invitation = invitation,
            timestampMs = timestampMs
        )
        return QrCodeMatrix.encode(payload, QrCodeMatrix.EccLevel.L)
    }

    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    internal fun ByteArray.toHexLower(): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xff
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0f]
        }
        return out.concatToString()
    }
}

/**
 * Multi-use TTL-bounded invite token tracker (`180s` TTL, NOT single-use within TTL because
 * the phone often reconnects twice on first pair).
 *
 * Avoids anonymous `LinkedHashMap` subclasses to prevent K2 2.1.10 compiler backend issues.
 */
class SmartConnectInviteTokenStore(
    private val ttlMs: Long = SmartConnectConstants.INVITE_TOKEN_TTL_MS,
    private val maxTokens: Int = 32
) {
    private val issuedTokens = mutableMapOf<String, Long>()

    /**
     * Registers or refreshes an invite token at [nowMs].
     */
    fun issueToken(
        nowMs: Long,
        token: String = SmartConnectIdentity.generateInviteToken()
    ): String {
        pruneExpired(nowMs)
        if (issuedTokens.size >= maxTokens && !issuedTokens.containsKey(token)) {
            val oldestKey = issuedTokens.entries.minByOrNull { it.value }?.key
            if (oldestKey != null) {
                issuedTokens.remove(oldestKey)
            }
        }
        issuedTokens[token] = nowMs
        return token
    }

    /**
     * Validates [candidateToken] at [nowMs]. Returns `true` if the token was issued within
     * [ttlMs]. Does NOT consume/remove the token so repeated phone handshakes within the 180s
     * window succeed.
     */
    fun isValid(candidateToken: String?, nowMs: Long): Boolean {
        if (candidateToken.isNullOrBlank()) return false
        pruneExpired(nowMs)
        val issuedAt = issuedTokens[candidateToken.trim()] ?: return false
        return (nowMs - issuedAt) in 0..ttlMs
    }

    fun pruneExpired(nowMs: Long) {
        val iterator = issuedTokens.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > ttlMs) {
                iterator.remove()
            }
        }
    }
}

@Serializable
data class SmartConnectQrEnvelope(
    val wsData: SmartConnectQrWsData,
    val version: String = SmartConnectConstants.ENVELOPE_VERSION
)

@Serializable
data class SmartConnectQrWsData(
    val ips: List<String>,
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val deviceId: String,
    val deviceName: String,
    val type: Int = SmartConnectConstants.PC_TYPE_LAPTOP,
    val os: Int = SmartConnectConstants.PC_OS_WINDOWS,
    val appType: Int = SmartConnectConstants.PC_APP_TYPE_DESKTOP,
    val scType: Int = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
    val cap: Int = SmartConnectConstants.PC_BASE_CAP,
    val fp: String,
    val invitation: String,
    val net: Int = SmartConnectConstants.PC_NET_DEFAULT,
    val timestamp: Long
)
