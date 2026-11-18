/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Home dashboard — M3 Expressive bento tiles (mirror, camera, files, clipboard,
 * notifications) over the live hub state, then the nearby-device grid for
 * LocalSend-wire neighbors. Everything on this screen is real state — no filler.
 */
package ec0.ui.panels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.ui.models.Capabilities
import ec0.ui.models.PeerLinkState
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferState
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
fun HomePanel(
    bridge: UiBridge,
    pickFile: (onPicked: (Any?) -> Unit) -> Unit,
    compact: Boolean = false,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val devices by bridge.devices.collectAsState()
    val transfers by bridge.transfers.collectAsState()
    val scanning by bridge.scanning.collectAsState()
    val settings by bridge.settings.collectAsState()
    val incoming by bridge.incomingRequest.collectAsState()
    val peerLink by bridge.peerLink.collectAsState()
    val mirrorRunning by bridge.mirrorRunning.collectAsState()
    val cameraState by bridge.cameraStateText.collectAsState()
    val v4l2Preflight by bridge.v4l2PreflightText.collectAsState()
    val clipSummary by bridge.clipboardSummary.collectAsState()
    val autoSync by bridge.autoSyncClipboard.collectAsState()
    val notifications by bridge.relayedNotifications.collectAsState()
    val statusBanner by bridge.statusBanner.collectAsState()

    // Adaptive bento: 3-up on wide windows, 2-up when squeezed, 1-up on the phone.
    val tileColumns = if (compact) 1 else if (maxWidth >= 940.dp) 3 else 2

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

        // Honest status strip: mirror/scan failures MUST be visible, never swallowed.
        statusBanner?.takeIf { it.isNotBlank() && !it.startsWith("Ready —") }?.let { banner ->
            Surface(
                shape = Ec0Shapes.pill,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    banner,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Status header: this device + live link to the trusted peer + scan.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                settings.alias,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(12.dp))
            peerLink?.let { PeerLinkChip(it) }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = bridge::startScan,
                enabled = !scanning,
                shape = Ec0Shapes.pill,
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Scanning" else "Scan")
            }
        }

        // B3 tile registry — the bento renders peer capabilities ∩ local support.
        val tiles by bridge.tiles.collectAsState()
        val tilePeers = tiles.map { it.deviceId }.distinct()
        var activeDeviceId by remember { mutableStateOf<String?>(null) }
        val activeId = activeDeviceId ?: tilePeers.firstOrNull()

        if (tilePeers.size > 1) {
            // Device switcher: more than one paired peer drives the dashboard.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                tilePeers.forEach { deviceId ->
                    val alias = devices.firstOrNull { it.id == deviceId }?.alias ?: deviceId.take(8)
                    FilledTonalButton(
                        onClick = { activeDeviceId = deviceId },
                        shape = Ec0Shapes.pill,
                    ) {
                        Text(if (activeId == deviceId) "● $alias" else alias)
                    }
                }
            }
        }

        val activeTiles = tiles.filter { it.deviceId == activeId }
        val tileFactories: Map<String, @Composable (Modifier) -> Unit> = mapOf(
            "mirror" to { m -> MirrorTile(bridge, mirrorRunning, settings, activeId, m) },
            "cam" to { m -> CameraTile(bridge, cameraState, v4l2Preflight, m) },
            "file" to { m -> FilesTile(transfers, devices, scanning, m) },
            "clip" to { m -> ClipboardTile(bridge, clipSummary, autoSync, m) },
            "notif" to { m -> NotificationsTile(bridge, notifications, m) },
            "input" to { m -> RemoteInputTile(bridge, m) },
        )
        activeTiles.chunked(tileColumns).forEach { rowTiles ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowTiles.forEach { tile ->
                    tileFactories[tile.capabilityId]?.invoke(Modifier.weight(1f).height(190.dp))
                        ?: Spacer(Modifier.weight(1f))
                }
                repeat(tileColumns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // The honesty card is not capability-driven — always rendered last.
        Row(modifier = Modifier.fillMaxWidth()) {
            HonestyTile(Modifier.weight(1f).height(190.dp))
            repeat((tileColumns - 1).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
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
}

@Composable
private fun MirrorTile(
    bridge: UiBridge,
    running: Boolean,
    settings: SettingsState,
    activeDeviceId: String?,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Mirror the screen",
        subtitle = if (running) "scrcpy · live · touch enabled" else "scrcpy · wireless adb · tap to start",
        icon = Icons.Filled.DesktopWindows,
        iconTint = MaterialTheme.colorScheme.tertiary,
        live = running,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            if (running) {
                TileButton("Stop mirror", destructive = true) { bridge.stopScreenMirror() }
            } else {
                TileButton("Start mirror") { bridge.startMirrorFor(deviceId = activeDeviceId ?: "") }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("fps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(30, 60).forEach { fps ->
                TileButton(
                    "$fps",
                    subtle = settings.mirrorMaxFps != fps,
                ) { bridge.updateSettings { it.copy(mirrorMaxFps = fps) } }
            }
            Text("Mbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(4, 8, 16).forEach { mbps ->
                TileButton(
                    "$mbps",
                    subtle = settings.mirrorBitrateMbps != mbps,
                ) { bridge.updateSettings { it.copy(mirrorBitrateMbps = mbps) } }
            }
        }
        Text(
            if (settings.mirrorAudio) "audio · pending coordinator support" else "audio off · pending coordinator support",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteInputTile(bridge: UiBridge, modifier: Modifier = Modifier) {
    // The tile is the phone's natural entry point to the trackpad — tapping the
    // "Remote input" card on HOME must open it, not just display that the
    // capability exists (the real toggle lives in Sync & continuity too).
    val remoteInput = bridge.remoteInput
    val available = remoteInput?.available?.collectAsState()?.value == true
    DashboardTile(
        title = "Remote input",
        subtitle = if (available) {
            "Phone as trackpad · uinput direct · tap to open"
        } else {
            "Phone as trackpad · uinput direct · batched 8 ms"
        },
        icon = Icons.Filled.TouchApp,
        iconTint = MaterialTheme.colorScheme.secondary,
        live = null,
        modifier = if (available) {
            modifier.clickable { remoteInput?.openTouchpad() }
        } else {
            modifier
        },
    )
}

@Composable
private fun CameraTile(
    bridge: UiBridge,
    cameraState: String?,
    v4l2Preflight: String?,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Phone camera → PC",
        subtitle = cameraState?.takeIf { it != "idle" } ?: "v4l2loopback · tap to start",
        icon = Icons.Filled.CameraAlt,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        live = null,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            TileButton("Start camera") { bridge.startCamera() }
            TileButton("Stop", subtle = true) { bridge.stopCamera() }
            TileButton("Load v4l2", subtle = true) { bridge.setupV4l2Loopback() }
        }
        if (v4l2Preflight != null) {
            Text(
                v4l2Preflight,
                style = MaterialTheme.typography.labelSmall,
                color = if (v4l2Preflight.startsWith("Run:")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilesTile(
    transfers: List<ec0.ui.models.TransferItem>,
    devices: List<UiDevice>,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = transfers.firstOrNull { it.state == TransferState.RUNNING } ?: transfers.firstOrNull()
    val failed = transfers.firstOrNull { it.state == TransferState.FAILED }
    DashboardTile(
        title = "Send files",
        subtitle = when {
            active != null -> "${active.fileName} · ${active.progressPct}%"
            devices.isEmpty() -> "Searching the network… (Bluetooth-free LAN sweep)"
            else -> "Pick a device below and send"
        },
        icon = Icons.Filled.ArrowUpward,
        iconTint = MaterialTheme.colorScheme.primary,
        live = devices.isNotEmpty(),
        modifier = modifier,
    ) {
        if (failed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Last send failed: ${failed.error ?: "transfer error"} · ${failed.peerAlias}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ClipboardTile(
    bridge: UiBridge,
    summary: String,
    autoSync: Boolean,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Clipboard",
        subtitle = summary,
        icon = Icons.Filled.ContentPaste,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        live = autoSync,
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Auto-sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoSync, onCheckedChange = { bridge.setAutoSyncClipboard(it) })
        }
    }
}

@Composable
private fun NotificationsTile(
    bridge: UiBridge,
    notifications: List<ec0.ui.models.NotificationItem>,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Notifications",
        subtitle = notifications.firstOrNull()?.let { "${it.appName}: ${it.body}" }
            ?: "Relayed phone notifications appear here",
        icon = Icons.Filled.Notifications,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        live = notifications.isNotEmpty(),
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        val latest = notifications.firstOrNull { it.canReply }
        var reply by remember(latest?.id) { mutableStateOf("") }
        val send = {
            if (latest != null && reply.isNotBlank()) {
                bridge.sendNotificationReply(latest.id, reply)
                reply = ""
            }
            Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it },
                placeholder = { Text("Reply…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                enabled = latest != null,
                shape = Ec0Shapes.pill,
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TileButton("Send", enabled = reply.isNotBlank()) { send() }
        }
    }
}

@Composable
private fun HonestyTile(modifier: Modifier = Modifier) {
    DashboardTile(
        title = "Said plainly",
        subtitle = "phone→PC clipboard syncs while ec0 is in the foreground — " +
            "Android 10+ blocks background reads. PC→phone always works.",
        icon = Icons.Filled.SensorDoor,
        iconTint = MaterialTheme.colorScheme.primary,
        live = null,
        modifier = modifier,
    )
}

/** Live-link chip for the dashboard header — connected (green dot) or searching. */
@Composable
private fun PeerLinkChip(link: PeerLinkState) {
    val (container, content, label) = if (link.connected) {
        Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            link.transport,
        )
    } else {
        Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "searching for the trusted peer…",
        )
    }
    Surface(shape = Ec0Shapes.pill, color = container, contentColor = content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (link.connected) ec0Colors().success else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * M3 Expressive dashboard tile shell — tonal container, oversized corners,
 * status dot when [live] is meaningful, single [content] slot for controls.
 */
@Composable
private fun DashboardTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    live: Boolean?,
    modifier: Modifier = Modifier,
    clippedSubtitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (live == true) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (live != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (live) ec0Colors().success else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (clippedSubtitle) 1 else 3,
            )
            content()
        }
    }
}

@Composable
private fun TileButton(
    label: String,
    destructive: Boolean = false,
    subtle: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = Ec0Shapes.pill,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = when {
                destructive -> MaterialTheme.colorScheme.errorContainer
                subtle -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> MaterialTheme.colorScheme.primary
            },
            contentColor = when {
                destructive -> MaterialTheme.colorScheme.onErrorContainer
                subtle -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onPrimary
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(label, maxLines = 1, softWrap = false)
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
                "Peers running ec0 appear here automatically",
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

/** Incoming-transfer decision card (accept / accept & trust / decline → 403). */
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Button(onClick = onAccept, shape = Ec0Shapes.pill) {
                    Text("Accept", maxLines = 1, softWrap = false)
                }
                FilledTonalButton(onClick = onAcceptTrust, shape = Ec0Shapes.pill) {
                    Text("Accept & trust", maxLines = 1, softWrap = false)
                }
                FilledTonalButton(onClick = onDecline, shape = Ec0Shapes.pill) {
                    Text("Decline", maxLines = 1, softWrap = false)
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
