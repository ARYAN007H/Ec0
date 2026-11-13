/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.widgets.SectionHeader

@Composable
fun SettingsPanel(bridge: UiBridge) {
    val settings by bridge.settings.collectAsState()
    var aliasDraft by remember(settings.alias) { mutableStateOf(settings.alias) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Settings")

        SettingsCard("Profile") {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = { aliasDraft = it },
                label = { Text("Device alias") },
                singleLine = true,
                shape = Ec0Shapes.chip,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (aliasDraft != settings.alias) {
                        androidx.compose.material3.TextButton(onClick = {
                            bridge.updateSettings { it.copy(alias = aliasDraft.trim().ifBlank { "ec0" }) }
                        }) { Text("Save") }
                    }
                },
            )
            Spacer(Modifier.width(0.dp))
            Text(
                "How peers see this device on the network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val options = listOf("System", "Light", "Dark")
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = when (label) {
                                "System" -> settings.darkTheme == null
                                "Light" -> settings.darkTheme == false
                                else -> settings.darkTheme == true
                            },
                            onClick = {
                                bridge.updateSettings {
                                    it.copy(darkTheme = when (label) {
                                        "System" -> null
                                        "Light" -> false
                                        else -> true
                                    })
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
            }
        }

        SettingsCard("Security") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require receive PIN", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Peers must enter a PIN before sending to this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.requirePin,
                    onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(requirePin = checked) } },
                )
            }
            if (settings.requirePin) {
                OutlinedTextField(
                    value = settings.pin,
                    onValueChange = { v -> bridge.updateSettings { it.copy(pin = v.filter { c -> c.isDigit() }.take(8)) } },
                    label = { Text("PIN (digits)") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SettingsCard("Protocols") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Synergy (O+ Connect) probe", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Probe O+ Connect peers — enabled after the T4 capture lands",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.synergyProbeEnabled,
                    onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(synergyProbeEnabled = checked) } },
                )
            }
        }

        SettingsCard("About") {
            Text("ec0 — unified cross-device ecosystem", style = MaterialTheme.typography.titleSmall)
            Text(
                "LocalSend v2 discovery + transfer · ec0-engine (Ed25519 + AEAD) · Synergy RPC seam\nGPL-3.0 — clean-room interoperability",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(0.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        shape = Ec0Shapes.card,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}
