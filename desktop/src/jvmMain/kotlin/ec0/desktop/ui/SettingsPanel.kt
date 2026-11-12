package ec0.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.desktop.state.SettingsState

/** Settings panel: identity, transport, storage, PIN. */
@Composable
fun SettingsPanel(
    settings: SettingsState,
    onUpdate: (SettingsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.h6)

        Text("Identity", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
        OutlinedTextField(
            value = settings.alias,
            onValueChange = { newValue -> onUpdate(settings.copy(alias = newValue)) },
            label = { Text("Device alias") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.deviceModel,
            onValueChange = { newValue -> onUpdate(settings.copy(deviceModel = newValue)) },
            label = { Text("Device model") },
            modifier = Modifier.fillMaxWidth(),
        )
        DeviceTypeDropdown(settings, onUpdate)

        Text("Transport", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
        OutlinedTextField(
            value = settings.port.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let { port -> onUpdate(settings.copy(port = port)) }
            },
            label = { Text("Server port") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Protocol: ${settings.protocol.wire} (fixed for now — HTTPS/TLS lands with the transport PR)",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )

        Text("Storage", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
        OutlinedTextField(
            value = settings.downloadDir,
            onValueChange = { newValue -> onUpdate(settings.copy(downloadDir = newValue)) },
            label = { Text("Download directory") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Security", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.requirePin,
                onCheckedChange = { checked -> onUpdate(settings.copy(requirePin = checked)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("Require PIN for incoming transfers")
        }
        if (settings.requirePin) {
            OutlinedTextField(
                value = settings.pin,
                onValueChange = { newValue -> onUpdate(settings.copy(pin = newValue)) },
                label = { Text("PIN") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text("Synergy (O+ Connect)", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.synergyProbeEnabled,
                onCheckedChange = { checked -> onUpdate(settings.copy(synergyProbeEnabled = checked)) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Enable Synergy probe (pinned-TLS connect; device list needs T4 capture)",
                style = MaterialTheme.typography.body2,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Settings are in-memory in this PR; persistence lands with the transport PR.",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun DeviceTypeDropdown(settings: SettingsState, onUpdate: (SettingsState) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Device type: ${settings.deviceType.wire}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DeviceType.entries.forEach { type ->
                DropdownMenuItem(onClick = {
                    onUpdate(settings.copy(deviceType = type))
                    expanded = false
                }) {
                    Text(type.wire)
                }
            }
        }
    }
}
