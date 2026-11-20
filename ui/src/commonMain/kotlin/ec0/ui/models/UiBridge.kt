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
 * by the onboarding screen for the phone's in-app scanner to capture.
 */
data class PairingQr(
    val uri: String,
    val size: Int,
    val modules: List<Boolean>,
)

/**
 * A pinned peer for the device-management surface on BOTH desktop and phone.
 */
data class PairedPeerInfo(
    val deviceId: String,
    val alias: String,
    /** First 16 hex chars of SHA-256(publicKeyRaw) — human verification on BOTH screens. */
    val fingerprintPrefix: String,
    val pinnedAtMs: Long,
    val trustLevel: String,
    val connected: Boolean,
    /** Granted capability wire names ("remote_input", "vfs") — per-device grants. */
    val grantedCapabilities: Set<String>,
)

/** Unpair + per-device capability grants (supported on both desktop and Android). */
interface PeerManagementBridge {
    val peers: StateFlow<List<PairedPeerInfo>>
    /** Sends best-effort wire unpair notice, then revokes pin + session. */
    fun unpair(deviceId: String)
    /** Grant/revoke a per-device capability (wire name, e.g. "remote_input"). */
    fun setCapability(deviceId: String, wireName: String, granted: Boolean)
}

/** Remote-input senders — available over a live negotiated session. */
interface RemoteInputBridge {
    val available: StateFlow<Boolean>
    /** Platform opens the full-screen touchpad/keyboard/media controller. */
    fun openTouchpad()
}

/** Phone-camera runtime-permission state for the phone's Settings row. */
data class PhoneCameraGrant(
    val granted: Boolean,
    /** true → the system dialog auto-denies; Enable must bounce to app settings. */
    val needsSystemSettings: Boolean,
    val stateLine: String,
    val detailLine: String,
)

/** Enable action for the phone-camera Settings row (see [PhoneCameraGrant]). */
interface PhoneCameraBridge {
    val grant: StateFlow<PhoneCameraGrant>
    /** Asks Android for the grant, or opens app settings when [PhoneCameraGrant.needsSystemSettings]. */
    fun requestGrant()
}

/** Live link state to the trusted peer — drives the dashboard header and Hero Card. */
data class PeerLinkState(
    val connected: Boolean,
    /** e.g. "USB-C Direct", "Wi-Fi TLS", "searching". */
    val transport: String,
)

/** One notification relayed by the engine, shown on the dashboard tile. */
data class NotificationItem(
    val id: String,
    val appName: String,
    val title: String,
    val body: String,
    val canReply: Boolean,
    val otpCode: String? = null,
    val timestampMs: Long = 0L,
    val appIconB64: String? = null,
    val actions: List<String> = emptyList(),
)

/**
 * Everything the shared UI needs from the host platform. Implementations are
 * thin: desktop wraps [ec0.desktop.state.AppState] + [ec0.desktop.continuity.ContinuityHubController],
 * Android wraps [ec0.android.AndroidAppState]. File refs are opaque (`File` / `List<File>` / `Uri` / `List<Uri>`).
 */
interface UiBridge {
    val devices: StateFlow<List<UiDevice>>
    val transfers: StateFlow<List<TransferItem>>
    val settings: StateFlow<SettingsState>
    val scanning: StateFlow<Boolean>
    val capabilities: Capabilities

    val clipboardSyncEnabled: StateFlow<Boolean> get() = MutableStateFlow(true)
    fun setClipboardSyncEnabled(enabled: Boolean) = Unit

    val notificationRelayEnabled: StateFlow<Boolean> get() = MutableStateFlow(true)
    fun setNotificationRelayEnabled(enabled: Boolean) = Unit

    fun startScan()
    fun cancelTransfer(id: String)
    fun clearFinishedTransfers() {}
    fun updateSettings(transform: (SettingsState) -> SettingsState)

    /** Send an opaque platform file handle (single file or list of files) to the device. */
    fun sendFile(fileRef: Any, device: UiDevice)

    /** Send a file directly to a paired engine peer by deviceId (Hero Card quick action). */
    fun sendFileToPairedPeer(fileRef: Any, deviceId: String) {}

    fun isDarkTheme(): Boolean = false

    val peerManagement: PeerManagementBridge? get() = null
    val remoteInput: RemoteInputBridge? get() = null
    val phoneCamera: PhoneCameraBridge? get() = null

    val smartConnectPairingQr: StateFlow<SmartConnectPairingQr?>
        get() = MutableStateFlow<SmartConnectPairingQr?>(null)

    val pairingQr: StateFlow<PairingQr?> get() = MutableStateFlow<PairingQr?>(null)
    val trustedPeerCount: StateFlow<Int> get() = MutableStateFlow(0)

    // ---- Ecosystem Hero & Dashboard flows ----

    /** Connected/pinned peers with live battery, transport, and verification metadata. */
    val connectedPeers: StateFlow<List<ConnectedPeerSummary>> get() = MutableStateFlow(emptyList())

    /** Live link state to the trusted peer; null when no peer has ever paired. */
    val peerLink: StateFlow<PeerLinkState?> get() = MutableStateFlow(null)

    /** Transient status/honest-error strip (mirror failures, scan results, …). */
    val statusBanner: StateFlow<String?> get() = MutableStateFlow(null)

    /** Screen mirror running? */
    val mirrorRunning: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** Camera pipeline state line (e.g. "idle" / "streaming → /dev/video10"), or null if unavailable. */
    val cameraStateText: StateFlow<String?> get() = MutableStateFlow(null)

    /** Live camera studio controls (lens, torch, zoom, resolution, virtual mic). */
    val cameraControls: StateFlow<CameraControlsState?> get() = MutableStateFlow(null)

    /** Last clipboard sync summary line. */
    val clipboardSummary: StateFlow<String> get() = MutableStateFlow("No clipboard items synced yet")

    /** Whether local clipboard changes auto-sync to the trusted peer. */
    val autoSyncClipboard: StateFlow<Boolean> get() = MutableStateFlow(true)

    /** Relayed notifications (bounded, newest-first). */
    val relayedNotifications: StateFlow<List<NotificationItem>> get() = MutableStateFlow(emptyList())

    /** Live remote media session (Now Playing) mirrored across devices. */
    val mediaSession: StateFlow<MediaSessionUi?> get() = MutableStateFlow(null)

    /** True while Find My Phone siren is actively ringing. */
    val findMyRinging: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** True when USB-C adb tunnel is active (desktop). */
    val usbTunnelActive: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** Active URL handoff received from peer, if any. */
    val remoteHandoffUrl: StateFlow<String?> get() = MutableStateFlow(null)

    /** Platform capability hints for Hero / Settings actions. */
    val canRingPhone: Boolean get() = false
    val canLockPc: Boolean get() = false
    val hasUsbTunnelControl: Boolean get() = false
    val hasDesktopIntegration: Boolean get() = false

    fun startScreenMirror() {}
    fun stopScreenMirror() {}
    fun startCamera() {}
    fun stopCamera() {}
    fun switchCameraLens() {}
    fun setCameraZoom(zoomX100: Int) {}
    fun setCameraFlash(enabled: Boolean) {}
    fun toggleVirtualMic() {}
    fun setAutoSyncClipboard(enabled: Boolean) {}
    fun pushClipboardNow() {}
    fun sendNotificationReply(id: String, text: String) {}
    fun dismissNotification(id: String) {}
    fun copyOtpCode(code: String) {}
    fun sendMediaControl(action: String, volumePct: Int? = null) {}
    fun triggerFindMyPhone(start: Boolean) {}
    fun lockRemoteWorkstation() {}
    fun toggleUsbTunnel() {}
    fun openRemoteHandoff() {}
    fun sendHandoffUrl(url: String) {}
    fun installDesktopIntegration() {}
    fun setupV4l2Loopback() {}

    /** Dynamic tile registry — peer capabilities ∩ local support. */
    val tiles: StateFlow<List<CapabilityTile>> get() = MutableStateFlow(emptyList())

    fun startMirrorFor(deviceId: String) {}
    fun startCameraFor(deviceId: String) {}

    /** Honest outcome of the last v4l2 pre-flight (null = never run). */
    val v4l2PreflightText: StateFlow<String?> get() = MutableStateFlow(null)

    /** Phone file browser (VFS): current listing (null = nothing browsed yet). */
    val vfsBrowseState: StateFlow<VfsBrowseState?> get() = MutableStateFlow(null)

    fun vfsBrowse(path: String) {}
    fun vfsDownload(path: String) {}
    fun vfsUpload(remoteDirPath: String, fileRef: Any) {}

    /** Launchable Android apps advertised by the connected phone for Virtual Display streaming. */
    val phoneApps: StateFlow<List<AndroidAppUi>> get() = MutableStateFlow(emptyList())
    fun launchPhoneApp(packageName: String) {}

    /** Active/ringing calls relayed from the paired phone. */
    val activeCalls: StateFlow<List<ActiveCallUi>> get() = MutableStateFlow(emptyList())
    fun sendCallAction(callId: String, action: String, smsText: String? = null) {}

    /** Triggers a cached non-reply action button (e.g. Mark as Read / Archive) on the phone. */
    fun triggerNotificationAction(notifId: String, actionIndex: Int, label: String) {}

    /** Safely opens or reveals a completed downloaded transfer inside the app's download folder. */
    fun openCompletedTransfer(localPath: String, revealInFolder: Boolean = false) {}

    /** Real-time link RTT telemetry for the Continuity sparkline card. */
    val linkTelemetry: StateFlow<LinkTelemetryUi> get() = MutableStateFlow(LinkTelemetryUi())

    /** Guided system setup & health checklist items with 1-click fixes. */
    val setupChecks: StateFlow<List<SetupCheckItem>> get() = MutableStateFlow(emptyList())
    fun runSetupCheckAction(checkId: String) {}

    /** Desktop autostart, Universal Control screen-edge barrier, BLE proximity, and Instant Hotspot. */
    fun setAutostartOnLogin(enabled: Boolean) {}
    fun setUniversalControlEdge(edge: String) {}
    fun setWalkAwayAutoLock(enabled: Boolean) {}
    val proximitySummary: StateFlow<String> get() = MutableStateFlow("Off")
    val hotspotSsid: StateFlow<String?> get() = MutableStateFlow(null)
    fun joinPhoneHotspot() {}

    /** True while files are being dragged over the desktop window (AirDrop overlay). */
    val isDragOverWindow: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** Platform capability flag: true on Android 12+ where Material You wallpaper colors are supported. */
    val supportsDynamicColor: Boolean get() = false

    /** Incoming transfer awaiting accept/decline decision. */
    val incomingRequest: StateFlow<IncomingRequest?> get() = MutableStateFlow<IncomingRequest?>(null)

    fun acceptIncoming(trust: Boolean = false) {}
    fun declineIncoming() {}
}
