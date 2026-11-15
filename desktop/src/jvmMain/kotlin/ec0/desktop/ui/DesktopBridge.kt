/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop host for the shared UI (:ui). Thin adapter over AppState.
 */
package ec0.desktop.ui

import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.ui.models.Capabilities
import ec0.ui.models.SettingsState
import ec0.ui.models.SmartConnectPairingQr
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class DesktopBridge(
    private val appState: AppState,
    private val hub: ContinuityHubController? = null,
    private val smartConnectWsPort: Int? = null,
    qrRefreshScope: CoroutineScope? = null,
) : UiBridge {
    override val devices: StateFlow<List<UiDevice>> = appState.devices
    override val transfers: StateFlow<List<ec0.ui.models.TransferItem>> = appState.transfers
    override val settings: StateFlow<SettingsState> = appState.settings
    override val scanning: StateFlow<Boolean> = appState.scanning

    private val _smartConnectPairingQr: MutableStateFlow<SmartConnectPairingQr?> = MutableStateFlow(
        buildSmartConnectPairingQr()
    )
    override val smartConnectPairingQr: StateFlow<SmartConnectPairingQr?> =
        _smartConnectPairingQr.asStateFlow()

    init {
        // The Smart Connect invite token expires after ~180 s — rotate and rebuild so the
        // rendered QR never goes stale mid-session.
        qrRefreshScope?.launch {
            while (isActive) {
                delay(120_000L)
                runCatching {
                    hub?.node?.smartConnectCoordinator?.rotateNonceIdAndInviteToken()
                    _smartConnectPairingQr.value = buildSmartConnectPairingQr()
                }
            }
        }
    }

    private fun buildSmartConnectPairingQr(): SmartConnectPairingQr? {
        val coordinator = hub?.node?.smartConnectCoordinator ?: return null
        return runCatching {
            val nowMs = System.currentTimeMillis()
            val payload = coordinator.buildPairingQrPayload(nowMs)
            val matrix = coordinator.buildPairingQrMatrix(nowMs)
            SmartConnectPairingQr(
                payload = payload,
                size = matrix.size,
                modules = matrix.toRowMajorList(),
                wsPort = smartConnectWsPort ?: coordinator.boundPort,
                deviceName = coordinator.deviceName,
            )
        }.getOrNull()
    }

    // Honest capability flags: the continuity service attaches via the tray
    // path; receiver + sync slices land in later PRs.
    override val capabilities: Capabilities = Capabilities(
        canReceive = true,
        clipboardSync = false,
        notifications = false,
        photoSync = false,
        remoteInput = false,
    )

    override fun startScan() = appState.startScan()
    override fun cancelTransfer(id: String) = appState.cancelTransfer(id)
    override fun updateSettings(transform: (SettingsState) -> SettingsState) =
        appState.updateSettings(transform)

    override val incomingRequest: StateFlow<ec0.ui.models.IncomingRequest?> = appState.incomingRequest

    override fun acceptIncoming(trust: Boolean) = appState.acceptIncoming(trust)

    override fun declineIncoming() = appState.declineIncoming()

    override fun sendFile(fileRef: Any, device: UiDevice) {
        if (fileRef is File) appState.sendFile(fileRef, device)
    }
}

