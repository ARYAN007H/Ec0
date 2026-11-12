package ec0.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.NavigationRail
import androidx.compose.material.NavigationRailItem
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ec0.desktop.state.AppState
import ec0.desktop.state.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Top-level navigation destinations. */
enum class Panel { DEVICES, TRANSFERS, SETTINGS }

/**
 * Entry factory used by Main.kt. Owns the [AppState] lifecycle for the
 * window.
 */
fun runApp() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val appState = AppState(scope)
    appState.startScan()
    application {
        val windowState = rememberWindowState(width = 900.dp, height = 640.dp)
        Window(onCloseRequest = ::exitApplication, title = "ec0 — unified ecosystem", state = windowState) {
            MaterialTheme {
                MainScaffold(appState)
            }
        }
    }
}

@Composable
private fun MainScaffold(appState: AppState) {
    var current by remember { mutableStateOf(Panel.DEVICES) }
    val devices by appState.devices.collectAsState()
    val scanning by appState.scanning.collectAsState()
    val transfers by appState.transfers.collectAsState()
    val settings by appState.settings.collectAsState()
    val activeCount = transfers.count { it.state in AppState.ACTIVE_STATES }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ec0") }) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail {
                NavigationRailItem(
                    selected = current == Panel.DEVICES,
                    onClick = { current = Panel.DEVICES },
                    icon = { Text("📱") },
                    label = { Text("Devices") },
                )
                NavigationRailItem(
                    selected = current == Panel.TRANSFERS,
                    onClick = { current = Panel.TRANSFERS },
                    icon = { Text("⇅") },
                    label = {
                        Text(if (activeCount > 0) "Transfers ($activeCount)" else "Transfers")
                    },
                )
                NavigationRailItem(
                    selected = current == Panel.SETTINGS,
                    onClick = { current = Panel.SETTINGS },
                    icon = { Text("⚙") },
                    label = { Text("Settings") },
                )
            }
            when (current) {
                Panel.DEVICES -> DeviceListPanel(
                    devices = devices,
                    scanning = scanning,
                    onRescan = appState::startScan,
                    onSend = { device ->
                        // Demo enqueue — real outgoing sends land with the transport PR.
                        appState.enqueueOutgoing("send-test.png", 1_200_000, device)
                    },
                    modifier = Modifier.weight(1f),
                )
                Panel.TRANSFERS -> TransferQueuePanel(
                    transfers = transfers,
                    onCancel = appState::cancelTransfer,
                    onClearFinished = appState::clearFinished,
                    modifier = Modifier.weight(1f),
                )
                Panel.SETTINGS -> SettingsPanel(
                    settings = settings,
                    onUpdate = { newState -> appState.updateSettings { newState } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
