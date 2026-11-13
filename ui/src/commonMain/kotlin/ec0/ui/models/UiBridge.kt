/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The seam between the shared UI and the platform hosts (desktop AppState /
 * Android app state). Everything the full-suite UI renders flows through
 * here; no panel knows which platform it is on.
 */
package ec0.ui.models

import kotlinx.coroutines.flow.StateFlow

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
}
