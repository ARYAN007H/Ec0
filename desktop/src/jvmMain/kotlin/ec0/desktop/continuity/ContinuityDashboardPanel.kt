/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Compose Multiplatform Continuity Dashboard Panel (`ec0.desktop.continuity`):
 *   - Phone Presence & 1-click Scrcpy Screen Mirroring
 *   - Continuity Camera Studio (`ARYAN007H/CamDroid` TCP :4747 + `/dev/video10`
 *     V4L2 controls: lens switch, zoom, torch, mirror, white balance, exposure,
 *     resolution, FPS, codec)
 *   - Bidirectional Wayland/X11 Clipboard Sync & QR Pairing
 *   - Live Android Notification Center with inline quick-reply & dismissal
 *   - Honest Linux System Readiness Diagnostics & 1-click File Manager AirDrop installer
 */
package ec0.desktop.continuity

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec0.core.engine.linux.LinuxSystemCapabilities
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.PresenceBeaconInfo

@Composable
fun ContinuityDashboardPanel(
    controller: ContinuityHubController,
    modifier: Modifier = Modifier,
) {
    val capabilities by controller.linuxCapabilities.collectAsState()
    val qrUri by controller.pairingQrUri.collectAsState()
    val clipSummary by controller.lastSyncedClipSummary.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    val autoClip by controller.autoSyncClipboard.collectAsState()
    val forwardNotifs by controller.forwardNotificationsToDesktop.collectAsState()
    val notifications by controller.notifications.collectAsState()
    val peerPresence by controller.peerPresence.collectAsState()
    val cameraStatus by controller.cameraStatus.collectAsState()

    var phoneHostInput by remember { mutableStateOf("192.168.1.42") }
    var qrScanInput by remember { mutableStateOf("") }
    var manualClipInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Continuity Hub (ec0-engine + CamDroid)", style = MaterialTheme.typography.h6)
        Text(
            banner,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.primary,
        )

        PresenceAndMirrorCard(
            phoneHost = phoneHostInput,
            onPhoneHostChange = { phoneHostInput = it },
            presenceMap = peerPresence,
            onStartMirror = { controller.startScreenMirror(phoneHostInput) },
            onStopMirror = controller::stopScreenMirror,
        )

        ContinuityCameraStudioCard(
            phoneHost = phoneHostInput,
            cameraStatus = cameraStatus,
            onStartCamDroid = { res, fps, codec, front ->
                controller.startContinuityCamera(
                    host = phoneHostInput,
                    port = CamDroidConstants.DEFAULT_TCP_PORT,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    resolution = res,
                    fps = fps,
                    codec = codec,
                    frontCamera = front,
                )
            },
            onStartScrcpyFallback = { res, fps, front ->
                controller.startContinuityCamera(
                    host = phoneHostInput,
                    port = 5555,
                    backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                    resolution = res,
                    fps = fps,
                    frontCamera = front,
                )
            },
            onCommand = controller::sendCameraControl,
            onStop = controller::stopContinuityCamera,
        )

        ClipboardAndPairingCard(
            qrUri = qrUri,
            qrScanInput = qrScanInput,
            onQrScanInputChange = { qrScanInput = it },
            onPairQr = { controller.pairWithQrUri(qrScanInput) },
            onRefreshQr = controller::refreshPairingQr,
            autoSyncClipboard = autoClip,
            onToggleAutoSync = controller::setAutoSyncClipboard,
            lastSyncedSummary = clipSummary,
            manualClipInput = manualClipInput,
            onManualClipChange = { manualClipInput = it },
            onPushClip = {
                controller.pushTextClipboard(manualClipInput)
                manualClipInput = ""
            },
            onPollSystemClip = controller::syncLocalClipboardNow,
        )

        NotificationRelayCard(
            notifications = notifications,
            forwardToDesktop = forwardNotifs,
            onToggleForward = controller::setForwardNotificationsToDesktop,
            onReply = controller::sendNotificationReply,
            onDismiss = controller::dismissNotification,
        )

        LinuxSystemStatusCard(
            capabilities = capabilities,
            onRefreshProbe = controller::refreshLinuxCapabilities,
            onInstallAirDrop = { controller.installLinuxAirDropIntegration() },
        )
    }
}

@Composable
private fun PresenceAndMirrorCard(
    phoneHost: String,
    onPhoneHostChange: (String) -> Unit,
    presenceMap: Map<String, PresenceBeaconInfo>,
    onStartMirror: () -> Unit,
    onStopMirror: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Phone Presence & Screen Mirroring (scrcpy)",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.SemiBold,
            )
            if (presenceMap.isEmpty()) {
                Text(
                    "No active phone presence beacons yet — pair or connect your Android device.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            } else {
                presenceMap.forEach { (peerId, beacon) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Peer ${peerId.take(10)}…",
                            style = MaterialTheme.typography.body2,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Battery: ${beacon.batteryPct}%" + if (beacon.isCharging) " (Charging ⚡)" else "",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Screen: ${if (beacon.screenOn) "ON" else "OFF"}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = phoneHost,
                    onValueChange = onPhoneHostChange,
                    label = { Text("Phone LAN / Hotspot IP") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onStartMirror) { Text("Mirror Screen") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onStopMirror) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun ContinuityCameraStudioCard(
    phoneHost: String,
    cameraStatus: CameraStatusInfo,
    onStartCamDroid: (resolution: String, fps: Int, codec: String, front: Boolean) -> Unit,
    onStartScrcpyFallback: (resolution: String, fps: Int, front: Boolean) -> Unit,
    onCommand: (CamDroidControlCommand) -> Unit,
    onStop: () -> Unit,
) {
    var selectedRes by remember { mutableStateOf(cameraStatus.resolution) }
    var selectedFps by remember { mutableStateOf(cameraStatus.fps) }
    var selectedCodec by remember { mutableStateOf(cameraStatus.codec) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Continuity Camera Studio (CamDroid :4747 + V4L2)",
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${cameraStatus.state.wireName.uppercase()} · ${cameraStatus.v4l2DevicePath}",
                    style = MaterialTheme.typography.caption,
                    color = if (cameraStatus.state == CameraStreamState.STREAMING) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Res:", style = MaterialTheme.typography.caption)
                listOf("1080p", "1440p", "4k").forEach { res ->
                    OutlinedButton(onClick = {
                        selectedRes = res
                        onCommand(CamDroidControlCommand.SetResolution(res))
                    }) {
                        Text(if (selectedRes == res) "[$res]" else res)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("FPS:", style = MaterialTheme.typography.caption)
                listOf(30, 60).forEach { fps ->
                    OutlinedButton(onClick = {
                        selectedFps = fps
                        onCommand(CamDroidControlCommand.SetFps(fps))
                    }) {
                        Text(if (selectedFps == fps) "[${fps}]" else "$fps")
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("Codec:", style = MaterialTheme.typography.caption)
                listOf("h264", "h265", "mjpeg").forEach { codec ->
                    OutlinedButton(onClick = {
                        selectedCodec = codec
                        onCommand(CamDroidControlCommand.SetCodec(codec))
                    }) {
                        Text(if (selectedCodec == codec) "[$codec]" else codec)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom:", style = MaterialTheme.typography.caption)
                listOf(100L to "1.0x", 150L to "1.5x", 200L to "2.0x", 300L to "3.0x").forEach { (zx100, label) ->
                    OutlinedButton(onClick = { onCommand(CamDroidControlCommand.SetZoom(zx100)) }) {
                        Text(label)
                    }
                }
                OutlinedButton(onClick = { onCommand(CamDroidControlCommand.SwitchCamera) }) {
                    Text(if (cameraStatus.frontCamera) "Lens: Front" else "Lens: Rear")
                }
                OutlinedButton(onClick = { onCommand(CamDroidControlCommand.SetFlash(!cameraStatus.flashEnabled)) }) {
                    Text(if (cameraStatus.flashEnabled) "Torch: ON" else "Torch: OFF")
                }
                OutlinedButton(onClick = { onCommand(CamDroidControlCommand.SetMirror(!cameraStatus.mirrorEnabled)) }) {
                    Text(if (cameraStatus.mirrorEnabled) "Mirror: ON" else "Mirror: OFF")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onStartCamDroid(selectedRes, selectedFps, selectedCodec, cameraStatus.frontCamera)
                }) {
                    Text("Start CamDroid (:4747)")
                }
                OutlinedButton(onClick = {
                    onStartScrcpyFallback(selectedRes, selectedFps, cameraStatus.frontCamera)
                }) {
                    Text("scrcpy Camera Fallback")
                }
                OutlinedButton(onClick = { onCommand(CamDroidControlCommand.RequestKeyframe) }) {
                    Text("Request I-Frame")
                }
                TextButton(onClick = onStop) {
                    Text("Stop Camera")
                }
            }
            cameraStatus.reason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ClipboardAndPairingCard(
    qrUri: String,
    qrScanInput: String,
    onQrScanInputChange: (String) -> Unit,
    onPairQr: () -> Unit,
    onRefreshQr: () -> Unit,
    autoSyncClipboard: Boolean,
    onToggleAutoSync: (Boolean) -> Unit,
    lastSyncedSummary: String,
    manualClipInput: String,
    onManualClipChange: (String) -> Unit,
    onPushClip: () -> Unit,
    onPollSystemClip: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Universal Clipboard & Ed25519 QR Pairing",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoSyncClipboard, onCheckedChange = onToggleAutoSync)
                Spacer(Modifier.width(8.dp))
                Text("Auto-sync Linux clipboard (Wayland wl-clipboard / X11 xclip)")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onPollSystemClip) { Text("Sync System Clip Now") }
            }
            Text(
                lastSyncedSummary,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualClipInput,
                    onValueChange = onManualClipChange,
                    label = { Text("Push text to phone clipboard") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPushClip, enabled = manualClipInput.isNotBlank()) {
                    Text("Push Clip")
                }
            }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Desktop Pairing URI: $qrUri",
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRefreshQr) { Text("Rotate Nonce") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = qrScanInput,
                    onValueChange = onQrScanInputChange,
                    label = { Text("Paste phone ec0://pair?v=1... URI") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPairQr, enabled = qrScanInput.isNotBlank()) {
                    Text("Pair & Connect")
                }
            }
        }
    }
}

@Composable
private fun NotificationRelayCard(
    notifications: List<NotificationPostInfo>,
    forwardToDesktop: Boolean,
    onToggleForward: (Boolean) -> Unit,
    onReply: (notifId: String, action: String, text: String) -> Unit,
    onDismiss: (notifId: String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Android Notification Center (${notifications.size})",
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = forwardToDesktop, onCheckedChange = onToggleForward)
                Spacer(Modifier.width(4.dp))
                Text("Freedesktop notify-send", style = MaterialTheme.typography.caption)
            }
            if (notifications.isEmpty()) {
                Text(
                    "No active notifications from phone.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            } else {
                notifications.forEach { notif ->
                    var replyText by remember(notif.notifId) { mutableStateOf("") }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${notif.appName} — ${notif.title}",
                                style = MaterialTheme.typography.body2,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onDismiss(notif.notifId) }) {
                                Text("Dismiss")
                            }
                        }
                        Text(notif.body, style = MaterialTheme.typography.body2)
                        if (notif.canReply || notif.actions.isNotEmpty()) {
                            val defaultAction = notif.actions.firstOrNull() ?: "reply"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = replyText,
                                    onValueChange = { replyText = it },
                                    label = { Text("Inline reply") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        onReply(notif.notifId, defaultAction, replyText)
                                        replyText = ""
                                    },
                                    enabled = replyText.isNotBlank(),
                                ) {
                                    Text("Reply")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinuxSystemStatusCard(
    capabilities: LinuxSystemCapabilities,
    onRefreshProbe: () -> Unit,
    onInstallAirDrop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Linux System Integration Diagnostics (${capabilities.displayServer})",
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRefreshProbe) { Text("Re-probe OS") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onInstallAirDrop) { Text("Install AirDrop Menus") }
            }
            Text(
                "Clipboard (${if (capabilities.clipboardReady) "READY" else "MISSING"}) · " +
                    "Notifications (${if (capabilities.notificationsReady) "READY" else "MISSING"}) · " +
                    "scrcpy+adb (${if (capabilities.screenMirrorReady) "READY" else "MISSING"}) · " +
                    "V4L2 ${capabilities.v4l2DevicePath} (${if (capabilities.v4l2DeviceReady) "READY" else "NOT LOADED"})",
                style = MaterialTheme.typography.body2,
            )
            capabilities.remediationHints().forEach { hint ->
                Text(
                    "• $hint",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}
