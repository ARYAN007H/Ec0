/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Home dashboard — M3 Expressive Connected Device Hero Card, adaptive
 * bento capability cards (mirror, camera studio, media, notifications,
 * clipboard, remote input, files, Find My), URL handoff banner, and
 * nearby LAN peers. Every control reflects live state.
 */
package ec0.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.ui.models.ActiveCallUi
import ec0.ui.models.AndroidAppUi
import ec0.ui.models.CameraControlsState
import ec0.ui.models.ConnectedPeerSummary
import ec0.ui.models.MediaSessionUi
import ec0.ui.models.NotificationItem
import ec0.ui.models.PeerLinkState
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import ec0.ui.theme.Ec0Motion
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.theme.protocolAccent
import ec0.ui.widgets.DeviceTypeIcon
import ec0.ui.widgets.ProtocolChip
import ec0.ui.widgets.SectionHeader
import ec0.ui.widgets.TransferStrip
import ec0.ui.widgets.formatByteSize

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
        val connectedPeers by bridge.connectedPeers.collectAsState()
        val mirrorRunning by bridge.mirrorRunning.collectAsState()
        val cameraState by bridge.cameraStateText.collectAsState()
        val cameraControls by bridge.cameraControls.collectAsState()
        val v4l2Preflight by bridge.v4l2PreflightText.collectAsState()
        val clipSummary by bridge.clipboardSummary.collectAsState()
        val autoSync by bridge.autoSyncClipboard.collectAsState()
        val notifications by bridge.relayedNotifications.collectAsState()
        val mediaSession by bridge.mediaSession.collectAsState()
        val findMyRinging by bridge.findMyRinging.collectAsState()
        val remoteHandoffUrl by bridge.remoteHandoffUrl.collectAsState()
        val statusBanner by bridge.statusBanner.collectAsState()
        val tiles by bridge.tiles.collectAsState()
        val phoneApps by bridge.phoneApps.collectAsState()
        val activeCalls by bridge.activeCalls.collectAsState()

        // Adaptive bento: 3-up on wide windows, 2-up on medium, 1-up on phones.
        val tileColumns = if (compact) 1 else if (maxWidth >= 960.dp) 3 else 2

        val tilePeers = (connectedPeers.map { it.deviceId } + tiles.map { it.deviceId }).distinct()
        var activeDeviceId by remember { mutableStateOf<String?>(null) }
        val activeId = activeDeviceId?.takeIf { it in tilePeers } ?: tilePeers.firstOrNull()
        val activePeerSummary = connectedPeers.firstOrNull { it.deviceId == activeId }
            ?: connectedPeers.firstOrNull()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            activeCalls.firstOrNull()?.let { call ->
                IncomingCallBanner(
                    call = call,
                    onAnswer = { bridge.sendCallAction(call.callId, "answer_speaker") },
                    onDecline = { bridge.sendCallAction(call.callId, "reject") },
                    onQuickSms = { bridge.sendCallAction(call.callId, "reject_sms", "Can't talk right now, calling you back shortly.") },
                )
            }

            incoming?.let { request ->
                IncomingRequestCard(
                    request = request,
                    onAccept = { bridge.acceptIncoming(trust = false) },
                    onAcceptTrust = { bridge.acceptIncoming(trust = true) },
                    onDecline = { bridge.declineIncoming() },
                )
            }

            // Transient status / error banner (always visible when actionable).
            AnimatedVisibility(
                visible = !statusBanner.isNullOrBlank() && !statusBanner!!.startsWith("Ready —"),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                statusBanner?.let { banner ->
                    Surface(
                        shape = Ec0Shapes.chip,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            banner,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // Header bar: device name + live encrypted link chip + Scan button.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        settings.alias,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    peerLink?.let { link ->
                        Spacer(Modifier.height(4.dp))
                        PeerLinkChip(link)
                    }
                }
                Spacer(Modifier.width(12.dp))
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

            // Multi-device switcher pills when >1 paired device is available.
            if (tilePeers.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    tilePeers.forEach { deviceId ->
                        val summary = connectedPeers.firstOrNull { it.deviceId == deviceId }
                        val alias = summary?.alias
                            ?: devices.firstOrNull { it.id == deviceId }?.alias
                            ?: deviceId.take(8)
                        val selected = activeId == deviceId
                        FilledTonalButton(
                            onClick = { activeDeviceId = deviceId },
                            shape = Ec0Shapes.pill,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (summary?.connected == true) ec0Colors().success
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(alias, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }

            // Connected Device Hero Card — live battery, transport, fingerprint & 1-tap actions.
            activePeerSummary?.let { peer ->
                ConnectedPeerHeroCard(
                    peer = peer,
                    bridge = bridge,
                    mirrorRunning = mirrorRunning,
                    cameraStreaming = cameraControls?.streaming == true,
                    findMyRinging = findMyRinging,
                    compact = compact,
                    onSendFile = {
                        pickFile { ref -> ref?.let { bridge.sendFileToPairedPeer(it, peer.deviceId) } }
                    },
                )
            }

            // Incoming URL Handoff banner (Apple Handoff-style 1-tap open).
            remoteHandoffUrl?.takeIf { it.isNotBlank() }?.let { url ->
                HandoffBanner(url = url, onOpen = bridge::openRemoteHandoff)
            }

            // Capability Bento Grid — intrinsic height so controls never clip.
            val activeTiles = tiles.filter { it.deviceId == activeId }
            val tileFactories: Map<String, @Composable (Modifier) -> Unit> = mapOf(
                "mirror" to { m -> MirrorTile(bridge, mirrorRunning, settings, activeId, phoneApps, m) },
                "cam" to { m -> CameraTile(bridge, cameraState, cameraControls, v4l2Preflight, settings, activeId, m) },
                "file" to { m -> FilesTile(transfers, devices, activeId, bridge, pickFile, m) },
                "clip" to { m -> ClipboardTile(bridge, clipSummary, autoSync, compact, m) },
                "notif" to { m -> NotificationsTile(bridge, notifications, m) },
                "input" to { m -> RemoteInputTile(bridge, m) },
                "media" to { m -> MediaTile(bridge, mediaSession, m) },
                "find_my" to { m -> FindMyTile(bridge, findMyRinging, m) },
            )

            // Also render Media tile automatically whenever a live mediaSession arrives,
            // even if the peer's beacon hasn't refreshed its media bit yet.
            val tileIds = activeTiles.map { it.capabilityId }.toMutableList()
            if (mediaSession != null && "media" !in tileIds) {
                tileIds.add("media")
            }

            tileIds.chunked(tileColumns).forEach { rowIds ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowIds.forEach { capId ->
                        tileFactories[capId]?.invoke(Modifier.weight(1f))
                            ?: Spacer(Modifier.weight(1f))
                    }
                    repeat(tileColumns - rowIds.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (transfers.isNotEmpty()) {
                SectionHeader("Active transfers", badge = transfers.size.toString())
                TransferStrip(transfers.takeLast(3).asReversed())
            }

            SectionHeader("Nearby devices", badge = devices.size.toString())

            if (devices.isEmpty()) {
                EmptyNearby(scanning)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.height(gridHeight(devices.size, compact)),
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(device) {
                            pickFile { ref -> ref?.let { bridge.sendFile(it, device) } }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ConnectedPeerHeroCard(
    peer: ConnectedPeerSummary,
    bridge: UiBridge,
    mirrorRunning: Boolean,
    cameraStreaming: Boolean,
    findMyRinging: Boolean,
    compact: Boolean,
    onSendFile: () -> Unit,
) {
    val container = if (peer.connected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Card(
        shape = Ec0Shapes.hero,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            Ec0Shapes.tile,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (compact) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            peer.alias,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OnlineDot(peer.connected)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            if (peer.connected) peer.transport else "Offline · auto-reconnecting",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (peer.fingerprintPrefix.isNotBlank()) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = "Verified peer",
                                tint = ec0Colors().success,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                peer.fingerprintPrefix.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                peer.batteryPct?.let { pct ->
                    BatteryBadge(pct = pct, charging = peer.isCharging)
                }
            }

            // Quick 1-tap ecosystem action strip
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                Button(
                    onClick = onSendFile,
                    enabled = peer.connected,
                    shape = Ec0Shapes.pill,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send files")
                }
                if (!compact) {
                    FilledTonalButton(
                        onClick = {
                            if (mirrorRunning) bridge.stopScreenMirror()
                            else bridge.startMirrorFor(peer.deviceId)
                        },
                        enabled = peer.connected || mirrorRunning,
                        shape = Ec0Shapes.pill,
                    ) {
                        Icon(Icons.Filled.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (mirrorRunning) "Stop mirror" else "Mirror")
                    }
                    FilledTonalButton(
                        onClick = {
                            if (cameraStreaming) bridge.stopCamera()
                            else bridge.startCameraFor(peer.deviceId)
                        },
                        enabled = peer.connected || cameraStreaming,
                        shape = Ec0Shapes.pill,
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (cameraStreaming) "Stop webcam" else "Webcam")
                    }
                    if (bridge.canRingPhone) {
                        FilledTonalButton(
                            onClick = { bridge.triggerFindMyPhone(!findMyRinging) },
                            enabled = peer.connected,
                            shape = Ec0Shapes.pill,
                        ) {
                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (findMyRinging) "Stop ring" else "Ring phone")
                        }
                    }
                } else {
                    val remoteInput = bridge.remoteInput
                    val remoteAvail = remoteInput?.available?.collectAsState()?.value == true
                    if (remoteInput != null) {
                        FilledTonalButton(
                            onClick = { remoteInput.openTouchpad() },
                            enabled = remoteAvail,
                            shape = Ec0Shapes.pill,
                        ) {
                            Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Trackpad")
                        }
                    }
                    if (bridge.canLockPc) {
                        FilledTonalButton(
                            onClick = bridge::lockRemoteWorkstation,
                            enabled = peer.connected,
                            shape = Ec0Shapes.pill,
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Lock PC")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryBadge(pct: Int, charging: Boolean) {
    val clamped = pct.coerceIn(0, 100)
    val tint = when {
        charging -> ec0Colors().success
        clamped <= 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = Ec0Shapes.chip,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    "$clamped%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { clamped / 100f },
                modifier = Modifier.width(44.dp).height(4.dp).clip(CircleShape),
                color = tint,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun HandoffBanner(url: String, onOpen: () -> Unit) {
    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.OpenInBrowser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Continue from paired device",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onOpen, shape = Ec0Shapes.pill) {
                Text("Open")
            }
        }
    }
}

@Composable
private fun IncomingCallBanner(
    call: ActiveCallUi,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onQuickSms: () -> Unit,
) {
    Card(
        shape = Ec0Shapes.hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(ec0Colors().success.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = ec0Colors().success,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (call.state.equals("RINGING", ignoreCase = true)) "Incoming call on phone" else "Active call on phone",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Text(
                        call.callerName.ifBlank { call.phoneNumber.ifBlank { "Unknown caller" } },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (call.phoneNumber.isNotBlank() && call.callerName.isNotBlank()) {
                        Text(
                            call.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                if (call.state.equals("RINGING", ignoreCase = true)) {
                    Button(onClick = onAnswer, shape = Ec0Shapes.pill) {
                        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Answer")
                    }
                }
                FilledTonalButton(
                    onClick = onDecline,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Decline")
                }
                FilledTonalButton(onClick = onQuickSms, shape = Ec0Shapes.pill) {
                    Icon(Icons.Filled.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Quick SMS")
                }
            }
        }
    }
}

@Composable
private fun MirrorTile(
    bridge: UiBridge,
    running: Boolean,
    settings: SettingsState,
    activeDeviceId: String?,
    phoneApps: List<AndroidAppUi>,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Screen Mirror",
        subtitle = if (running) {
            "Live · ${settings.mirrorMaxFps} fps · ${settings.mirrorBitrateMbps} Mbps" +
                if (settings.mirrorAudio) " · audio on" else " · muted"
        } else {
            "Low-latency wireless & USB phone display or individual app windows"
        },
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
            TileButton(
                if (settings.mirrorAudio) "Audio On" else "Audio Off",
                subtle = !settings.mirrorAudio,
            ) { bridge.updateSettings { it.copy(mirrorAudio = !it.mirrorAudio) } }
            TileButton(
                if (settings.mirrorTurnScreenOff) "Screen Off" else "Screen On",
                subtle = !settings.mirrorTurnScreenOff,
            ) { bridge.updateSettings { it.copy(mirrorTurnScreenOff = !it.mirrorTurnScreenOff) } }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text("FPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(30, 60).forEach { fps ->
                TileButton(
                    "$fps",
                    subtle = settings.mirrorMaxFps != fps,
                ) { bridge.updateSettings { it.copy(mirrorMaxFps = fps) } }
            }
            Spacer(Modifier.width(4.dp))
            Text("Bitrate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(4, 8, 16).forEach { mbps ->
                TileButton(
                    "${mbps}M",
                    subtle = settings.mirrorBitrateMbps != mbps,
                ) { bridge.updateSettings { it.copy(mirrorBitrateMbps = mbps) } }
            }
        }
        if (phoneApps.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Icon(
                    Icons.Filled.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                phoneApps.take(12).forEach { app ->
                    TileButton(app.label, subtle = true) {
                        bridge.launchPhoneApp(app.packageName)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    bridge: UiBridge,
    cameraState: String?,
    controls: CameraControlsState?,
    v4l2Preflight: String?,
    settings: SettingsState,
    activeDeviceId: String?,
    modifier: Modifier = Modifier,
) {
    val isLive = controls?.streaming == true || (cameraState != null && cameraState.startsWith("streaming"))
    DashboardTile(
        title = "Continuity Camera",
        subtitle = if (isLive) {
            "${controls?.resolution ?: settings.cameraResolution} @ ${controls?.fps ?: settings.cameraFps} fps · ${cameraState ?: "live"}"
        } else {
            cameraState?.takeIf { it != "idle" }
                ?: "Use your phone as a 1080p desktop webcam & virtual mic"
        },
        icon = Icons.Filled.CameraAlt,
        iconTint = if (isLive) ec0Colors().success else MaterialTheme.colorScheme.primary,
        live = isLive,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            if (isLive) {
                TileButton("Stop", destructive = true) { bridge.stopCamera() }
            } else {
                TileButton("Start webcam") {
                    if (activeDeviceId != null) bridge.startCameraFor(activeDeviceId)
                    else bridge.startCamera()
                }
            }
            TileButton("Check v4l2", subtle = true) { bridge.setupV4l2Loopback() }
        }

        // Capture profile selector (720p / 1080p, 30 / 60 fps, H.264 / MJPEG)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf("720p", "1080p").forEach { res ->
                TileButton(
                    res,
                    subtle = settings.cameraResolution != res,
                ) { bridge.updateSettings { it.copy(cameraResolution = res) } }
            }
            listOf(30, 60).forEach { fps ->
                TileButton(
                    "${fps}fps",
                    subtle = settings.cameraFps != fps,
                ) { bridge.updateSettings { it.copy(cameraFps = fps) } }
            }
            listOf("h264" to "H.264", "mjpeg" to "MJPEG").forEach { (codecKey, label) ->
                TileButton(
                    label,
                    subtle = !settings.cameraCodec.equals(codecKey, ignoreCase = true),
                ) { bridge.updateSettings { it.copy(cameraCodec = codecKey) } }
            }
        }

        // Live native camera studio controls (Lens, Torch, Zoom, Virtual Mic) — built directly into ec0.
        if (isLive && controls != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilledTonalIconButton(onClick = bridge::switchCameraLens, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch lens", modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(
                    onClick = { bridge.setCameraFlash(!controls.flashEnabled) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        if (controls.flashEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Toggle torch",
                        modifier = Modifier.size(18.dp),
                    )
                }
                FilledTonalIconButton(onClick = bridge::toggleVirtualMic, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (controls.virtualMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = "Toggle virtual mic",
                        tint = if (controls.virtualMicActive) ec0Colors().success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                listOf(100 to "1×", 200 to "2×", 300 to "3×").forEach { (zoomVal, label) ->
                    TileButton(label, subtle = controls.zoomX100 != zoomVal) {
                        bridge.setCameraZoom(zoomVal)
                    }
                }
            }
        }

        if (v4l2Preflight != null) {
            Text(
                v4l2Preflight,
                style = MaterialTheme.typography.labelSmall,
                color = if (v4l2Preflight.startsWith("Run:") || v4l2Preflight.contains("failed", ignoreCase = true)) {
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
private fun MediaTile(
    bridge: UiBridge,
    session: MediaSessionUi?,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Now Playing",
        subtitle = if (session != null) {
            buildString {
                append(session.title)
                if (session.artist.isNotBlank()) append(" — ${session.artist}")
                if (session.playerName.isNotBlank()) append(" · ${session.playerName}")
            }
        } else {
            "Control desktop & phone playback remotely"
        },
        icon = Icons.Filled.MusicNote,
        iconTint = MaterialTheme.colorScheme.secondary,
        live = session?.playing == true,
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            FilledTonalIconButton(
                onClick = { bridge.sendMediaControl("previous") },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(18.dp))
            }
            FilledTonalIconButton(
                onClick = { bridge.sendMediaControl("play_pause") },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (session?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(20.dp),
                )
            }
            FilledTonalIconButton(
                onClick = { bridge.sendMediaControl("next") },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(
                onClick = {
                    val cur = session?.volumePct ?: 70
                    bridge.sendMediaControl("set_volume", (cur - 10).coerceAtLeast(0))
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.VolumeDown, contentDescription = "Volume down", modifier = Modifier.size(18.dp))
            }
            FilledTonalIconButton(
                onClick = {
                    val cur = session?.volumePct ?: 70
                    bridge.sendMediaControl("set_volume", (cur + 10).coerceAtMost(100))
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = "Volume up", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RemoteInputTile(bridge: UiBridge, modifier: Modifier = Modifier) {
    val remoteInput = bridge.remoteInput
    val available = remoteInput?.available?.collectAsState()?.value == true
    DashboardTile(
        title = "Remote Input",
        subtitle = if (available) {
            "Use this phone as a precision trackpad, keyboard & media remote"
        } else {
            "Zero-latency virtual trackpad & keyboard over encrypted link"
        },
        icon = Icons.Filled.TouchApp,
        iconTint = MaterialTheme.colorScheme.secondary,
        live = available,
        modifier = if (available) modifier.clickable { remoteInput?.openTouchpad() } else modifier,
    ) {
        if (remoteInput != null) {
            TileButton("Open trackpad", enabled = available) {
                remoteInput.openTouchpad()
            }
        }
    }
}

@Composable
private fun FindMyTile(
    bridge: UiBridge,
    ringing: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDesktopHost = bridge.canRingPhone
    DashboardTile(
        title = if (isDesktopHost) "Find My Phone" else "Workstation Security",
        subtitle = when {
            isDesktopHost && ringing -> "Ringing paired phone at max alarm volume"
            isDesktopHost -> "Ring your paired phone even in Silent or DND mode"
            else -> "Lock your paired Linux workstation immediately"
        },
        icon = if (isDesktopHost) Icons.Filled.NotificationsActive else Icons.Filled.Lock,
        iconTint = if (ringing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        live = if (isDesktopHost) ringing else null,
        modifier = modifier,
    ) {
        if (isDesktopHost) {
            TileButton(
                label = if (ringing) "Stop ringing" else "Ring phone",
                destructive = ringing,
            ) { bridge.triggerFindMyPhone(!ringing) }
        } else if (bridge.canLockPc) {
            TileButton("Lock PC") { bridge.lockRemoteWorkstation() }
        }
    }
}

@Composable
private fun FilesTile(
    transfers: List<TransferItem>,
    devices: List<UiDevice>,
    activeDeviceId: String?,
    bridge: UiBridge,
    pickFile: (onPicked: (Any?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = transfers.firstOrNull { it.state == TransferState.RUNNING } ?: transfers.lastOrNull()
    val failed = transfers.lastOrNull { it.state == TransferState.FAILED }
    DashboardTile(
        title = "Fast Share",
        subtitle = when {
            active != null && active.state == TransferState.RUNNING -> buildString {
                append("${active.fileName} · ${active.progressPct}%")
                active.speedText?.let { append(" · $it") }
            }
            active != null && active.state == TransferState.DONE -> "Last: ${active.fileName} (${formatByteSize(active.sizeBytes)})"
            activeDeviceId != null -> "Encrypted multi-file & folder transfer ready"
            devices.isEmpty() -> "Listening for nearby ec0 devices on Wi-Fi…"
            else -> "${devices.size} nearby device(s) ready"
        },
        icon = Icons.Filled.ArrowUpward,
        iconTint = MaterialTheme.colorScheme.primary,
        live = activeDeviceId != null || devices.isNotEmpty(),
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        if (activeDeviceId != null) {
            TileButton("Send files") {
                pickFile { ref -> ref?.let { bridge.sendFileToPairedPeer(it, activeDeviceId) } }
            }
        }
        if (failed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Failed: ${failed.error ?: "transfer error"}",
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
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    DashboardTile(
        title = "Universal Clipboard",
        subtitle = summary,
        icon = Icons.Filled.ContentPaste,
        iconTint = MaterialTheme.colorScheme.primary,
        live = autoSync,
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Auto-sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TileButton("Push", subtle = true) { bridge.pushClipboardNow() }
            Switch(checked = autoSync, onCheckedChange = { bridge.setAutoSyncClipboard(it) })
        }
        if (compact) {
            Text(
                "Android shares clipboard while ec0 is open; PC → phone syncs anytime.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationsTile(
    bridge: UiBridge,
    notifications: List<NotificationItem>,
    modifier: Modifier = Modifier,
) {
    val head = notifications.firstOrNull()
    val latestReplyable = notifications.firstOrNull { it.canReply }
    DashboardTile(
        title = "Notifications",
        subtitle = head?.let {
            val content = it.body.ifBlank { it.title }
            "${it.appName}: $content"
        } ?: "Relayed notifications appear here with quick reply & OTP copy",
        icon = Icons.Filled.Notifications,
        iconTint = MaterialTheme.colorScheme.tertiary,
        live = notifications.isNotEmpty(),
        clippedSubtitle = true,
        modifier = modifier,
    ) {
        if (head != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                head.otpCode?.takeIf { it.isNotBlank() }?.let { otp ->
                    TileButton("Copy $otp") { bridge.copyOtpCode(otp) }
                }
                head.actions.take(3).forEachIndexed { idx, actionLabel ->
                    TileButton(actionLabel, subtle = true) {
                        bridge.triggerNotificationAction(head.id, idx, actionLabel)
                    }
                }
                TileButton("Dismiss", subtle = true) { bridge.dismissNotification(head.id) }
            }
        }
        if (latestReplyable != null) {
            var reply by remember(latestReplyable.id) { mutableStateOf("") }
            val send = {
                if (reply.isNotBlank()) {
                    bridge.sendNotificationReply(latestReplyable.id, reply)
                    reply = ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = { Text("Reply to ${latestReplyable.appName}…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = Ec0Shapes.pill,
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TileButton("Send", enabled = reply.isNotBlank()) { send() }
            }
        }
    }
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
            "searching for paired device…",
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
 * live indicator dot, intrinsic height so controls never clip.
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    overflow = TextOverflow.Ellipsis,
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
                maxLines = if (clippedSubtitle) 2 else 3,
                overflow = TextOverflow.Ellipsis,
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
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
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
        Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
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
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by transition.animateFloat(
                initialValue = 0.88f,
                targetValue = 1.08f,
                infiniteRepeatable(tween(950), RepeatMode.Reverse),
                label = "pulseScale",
            )
            Icon(
                Icons.Filled.SensorDoor,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier.size((48 * scale).dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (scanning) "Listening for nearby devices…" else "No nearby devices yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Devices running ec0 on the same Wi-Fi appear here automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Device tile with GPU-accelerated hover lift (zero layout shift) and tap → send flow. */
@Composable
fun DeviceCard(
    device: UiDevice,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val liftPx by animateFloatAsState(
        targetValue = if (hovered) -6f else 0f,
        animationSpec = Ec0Motion.snappy(),
        label = "hoverLift",
    )
    val accent = protocolAccent(device.protocol)

    Card(
        shape = Ec0Shapes.card,
        interactionSource = interaction,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = liftPx },
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
                    Text(
                        device.alias,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        device.deviceModel ?: "Nearby device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OnlineDot(device.online)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProtocolChip(device.protocol, accent)
                if (device.hostAddress.isNotBlank()) {
                    Text(
                        device.hostAddress,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onClick,
                shape = Ec0Shapes.pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send files")
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

private fun gridHeight(count: Int, compact: Boolean): Dp {
    val cols = if (compact) 1 else 2
    val rows = (count + cols - 1) / cols
    return (rows * 190).dp
}

/** Incoming-transfer decision card (accept / accept & trust / decline). */
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
                    "• ${f.fileName} (${formatByteSize(f.size)})",
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
