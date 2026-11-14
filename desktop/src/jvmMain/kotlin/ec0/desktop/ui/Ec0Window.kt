/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Entry factory used by Main.kt. Renders the shared full-suite UI (:ui)
 * through the DesktopBridge.
 */
package ec0.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ec0.desktop.state.AppState
import ec0.ui.Ec0App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun runApp() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val appState = AppState(scope)
    appState.startScan()

    // Engine + Smart Connect (T16 S-A): identity persists across runs; the
    // coordinator's TLS listener pairs with stock Smart Connect on phones.
    val hub = ec0.desktop.continuity.ContinuityHubController(scope)
    runCatching { hub.node.startServer() }
    val smartConnectPort = runCatching { hub.node.startSmartConnectServer() }.getOrDefault(0)
    val smartConnectQrSupplier: () -> ec0.ui.models.SmartConnectPairingQr? = {
        runCatching {
            val matrix = hub.node.smartConnectCoordinator.buildPairingQrMatrix()
            ec0.ui.models.SmartConnectPairingQr(
                payload = hub.node.smartConnectCoordinator.buildPairingQrPayload(),
                size = matrix.size,
                modules = List(matrix.size * matrix.size) { i ->
                    matrix[i / matrix.size, i % matrix.size]
                },
                wsPort = smartConnectPort,
                deviceName = hub.node.smartConnectCoordinator.deviceName,
            )
        }.getOrNull()
    }

    application {
        val windowState = rememberWindowState(width = 1080.dp, height = 720.dp)
        val bridge = remember { DesktopBridge(appState, smartConnectQrSupplier) }
        Window(onCloseRequest = ::exitApplication, title = "ec0 — unified ecosystem", state = windowState) {
            Ec0App(
                bridge = bridge,
                pickFile = { onPicked -> pickFile(onPicked) },
                compact = false,
            )
        }
    }
}

/** AWT file chooser → opaque handle for the shared UI. Blocks while open (AWT modal). */
private fun pickFile(onPicked: (Any?) -> Unit) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Send a file", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.files.firstOrNull()
    dialog.dispose()
    onPicked(file)
}
