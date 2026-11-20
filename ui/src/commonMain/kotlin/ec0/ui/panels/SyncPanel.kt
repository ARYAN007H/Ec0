/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.widgets.LinkTelemetrySparkline
import ec0.ui.widgets.SectionHeader

/**
 * Continuity features — Universal Control, Remote Input, Universal Clipboard,
 * Bidirectional Notification Relay, BLE Proximity Auto-Lock, Instant Hotspot,
 * USB-C Direct Tunnel, Live Link Telemetry Sparkline, and URL Handoff.
 */
@Composable
fun SyncPanel(bridge: UiBridge) {
    val caps = bridge.capabilities
    val settings by bridge.settings.collectAsState()

    val clipboardSyncEnabled = remember { bridge.clipboardSyncEnabled }.collectAsState()
    val notificationRelayEnabled = remember { bridge.notificationRelayEnabled }.collectAsState()
    val usbTunnelActive by bridge.usbTunnelActive.collectAsState()
    val remoteHandoffUrl by bridge.remoteHandoffUrl.collectAsState()
    val linkTelemetry by bridge.linkTelemetry.collectAsState()
    val proximitySummary by bridge.proximitySummary.collectAsState()
    val hotspotSsid by bridge.hotspotSsid.collectAsState()

    val remoteInputLocal = bridge.remoteInput
    val remoteInputAvailable: Boolean = if (remoteInputLocal != null) {
        remoteInputLocal.available.collectAsState().value
    } else {
        false
    }

    var handoffDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Continuity")

        // Remote input (Android touchpad/keyboard/media controller)
        bridge.remoteInput?.let { remoteInput ->
            FeatureCard(
                icon = Icons.Filled.Mouse,
                accent = ec0Colors().synergy,
                title = "Remote Input",
                live = remoteInputAvailable,
                onToggle = if (remoteInputAvailable) ({ _ -> remoteInput.openTouchpad() }) else null,
                detail = if (remoteInputAvailable) {
                    "Precision trackpad, keyboard, presentation & media remote for your paired PC"
                } else {
                    "Connects automatically when paired desktop is reachable"
                },
            )
        }

        // Universal Clipboard
        FeatureCard(
            icon = Icons.Filled.ContentCopy,
            accent = ec0Colors().synergy,
            title = "Universal Clipboard",
            live = caps.clipboardSync && clipboardSyncEnabled.value,
            checked = clipboardSyncEnabled.value,
            onToggle = { bridge.setClipboardSyncEnabled(it) },
            detail = when {
                !clipboardSyncEnabled.value -> "Clipboard sync paused — toggle to resume"
                caps.clipboardSync -> "Copy on one device, paste on the other — sensitive OTPs auto-scrub in 30s"
                else -> "Waiting for paired link — sync resumes automatically"
            },
        )

        // Notification Relay (Phone ↔ Desktop)
        FeatureCard(
            icon = Icons.Filled.Notifications,
            accent = ec0Colors().localSend,
            title = "Notification Relay",
            live = caps.notifications && notificationRelayEnabled.value,
            checked = notificationRelayEnabled.value,
            onToggle = { bridge.setNotificationRelayEnabled(it) },
            detail = when {
                !notificationRelayEnabled.value && caps.notifications -> "Notification relay paused — toggle to resume"
                caps.notifications -> "Notifications & inline replies mirrored over encrypted session"
                else -> "Enable notification access on your phone to mirror alerts"
            },
        )

        // Desktop-only: Desktop → Phone notification relay policy (privacy & quiet hours)
        if (bridge.hasDesktopIntegration) {
            Card(
                shape = Ec0Shapes.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Mirror desktop alerts to phone",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Forwards Linux desktop notifications to your paired phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.notifRelayEnabled,
                            onCheckedChange = { enabled ->
                                bridge.updateSettings { it.copy(notifRelayEnabled = enabled) }
                            },
                        )
                    }
                    if (settings.notifRelayEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Title-only privacy mode", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Hide notification message body on the phone",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.notifTitleOnly,
                                onCheckedChange = { titleOnly ->
                                    bridge.updateSettings { it.copy(notifTitleOnly = titleOnly) }
                                },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Quiet hours (22:00 – 08:00)", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Pause desktop-to-phone notification forwarding overnight",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.notifQuietHoursEnabled,
                                onCheckedChange = { quiet ->
                                    bridge.updateSettings { it.copy(notifQuietHoursEnabled = quiet) }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Desktop-only: USB-C Direct Tunnel (Tier-0 ultra-low-latency transport)
        if (bridge.hasUsbTunnelControl) {
            FeatureCard(
                icon = Icons.Filled.Usb,
                accent = MaterialTheme.colorScheme.tertiary,
                title = "USB-C Direct Link",
                live = usbTunnelActive,
                checked = usbTunnelActive,
                onToggle = { bridge.toggleUsbTunnel() },
                detail = if (usbTunnelActive) {
                    "Active over USB-C — ultra-low latency for files, webcam & mirroring"
                } else {
                    "Plug in your phone over USB-C for zero-Wi-Fi gigabit continuity"
                },
            )
        }

        // Desktop-only: Universal Control screen-edge cursor barrier
        if (bridge.hasDesktopIntegration) {
            Card(
                shape = Ec0Shapes.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), Ec0Shapes.tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Mouse, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Universal Control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Slide your mouse past a monitor edge to seamlessly control your paired phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        listOf("off" to "Off", "left" to "Left Edge", "right" to "Right Edge", "top" to "Top Edge", "bottom" to "Bottom Edge").forEach { (key, label) ->
                            val selected = settings.universalControlEdge.equals(key, ignoreCase = true)
                            FilledTonalButton(
                                onClick = { bridge.setUniversalControlEdge(key) },
                                shape = Ec0Shapes.pill,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            // BLE Proximity Walk-Away Auto-Lock
            FeatureCard(
                icon = Icons.Filled.BluetoothSearching,
                accent = MaterialTheme.colorScheme.primary,
                title = "Walk-Away Auto-Lock",
                live = settings.walkAwayAutoLock,
                checked = settings.walkAwayAutoLock,
                onToggle = { bridge.setWalkAwayAutoLock(it) },
                detail = if (settings.walkAwayAutoLock) {
                    "BLE Proximity active ($proximitySummary) — locks desktop 15s after you step away"
                } else {
                    "Automatically locks this Linux workstation when your phone moves out of range"
                },
            )

            // Instant Phone Hotspot 1-click connect
            Card(
                shape = Ec0Shapes.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), Ec0Shapes.tile),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Instant Hotspot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            hotspotSsid?.let { "Phone hotspot ready: $it" }
                                ?: "Join your paired phone's Wi-Fi hotspot in one click via NetworkManager",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = bridge::joinPhoneHotspot,
                        shape = Ec0Shapes.pill,
                    ) {
                        Text(if (hotspotSsid != null) "Join" else "Request")
                    }
                }
            }
        }

        // Real-Time Link Telemetry Sparkline Card
        Card(
            shape = Ec0Shapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Speed, contentDescription = null, tint = ec0Colors().success, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Link Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val latestRtt = linkTelemetry.rttSamplesMs.lastOrNull()
                        Text(
                            buildString {
                                append(linkTelemetry.transportLabel)
                                if (latestRtt != null) append(" · ${latestRtt} ms RTT")
                                if (linkTelemetry.autoFailoverActive) append(" · auto-failover")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinkTelemetrySparkline(
                    samplesMs = linkTelemetry.rttSamplesMs,
                    accent = if (linkTelemetry.rttSamplesMs.isNotEmpty()) ec0Colors().success else MaterialTheme.colorScheme.outline,
                )
            }
        }

        // URL Handoff Card — send a link directly to the paired device's browser
        Card(
            shape = Ec0Shapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), Ec0Shapes.tile),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("URL Handoff", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Open any https:// link immediately on your paired device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val sendUrl = {
                    val url = handoffDraft.trim()
                    if (url.isNotEmpty()) {
                        bridge.sendHandoffUrl(url)
                        handoffDraft = ""
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = handoffDraft,
                        onValueChange = { handoffDraft = it },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        shape = Ec0Shapes.pill,
                        keyboardActions = KeyboardActions(onSend = { sendUrl() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = sendUrl,
                        enabled = handoffDraft.trim().startsWith("http://") || handoffDraft.trim().startsWith("https://"),
                        shape = Ec0Shapes.pill,
                    ) {
                        Text("Send")
                    }
                }
                remoteHandoffUrl?.takeIf { it.isNotBlank() }?.let { incomingUrl ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            incomingUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = bridge::openRemoteHandoff, shape = Ec0Shapes.pill) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    live: Boolean,
    detail: String,
    checked: Boolean = live,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(accent.copy(alpha = if (live) 0.28f else 0.12f), Ec0Shapes.tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = (if (live) ec0Colors().success else MaterialTheme.colorScheme.outline)
                            .copy(alpha = 0.15f),
                    ) {
                        Text(
                            if (live) "LIVE" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (live) ec0Colors().success else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onToggle != null) {
                Switch(checked = checked, onCheckedChange = { onToggle(it) })
            }
        }
    }
}
