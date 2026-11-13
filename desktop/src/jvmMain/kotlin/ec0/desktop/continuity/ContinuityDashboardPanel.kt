/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Production-Grade Compose Multiplatform Continuity Studio (`ec0.desktop.continuity`):
 *   - 60-30-10 Dark Slate (`#0B1120`) & Crisp Light (`#F8FAFC`) Theme (`ContinuityTheme`)
 *   - 3-Zone Studio Header with `SegmentedOptionBar` navigation (`OVERVIEW`,
 *     `CAMERA_STUDIO`, `DISPLAY_INPUT`, `AIRDROP_CLIPBOARD`, `SYSTEM_SECURITY`),
 *     Quick Command Palette (`Ctrl+K`), and Live Link-Health Sparkline
 *   - Tabular Monospace KPI Strip (`TabularMetricCell`) + Tier-0 USB-C ADB Tunnel indicator
 *   - Continuity Camera Studio (`ARYAN007H/CamDroid` TCP `:4747` + `/dev/video10`
 *     V4L2 controls + PipeWire/PulseAudio Virtual Microphone `CamDroidMic` + A/V drift)
 *   - Seamless Browser/App Handoff (`xdg-open` <-> Android `ACTION_VIEW`)
 *   - Spatial Universal Control Monitor Canvas (`LEFT`, `RIGHT`, `TOP`, `BOTTOM`)
 *     + Live Remote Trackpad & UTF-8 Keyboard Input Forwarding (`InputShareService`)
 *   - Resumable AirDrop Transfer Queue (`.ec0part` progress bars, live EMA `MB/s` + ETA,
 *     mid-stream cancel, `Ctrl+C` `text/uri-list` file paste) + Continuity Sketch Markup Canvas
 *   - Smart 2FA / OTP Code Extractor with 30-second Ephemeral Clipboard Auto-Scrub
 *   - Proximity Workstation Lock (`loginctl lock-session`), Find My Phone Siren,
 *     and Automatic Session Re-Keying (`FRAME_KEY_UPDATE = 0x04`)
 *   - Pure-Kotlin ISO/IEC 18004 QR Code Canvas Renderer & 8-Char SAS Verification Badge
 *   - Persistent PinStore Security Ledger & Linux OS Readiness Diagnostics
 */
package ec0.desktop.continuity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec0.core.engine.DiscoveredCamDroidService
import ec0.core.engine.EngineIdentity
import ec0.core.engine.QrCodeMatrix
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.linux.LinuxSystemCapabilities
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.ContinuitySketchStroke
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.HandoffActivityInfo
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.services.TransferProgressSnapshot
import ec0.core.engine.services.UsbTunnelStatus

@Composable
fun ContinuityDashboardPanel(
    controller: ContinuityHubController,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme by controller.isDarkTheme.collectAsState()
    val activeTab by controller.activeStudioTab.collectAsState()
    val commandPaletteOpen by controller.commandPaletteOpen.collectAsState()
    val capabilities by controller.linuxCapabilities.collectAsState()
    val qrUri by controller.pairingQrUri.collectAsState()
    val qrMatrix by controller.pairingQrMatrix.collectAsState()
    val sasBadge by controller.lastVerifiedSasBadge.collectAsState()
    val clipSummary by controller.lastSyncedClipSummary.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    val autoClip by controller.autoSyncClipboard.collectAsState()
    val forwardNotifs by controller.forwardNotificationsToDesktop.collectAsState()
    val notifications by controller.notifications.collectAsState()
    val peerPresence by controller.peerPresence.collectAsState()
    val cameraStatus by controller.cameraStatus.collectAsState()
    val camDroidServices by controller.discoveredCamDroidServices.collectAsState()
    val hotspotStatus by controller.hotspotStatus.collectAsState()
    val activeCalls by controller.activeCalls.collectAsState()
    val mediaSession by controller.remoteMediaSession.collectAsState()
    val activeEdge by controller.universalControlEdge.collectAsState()
    val transfers by controller.transfers.collectAsState()
    val pinnedPeers by controller.pinnedPeers.collectAsState()
    val usbTunnelStatus by controller.usbTunnelStatus.collectAsState()
    val virtualMicActive by controller.virtualMicActive.collectAsState()
    val avDriftMs by controller.avDriftMs.collectAsState()
    val remoteHandoff by controller.remoteHandoffActivity.collectAsState()
    val findMyRinging by controller.findMyRinging.collectAsState()
    val rekeyEpoch by controller.rekeyEpoch.collectAsState()
    val rttSamples by controller.linkRttSamplesMs.collectAsState()
    val sketchStrokes by controller.sketchStrokes.collectAsState()

    var phoneHostInput by remember { mutableStateOf("192.168.1.42") }
    var qrScanInput by remember { mutableStateOf("") }
    var manualClipInput by remember { mutableStateOf("") }
    var filePathInput by remember { mutableStateOf("") }
    var remoteKeyText by remember { mutableStateOf("") }
    var handoffUrlInput by remember { mutableStateOf("https://") }
    var commandQuery by remember { mutableStateOf("") }

    ContinuityTheme(darkTheme = isDarkTheme) {
        val palette = LocalContinuityPalette.current
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.canvasBackground)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StudioTopHeaderBar(
                desktopAlias = controller.desktopAlias,
                localDeviceId = controller.localIdentity.deviceId,
                sasBadge = sasBadge,
                statusBanner = banner,
                activeTab = activeTab,
                onSelectTab = controller::selectStudioTab,
                commandPaletteOpen = commandPaletteOpen,
                onToggleCommandPalette = controller::toggleCommandPalette,
                isDarkTheme = isDarkTheme,
                onToggleTheme = controller::toggleDarkTheme,
            )

            if (commandPaletteOpen) {
                CommandPaletteSurface(
                    query = commandQuery,
                    onQueryChange = { commandQuery = it },
                    commands = controller.availableQuickCommands(phoneHostInput),
                    onExecuteCommand = { cmd ->
                        cmd.action()
                        controller.setCommandPaletteOpen(false)
                        commandQuery = ""
                    },
                    onDismiss = { controller.setCommandPaletteOpen(false) },
                )
            }

            StudioKpiStrip(
                pinnedPeers = pinnedPeers,
                sasBadge = sasBadge,
                presenceMap = peerPresence,
                cameraStatus = cameraStatus,
                transfers = transfers,
                hotspotStatus = hotspotStatus,
                usbTunnelStatus = usbTunnelStatus,
                rttSamples = rttSamples,
            )

            if (remoteHandoff != null) {
                HandoffBannerCard(
                    activity = remoteHandoff!!,
                    onOpenOnLinux = controller::openRemoteHandoffOnLinux,
                )
            }

            if (activeCalls.isNotEmpty()) {
                IncomingCallBannerCard(
                    calls = activeCalls,
                    onCallAction = controller::sendTelephonyCallAction,
                )
            }

            when (activeTab) {
                ContinuityStudioTab.OVERVIEW -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PresenceAndMirrorCard(
                                phoneHost = phoneHostInput,
                                onPhoneHostChange = { phoneHostInput = it },
                                presenceMap = peerPresence,
                                onStartMirror = { controller.startScreenMirror(phoneHostInput) },
                                onStopMirror = controller::stopScreenMirror,
                                handoffUrlInput = handoffUrlInput,
                                onHandoffUrlChange = { handoffUrlInput = it },
                                onSendHandoffUrl = {
                                    controller.sendUrlHandoffToPhone(handoffUrlInput, "Browser Handoff")
                                },
                            )
                            ContinuityCameraStudioCard(
                                phoneHost = phoneHostInput,
                                onSelectDiscoveredHost = { phoneHostInput = it },
                                discoveredCamDroid = camDroidServices,
                                cameraStatus = cameraStatus,
                                v4l2Ready = capabilities.v4l2DeviceReady,
                                virtualMicActive = virtualMicActive,
                                avDriftMs = avDriftMs,
                                onToggleVirtualMic = controller::toggleVirtualMic,
                                onSetupV4l2Pkexec = controller::setupV4l2LoopbackWithPkexec,
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
                            AirDropTransferQueueSection(
                                transfers = transfers,
                                formatThroughput = { snap ->
                                    controller.formatTransferThroughput(snap).formatTabular()
                                },
                                filePathInput = filePathInput,
                                onFilePathChange = { filePathInput = it },
                                onSendFilePath = {
                                    controller.sendFilePath(filePathInput)
                                    filePathInput = ""
                                },
                                onSendCopiedFiles = controller::sendCopiedFilesFromClipboard,
                                onCancelTransfer = controller::cancelFileTransfer,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            UniversalControlAndHotspotCard(
                                activeEdge = activeEdge,
                                onSelectEdge = controller::setUniversalControlEdge,
                                onTestHandoff = { controller.triggerEdgeCursorHandoff() },
                                onReturnCursor = controller::returnCursorToLocalDesktop,
                                hotspotStatus = hotspotStatus,
                                onToggleHotspot = controller::toggleInstantHotspot,
                                onJoinHotspot = controller::joinPhoneHotspotViaNmcli,
                                mediaSession = mediaSession,
                                onMediaControl = { action -> controller.sendMediaControl(action) },
                            )
                            ClipboardAndPairingCard(
                                qrUri = qrUri,
                                qrMatrix = qrMatrix,
                                sasBadge = sasBadge,
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
                                onSendCopiedFiles = controller::sendCopiedFilesFromClipboard,
                            )
                            NotificationRelayCard(
                                notifications = notifications,
                                forwardToDesktop = forwardNotifs,
                                onToggleForward = controller::setForwardNotificationsToDesktop,
                                onCopyOtpCode = controller::copyExtractedOtpCode,
                                onReply = controller::sendNotificationReply,
                                onDismiss = controller::dismissNotification,
                            )
                        }
                    }
                }

                ContinuityStudioTab.CAMERA_STUDIO -> {
                    ContinuityCameraStudioCard(
                        phoneHost = phoneHostInput,
                        onSelectDiscoveredHost = { phoneHostInput = it },
                        discoveredCamDroid = camDroidServices,
                        cameraStatus = cameraStatus,
                        v4l2Ready = capabilities.v4l2DeviceReady,
                        virtualMicActive = virtualMicActive,
                        avDriftMs = avDriftMs,
                        onToggleVirtualMic = controller::toggleVirtualMic,
                        onSetupV4l2Pkexec = controller::setupV4l2LoopbackWithPkexec,
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
                }

                ContinuityStudioTab.DISPLAY_INPUT -> {
                    PresenceAndMirrorCard(
                        phoneHost = phoneHostInput,
                        onPhoneHostChange = { phoneHostInput = it },
                        presenceMap = peerPresence,
                        onStartMirror = { controller.startScreenMirror(phoneHostInput) },
                        onStopMirror = controller::stopScreenMirror,
                        handoffUrlInput = handoffUrlInput,
                        onHandoffUrlChange = { handoffUrlInput = it },
                        onSendHandoffUrl = {
                            controller.sendUrlHandoffToPhone(handoffUrlInput, "Browser Handoff")
                        },
                    )
                    UniversalControlAndHotspotCard(
                        activeEdge = activeEdge,
                        onSelectEdge = controller::setUniversalControlEdge,
                        onTestHandoff = { controller.triggerEdgeCursorHandoff() },
                        onReturnCursor = controller::returnCursorToLocalDesktop,
                        hotspotStatus = hotspotStatus,
                        onToggleHotspot = controller::toggleInstantHotspot,
                        onJoinHotspot = controller::joinPhoneHotspotViaNmcli,
                        mediaSession = mediaSession,
                        onMediaControl = { action -> controller.sendMediaControl(action) },
                    )
                    RemoteInputPadSection(
                        remoteKeyText = remoteKeyText,
                        onRemoteKeyTextChange = { remoteKeyText = it },
                        onSendTextCommit = {
                            controller.sendRemoteTextCommit(remoteKeyText)
                            remoteKeyText = ""
                        },
                        onPointerEvent = controller::sendRemotePointerEvent,
                    )
                }

                ContinuityStudioTab.AIRDROP_CLIPBOARD -> {
                    AirDropTransferQueueSection(
                        transfers = transfers,
                        formatThroughput = { snap ->
                            controller.formatTransferThroughput(snap).formatTabular()
                        },
                        filePathInput = filePathInput,
                        onFilePathChange = { filePathInput = it },
                        onSendFilePath = {
                            controller.sendFilePath(filePathInput)
                            filePathInput = ""
                        },
                        onSendCopiedFiles = controller::sendCopiedFilesFromClipboard,
                        onCancelTransfer = controller::cancelFileTransfer,
                    )
                    ContinuitySketchSection(
                        strokes = sketchStrokes,
                        onAddPresetStroke = controller::addSketchPresetStroke,
                        onClearStrokes = controller::clearSketchStrokes,
                        onSyncToPhone = controller::syncSketchOverlayToPeer,
                    )
                    ClipboardAndPairingCard(
                        qrUri = qrUri,
                        qrMatrix = qrMatrix,
                        sasBadge = sasBadge,
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
                        onSendCopiedFiles = controller::sendCopiedFilesFromClipboard,
                    )
                }

                ContinuityStudioTab.SYSTEM_SECURITY -> {
                    LinuxSystemStatusCard(
                        capabilities = capabilities,
                        onRefreshProbe = controller::refreshLinuxCapabilities,
                        onInstallAirDrop = { controller.installLinuxAirDropIntegration() },
                    )
                    SecurityAndPinnedPeersSection(
                        localDeviceId = controller.localIdentity.deviceId,
                        localPubKey = controller.localIdentity.publicKeyRaw,
                        pinnedPeers = pinnedPeers,
                        usbTunnelStatus = usbTunnelStatus,
                        findMyRinging = findMyRinging,
                        rekeyEpoch = rekeyEpoch,
                        onProbeUsbTunnel = controller::probeAndConnectUsbTunnel,
                        onTearDownUsbTunnel = controller::tearDownUsbTunnel,
                        onToggleFindMyPhone = { controller.triggerFindMyPhone(!findMyRinging) },
                        onLockWorkstation = controller::lockLocalWorkstation,
                        onRotateSessionKeys = controller::rotateActiveSessionKeys,
                        onUnpair = controller::unpairPeer,
                    )
                    NotificationRelayCard(
                        notifications = notifications,
                        forwardToDesktop = forwardNotifs,
                        onToggleForward = controller::setForwardNotificationsToDesktop,
                        onCopyOtpCode = controller::copyExtractedOtpCode,
                        onReply = controller::sendNotificationReply,
                        onDismiss = controller::dismissNotification,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioTopHeaderBar(
    desktopAlias: String,
    localDeviceId: String,
    sasBadge: String?,
    statusBanner: String,
    activeTab: ContinuityStudioTab,
    onSelectTab: (ContinuityStudioTab) -> Unit,
    commandPaletteOpen: Boolean,
    onToggleCommandPalette: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfacePrimary, RoundedCornerShape(10.dp))
            .border(1.dp, palette.borderHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ec0 Continuity Studio",
                    color = palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                UnboxedMetadataRow(
                    items = listOfNotNull(
                        desktopAlias,
                        "id=${localDeviceId.take(12)}…",
                        sasBadge?.let { "SAS $it" },
                    ),
                    monospace = true,
                )
            }
            SegmentedOptionBar(
                options = ContinuityStudioTab.entries,
                selected = activeTab,
                labelSelector = { it.label },
                onSelect = onSelectTab,
            )
            OutlinedButton(onClick = onToggleCommandPalette) {
                Text(if (commandPaletteOpen) "Close Palette" else "Command Palette (Ctrl+K)")
            }
            OutlinedButton(onClick = onToggleTheme) {
                Text(if (isDarkTheme) "Dark Slate" else "Crisp Light")
            }
        }
        Divider(color = palette.borderHairline, thickness = 1.dp)
        Text(
            text = statusBanner,
            color = palette.accentPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CommandPaletteSurface(
    query: String,
    onQueryChange: (String) -> Unit,
    commands: List<StudioQuickCommand>,
    onExecuteCommand: (StudioQuickCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    val filtered = remember(query, commands) {
        if (query.isBlank()) {
            commands
        } else {
            val q = query.trim().lowercase()
            commands.filter {
                it.title.lowercase().contains(q) ||
                    it.category.lowercase().contains(q) ||
                    it.shortcutHint.lowercase().contains(q) ||
                    it.id.lowercase().contains(q)
            }
        }
    }
    ContinuitySectionSurface(
        title = "Studio Command Palette (Keyboard-First Quick Actions)",
        metadataItems = listOf("${filtered.size} matching command(s)", "Instant continuity execution"),
        headerActions = {
            TextButton(onClick = onDismiss) { Text("Esc / Close") }
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Filter commands (e.g. USB-C, CamDroid, Virtual Mic, Re-Key, Find My, Lock)…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            filtered.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceSubtle, RoundedCornerShape(6.dp))
                        .border(1.dp, palette.borderHairline, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cmd.title,
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${cmd.category} · ${cmd.shortcutHint}",
                            color = palette.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Button(onClick = { onExecuteCommand(cmd) }) {
                        Text("Run")
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioKpiStrip(
    pinnedPeers: List<PinnedPeer>,
    sasBadge: String?,
    presenceMap: Map<String, PresenceBeaconInfo>,
    cameraStatus: CameraStatusInfo,
    transfers: Map<String, TransferProgressSnapshot>,
    hotspotStatus: HotspotStatusInfo,
    usbTunnelStatus: UsbTunnelStatus?,
    rttSamples: List<Long>,
) {
    val palette = LocalContinuityPalette.current
    val primaryBeacon = presenceMap.values.firstOrNull()
    val activeTransferCount = transfers.values.count {
        it.state == FileTransferState.TRANSFERRING || it.state == FileTransferState.OFFERED
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabularMetricCell(
            label = "TRUSTED PEERS",
            value = "${pinnedPeers.size} PINNED",
            subtext = sasBadge?.let { "SAS $it" } ?: "Ed25519 + X25519",
            valueColor = if (pinnedPeers.isNotEmpty()) palette.statusNominal else palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TabularMetricCell(
            label = "TRANSPORT TIER",
            value = if (usbTunnelStatus != null) "TIER-0 USB-C" else "WI-FI LAN / P2P",
            subtext = if (usbTunnelStatus != null) {
                "adb ${usbTunnelStatus.serial}"
            } else if (primaryBeacon != null) {
                "${primaryBeacon.batteryPct}%" + if (primaryBeacon.isCharging) " CHG" else ""
            } else {
                "mDNS + TCP :49880"
            },
            valueColor = if (usbTunnelStatus != null) palette.statusNominal else palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TabularMetricCell(
            label = "CAMDROID V4L2",
            value = cameraStatus.state.wireName.uppercase(),
            subtext = "${cameraStatus.v4l2DevicePath} · ${cameraStatus.resolution}@${cameraStatus.fps}",
            valueColor = if (cameraStatus.state == CameraStreamState.STREAMING) {
                palette.statusNominal
            } else {
                palette.textPrimary
            },
            modifier = Modifier.weight(1f),
        )
        TabularMetricCell(
            label = "AIRDROP QUEUE",
            value = "$activeTransferCount ACTIVE",
            subtext = "${transfers.size} total session transfer(s)",
            modifier = Modifier.weight(1f),
        )
        TabularMetricCell(
            label = "INSTANT HOTSPOT",
            value = if (hotspotStatus.enabled) "ON ${hotspotStatus.bandMhz}MHz" else "LAN READY",
            subtext = hotspotStatus.ssid ?: "Direct Wi-Fi / SoftAP",
            valueColor = if (hotspotStatus.enabled) palette.statusNominal else palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        LinkHealthSparklineCanvas(
            rttSamples = rttSamples,
            modifier = Modifier
                .width(140.dp)
                .height(66.dp),
        )
    }
}

@Composable
private fun LinkHealthSparklineCanvas(
    rttSamples: List<Long>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalContinuityPalette.current
    val latestRtt = rttSamples.lastOrNull() ?: 2L
    Column(
        modifier = modifier
            .background(palette.surfacePrimary, RoundedCornerShape(8.dp))
            .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LINK RTT",
                color = palette.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${latestRtt}ms",
                color = palette.statusNominal,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
        ) {
            if (rttSamples.size >= 2) {
                val maxVal = (rttSamples.maxOrNull() ?: 20L).coerceAtLeast(10L).toFloat()
                val stepX = size.width / (rttSamples.size - 1).coerceAtLeast(1)
                for (i in 0 until rttSamples.size - 1) {
                    val x1 = i * stepX
                    val y1 = size.height - (rttSamples[i].toFloat() / maxVal).coerceIn(0.08f, 0.95f) * size.height
                    val x2 = (i + 1) * stepX
                    val y2 = size.height - (rttSamples[i + 1].toFloat() / maxVal).coerceIn(0.08f, 0.95f) * size.height
                    drawLine(
                        color = palette.accentPrimary,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2f,
                    )
                }
            }
        }
    }
}

@Composable
private fun HandoffBannerCard(
    activity: HandoffActivityInfo,
    onOpenOnLinux: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Seamless Browser & App Handoff Available",
        metadataItems = listOf(
            activity.sourceApp.ifBlank { "Android Browser" },
            "Validated HTTPS/HTTP URL",
        ),
        headerActions = {
            Button(onClick = onOpenOnLinux) {
                Text("Continue on Linux (xdg-open)")
            }
        },
    ) {
        Text(
            text = activity.title.ifBlank { activity.url },
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = activity.url,
            color = palette.accentPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IncomingCallBannerCard(
    calls: List<CallStateInfo>,
    onCallAction: (CallActionCommand) -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Incoming Android Telephony Alert",
        metadataItems = listOf("Auto-paused Linux MPRIS2 media", "${calls.size} active call(s)"),
    ) {
        calls.forEach { call ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${call.state.wireName.uppercase()}: ${call.callerName.ifBlank { call.callerNumber }}",
                        color = palette.statusWarning,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    if (call.callerNumber.isNotBlank()) {
                        Text(
                            text = call.callerNumber,
                            color = palette.textMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Button(onClick = {
                    onCallAction(
                        CallActionCommand(
                            callId = call.callId,
                            action = CallActionCommand.ACTION_ANSWER_SPEAKER,
                        ),
                    )
                }) {
                    Text("Answer (Speaker)")
                }
                OutlinedButton(onClick = {
                    onCallAction(
                        CallActionCommand(
                            callId = call.callId,
                            action = CallActionCommand.ACTION_REJECT_WITH_SMS,
                            smsReplyText = "In a meeting — will call right back.",
                        ),
                    )
                }) {
                    Text("Reply SMS")
                }
                TextButton(onClick = {
                    onCallAction(
                        CallActionCommand(
                            callId = call.callId,
                            action = CallActionCommand.ACTION_REJECT,
                        ),
                    )
                }) {
                    Text("Decline")
                }
            }
        }
    }
}

@Composable
private fun PresenceAndMirrorCard(
    phoneHost: String,
    onPhoneHostChange: (String) -> Unit,
    presenceMap: Map<String, PresenceBeaconInfo>,
    onStartMirror: () -> Unit,
    onStopMirror: () -> Unit,
    handoffUrlInput: String,
    onHandoffUrlChange: (String) -> Unit,
    onSendHandoffUrl: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Phone Presence, Screen Mirroring & Browser Handoff",
        metadataItems = listOf("adb connect + scrcpy", "60 FPS low-latency H.264", "ACTION_VIEW Handoff"),
        headerActions = {
            Button(onClick = onStartMirror) { Text("Mirror Screen") }
            OutlinedButton(onClick = onStopMirror) { Text("Stop") }
        },
    ) {
        if (presenceMap.isEmpty()) {
            Text(
                text = "No live presence beacons received yet — pair or connect your Android device (Wi-Fi or USB-C).",
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        } else {
            presenceMap.forEach { (peerId, beacon) ->
                UnboxedMetadataRow(
                    items = listOf(
                        "peer=${peerId.take(12)}…",
                        "battery=${beacon.batteryPct}%" + if (beacon.isCharging) " (charging)" else "",
                        "screen=${if (beacon.screenOn) "ON" else "OFF"}",
                        "wifi=${beacon.wifiSsid ?: "LAN"}",
                    ),
                    monospace = true,
                )
            }
        }
        OutlinedTextField(
            value = phoneHost,
            onValueChange = onPhoneHostChange,
            label = { Text("Phone LAN / Hotspot IP Address (or 127.0.0.1 for USB-C Tunnel)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = handoffUrlInput,
                onValueChange = onHandoffUrlChange,
                label = { Text("Handoff URL to Android Browser (https://…)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = onSendHandoffUrl,
                enabled = handoffUrlInput.startsWith("http://") || handoffUrlInput.startsWith("https://"),
            ) {
                Text("Handoff URL")
            }
        }
    }
}

@Composable
private fun ContinuityCameraStudioCard(
    phoneHost: String,
    onSelectDiscoveredHost: (String) -> Unit,
    discoveredCamDroid: List<DiscoveredCamDroidService>,
    cameraStatus: CameraStatusInfo,
    v4l2Ready: Boolean,
    virtualMicActive: Boolean,
    avDriftMs: Long,
    onToggleVirtualMic: () -> Unit,
    onSetupV4l2Pkexec: () -> Unit,
    onStartCamDroid: (resolution: String, fps: Int, codec: String, front: Boolean) -> Unit,
    onStartScrcpyFallback: (resolution: String, fps: Int, front: Boolean) -> Unit,
    onCommand: (CamDroidControlCommand) -> Unit,
    onStop: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    var selectedRes by remember { mutableStateOf(cameraStatus.resolution) }
    var selectedFps by remember { mutableStateOf(cameraStatus.fps) }
    var selectedCodec by remember { mutableStateOf(cameraStatus.codec) }
    var selectedWb by remember { mutableStateOf("auto") }
    var selectedEv by remember { mutableStateOf(0) }

    ContinuitySectionSurface(
        title = "Continuity Camera & PipeWire Virtual Mic (CamDroid :4747 + V4L2)",
        metadataItems = listOf(
            cameraStatus.state.wireName.uppercase(),
            cameraStatus.v4l2DevicePath,
            "${selectedRes}@${selectedFps}fps",
            selectedCodec.uppercase(),
            if (virtualMicActive) "Mic: CamDroidMic (drift ${avDriftMs}ms)" else "Mic: Standby",
        ),
        headerActions = {
            Button(onClick = {
                onStartCamDroid(selectedRes, selectedFps, selectedCodec, cameraStatus.frontCamera)
            }) {
                Text("Start CamDroid")
            }
            OutlinedButton(onClick = onToggleVirtualMic) {
                Text(if (virtualMicActive) "Disable Mic" else "Enable Virtual Mic")
            }
            OutlinedButton(onClick = {
                onStartScrcpyFallback(selectedRes, selectedFps, cameraStatus.frontCamera)
            }) {
                Text("scrcpy Fallback")
            }
            TextButton(onClick = onStop) {
                Text("Stop")
            }
        },
    ) {
        if (discoveredCamDroid.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Discovered CamDroid Peers:", color = palette.textMuted, fontSize = 12.sp)
                discoveredCamDroid.forEach { svc ->
                    val ip = svc.addresses.firstOrNull() ?: return@forEach
                    OutlinedButton(onClick = { onSelectDiscoveredHost(ip) }) {
                        Text("${svc.deviceName} ($ip:${svc.port})")
                    }
                }
            }
        }

        if (!v4l2Ready) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
                    .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Kernel module v4l2loopback (${cameraStatus.v4l2DevicePath}) is not loaded",
                    color = palette.statusWarning,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSetupV4l2Pkexec) {
                    Text("1-Click Load V4L2 (pkexec)")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Resolution", color = palette.textMuted, fontSize = 11.sp)
                SegmentedOptionBar(
                    options = listOf("1080p", "1440p", "4k"),
                    selected = selectedRes,
                    labelSelector = { it.uppercase() },
                    onSelect = { res ->
                        selectedRes = res
                        onCommand(CamDroidControlCommand.SetResolution(res))
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Frame Rate", color = palette.textMuted, fontSize = 11.sp)
                SegmentedOptionBar(
                    options = listOf(30, 60),
                    selected = selectedFps,
                    labelSelector = { "${it}fps" },
                    onSelect = { fps ->
                        selectedFps = fps
                        onCommand(CamDroidControlCommand.SetFps(fps))
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Encoder", color = palette.textMuted, fontSize = 11.sp)
                SegmentedOptionBar(
                    options = listOf("h264", "h265", "mjpeg"),
                    selected = selectedCodec,
                    labelSelector = { it.uppercase() },
                    onSelect = { codec ->
                        selectedCodec = codec
                        onCommand(CamDroidControlCommand.SetCodec(codec))
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("White Balance", color = palette.textMuted, fontSize = 11.sp)
                SegmentedOptionBar(
                    options = listOf("auto", "daylight", "tungsten", "fluorescent", "cloudy"),
                    selected = selectedWb,
                    labelSelector = { it.replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { wb ->
                        selectedWb = wb
                        onCommand(CamDroidControlCommand.SetWhiteBalance(wb))
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Exposure EV", color = palette.textMuted, fontSize = 11.sp)
                SegmentedOptionBar(
                    options = listOf(-2, -1, 0, 1, 2),
                    selected = selectedEv,
                    labelSelector = { if (it > 0) "+$it" else "$it" },
                    onSelect = { ev ->
                        selectedEv = ev
                        onCommand(CamDroidControlCommand.SetExposure(ev))
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            OutlinedButton(onClick = { onCommand(CamDroidControlCommand.RequestKeyframe) }) {
                Text("Sync I-Frame")
            }
        }

        cameraStatus.reason?.let { reason ->
            Text(text = reason, color = palette.textMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun UniversalControlAndHotspotCard(
    activeEdge: ScreenEdge,
    onSelectEdge: (ScreenEdge) -> Unit,
    onTestHandoff: () -> Unit,
    onReturnCursor: () -> Unit,
    hotspotStatus: HotspotStatusInfo,
    onToggleHotspot: (Boolean) -> Unit,
    onJoinHotspot: () -> Unit,
    mediaSession: MediaSessionInfo?,
    onMediaControl: (String) -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Spatial Universal Control, Hotspot & Media Remote",
        metadataItems = listOf(
            "Edge: ${activeEdge.wireName.uppercase()}",
            if (hotspotStatus.enabled) "Hotspot ON" else "Hotspot Standby",
            "MPRIS2 Bridge",
        ),
        headerActions = {
            Button(onClick = onTestHandoff) { Text("Handoff Cursor") }
            OutlinedButton(onClick = onReturnCursor) { Text("Return to Linux") }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpatialMonitorCanvas(
                activeEdge = activeEdge,
                modifier = Modifier.size(width = 160.dp, height = 96.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Phone Position Relative to Linux Monitor",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                SegmentedOptionBar(
                    options = ScreenEdge.entries,
                    selected = activeEdge,
                    labelSelector = { it.wireName.uppercase() },
                    onSelect = onSelectEdge,
                )
            }
        }

        Divider(color = palette.borderHairline, thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hotspotStatus.enabled) {
                        "Instant Hotspot Active (${hotspotStatus.ssid ?: "SoftAP"})"
                    } else {
                        "Instant Wi-Fi Hotspot (Zero-Touch Tethering)"
                    },
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                UnboxedMetadataRow(
                    items = listOf(
                        "${hotspotStatus.bandMhz} MHz",
                        "${hotspotStatus.connectedClients} client(s)",
                        "nmcli auto-join",
                    ),
                    monospace = true,
                )
            }
            Button(onClick = { onToggleHotspot(!hotspotStatus.enabled) }) {
                Text(if (hotspotStatus.enabled) "Disable Hotspot" else "Enable Hotspot")
            }
            if (hotspotStatus.enabled && !hotspotStatus.ssid.isNullOrBlank()) {
                OutlinedButton(onClick = onJoinHotspot) {
                    Text("Join via nmcli")
                }
            }
        }

        Divider(color = palette.borderHairline, thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val trackLabel = if (mediaSession != null && mediaSession.title.isNotBlank()) {
                "${mediaSession.title} — ${mediaSession.artist} (${mediaSession.state.wireName})"
            } else {
                "Cross-Device Media Remote (MPRIS2 / Android MediaSession)"
            }
            Text(
                text = trackLabel,
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(onClick = { onMediaControl(MediaControlCommand.ACTION_PREVIOUS) }) {
                Text("Prev")
            }
            Button(onClick = { onMediaControl(MediaControlCommand.ACTION_PLAY_PAUSE) }) {
                Text("Play/Pause")
            }
            OutlinedButton(onClick = { onMediaControl(MediaControlCommand.ACTION_NEXT) }) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun SpatialMonitorCanvas(
    activeEdge: ScreenEdge,
    modifier: Modifier = Modifier,
) {
    val palette = LocalContinuityPalette.current
    Canvas(
        modifier = modifier
            .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
            .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        val monitorW = size.width * 0.52f
        val monitorH = size.height * 0.52f
        val monitorX = (size.width - monitorW) / 2f
        val monitorY = (size.height - monitorH) / 2f

        drawRoundRect(
            color = palette.surfacePrimary,
            topLeft = Offset(monitorX, monitorY),
            size = Size(monitorW, monitorH),
            cornerRadius = CornerRadius(6f, 6f),
        )
        drawRoundRect(
            color = palette.borderHairline,
            topLeft = Offset(monitorX, monitorY),
            size = Size(monitorW, monitorH),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 2f),
        )

        val phoneW = monitorW * 0.28f
        val phoneH = monitorH * 0.65f
        val gap = 8f
        val (phoneX, phoneY) = when (activeEdge) {
            ScreenEdge.LEFT -> (monitorX - phoneW - gap) to (monitorY + (monitorH - phoneH) / 2f)
            ScreenEdge.RIGHT -> (monitorX + monitorW + gap) to (monitorY + (monitorH - phoneH) / 2f)
            ScreenEdge.TOP -> (monitorX + (monitorW - phoneH) / 2f) to (monitorY - phoneW - gap)
            ScreenEdge.BOTTOM -> (monitorX + (monitorW - phoneH) / 2f) to (monitorY + monitorH + gap)
        }
        val (actualW, actualH) = when (activeEdge) {
            ScreenEdge.LEFT, ScreenEdge.RIGHT -> phoneW to phoneH
            ScreenEdge.TOP, ScreenEdge.BOTTOM -> phoneH to phoneW
        }

        drawRoundRect(
            color = palette.accentPrimary,
            topLeft = Offset(phoneX, phoneY),
            size = Size(actualW, actualH),
            cornerRadius = CornerRadius(4f, 4f),
        )
    }
}

@Composable
private fun RemoteInputPadSection(
    remoteKeyText: String,
    onRemoteKeyTextChange: (String) -> Unit,
    onSendTextCommit: () -> Unit,
    onPointerEvent: (action: String, dx: Int, dy: Int, scrollY: Int, buttonsMask: Int) -> Unit,
) {
    ContinuitySectionSurface(
        title = "Remote Trackpad & Keyboard Bridge (InputShareService)",
        metadataItems = listOf("Wayland ydotool / X11 xdotool", "60 Hz relative pointer & UTF-8 text commit"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_MOVE, -40, 0, 0, 0)
            }) {
                Text("Move ←")
            }
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_MOVE, 0, -40, 0, 0)
            }) {
                Text("Move ↑")
            }
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_MOVE, 0, 40, 0, 0)
            }) {
                Text("Move ↓")
            }
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_MOVE, 40, 0, 0, 0)
            }) {
                Text("Move →")
            }
            Button(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_CLICK, 0, 0, 0, 1)
            }) {
                Text("Left Click")
            }
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_CLICK, 0, 0, 0, 2)
            }) {
                Text("Right Click")
            }
            OutlinedButton(onClick = {
                onPointerEvent(InputProtocol.POINTER_ACTION_SCROLL, 0, 0, -3, 0)
            }) {
                Text("Scroll Down")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = remoteKeyText,
                onValueChange = onRemoteKeyTextChange,
                label = { Text("Type UTF-8 text directly onto paired Android phone") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = onSendTextCommit, enabled = remoteKeyText.isNotBlank()) {
                Text("Commit Text")
            }
        }
    }
}

@Composable
private fun AirDropTransferQueueSection(
    transfers: Map<String, TransferProgressSnapshot>,
    formatThroughput: (TransferProgressSnapshot) -> String,
    filePathInput: String,
    onFilePathChange: (String) -> Unit,
    onSendFilePath: () -> Unit,
    onSendCopiedFiles: () -> Unit,
    onCancelTransfer: (String) -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Resumable AirDrop File & Folder Queue",
        metadataItems = listOf(
            "Adaptive 64K / 256K / 1M chunks",
            ".ec0part crash-safe resume",
            "Live EMA MB/s + ETA",
        ),
        headerActions = {
            OutlinedButton(onClick = onSendCopiedFiles) {
                Text("AirDrop Copied Files (Ctrl+C)")
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filePathInput,
                onValueChange = onFilePathChange,
                label = { Text("Local file or folder path to AirDrop") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = onSendFilePath, enabled = filePathInput.isNotBlank()) {
                Text("Send Path")
            }
        }

        if (transfers.isEmpty()) {
            Text(
                text = "No active or recent transfers in this session. Copy files with Ctrl+C in Dolphin/Nautilus or enter a path above.",
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        } else {
            transfers.values.forEach { snap ->
                val pct = if (snap.totalBytes > 0L) {
                    (snap.transferredBytes.toFloat() / snap.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
                        .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${if (snap.isOutgoing) "OUT" else "IN"} · ${snap.transferId.take(12)}…",
                            color = palette.textPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        UnboxedMetadataRow(
                            items = listOf(
                                snap.state.name,
                                "${snap.completedFiles}/${snap.totalFiles} files",
                                "${snap.transferredBytes / 1024} / ${snap.totalBytes / 1024} KiB",
                                formatThroughput(snap),
                            ),
                            monospace = true,
                        )
                        if (snap.state == FileTransferState.TRANSFERRING || snap.state == FileTransferState.OFFERED) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onCancelTransfer(snap.transferId) }) {
                                Text("Cancel")
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = pct,
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accentPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuitySketchSection(
    strokes: List<ContinuitySketchStroke>,
    onAddPresetStroke: (String) -> Unit,
    onClearStrokes: () -> Unit,
    onSyncToPhone: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Continuity Sketch & Instant Screenshot Markup",
        metadataItems = listOf(
            "${strokes.size} vector stroke(s)",
            "0..1000 permille coordinates",
        ),
        headerActions = {
            OutlinedButton(onClick = onClearStrokes) { Text("Clear") }
            Button(onClick = onSyncToPhone, enabled = strokes.isNotEmpty()) {
                Text("Push Markup to Phone")
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onAddPresetStroke("#EF4444") }) {
                Text("+ Red Callout Stroke")
            }
            OutlinedButton(onClick = { onAddPresetStroke("#3B82F6") }) {
                Text("+ Blue Highlight Stroke")
            }
            OutlinedButton(onClick = { onAddPresetStroke("#10B981") }) {
                Text("+ Emerald Underline Stroke")
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
                .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            strokes.forEach { stroke ->
                val pts = stroke.points
                for (i in 0 until pts.size - 1) {
                    val x1 = (pts[i].xPermille / 1000f) * size.width
                    val y1 = (pts[i].yPermille / 1000f) * size.height
                    val x2 = (pts[i + 1].xPermille / 1000f) * size.width
                    val y2 = (pts[i + 1].yPermille / 1000f) * size.height
                    drawLine(
                        color = palette.accentPrimary,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = stroke.strokeWidthPx.toFloat().coerceAtLeast(3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardAndPairingCard(
    qrUri: String,
    qrMatrix: QrCodeMatrix?,
    sasBadge: String?,
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
    onSendCopiedFiles: () -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Universal Clipboard & Ed25519 QR Pairing",
        metadataItems = listOfNotNull(
            "wl-paste --watch / xclip",
            "SHA-256 loop suppression",
            sasBadge?.let { "Verified SAS: $it" },
        ),
        headerActions = {
            OutlinedButton(onClick = onPollSystemClip) { Text("Sync Clip Now") }
            OutlinedButton(onClick = onSendCopiedFiles) { Text("Paste Files") }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = autoSyncClipboard, onCheckedChange = onToggleAutoSync)
            Text(
                text = "Auto-sync Linux clipboard with connected peers",
                color = palette.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = lastSyncedSummary,
            color = palette.textMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualClipInput,
                onValueChange = onManualClipChange,
                label = { Text("Push text clip to phone") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onPushClip, enabled = manualClipInput.isNotBlank()) {
                Text("Push Clip")
            }
        }
        Divider(color = palette.borderHairline, thickness = 1.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (qrMatrix != null) {
                QrCodeCanvas(
                    matrix = qrMatrix,
                    modifier = Modifier
                        .size(116.dp)
                        .border(1.dp, palette.borderHairline, RoundedCornerShape(6.dp)),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = qrUri,
                        color = palette.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
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
}

@Composable
private fun QrCodeCanvas(
    matrix: QrCodeMatrix,
    modifier: Modifier = Modifier,
) {
    val quietZone = 2
    val totalModules = matrix.size + quietZone * 2
    Canvas(modifier = modifier) {
        drawRect(color = Color.White, size = size)
        val cellW = size.width / totalModules.toFloat()
        val cellH = size.height / totalModules.toFloat()
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + quietZone) * cellW, (y + quietZone) * cellH),
                        size = Size(cellW, cellH),
                    )
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
    onCopyOtpCode: (String) -> Unit,
    onReply: (notifId: String, action: String, text: String) -> Unit,
    onDismiss: (notifId: String) -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Android Notification Center & Smart 2FA Extractor (${notifications.size})",
        metadataItems = listOf("org.freedesktop.Notifications D-Bus", "30s OTP auto-scrub"),
        headerActions = {
            Switch(checked = forwardToDesktop, onCheckedChange = onToggleForward)
            Text("Desktop Popups", color = palette.textSecondary, fontSize = 12.sp)
        },
    ) {
        if (notifications.isEmpty()) {
            Text(
                text = "No active notifications from paired Android phone.",
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        } else {
            notifications.forEach { notif ->
                var replyText by remember(notif.notifId) { mutableStateOf("") }
                val extractedOtp = remember(notif.title, notif.body) {
                    OtpCodeExtractor.extractOtpCode(notif.title, notif.body)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
                        .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${notif.appName} — ${notif.title}",
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (extractedOtp != null) {
                            OutlinedButton(onClick = { onCopyOtpCode(extractedOtp) }) {
                                Text("Copy 2FA: $extractedOtp (30s scrub)")
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        TextButton(onClick = { onDismiss(notif.notifId) }) {
                            Text("Dismiss")
                        }
                    }
                    Text(text = notif.body, color = palette.textSecondary, fontSize = 12.sp)
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

@Composable
private fun SecurityAndPinnedPeersSection(
    localDeviceId: String,
    localPubKey: ByteArray,
    pinnedPeers: List<PinnedPeer>,
    usbTunnelStatus: UsbTunnelStatus?,
    findMyRinging: Boolean,
    rekeyEpoch: Int,
    onProbeUsbTunnel: () -> Unit,
    onTearDownUsbTunnel: () -> Unit,
    onToggleFindMyPhone: () -> Unit,
    onLockWorkstation: () -> Unit,
    onRotateSessionKeys: () -> Unit,
    onUnpair: (String) -> Unit,
) {
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Cryptographic Identity, USB-C Tunnel & Proximity Security",
        metadataItems = listOf(
            "ChaCha20-Poly1305 KeyStore",
            "Rekey Epoch #$rekeyEpoch (0x04)",
            if (usbTunnelStatus != null) "USB-C Tunnel: ${usbTunnelStatus.serial}" else "USB-C Tunnel: Standby",
        ),
        headerActions = {
            Button(onClick = onProbeUsbTunnel) {
                Text("Connect USB-C (adb forward)")
            }
            if (usbTunnelStatus != null) {
                OutlinedButton(onClick = onTearDownUsbTunnel) {
                    Text("Disconnect USB-C")
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRotateSessionKeys) {
                Text("Rotate Session Keys (Epoch #${rekeyEpoch + 1})")
            }
            OutlinedButton(onClick = onToggleFindMyPhone) {
                Text(if (findMyRinging) "Stop Find My Siren" else "Find My Phone (Siren)")
            }
            OutlinedButton(onClick = onLockWorkstation) {
                Text("Lock Workstation (loginctl)")
            }
        }

        Divider(color = palette.borderHairline, thickness = 1.dp)

        UnboxedMetadataRow(
            items = listOf(
                "Local DeviceId: ${localDeviceId.take(24)}…",
                "pinned_peers.json (${pinnedPeers.size} peer(s))",
            ),
            monospace = true,
        )
        if (pinnedPeers.isEmpty()) {
            Text(
                text = "No peers pinned yet. Scan or paste an ec0://pair?v=1... QR URI to pin a verified Ed25519 peer key.",
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        } else {
            pinnedPeers.forEach { peer ->
                val sas = EngineIdentity.verificationBadge(localPubKey, peer.publicKeyRaw)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
                        .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${peer.alias} (${peer.trustLevel.name})",
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        UnboxedMetadataRow(
                            items = listOf(
                                "id=${peer.deviceId.take(16)}…",
                                "SAS=$sas",
                            ),
                            monospace = true,
                        )
                    }
                    OutlinedButton(onClick = { onUnpair(peer.deviceId) }) {
                        Text("Unpair")
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
    val palette = LocalContinuityPalette.current
    ContinuitySectionSurface(
        title = "Linux System Integration Diagnostics (${capabilities.displayServer})",
        metadataItems = listOf(
            "Clipboard: ${if (capabilities.clipboardReady) "READY" else "MISSING"}",
            "Notifications: ${if (capabilities.notificationsReady) "READY" else "MISSING"}",
            "scrcpy+adb: ${if (capabilities.screenMirrorReady) "READY" else "MISSING"}",
            "V4L2 ${capabilities.v4l2DevicePath}: ${if (capabilities.v4l2DeviceReady) "READY" else "NOT LOADED"}",
        ),
        headerActions = {
            OutlinedButton(onClick = onRefreshProbe) { Text("Re-probe OS") }
            Button(onClick = onInstallAirDrop) { Text("Install AirDrop Menus") }
        },
    ) {
        val hints = capabilities.remediationHints()
        if (hints.isEmpty()) {
            Text(
                text = "All Linux desktop subsystems (Wayland/X11 clipboard, D-Bus notifications, adb+scrcpy, and V4L2 loopback) are ready.",
                color = palette.statusNominal,
                fontSize = 12.sp,
            )
        } else {
            hints.forEach { hint ->
                Text(
                    text = "• $hint",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
