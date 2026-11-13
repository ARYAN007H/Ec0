/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * UI-facing models shared by desktop and Android clients (moved from
 * ec0.desktop.state so the phone app reuses the exact same device model,
 * transfer queue and settings shape).
 */
package ec0.core.uimodels

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
