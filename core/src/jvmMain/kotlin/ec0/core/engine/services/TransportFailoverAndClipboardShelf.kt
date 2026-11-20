/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Features #8 & #12:
 *   - Feature #8: TransportFailoverCoordinator (HMAC-SHA256 single-use 0-RTT session
 *     migration tokens + Wi-Fi Direct turbo upgrade advisor & safe argv-only connector).
 *   - Feature #12: ClipboardShelfManager (bounded 20-item encrypted/ephemeral cross-device
 *     clipboard shelf, search, pin/unpin, 30s OTP TTL eviction, and Undo last remote overwrite).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.HkdfSha256
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardShelfEntry
import ec0.core.engine.protocol.SessionResumeTokenInfo
import ec0.core.engine.protocol.TransportFailoverProtocol
import ec0.core.engine.protocol.WifiDirectTurboCommand
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ---------------------------------------------------------------------------
// Feature #8 — 0-RTT Transport Migration & Wi-Fi Direct Turbo Upgrade
// ---------------------------------------------------------------------------

enum class TransportTier(val label: String, val priority: Int) {
    USB_C_DIRECT("USB-C Direct", 0),
    WIFI_DIRECT_TURBO("Wi-Fi Direct Turbo", 1),
    WIFI_LAN_TLS("Wi-Fi Encrypted", 2),
    SOFTAP_FALLBACK("Hotspot Gateway", 3),
}

class TransportFailoverCoordinator(
    private val localSecretKey32: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val onRemoteWifiDirectCommand: (WifiDirectTurboCommand) -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val rng = SecureRandom()
    private val issuedTokens = ConcurrentHashMap<String, SessionResumeTokenInfo>()

    private val _activeTier = MutableStateFlow(TransportTier.WIFI_LAN_TLS)
    val activeTier: StateFlow<TransportTier> = _activeTier.asStateFlow()

    private val _turboActive = MutableStateFlow(false)
    val turboActive: StateFlow<Boolean> = _turboActive.asStateFlow()

    fun issueResumeToken(
        peerDeviceId: String,
        epoch: Int,
        ttlMs: Long = DEFAULT_TOKEN_TTL_MS,
    ): SessionResumeTokenInfo {
        pruneExpiredTokens()
        val tokenIdHex = ByteArray(16).also { rng.nextBytes(it) }.toHex()
        val expiresAt = clockMs() + ttlMs.coerceIn(5_000L, 120_000L)
        val macHex = computeTokenMac(tokenIdHex, peerDeviceId.lowercase(), epoch, expiresAt)
        val token = SessionResumeTokenInfo(
            tokenIdHex = tokenIdHex,
            peerDeviceId = peerDeviceId.lowercase(),
            epoch = epoch,
            expiresAtMs = expiresAt,
            macHex = macHex,
        )
        issuedTokens[tokenIdHex] = token
        return token
    }

    /**
     * Constant-time, single-use verification of a 0-RTT transport migration token.
     * Consumes the token on success so it can never be replayed.
     */
    fun verifyAndConsumeResumeToken(candidate: SessionResumeTokenInfo): Boolean {
        pruneExpiredTokens()
        val stored = issuedTokens.remove(candidate.tokenIdHex.lowercase()) ?: return false
        if (clockMs() > stored.expiresAtMs) return false
        if (!stored.peerDeviceId.equals(candidate.peerDeviceId, ignoreCase = true)) return false
        if (stored.epoch != candidate.epoch) return false
        val expectedMac = computeTokenMac(
            stored.tokenIdHex,
            stored.peerDeviceId,
            stored.epoch,
            stored.expiresAtMs,
        ).decodeHex()
        val actualMac = runCatching { candidate.macHex.decodeHex() }.getOrNull() ?: return false
        return EngineEncoding.constantTimeEquals(expectedMac, actualMac)
    }

    /**
     * Recommends Wi-Fi Direct Turbo when a transfer is >= 100 MiB and current LAN
     * throughput is slow (< 12 MB/s) or RTT exceeds 60 ms (and USB-C is not plugged in).
     */
    fun shouldUpgradeToWifiDirect(
        transferTotalBytes: Long,
        lanBytesPerSec: Long,
        rttMs: Long,
        usbTunnelActive: Boolean,
    ): Boolean {
        if (usbTunnelActive) return false
        if (transferTotalBytes < TURBO_MIN_TRANSFER_BYTES) return false
        return (lanBytesPerSec in 1L until TURBO_SLOW_LAN_BPS) || rttMs > TURBO_HIGH_RTT_MS
    }

    fun selectBestTier(
        usbAvailable: Boolean,
        wifiDirectAvailable: Boolean,
        lanAvailable: Boolean,
    ): TransportTier {
        val next = when {
            usbAvailable -> TransportTier.USB_C_DIRECT
            wifiDirectAvailable -> TransportTier.WIFI_DIRECT_TURBO
            lanAvailable -> TransportTier.WIFI_LAN_TLS
            else -> TransportTier.SOFTAP_FALLBACK
        }
        _activeTier.value = next
        return next
    }

    /** Builds a strictly validated argv-only `nmcli` command to join a negotiated P2P group. */
    fun buildSafeP2pJoinCommand(cmd: WifiDirectTurboCommand): List<String> {
        val ssid = requireNotNull(cmd.ssid) { "Missing SSID" }
        val pass = requireNotNull(cmd.passphrase) { "Missing passphrase" }
        require(TransportFailoverProtocol.isValidSafeSsid(ssid)) { "Unsafe SSID" }
        require(TransportFailoverProtocol.isValidSafePassphrase(pass)) { "Unsafe passphrase" }
        return listOf("nmcli", "device", "wifi", "connect", ssid, "password", pass)
    }

    fun requestWifiDirectTurbo(session: Ec0Session?): WifiDirectTurboCommand {
        val cmd = WifiDirectTurboCommand(action = TransportFailoverProtocol.ACTION_REQUEST_P2P)
        session?.sendCmd(
            cmdType = TransportFailoverProtocol.CMD_WIFI_DIRECT_TURBO,
            cmdBody = cmd.toJsonObject(),
            category = TransportFailoverProtocol.CATEGORY_TRANSPORT,
            timestampMs = clockMs(),
        )
        return cmd
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            TransportFailoverProtocol.CMD_WIFI_DIRECT_TURBO -> {
                val cmd = WifiDirectTurboCommand.fromJsonObject(body)
                if (cmd.action == TransportFailoverProtocol.ACTION_P2P_READY && cmd.ssid != null && cmd.passphrase != null) {
                    runCatching {
                        val ok = launcher.runBlockingCommand(buildSafeP2pJoinCommand(cmd), timeoutMs = 8_000L).exitCode == 0
                        _turboActive.value = ok
                        if (ok) _activeTier.value = TransportTier.WIFI_DIRECT_TURBO
                    }
                } else if (cmd.action == TransportFailoverProtocol.ACTION_P2P_TEARDOWN) {
                    _turboActive.value = false
                    _activeTier.value = TransportTier.WIFI_LAN_TLS
                }
                onRemoteWifiDirectCommand(cmd)
                true
            }
            else -> false
        }
    }

    private fun computeTokenMac(tokenIdHex: String, peerId: String, epoch: Int, expiresAtMs: Long): String {
        val msg = "ec0-resume-v1|$tokenIdHex|$peerId|$epoch|$expiresAtMs".encodeToByteArray()
        return HkdfSha256.hmacSha256(localSecretKey32, msg).toHex()
    }

    private fun pruneExpiredTokens() {
        val now = clockMs()
        issuedTokens.entries.removeIf { now > it.value.expiresAtMs }
    }

    companion object {
        const val DEFAULT_TOKEN_TTL_MS: Long = 60_000L
        const val TURBO_MIN_TRANSFER_BYTES: Long = 100L * 1024L * 1024L
        const val TURBO_SLOW_LAN_BPS: Long = 12L * 1024L * 1024L
        const val TURBO_HIGH_RTT_MS: Long = 60L
    }
}

// ---------------------------------------------------------------------------
// Feature #12 — Encrypted/Ephemeral Cross-Device Clipboard Shelf & Undo Toast
// ---------------------------------------------------------------------------

data class ClipboardToastEvent(
    val id: String,
    val message: String,
    val canUndo: Boolean,
    val timestampMs: Long,
)

class ClipboardShelfManager(
    private val maxEntries: Int = 20,
    private val sensitiveTtlMs: Long = 30_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private val items = mutableListOf<ClipboardShelfEntry>()
    private var previousLocalBeforeRemote: ClipboardItem? = null

    private val _entries = MutableStateFlow<List<ClipboardShelfEntry>>(emptyList())
    val entries: StateFlow<List<ClipboardShelfEntry>> = _entries.asStateFlow()

    private val _toastEvent = MutableStateFlow<ClipboardToastEvent?>(null)
    val toastEvent: StateFlow<ClipboardToastEvent?> = _toastEvent.asStateFlow()

    fun recordItem(
        item: ClipboardItem,
        sourceAlias: String,
        isRemote: Boolean,
        previousLocalClip: ClipboardItem? = null,
    ): ClipboardShelfEntry = lock.withLock {
        pruneExpiredLocked()
        val now = clockMs()
        if (isRemote && previousLocalClip != null) {
            previousLocalBeforeRemote = previousLocalClip
        }
        val textContent = if (item.mime.startsWith("text/")) {
            item.bytes.decodeToString().take(MAX_STORED_TEXT_CHARS)
        } else {
            null
        }
        val preview = when {
            item.isSensitive -> "Sensitive 2FA / secret (auto-expires in 30s)"
            textContent != null -> textContent.replace('\n', ' ').trim().take(120)
            else -> "${item.mime} (${item.bytes.size} bytes)"
        }
        val existing = items.firstOrNull { it.sha256Hex == item.sha256Hex }
        val entry = ClipboardShelfEntry(
            id = existing?.id ?: "clip_${item.sha256Hex.take(12)}_$now",
            mime = item.mime,
            previewText = preview.ifBlank { "(empty)" },
            fullText = textContent,
            sizeBytes = item.bytes.size,
            sha256Hex = item.sha256Hex,
            sourceAlias = sourceAlias.take(48),
            isSensitive = item.isSensitive,
            pinned = existing?.pinned == true && !item.isSensitive,
            createdAtMs = now,
            expiresAtMs = if (item.isSensitive) now + sensitiveTtlMs else null,
        )
        items.removeAll { it.sha256Hex == item.sha256Hex }
        items.add(0, entry)
        enforceCapacityLocked()
        publishLocked()

        if (isRemote) {
            _toastEvent.value = ClipboardToastEvent(
                id = entry.id,
                message = "Copied from $sourceAlias",
                canUndo = previousLocalBeforeRemote != null,
                timestampMs = now,
            )
        }
        entry
    }

    fun togglePin(entryId: String): Boolean = lock.withLock {
        val idx = items.indexOfFirst { it.id == entryId }
        if (idx < 0) return false
        val target = items[idx]
        if (target.isSensitive) return false // Never pin sensitive/OTP entries!
        items[idx] = target.copy(pinned = !target.pinned)
        publishLocked()
        true
    }

    fun removeEntry(entryId: String): Boolean = lock.withLock {
        val removed = items.removeAll { it.id == entryId }
        if (removed) publishLocked()
        removed
    }

    fun clearUnpinned() = lock.withLock {
        items.removeAll { !it.pinned }
        publishLocked()
    }

    fun search(query: String): List<ClipboardShelfEntry> = lock.withLock {
        pruneExpiredLocked()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items.toList()
        items.filter { e ->
            !e.isSensitive && (
                e.previewText.lowercase().contains(q) ||
                    (e.fullText?.lowercase()?.contains(q) == true) ||
                    e.sourceAlias.lowercase().contains(q)
                )
        }
    }

    fun consumeUndoSnapshot(): ClipboardItem? = lock.withLock {
        val prev = previousLocalBeforeRemote
        previousLocalBeforeRemote = null
        _toastEvent.value = null
        prev
    }

    fun dismissToast() {
        _toastEvent.value = null
    }

    fun pruneExpired() = lock.withLock {
        if (pruneExpiredLocked()) publishLocked()
    }

    private fun pruneExpiredLocked(): Boolean {
        val now = clockMs()
        return items.removeAll { it.expiresAtMs != null && now >= it.expiresAtMs }
    }

    private fun enforceCapacityLocked() {
        while (items.size > maxEntries) {
            val evictIdx = items.indexOfLast { !it.pinned }
            if (evictIdx >= 0) items.removeAt(evictIdx) else items.removeAt(items.lastIndex)
        }
    }

    private fun publishLocked() {
        _entries.value = items.sortedWith(
            compareByDescending<ClipboardShelfEntry> { it.pinned }.thenByDescending { it.createdAtMs },
        )
    }

    companion object {
        const val MAX_STORED_TEXT_CHARS: Int = 32_768
    }
}
