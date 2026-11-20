/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Entry factory used by Main.kt. Renders the shared full-suite UI (:ui)
 * through the DesktopBridge.
 */
package ec0.desktop.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.services.NotificationRelayPolicy
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.continuity.ContinuitySystemTray
import ec0.desktop.continuity.ContinuityTrayController
import ec0.desktop.state.AppState
import ec0.ui.Ec0App
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun runApp() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val appState = AppState(scope)
    val hub = ContinuityHubController(
        scope = scope,
        desktopAlias = appState.settings.value.alias,
        desktopNotifRelayEnabledProvider = { appState.settings.value.notifRelayEnabled },
        desktopNotificationPolicyProvider = {
            val s = appState.settings.value
            NotificationRelayPolicy(
                disabledApps = s.notifRelayDisabledApps,
                quietHoursEnabled = s.notifQuietHoursEnabled,
                quietStartMinuteOfDay = s.notifQuietStartMin,
                quietEndMinuteOfDay = s.notifQuietEndMin,
                titleOnly = s.notifTitleOnly,
            )
        },
    )
    val smartConnectPort = runCatching {
        hub.node.startServer(startMdns = true)
        val port = hub.node.startSmartConnectServer(announceMdns = true)
        writePrivateStatusFile(
            "status=running\nwsPort=$port\ndeviceName=${hub.node.smartConnectCoordinator.deviceName}\n",
        )
        port
    }.getOrElse { err ->
        writePrivateStatusFile("status=error\nerror=${err.message ?: err.javaClass.simpleName}\n")
        hub.node.smartConnectCoordinator.boundPort
    }
    appState.startScan()
    runCatching { hub.start(startMdns = false) }
    // Restore persisted continuity policies on startup
    val initialSettings = appState.settings.value
    if (initialSettings.universalControlEdge != "off") {
        hub.configureUniversalControlEdge(initialSettings.universalControlEdge)
    }
    if (initialSettings.walkAwayAutoLock) {
        hub.setWalkAwayAutoLock(true)
    }

    val trayController = ContinuityTrayController(hub)
    val quitRef = java.util.concurrent.atomic.AtomicReference<() -> Unit>({})
    val systemTray = ContinuitySystemTray(
        controller = trayController,
        onQuit = {
            runCatching { hub.close() }
            quitRef.get().invoke()
        },
        onSendClipboard = { hub.setAutoSyncClipboard(true) },
    )
    systemTray.attach()
    scope.launch {
        appState.settings.collect { trayController.setMinimizeToTrayOnClose(it.minimizeToTray) }
    }
    application {
        quitRef.set {
            runCatching { hub.close() }
            exitApplication()
        }
        val windowState = rememberWindowState(width = 1120.dp, height = 760.dp)
        val bridge = remember { DesktopBridge(appState, hub, smartConnectPort, scope) }
        val windowVisible by trayController.windowVisible.collectAsState()
        val connectedPeers by bridge.connectedPeers.collectAsState()

        val peerSummary = connectedPeers.firstOrNull { it.connected }
        val trayTip = if (peerSummary != null) {
            val bat = peerSummary.batteryPct?.let { " · $it%" }.orEmpty()
            "ec0 — ${peerSummary.alias} (${peerSummary.transport}$bat)"
        } else {
            "ec0 — unified ecosystem"
        }
        systemTray.updateTooltip(trayTip)

        Window(
            onCloseRequest = {
                if (!trayController.onWindowCloseRequested()) {
                    runCatching { hub.close() }
                    exitApplication()
                }
            },
            visible = windowVisible,
            title = "ec0 — unified ecosystem",
            state = windowState,
        ) {
            DisposableEffect(window) {
                val target = DropTarget(
                    window.contentPane,
                    DnDConstants.ACTION_COPY,
                    object : DropTargetAdapter() {
                        override fun dragEnter(dtde: DropTargetDragEvent) {
                            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                                bridge.setDragOverWindow(true)
                            } else {
                                dtde.rejectDrag()
                            }
                        }

                        override fun dragExit(dte: DropTargetEvent) {
                            bridge.setDragOverWindow(false)
                        }

                        override fun drop(dtde: DropTargetDropEvent) {
                            bridge.setDragOverWindow(false)
                            if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                dtde.rejectDrop()
                                return
                            }
                            dtde.acceptDrop(DnDConstants.ACTION_COPY)
                            val dropped = runCatching {
                                @Suppress("UNCHECKED_CAST")
                                dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                            }.getOrNull().orEmpty()
                            if (dropped.isNotEmpty()) {
                                bridge.handleDroppedFiles(dropped)
                            }
                            dtde.dropComplete(dropped.isNotEmpty())
                        }
                    },
                    true,
                )
                window.contentPane.dropTarget = target
                onDispose {
                    window.contentPane.dropTarget = null
                    bridge.setDragOverWindow(false)
                }
            }
            Ec0App(
                bridge = bridge,
                pickFile = { onPicked -> pickFile(onPicked) },
                compact = false,
            )
        }
    }
}

/**
 * Audit M5: never write diagnostics or device identifiers to world-writable `/tmp`.
 * Status is written atomically with 0600 permissions inside the 0700 user config dir.
 */
private fun writePrivateStatusFile(content: String) {
    runCatching {
        val dir = EncryptedIdentityStore.defaultConfigDir()
        EncryptedIdentityStore.ensureDirectoryPermissions(dir)
        EncryptedIdentityStore.atomicWriteText(File(dir, "smartconnect-status.txt"), content)
    }
}

/** AWT multi-file chooser → opaque handle (`File` or `List<File>`) for the shared UI. */
private fun pickFile(onPicked: (Any?) -> Unit) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Send files", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val selected = dialog.files?.toList().orEmpty()
    dialog.dispose()
    val payload: Any? = when (selected.size) {
        0 -> null
        1 -> selected.first()
        else -> selected
    }
    onPicked(payload)
}
