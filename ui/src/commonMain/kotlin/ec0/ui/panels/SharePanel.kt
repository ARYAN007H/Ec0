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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.widgets.EmptyState
import ec0.ui.widgets.SectionHeader

@Composable
fun SharePanel(bridge: UiBridge, pickFile: (onPicked: (Any?) -> Unit) -> Unit) {
    val devices by bridge.devices.collectAsState()
    val connectedPeers by bridge.connectedPeers.collectAsState()
    val scanning by bridge.scanning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Files & Share")

        // Paired encrypted peers: 1-tap encrypted resumable transfer
        val livePaired = connectedPeers.filter { it.connected }
        if (livePaired.isNotEmpty()) {
            Card(
                shape = Ec0Shapes.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = ec0Colors().success, modifier = Modifier.size(18.dp))
                        Text(
                            "Encrypted direct send",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "Resumable, SHA-256 verified transfers over your paired device link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    livePaired.forEach { peer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.alias, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    peer.transport,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = {
                                    pickFile { ref -> ref?.let { bridge.sendFileToPairedPeer(it, peer.deviceId) } }
                                },
                                shape = Ec0Shapes.pill,
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Send files")
                            }
                        }
                    }
                }
            }
        }

        // Phone storage browser (desktop has active VFS responder on phone)
        VfsPanel(bridge)

        SectionHeader(
            "Nearby on Wi-Fi",
            badge = devices.size.toString(),
            trailing = {
                FilledTonalButton(
                    onClick = bridge::startScan,
                    enabled = !scanning,
                    shape = Ec0Shapes.pill,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Scan")
                }
            },
        )

        if (devices.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Share,
                title = "No nearby devices found",
                hint = "Devices running ec0 on your local network appear here automatically",
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
