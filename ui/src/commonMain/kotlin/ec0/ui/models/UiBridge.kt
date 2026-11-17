/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The seam between the shared UI and the platform hosts (desktop AppState /
 * Android app state). Everything the full-suite UI renders flows through
 * here; no panel knows which platform it is on.
 */
package ec0.ui.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One offered file of a pending incoming transfer, UI-safe view. */
data class IncomingFileInfo(val fileName: String, val size: Long)

/** An incoming transfer awaiting the user's decision (accept / decline / trust). */
data class IncomingRequest(
    val senderAlias: String,
    val senderFingerprint: String,
    val senderIp: String,
    val files: List<IncomingFileInfo>,
)

/** Which full-suite features are live on this platform — panels render REAL states only. */
data class Capabilities(
    /** Receiver server accepting incoming connections. */
    val canReceive: Boolean,
    /** Live clipboard sync attached to an engine/OS bridge. */
    val clipboardSync: Boolean,
    /** Notification relay listener active. */
    val notifications: Boolean,
    /** Photo auto-sync job running. */
    val photoSync: Boolean,
    /** Remote input / mirror host available. */
    val remoteInput: Boolean,
)

/**
 * Smart Connect pairing QR code (`motorolardpconnection{...}`) for stock
 * Motorola Smart Connect Android clients to scan.
 */
data class SmartConnectPairingQr(
    val payload: String,
    val size: Int,
    val modules: List<Boolean>,
    val wsPort: Int,
    val deviceName: String,
)

/**
 * ec0↔ec0 pairing QR (`ec0://pair?...`, engine PairingCoordinator) — rendered
 * by the onboarding screen for the phone's in-app scanner to capture. The URI
 * carries a single-use nonce that expires (~120 s); the host refreshes it.
 */
data class PairingQr(
    val uri: String,
    val size: Int,
    val modules: List<Boolean>,
)

/** Live link state to the trusted peer — drives the dashboard header and tile honesty. */
data class PeerLinkState(
    val connected: Boolean,
    /** e.g. "USB tunnel", "Wi-Fi TLS", "searching". */
    val transport: String,
)

/** One phone notification relayed by the engine, shown on the dashboard tile. */
data class NotificationItem(
    val id: String,
    val appName: String,
    val title: String,
    val body: String,
    val canReply: Boolean,
)

/**
 * Everything the shared UI needs from the host platform. Implementations are
 * thin: desktop wraps [ec0.desktop.state.AppState]-shaped state, Android wraps
 * its own state holder. File refs are opaque (java.io.File / android Uri).
 */
interface UiBridge {
    val devices: StateFlow<List<UiDevice>>
    val transfers: StateFlow<List<TransferItem>>
    val settings: StateFlow<SettingsState>
    val scanning: StateFlow<Boolean>
    val capabilities: Capabilities

    fun startScan()
    fun cancelTransfer(id: String)
    fun updateSettings(transform: (SettingsState) -> SettingsState)

    /** Send an opaque platform file handle to the device (host picks the real sender). */
    fun sendFile(fileRef: Any, device: UiDevice)

    /** Human label for the last raw protocol error on a transfer, or null. */
    fun isDarkTheme(): Boolean = false

    /**
     * Smart Connect pairing QR (`motorolardpconnection{...}`) rendered in Settings.
     * Platforms without a Smart Connect PC listener keep this null.
     */
    val smartConnectPairingQr: StateFlow<SmartConnectPairingQr?>
        get() = MutableStateFlow<SmartConnectPairingQr?>(null)

    /**
     * ec0↔ec0 pairing QR for the onboarding screen. Null while the engine's
     * pairing service is unavailable (Android before the companion runtime).
     */
    val pairingQr: StateFlow<PairingQr?> get() = MutableStateFlow<PairingQr?>(null)

    /** Pinned (trusted) engine peers — 0 means this end has never paired. */
    val trustedPeerCount: StateFlow<Int> get() = MutableStateFlow(0)

    // ---- Dashboard tiles (HOME). Desktop maps these from ContinuityHubController;
    // platforms without the engine keep the defaults — tiles render honest OFF states. ----

    /** Live link state to the trusted peer; null when no peer has ever paired. */
    val peerLink: StateFlow<PeerLinkState?> get() = MutableStateFlow(null)

    /** Transient status/honest-error strip (mirror failures, scan results, …). */
    val statusBanner: StateFlow<String?> get() = MutableStateFlow(null)

    /** scrcpy mirror running? */
    val mirrorRunning: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** Camera pipeline state line (e.g. "idle" / "streaming → /dev/video10"), or null if unavailable. */
    val cameraStateText: StateFlow<String?> get() = MutableStateFlow(null)

    /** Last clipboard sync summary line. */
    val clipboardSummary: StateFlow<String> get() = MutableStateFlow("No clipboard items synced yet")

    /** Whether local clipboard changes auto-sync to the trusted peer. */
    val autoSyncClipboard: StateFlow<Boolean> get() = MutableStateFlow(true)

    /** Phone notifications relayed by the engine (bounded, newest-first). */
    val relayedNotifications: StateFlow<List<NotificationItem>> get() = MutableStateFlow(emptyList())

    fun startScreenMirror() {}
    fun stopScreenMirror() {}
    fun startCamera() {}
    fun stopCamera() {}
    fun setAutoSyncClipboard(enabled: Boolean) {}
    fun sendNotificationReply(id: String, text: String) {}
    fun setupV4l2Loopback() {}

    /**
     * B3 dynamic tile registry — peer capabilities ∩ local support, ordered
     * canonically, one tile per (capability, deviceId). Default: empty
     * (registry-less hosts render their own static set).
     */
    val tiles: StateFlow<List<CapabilityTile>> get() = MutableStateFlow(emptyList())

    /** DeviceId-scoped mirror start (registry actions). Default: no-op host. */
    fun startMirrorFor(deviceId: String) {}

    /** DeviceId-scoped camera start (registry actions). Default: no-op host. */
    fun startCameraFor(deviceId: String) {}

    /** Honest outcome of the last "Load v4l2" pre-flight (null = never run). */
    val v4l2PreflightText: StateFlow<String?> get() = MutableStateFlow(null)

    /** B6 VFS browser: current listing (null = nothing browsed yet). */
    val vfsBrowseState: StateFlow<VfsBrowseState?> get() = MutableStateFlow(null)

    /** B6 VFS browser: request a directory listing from the phone. */
    fun vfsBrowse(path: String) {}

    /** B6 VFS browser: stream a file from the phone ("download here"). */
    fun vfsDownload(path: String) {}

    /**
     * Incoming transfer awaiting the user's accept/decline decision —
     * non-null while the dialog should show. Platforms without a receiver
     * keep this null forever.
     */
    val incomingRequest: StateFlow<IncomingRequest?> get() = MutableStateFlow<IncomingRequest?>(null)

    /** Accept the pending incoming transfer (all offered files), optionally trusting the sender. */
    fun acceptIncoming(trust: Boolean = false) {}

    /** Decline the pending incoming transfer (→ 403 to the sender). */
    fun declineIncoming() {}
}
