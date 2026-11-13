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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.widgets.DeviceCard
import ec0.ui.widgets.EmptyState
import ec0.ui.widgets.SectionHeader

@Composable
fun SharePanel(bridge: UiBridge, pickFile: (onPicked: (Any?) -> Unit) -> Unit) {
    val devices by bridge.devices.collectAsState()
    val scanning by bridge.scanning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Share")

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = bridge::startScan,
                enabled = !scanning,
                shape = Ec0Shapes.pill,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Scanning" else "Refresh nearby")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Tap a device, pick a file — it streams straight over your LAN",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (devices.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Share,
                title = "No peers in range",
                hint = "Peers running ec0 or LocalSend appear here — scan, then tap one to send",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                devices.forEach { device ->
                    DeviceCard(device) {
                        pickFile { ref -> ref?.let { bridge.sendFile(it, device) } }
                    }
                }
            }
        }
    }
}
