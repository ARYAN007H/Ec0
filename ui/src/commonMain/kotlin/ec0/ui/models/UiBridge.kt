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
