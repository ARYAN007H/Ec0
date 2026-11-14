/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Home dashboard: this-device hero, nearby device grid, live transfer strip.
 * Everything on this screen is real state — no filler.
 */
package ec0.ui.panels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.ui.models.Capabilities
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.theme.protocolAccent
import ec0.ui.widgets.DeviceTypeIcon
import ec0.ui.widgets.ProtocolChip
import ec0.ui.widgets.SectionHeader
import ec0.ui.widgets.TransferStrip

@Composable
fun HomePanel(bridge: UiBridge, pickFile: (onPicked: (Any?) -> Unit) -> Unit) {
    val devices by bridge.devices.collectAsState()
    val transfers by bridge.transfers.collectAsState()
    val scanning by bridge.scanning.collectAsState()
    val settings by bridge.settings.collectAsState()
    val incoming by bridge.incomingRequest.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        incoming?.let { request ->
            IncomingRequestCard(
                request = request,
                onAccept = { bridge.acceptIncoming(trust = false) },
                onAcceptTrust = { bridge.acceptIncoming(trust = true) },
                onDecline = { bridge.declineIncoming() },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, Ec0Shapes.hero)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = deviceIcon(settings.deviceType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        settings.alias,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${settings.deviceModel} · ${bridge.capabilities.label()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = bridge::startScan,
                    enabled = !scanning,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (scanning) "Scanning" else "Scan")
                }
            }
        }

        SectionHeader("Nearby devices", badge = devices.size.toString())

        if (devices.isEmpty()) {
            EmptyNearby(scanning)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.height(gridHeight(devices.size)),
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(device) {
                        pickFile { ref -> ref?.let { bridge.sendFile(it, device) } }
                    }
                }
            }
        }

        if (transfers.isNotEmpty()) {
            SectionHeader("Active transfers")
            TransferStrip(transfers)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EmptyNearby(scanning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by transition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.1f,
                infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "pulseScale",
            )
            Icon(
                Icons.Filled.SensorDoor,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size((52 * scale).dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (scanning) "Listening for neighbors…" else "Nobody around yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Peers running ec0 or LocalSend appear here automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Device tile with protocol accent, hover lift (desktop), tap → send flow. */
@Composable
fun DeviceCard(
    device: UiDevice,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) 4.dp else 0.dp, label = "lift")
    val accent = protocolAccent(device.protocol)

    Card(
        shape = Ec0Shapes.card,
        interactionSource = interaction,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = lift),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.16f), Ec0Shapes.tile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        DeviceTypeIcon.forDevice(device),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        device.deviceModel ?: "Unknown model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OnlineDot(device.online)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProtocolChip(device.protocol, accent)
                Text(
                    "${device.hostAddress}:${device.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onClick,
                shape = Ec0Shapes.pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send a file")
            }
        }
    }
}

@Composable
fun OnlineDot(online: Boolean) {
    val color = if (online) ec0Colors().success else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.MOBILE -> Icons.Filled.PhoneAndroid
    else -> Icons.Filled.Computer
}

private fun gridHeight(count: Int): Dp = (((count + 1) / 2) * 175).dp

private fun Capabilities.label(): String = buildString {
    if (canReceive) append("Receiving")
    if (clipboardSync) append(if (isEmpty()) "Clipboard" else " · Clipboard")
    if (isEmpty()) append("Discovery + send")
}

/** Incoming-transfer decision dialog (reference: upstream's ask flow). */
@Composable
private fun IncomingRequestCard(
    request: ec0.ui.models.IncomingRequest,
    onAccept: () -> Unit,
    onAcceptTrust: () -> Unit,
    onDecline: () -> Unit,
) {
    val accent = ec0Colors().synergy
    Card(
        shape = Ec0Shapes.hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Incoming transfer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${request.senderAlias} · ${request.files.size} file(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            request.files.take(4).forEach { f ->
                Text(
                    "• ${f.fileName} (${f.size} B)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, shape = Ec0Shapes.pill) {
                    Text("Accept")
                }
                FilledTonalButton(onClick = onAcceptTrust, shape = Ec0Shapes.pill) {
                    Text("Accept & trust")
                }
                FilledTonalButton(onClick = onDecline, shape = Ec0Shapes.pill) {
                    Text("Decline")
                }
            }
            Text(
                "Trusted devices are accepted automatically next time",
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.9f),
            )
        }
    }
}
