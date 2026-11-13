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
    application {
        val windowState = rememberWindowState(width = 1080.dp, height = 720.dp)
        val bridge = remember { DesktopBridge(appState) }
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
