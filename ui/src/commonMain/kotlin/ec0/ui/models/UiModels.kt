/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * UI-facing models shared by desktop and Android clients so both ends
 * render one cohesive ecosystem experience.
 */
package ec0.ui.models

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType

/** Which protocol stack a discovered device speaks. */
enum class ProtocolFamily { LOCALSEND, SYNERGY, KDECONNECT }

/** A device shown in the device list. */
data class UiDevice(
    val id: String,
    val alias: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val protocol: ProtocolFamily,
    val port: Int,
    val fingerprint: String,
    /** LAN address the announcement came from (empty for demo devices). */
    val hostAddress: String = "",
    /** Wire scheme of the peer's server: "http" or "https" (from its announce). */
    val wireProtocol: String = "http",
    val download: Boolean = false,
    val online: Boolean = true,
)

/** Direction of a queued transfer. */
enum class TransferDirection { INCOMING, OUTGOING }

/** Lifecycle of a queued transfer. */
enum class TransferState { PENDING, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** One row of the transfer queue. */
data class TransferItem(
    val id: String,
    val fileName: String,
    val direction: TransferDirection,
    val peerAlias: String,
    val sizeBytes: Long,
    val progressPct: Int = 0,
    val state: TransferState = TransferState.PENDING,
    val error: String? = null,
    val speedText: String? = null,
    val etaText: String? = null,
    val encrypted: Boolean = false,
    val localPath: String? = null,
)

/** Launchable Android app advertised for Virtual Display App Streaming. */
data class AndroidAppUi(
    val packageName: String,
    val label: String,
)

/** Active or incoming phone call relayed for the desktop Incoming Call overlay. */
data class ActiveCallUi(
    val callId: String,
    val callerName: String,
    val phoneNumber: String,
    val state: String,
)

/** One checklist item in the guided System Setup & Health panel. */
data class SetupCheckItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val ready: Boolean,
    val actionLabel: String? = null,
)

/** Real-time link RTT telemetry for the Continuity sparkline card. */
data class LinkTelemetryUi(
    val transportLabel: String = "Offline",
    val rttSamplesMs: List<Int> = emptyList(),
    val autoFailoverActive: Boolean = false,
)

/** Live summary of a paired peer for the M3 Expressive Hero Card. */
data class ConnectedPeerSummary(
    val deviceId: String,
    val alias: String,
    val transport: String,
    val connected: Boolean,
    val batteryPct: Int? = null,
    val isCharging: Boolean = false,
    val screenOn: Boolean = true,
    val fingerprintPrefix: String = "",
    val usbActive: Boolean = false,
)

/** Live Now-Playing state mirrored from the paired device's media session. */
data class MediaSessionUi(
    val playerName: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val playing: Boolean,
    val volumePct: Int = 100,
)

/** Live Continuity Camera studio state surfaced directly in the native Camera card. */
data class CameraControlsState(
    val streaming: Boolean = false,
    val stateLine: String = "idle",
    val resolution: String = "1080p",
    val fps: Int = 30,
    val frontCamera: Boolean = false,
    val flashEnabled: Boolean = false,
    val zoomX100: Int = 100,
    val virtualMicActive: Boolean = false,
)

/** User-controlled settings — persisted to disk by each platform bridge. */
@kotlinx.serialization.Serializable
data class SettingsState(
    val alias: String = "ec0-desktop",
    val deviceModel: String = "Linux",
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val port: Int = 53317,
    val protocol: ProtocolType = ProtocolType.HTTPS,
    val downloadDir: String = "",
    val requirePin: Boolean = false,
    val pin: String = "",
    /** Legacy probe toggle — kept for persisted JSON compatibility. */
    val synergyProbeEnabled: Boolean = false,
    /** null = follow system. */
    val darkTheme: Boolean? = null,
    /** M3 Expressive seed id (ec0.ui.theme.Ec0Seed) — user-picked color theme. */
    val themeSeed: String = "amber",
    /** Peers whose incoming transfers are auto-accepted (user trusted them). */
    val trustedFingerprints: Set<String> = emptySet(),
    /**
     * THIS device's direct-LAN fingerprint. Generated once, persisted by the
     * platform bridge so trust survives restarts.
     */
    val ownFingerprint: String = "",
    /** Desktop only: closing the window hides it to the tray instead of exiting. */
    val minimizeToTray: Boolean = true,
    /** Mirror options — fps, bitrate, audio forwarding, and screen-off mode. */
    val mirrorMaxFps: Int = 60,
    val mirrorBitrateMbps: Int = 8,
    val mirrorAudio: Boolean = true,
    val mirrorTurnScreenOff: Boolean = false,
    val mirrorRotation: String = "auto",
    /** Continuity Camera default capture profile. */
    val cameraResolution: String = "1080p",
    val cameraFps: Int = 30,
    val cameraCodec: String = "h264",
    /** Desktop→phone notification relay policy. */
    val notifRelayEnabled: Boolean = false,
    val notifRelayDisabledApps: Set<String> = emptySet(),
    val notifQuietHoursEnabled: Boolean = false,
    val notifQuietStartMin: Int = 22 * 60,
    val notifQuietEndMin: Int = 8 * 60,
    val notifTitleOnly: Boolean = false,
    /** Desktop login autostart (`~/.config/autostart/ec0.desktop`). */
    val autostartOnLogin: Boolean = false,
    /** BLE proximity walk-away auto-lock. */
    val walkAwayAutoLock: Boolean = false,
    /** Universal Control edge barrier ("off", "left", "right", "top", "bottom"). */
    val universalControlEdge: String = "off",
    /** Android 12+ Material You dynamic wallpaper color palette. */
    val dynamicColor: Boolean = true,
    /** Phone opt-in gate for desktop-to-phone VFS file uploads. */
    val allowVfsUploads: Boolean = false,
    /** Automatically relay newly copied Android screenshots/images to paired PC. */
    val autoRelayScreenshots: Boolean = true,
)

/** B6: one phone-side file entry rendered by the VFS browser. */
data class VfsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val thumbB64: String? = null,
    val mimeHint: String? = null,
)

/** B6: VFS browser UI state — parsed listing or an honest error. */
data class VfsBrowseState(
    val path: String? = null,
    val entries: List<VfsEntry> = emptyList(),
    val error: String? = null,
    val lastDownloadedPath: String? = null,
    val lastUploadedPath: String? = null,
)

/** Feature #12: One item in the 20-slot cross-device encrypted clipboard shelf. */
data class ClipboardShelfItemUi(
    val id: String,
    val preview: String,
    val mime: String,
    val sourceDevice: String,
    val pinned: Boolean,
    val sensitive: Boolean,
    val timestampMs: Long,
)

/** Feature #12: Non-intrusive clipboard arrival toast with 1-click Undo. */
data class ClipboardToastUi(
    val id: String,
    val message: String,
    val canUndo: Boolean,
)

/** Feature #5: Persistent SMS/MMS inbox thread summary. */
data class SmsThreadUi(
    val threadId: String,
    val address: String,
    val displayName: String,
    val lastBody: String,
    val lastTimestampMs: Long,
    val messageCount: Int,
)

/** Feature #7: Passive browser tab & video timestamp handoff banner. */
data class BrowserTabHandoffUi(
    val url: String,
    val title: String,
    val resumeUrl: String,
    val mediaPositionSec: Long = 0L,
)

/** Feature #11: Continuity Camera Studio Effects + Adaptive Bitrate state. */
data class CameraEffectsUi(
    val centerStage: Boolean = false,
    val portraitBlur: Boolean = false,
    val deskView: Boolean = false,
    val hwDecode: String = "auto",
    val targetBitrateKbps: Int = 6000,
)

/** Feature #14: One system health diagnostic probe result. */
data class DiagnosticCheckUi(
    val id: String,
    val title: String,
    val severity: String,
    val summary: String,
    val fixHint: String = "",
)

/** Seam between the UI and the discovery transports. */
interface DeviceSource {
    /** One discovery sweep over the configured transports. */
    suspend fun discover(): List<UiDevice>
}

/** Deterministic sample devices for UI development. */
class DemoDeviceSource : DeviceSource {
    override suspend fun discover(): List<UiDevice> = listOf(
        UiDevice(
            id = "demo-phone",
            alias = "A5x",
            deviceModel = "OPPO A5x 5G",
            deviceType = DeviceType.MOBILE,
            protocol = ProtocolFamily.LOCALSEND,
            port = 53317,
            fingerprint = "demo-fingerprint-phone",
            download = true,
        ),
        UiDevice(
            id = "demo-laptop",
            alias = "arch-box",
            deviceModel = "Linux",
            deviceType = DeviceType.DESKTOP,
            protocol = ProtocolFamily.SYNERGY,
            port = 39888,
            fingerprint = "demo-fingerprint-laptop",
        ),
        UiDevice(
            id = "demo-tablet",
            alias = "Pad 2",
            deviceModel = "Lenovo",
            deviceType = DeviceType.HEADLESS,
            protocol = ProtocolFamily.KDECONNECT,
            port = 1716,
            fingerprint = "demo-fingerprint-tablet",
        ),
    )
}
