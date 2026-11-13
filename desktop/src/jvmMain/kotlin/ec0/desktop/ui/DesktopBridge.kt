/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop host for the shared UI (:ui). Thin adapter over AppState.
 */
package ec0.desktop.ui

import ec0.desktop.state.AppState
import ec0.ui.models.Capabilities
import ec0.ui.models.SettingsState
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class DesktopBridge(private val appState: AppState) : UiBridge {
    override val devices: StateFlow<List<UiDevice>> = appState.devices
    override val transfers: StateFlow<List<ec0.ui.models.TransferItem>> = appState.transfers
    override val settings: StateFlow<SettingsState> = appState.settings
    override val scanning: StateFlow<Boolean> = appState.scanning

    // Honest capability flags: the continuity service attaches via the tray
    // path; receiver + sync slices land in later PRs.
    override val capabilities: Capabilities = Capabilities(
        canReceive = false,
        clipboardSync = false,
        notifications = false,
        photoSync = false,
        remoteInput = false,
    )

    override fun startScan() = appState.startScan()
    override fun cancelTransfer(id: String) = appState.cancelTransfer(id)
    override fun updateSettings(transform: (SettingsState) -> SettingsState) =
        appState.updateSettings(transform)

    override fun sendFile(fileRef: Any, device: UiDevice) {
        if (fileRef is File) appState.sendFile(fileRef, device)
    }
}
