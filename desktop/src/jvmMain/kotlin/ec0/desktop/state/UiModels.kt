package ec0.desktop.state

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

/** User-controlled settings (persisted in a later PR; in-memory for now). */
data class SettingsState(
    val alias: String = "ec0-desktop",
    val deviceModel: String = "Linux",
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val port: Int = 53317,
    val protocol: ProtocolType = ProtocolType.HTTPS,
    val downloadDir: String = System.getProperty("user.home") + "/Downloads",
    val requirePin: Boolean = false,
    val pin: String = "",
    /** T4-gated Synergy probe — off until discovery constants land. */
    val synergyProbeEnabled: Boolean = false,
)
