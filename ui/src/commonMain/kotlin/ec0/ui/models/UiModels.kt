/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * UI-facing models shared by desktop and Android clients (moved from
 * ec0.desktop.state so the phone app reuses the exact same device model,
 * transfer queue and settings shape).
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
    /** T4-gated Synergy probe — off until discovery constants land. */
    val synergyProbeEnabled: Boolean = false,
    /** null = follow system. */
    val darkTheme: Boolean? = null,
    /** M3 Expressive seed id (ec0.ui.theme.Ec0Seed) — user-picked color theme. */
    val themeSeed: String = "amber",
    /** Peers whose incoming transfers are auto-accepted (user trusted them). */
    val trustedFingerprints: Set<String> = emptySet(),
    /**
     * THIS device's LocalSend fingerprint. Peers trust THIS id — it must survive
     * restarts (a per-boot random value makes every peer's stored trust stale on
     * the next service start). Generated once, persisted by the platform bridge.
     */
    val ownFingerprint: String = "",
    /** Desktop only: closing the window hides it to the tray instead of exiting. */
    val minimizeToTray: Boolean = true,
    /** B5 mirror options — fps/bitrate wire through the mirror command today. */
    val mirrorMaxFps: Int = 60,
    val mirrorBitrateMbps: Int = 8,
    /** Pending coordinator support (ScrcpyMirrorCoordinator is protected). */
    val mirrorAudio: Boolean = true,
    val mirrorRotation: String = "auto",
    /** B6 VFS browser state — read-only listing from the phone's granted roots. */
    val notifRelayEnabled: Boolean = false,
    val notifRelayDisabledApps: Set<String> = emptySet(),
    val notifQuietHoursEnabled: Boolean = false,
    val notifQuietStartMin: Int = 22 * 60,
    val notifQuietEndMin: Int = 8 * 60,
    val notifTitleOnly: Boolean = false,
)

/** B6: one phone-side file entry rendered by the VFS browser. */
data class VfsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
)

/** B6: VFS browser UI state — parsed listing or an honest error. */
data class VfsBrowseState(
    val path: String? = null,
    val entries: List<VfsEntry> = emptyList(),
    val error: String? = null,
)

/**
 * Seam between the UI and the protocol stacks. [RealDeviceSource] (in
 * `ec0.core.localsend.client`) is the production source (LocalSend UDP :53317
 * works today; the Synergy probe is T4-gated). [DemoDeviceSource] remains
 * available for UI-only development.
 */
interface DeviceSource {
    /** One discovery sweep over the configured transports. */
    suspend fun discover(): List<UiDevice>
}

/**
 * Deterministic sample devices for UI development. Explicitly a demo seam —
 * NOT real discovery.
 */
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
