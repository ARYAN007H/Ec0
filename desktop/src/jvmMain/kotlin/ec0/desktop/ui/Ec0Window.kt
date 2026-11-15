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
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.ui.Ec0App
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun runApp() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val appState = AppState(scope)
    val hub = ContinuityHubController(
        scope = scope,
        desktopAlias = appState.settings.value.alias,
    )
    val smartConnectPort = runCatching {
        hub.node.startServer(startMdns = true)
        val port = hub.node.startSmartConnectServer(announceMdns = true)
        runCatching {
            File("/tmp/ec0-smartconnect-status.txt").writeText(
                "status=running\nwsPort=$port\ndeviceName=${hub.node.smartConnectCoordinator.deviceName}\n" +
                    "realId=${hub.node.smartConnectCoordinator.realDeviceIdBase64}\n"
            )
        }
        port
    }.getOrElse { err ->
        runCatching {
            File("/tmp/ec0-smartconnect-status.txt").writeText("status=error\nerror=${err.stackTraceToString()}\n")
        }
        hub.node.smartConnectCoordinator.boundPort
    }
    appState.startScan()
    // Wake the hub's loops: clipboard watch, notification forwarding, IPC daemon,
    // pairing-QR refresh and the trusted-peer reconnect supervisor.
    runCatching { hub.start(startMdns = false) }
    application {
        val windowState = rememberWindowState(width = 1080.dp, height = 720.dp)
        val bridge = remember { DesktopBridge(appState, hub, smartConnectPort, scope) }
        Window(
            onCloseRequest = {
                runCatching { hub.close() }
                exitApplication()
            },
            title = "ec0 — unified ecosystem",
            state = windowState,
        ) {
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
