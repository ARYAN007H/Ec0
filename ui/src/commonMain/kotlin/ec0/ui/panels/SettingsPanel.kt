/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.PairedPeerInfo
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Seed
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.widgets.HealthDiagnosticsCard
import ec0.ui.widgets.SectionHeader

@Composable
fun SettingsPanel(bridge: UiBridge) {
    val peerManagementLocal = bridge.peerManagement
    val peers: List<PairedPeerInfo> = if (peerManagementLocal != null) {
        peerManagementLocal.peers.collectAsState().value
    } else {
        emptyList()
    }
    val settings by bridge.settings.collectAsState()
    val pairingQr by bridge.smartConnectPairingQr.collectAsState()
    val setupChecks by bridge.setupChecks.collectAsState()
    var aliasDraft by remember(settings.alias) { mutableStateOf(settings.alias) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Settings")

        // Guided System Setup & Health Checklist (live 1-click remediation)
        if (setupChecks.isNotEmpty()) {
            val readyCount = setupChecks.count { it.ready }
            SettingsCard("System setup & health ($readyCount/${setupChecks.size} ready)") {
                setupChecks.forEachIndexed { idx, check ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (check.ready) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = if (check.ready) ec0Colors().success else ec0Colors().warning,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(check.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                check.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!check.ready && check.actionLabel != null) {
                            FilledTonalButton(
                                onClick = { bridge.runSetupCheckAction(check.id) },
                                shape = Ec0Shapes.pill,
                            ) {
                                Text(check.actionLabel)
                            }
                        }
                    }
                }
            }
        }

        // Paired devices management — available on BOTH desktop and phone.
        if (peerManagementLocal != null) {
            SettingsCard("Paired devices") {
                if (peers.isEmpty()) {
                    Text(
                        "No paired devices yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                peers.forEachIndexed { idx, peer ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                peer.alias,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = Ec0Shapes.pill,
                                color = (if (peer.connected) ec0Colors().success else MaterialTheme.colorScheme.outline)
                                    .copy(alpha = 0.14f),
                            ) {
                                Text(
                                    if (peer.connected) "Connected" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (peer.connected) ec0Colors().success
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = ec0Colors().success,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                "Ed25519 · ${peer.fingerprintPrefix}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Allow remote input", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Pointer & keyboard injection from this paired device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = "remote_input" in peer.grantedCapabilities,
                                onCheckedChange = { granted ->
                                    peerManagementLocal.setCapability(peer.deviceId, "remote_input", granted)
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { peerManagementLocal.unpair(peer.deviceId) },
                                shape = Ec0Shapes.pill,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Text("Unpair device")
                            }
                        }
                    }
                }
            }
        }

        // Phone-camera runtime grant (Android only).
        val phoneCameraLocal = bridge.phoneCamera
        if (phoneCameraLocal != null) {
            val cameraGrant by phoneCameraLocal.grant.collectAsState()
            SettingsCard("Continuity Camera permission") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Camera streaming", style = MaterialTheme.typography.titleSmall)
                        Text(
                            cameraGrant.detailLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!cameraGrant.granted) {
                        Button(
                            onClick = { phoneCameraLocal.requestGrant() },
                            shape = Ec0Shapes.pill,
                        ) {
                            Text(if (cameraGrant.needsSystemSettings) "Open settings" else "Enable")
                        }
                    } else {
                        Text(
                            cameraGrant.stateLine,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ec0Colors().success,
                        )
                    }
                }
            }
        }

        // Desktop-only: Linux File Manager context menus + Login Autostart
        if (bridge.hasDesktopIntegration) {
            SettingsCard("Desktop integration") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text("File manager context menus", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Adds \"Send via ec0\" to Dolphin & Nautilus right-click menus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = bridge::installDesktopIntegration,
                        shape = Ec0Shapes.pill,
                    ) {
                        Text("Install")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start in background on login", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Writes ~/.config/autostart/ec0.desktop so ec0 launches silently into the system tray",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autostartOnLogin,
                        onCheckedChange = { enabled -> bridge.setAutostartOnLogin(enabled) },
                    )
                }
            }
        }

        // Optional Smart Connect pairing QR (only when coordinator is active on desktop)
        pairingQr?.let { qr ->
            SettingsCard("Quick-pair QR (Smart Connect compatible)") {
                val bgSurface = Color.White
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(modifier = Modifier.size(200.dp)) {
                        drawRect(color = bgSurface)
                        val quietZone = 2
                        val totalModules = qr.size + quietZone * 2
                        if (qr.size > 0 && qr.modules.size >= qr.size * qr.size) {
                            val cellSize = size.minDimension / totalModules.toFloat()
                            for (y in 0 until qr.size) {
                                for (x in 0 until qr.size) {
                                    if (qr.modules[y * qr.size + x]) {
                                        val x0 = kotlin.math.floor((x + quietZone) * cellSize)
                                        val y0 = kotlin.math.floor((y + quietZone) * cellSize)
                                        val x1 = kotlin.math.ceil((x + quietZone + 1) * cellSize)
                                        val y1 = kotlin.math.ceil((y + quietZone + 1) * cellSize)
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(x0, y0),
                                            size = Size(x1 - x0, y1 - y0),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${qr.deviceName} · port ${qr.wsPort}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Optional interoperability QR for TLS-pinned Smart Connect clients on your local network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsCard("Device profile") {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = { aliasDraft = it },
                label = { Text("Device name") },
                singleLine = true,
                shape = Ec0Shapes.chip,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (aliasDraft != settings.alias) {
                        TextButton(onClick = {
                            bridge.updateSettings { it.copy(alias = aliasDraft.trim().ifBlank { "ec0" }) }
                        }) { Text("Save") }
                    }
                },
            )
            Text(
                "Shown to nearby and paired devices on your network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accent palette", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Ec0Seed.entries.forEach { seed ->
                    val selected = Ec0Seed.fromId(settings.themeSeed) == seed
                    Box(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(seed.swatch)
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .clickable { bridge.updateSettings { it.copy(themeSeed = seed.id) } },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                                    it.copy(
                                        darkTheme = when (label) {
                                            "System" -> null
                                            "Light" -> false
                                            else -> true
                                        },
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
            }
            if (bridge.supportsDynamicColor) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic wallpaper colors", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Adapt ec0's palette automatically to your Material You wallpaper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(dynamicColor = checked) } },
                    )
                }
            }
            if (bridge.hasDesktopIntegration) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Minimize to tray on close", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Closing the window keeps ec0 running in the system tray",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.minimizeToTray,
                        onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(minimizeToTray = checked) } },
                    )
                }
            }
        }

        SettingsCard("Security") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require PIN for unpaired transfers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Unpaired LAN devices must enter a PIN before sending files",
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
            if (!bridge.hasDesktopIntegration) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow desktop file uploads", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Permits paired Linux desktop to upload files into granted storage folders",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.allowVfsUploads,
                        onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(allowVfsUploads = checked) } },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-relay copied images & screenshots", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Pushes newly copied screenshots (up to 8 MB) straight to your desktop clipboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoRelayScreenshots,
                        onCheckedChange = { checked -> bridge.updateSettings { s -> s.copy(autoRelayScreenshots = checked) } },
                    )
                }
            }
        }

        // Features #13 & #14: Key Sealing & Health Diagnostics Center
        HealthDiagnosticsCard(bridge)

        SettingsCard("About") {
            Text("ec0 — unified cross-device ecosystem", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Self-contained continuity suite · Ed25519 + X25519 + ChaCha20-Poly1305 end-to-end encryption · GPL-3.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}
