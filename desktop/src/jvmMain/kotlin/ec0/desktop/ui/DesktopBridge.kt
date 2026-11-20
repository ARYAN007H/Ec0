/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop host for the shared UI (:ui). Adapts AppState + ContinuityHubController
 * into one unified, encrypted-first UiBridge.
 */
package ec0.desktop.ui

import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.MediaPlaybackState
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.ui.models.ActiveCallUi
import ec0.ui.models.AndroidAppUi
import ec0.ui.models.BrowserTabHandoffUi
import ec0.ui.models.CameraControlsState
import ec0.ui.models.CameraEffectsUi
import ec0.ui.models.Capabilities
import ec0.ui.models.CapabilityTile
import ec0.ui.models.ClipboardShelfItemUi
import ec0.ui.models.ClipboardToastUi
import ec0.ui.models.ConnectedPeerSummary
import ec0.ui.models.DiagnosticCheckUi
import ec0.ui.models.LinkTelemetryUi
import ec0.ui.models.MediaSessionUi
import ec0.ui.models.NotificationItem
import ec0.ui.models.PairedPeerInfo
import ec0.ui.models.PairingQr
import ec0.ui.models.PeerLinkState
import ec0.ui.models.PeerManagementBridge
import ec0.ui.models.SettingsState
import ec0.ui.models.SetupCheckItem
import ec0.ui.models.SmartConnectPairingQr
import ec0.ui.models.SmsThreadUi
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import ec0.ui.models.VfsBrowseState
import ec0.ui.models.VfsEntry
import ec0.ui.models.computeTileRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class DesktopBridge(
    private val appState: AppState,
    private val hub: ContinuityHubController? = null,
    private val smartConnectWsPort: Int? = null,
    uiScope: CoroutineScope? = null,
) : UiBridge {
    private val bridgeScope: CoroutineScope =
        uiScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val devices: StateFlow<List<UiDevice>> = appState.devices

    /**
     * Unified transfer queue: merges encrypted ec0-engine transfers (with live EMA
     * MB/s & ETA) with direct-LAN transfers so every transfer shows up in one place.
     */
    override val transfers: StateFlow<List<TransferItem>> =
        if (hub == null) {
            appState.transfers
        } else {
            combine(appState.transfers, hub.transfers, hub.pinnedPeers) { lanItems, engineMap, pinned ->
                val defaultPeerAlias = pinned.firstOrNull()?.alias ?: "Paired device"
                val engineItems = engineMap.values.map { snap ->
                    val pct = if (snap.totalBytes > 0L) {
                        ((snap.transferredBytes * 100L) / snap.totalBytes).toInt().coerceIn(0, 100)
                    } else if (snap.state == FileTransferState.COMPLETED) {
                        100
                    } else {
                        0
                    }
                    val speedSample = if (snap.state == FileTransferState.TRANSFERRING) {
                        hub.formatTransferThroughput(snap)
                    } else {
                        null
                    }
                    TransferItem(
                        id = "ec0:${snap.transferId}",
                        fileName = snap.displayName.ifBlank {
                            if (snap.totalFiles > 1) "${snap.totalFiles} files" else snap.transferId.take(8)
                        },
                        direction = if (snap.isOutgoing) TransferDirection.OUTGOING else TransferDirection.INCOMING,
                        peerAlias = defaultPeerAlias,
                        sizeBytes = snap.totalBytes,
                        progressPct = pct,
                        state = when (snap.state) {
                            FileTransferState.OFFERED -> TransferState.PENDING
                            FileTransferState.TRANSFERRING -> TransferState.RUNNING
                            FileTransferState.COMPLETED -> TransferState.DONE
                            FileTransferState.CANCELLED -> TransferState.CANCELLED
                            FileTransferState.FAILED -> TransferState.FAILED
                        },
                        error = snap.failureReason,
                        speedText = speedSample?.formattedSpeed,
                        etaText = speedSample?.formattedEta,
                        encrypted = true,
                        localPath = snap.savedLocalPath,
                    )
                }
                lanItems + engineItems
            }.stateIn(bridgeScope, SharingStarted.Eagerly, appState.transfers.value)
        }

    override val settings: StateFlow<SettingsState> = appState.settings
    override val scanning: StateFlow<Boolean> = appState.scanning

    override val canRingPhone: Boolean get() = hub != null
    override val canLockPc: Boolean get() = false
    override val hasUsbTunnelControl: Boolean get() = hub != null
    override val hasDesktopIntegration: Boolean get() = true

    private val _smartConnectPairingQr: MutableStateFlow<SmartConnectPairingQr?> = MutableStateFlow(
        buildSmartConnectPairingQr(),
    )
    override val smartConnectPairingQr: StateFlow<SmartConnectPairingQr?> =
        _smartConnectPairingQr.asStateFlow()

    override val pairingQr: StateFlow<PairingQr?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            combine(hub.pairingQrUri, hub.pairingQrMatrix) { uri, matrix ->
                matrix?.let { PairingQr(uri, it.size, it.toRowMajorList()) }
            }.stateIn(bridgeScope, SharingStarted.Eagerly, null)
        }

    override val trustedPeerCount: StateFlow<Int> =
        if (hub == null) {
            MutableStateFlow(0)
        } else {
            hub.pinnedPeers.map { it.size }.stateIn(bridgeScope, SharingStarted.Eagerly, 0)
        }

    override val statusBanner: StateFlow<String?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            hub.statusBanner.map { it as String? }.stateIn(bridgeScope, SharingStarted.Eagerly, null)
        }

    // ---- Connected Peers Hero summary + Paired Device Management ----

    override val connectedPeers: StateFlow<List<ConnectedPeerSummary>> =
        if (hub == null) {
            MutableStateFlow(emptyList())
        } else {
            combine(
                hub.pinnedPeers,
                hub.node.connectionStates,
                hub.peerPresence,
                hub.usbTunnelStatus,
            ) { pinned, states, presence, tunnel ->
                pinned.map { peer ->
                    val id = peer.deviceId.lowercase()
                    val connected = states[id] == EngineConnectionState.CONNECTED || tunnel != null
                    val beacon = presence[id] ?: presence[peer.deviceId]
                    val fp = ec0.core.kdeconnect.Sha256.digest(peer.publicKeyRaw)
                        .joinToString("") { "%02x".format(it) }.take(16)
                    ConnectedPeerSummary(
                        deviceId = peer.deviceId,
                        alias = peer.alias,
                        transport = when {
                            tunnel != null -> "USB-C Direct"
                            connected -> "Wi-Fi Encrypted"
                            else -> "Offline"
                        },
                        connected = connected,
                        batteryPct = beacon?.batteryPct,
                        isCharging = beacon?.isCharging == true,
                        screenOn = beacon?.screenOn ?: true,
                        fingerprintPrefix = fp,
                        usbActive = tunnel != null,
                    )
                }.sortedByDescending { it.connected }
            }.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList())
        }

    override val peerManagement: PeerManagementBridge? = hub?.let { h ->
        object : PeerManagementBridge {
            override val peers: StateFlow<List<PairedPeerInfo>> = combine(
                h.pinnedPeers,
                h.node.connectionStates,
                h.peerCapabilityGrants,
            ) { pinned, states, grants ->
                pinned.map { peer ->
                    val id = peer.deviceId.lowercase()
                    val fp = ec0.core.kdeconnect.Sha256.digest(peer.publicKeyRaw)
                        .joinToString("") { "%02x".format(it) }.take(16)
                    PairedPeerInfo(
                        deviceId = peer.deviceId,
                        alias = peer.alias,
                        fingerprintPrefix = fp,
                        pinnedAtMs = peer.pinnedAtMs,
                        trustLevel = peer.trustLevel.name,
                        connected = states[id] == EngineConnectionState.CONNECTED,
                        grantedCapabilities = grants[id] ?: setOf("remote_input"),
                    )
                }
            }.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList())

            override fun unpair(deviceId: String) {
                h.unpairPeer(deviceId)
            }

            override fun setCapability(deviceId: String, wireName: String, granted: Boolean) {
                h.setPeerCapability(deviceId, wireName, granted)
            }
        }
    }

    // ---- Dashboard (HOME tiles): live hub state, honest OFF defaults ----

    private fun mirrorPoll() = flow {
        while (true) {
            emit(
                runCatching { hub?.node?.scrcpyCoordinator?.isMirroring == true }.getOrDefault(false),
            )
            delay(3_000L)
        }
    }

    override val mirrorRunning: StateFlow<Boolean> = mirrorPoll()
        .stateIn(bridgeScope, SharingStarted.Eagerly, false)

    override val cameraStateText: StateFlow<String?> =
        hub?.cameraStatus?.map { st ->
            val state = st.state.wireName.lowercase()
            if (state == "idle") "idle" else "$state → ${st.v4l2DevicePath}"
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)

    private val _cameraFront = MutableStateFlow(false)
    private val _cameraFlash = MutableStateFlow(false)
    private val _cameraZoom = MutableStateFlow(100)

    override val cameraControls: StateFlow<CameraControlsState?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            combine(
                hub.cameraStatus,
                hub.virtualMicActive,
                _cameraFront,
                _cameraFlash,
                _cameraZoom,
            ) { st, mic, front, flash, zoom ->
                val stateWire = st.state.wireName.lowercase()
                CameraControlsState(
                    streaming = st.state == CameraStreamState.STREAMING,
                    stateLine = if (stateWire == "idle") "idle" else "$stateWire → ${st.v4l2DevicePath}",
                    resolution = st.resolution,
                    fps = st.fps,
                    frontCamera = front,
                    flashEnabled = flash,
                    zoomX100 = zoom,
                    virtualMicActive = mic,
                )
            }.stateIn(bridgeScope, SharingStarted.Eagerly, CameraControlsState())
        }

    override val peerLink: StateFlow<PeerLinkState?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            combine(hub.usbTunnelStatus, hub.node.connectionStates) { tunnel, states ->
                val engineConnected = states.values.any { it == EngineConnectionState.CONNECTED }
                when {
                    tunnel != null -> PeerLinkState(connected = true, transport = "USB-C Direct")
                    engineConnected -> PeerLinkState(connected = true, transport = "Wi-Fi TLS")
                    else -> PeerLinkState(connected = false, transport = "searching")
                }
            }.stateIn(bridgeScope, SharingStarted.Eagerly, null)
        }

    override val clipboardSummary: StateFlow<String> =
        hub?.lastSyncedClipSummary ?: MutableStateFlow("No clipboard items synced yet")

    override val autoSyncClipboard: StateFlow<Boolean> =
        hub?.autoSyncClipboard ?: MutableStateFlow(true)

    override val relayedNotifications: StateFlow<List<NotificationItem>> =
        hub?.notifications?.map { list ->
            list.map {
                NotificationItem(
                    id = it.notifId,
                    appName = it.appName,
                    title = it.title,
                    body = it.body,
                    canReply = it.canReply,
                    otpCode = OtpCodeExtractor.extractOtpCode(it.title, it.body),
                    timestampMs = it.timestampMs,
                    appIconB64 = it.appIconB64,
                    actions = it.actions,
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override val phoneApps: StateFlow<List<AndroidAppUi>> =
        hub?.peerApps?.map { byPeer ->
            byPeer.values.firstOrNull().orEmpty().map {
                AndroidAppUi(packageName = it.packageName, label = it.label)
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override fun launchPhoneApp(packageName: String) {
        hub?.launchPhoneApp(packageName)
    }

    override val activeCalls: StateFlow<List<ActiveCallUi>> =
        hub?.activeCalls?.map { list ->
            list.map {
                ActiveCallUi(
                    callId = it.callId,
                    callerName = it.callerName,
                    phoneNumber = it.callerNumber,
                    state = it.state.wireName,
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override fun sendCallAction(callId: String, action: String, smsText: String?) {
        val cmd = runCatching {
            CallActionCommand(
                callId = callId,
                action = action,
                smsReplyText = smsText?.takeIf { it.isNotBlank() },
            )
        }.getOrNull() ?: return
        hub?.sendTelephonyCallAction(cmd)
    }

    override fun triggerNotificationAction(notifId: String, actionIndex: Int, label: String) {
        hub?.sendNotificationAction(notifId = notifId, actionIndex = actionIndex, actionLabel = label)
    }

    override fun openCompletedTransfer(localPath: String, revealInFolder: Boolean) {
        bridgeScope.launch(Dispatchers.IO) {
            hub?.openLocalDownloadedFile(localPath, revealInFolder = revealInFolder)
        }
    }

    override val linkTelemetry: StateFlow<LinkTelemetryUi> =
        if (hub == null) {
            MutableStateFlow(LinkTelemetryUi())
        } else {
            combine(
                hub.usbTunnelStatus,
                hub.node.connectionStates,
                hub.linkRttSamplesMs,
            ) { tunnel, states, rtts ->
                val connected = states.values.any { it == EngineConnectionState.CONNECTED }
                val label = when {
                    tunnel != null -> "USB-C Direct"
                    connected -> "Wi-Fi Encrypted"
                    else -> "Offline"
                }
                LinkTelemetryUi(
                    transportLabel = label,
                    rttSamplesMs = rtts.map { it.toInt() },
                    autoFailoverActive = tunnel != null && connected,
                )
            }.stateIn(bridgeScope, SharingStarted.Eagerly, LinkTelemetryUi())
        }

    override val setupChecks: StateFlow<List<SetupCheckItem>> =
        if (hub == null) {
            MutableStateFlow(emptyList())
        } else {
            combine(hub.linuxCapabilities, hub.pinnedPeers, appState.settings) { caps, pinned, s ->
                listOf(
                    SetupCheckItem(
                        id = "paired",
                        title = "Paired Android device",
                        subtitle = if (pinned.isNotEmpty()) "${pinned.size} verified Ed25519 device(s)" else "Scan QR code from ec0 on Android",
                        ready = pinned.isNotEmpty(),
                    ),
                    SetupCheckItem(
                        id = "v4l2",
                        title = "Virtual webcam driver (v4l2loopback)",
                        subtitle = if (caps.v4l2DeviceReady) "/dev/video10 writable for Continuity Camera" else "Load v4l2loopback kernel module for 1080p webcam",
                        ready = caps.v4l2DeviceReady,
                        actionLabel = if (caps.v4l2DeviceReady) null else "Load driver",
                    ),
                    SetupCheckItem(
                        id = "scrcpy",
                        title = "Screen Mirror & App Streaming engine",
                        subtitle = if (caps.hasScrcpy) "scrcpy ready for wireless & USB mirroring" else "Install scrcpy >= 2.2 from your distro packages",
                        ready = caps.hasScrcpy,
                    ),
                    SetupCheckItem(
                        id = "autostart",
                        title = "Background login autostart",
                        subtitle = if (s.autostartOnLogin) "Starts silently into system tray at login" else "Enable for zero-click continuity after reboot",
                        ready = s.autostartOnLogin,
                        actionLabel = if (s.autostartOnLogin) null else "Enable",
                    ),
                    SetupCheckItem(
                        id = "menus",
                        title = "File manager right-click integration",
                        subtitle = "Adds \"Send via ec0\" to Dolphin & Nautilus",
                        ready = File(System.getProperty("user.home", "."), ".local/share/kio/servicemenus/ec0-send.desktop").isFile ||
                            File(System.getProperty("user.home", "."), ".local/share/nautilus/scripts/Send via ec0").isFile,
                        actionLabel = "Install",
                    ),
                )
            }.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList())
        }

    override fun runSetupCheckAction(checkId: String) {
        when (checkId) {
            "v4l2" -> hub?.setupV4l2LoopbackWithPkexec()
            "autostart" -> setAutostartOnLogin(true)
            "menus" -> installDesktopIntegration()
        }
    }

    override fun setAutostartOnLogin(enabled: Boolean) {
        val ok = hub?.setAutostartOnLogin(enabled) ?: false
        if (ok) {
            appState.updateSettings { it.copy(autostartOnLogin = enabled) }
        }
    }

    override fun setUniversalControlEdge(edge: String) {
        val normalized = edge.trim().lowercase().takeIf { it in setOf("off", "left", "right", "top", "bottom") } ?: "off"
        hub?.configureUniversalControlEdge(normalized)
        appState.updateSettings { it.copy(universalControlEdge = normalized) }
    }

    override fun setWalkAwayAutoLock(enabled: Boolean) {
        hub?.setWalkAwayAutoLock(enabled)
        appState.updateSettings { it.copy(walkAwayAutoLock = enabled) }
    }

    override val proximitySummary: StateFlow<String> =
        hub?.proximityStatus?.map { st ->
            when {
                !st.enabled -> "Off"
                st.smoothedRssiDbm != null -> "${st.zone.name.lowercase()} · ${st.smoothedRssiDbm} dBm"
                else -> "scanning BLE…"
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, "Off") ?: MutableStateFlow("Off")

    override val hotspotSsid: StateFlow<String?> =
        hub?.hotspotStatus?.map { st ->
            st.ssid?.takeIf { st.enabled && it.isNotBlank() }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)

    override fun joinPhoneHotspot() {
        val h = hub ?: return
        if (h.hotspotStatus.value.enabled && !h.hotspotStatus.value.ssid.isNullOrBlank()) {
            h.joinPhoneHotspotViaNmcli()
        } else {
            h.toggleInstantHotspot(true)
        }
    }

    private val _isDragOverWindow = MutableStateFlow(false)
    override val isDragOverWindow: StateFlow<Boolean> = _isDragOverWindow.asStateFlow()

    fun setDragOverWindow(active: Boolean) {
        _isDragOverWindow.value = active
    }

    fun handleDroppedFiles(files: List<File>) {
        val safeFiles = files
            .filter { it.exists() && !it.isHidden }
            .take(MAX_DROPPED_FILES)
        if (safeFiles.isEmpty()) return
        hub?.sendFilesToConnectedPeers(safeFiles)
    }

    companion object {
        const val MAX_DROPPED_FILES: Int = 64
    }

    override val mediaSession: StateFlow<MediaSessionUi?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            combine(hub.remoteMediaSession, hub.localMediaSession) { remote, local ->
                val active = remote ?: local
                active?.let {
                    MediaSessionUi(
                        playerName = it.playerName,
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                        playing = it.state == MediaPlaybackState.PLAYING,
                        volumePct = it.volumePct,
                    )
                }
            }.stateIn(bridgeScope, SharingStarted.Eagerly, null)
        }

    override val findMyRinging: StateFlow<Boolean> =
        hub?.findMyRinging ?: MutableStateFlow(false)

    override val usbTunnelActive: StateFlow<Boolean> =
        hub?.usbTunnelStatus?.map { it != null }
            ?.stateIn(bridgeScope, SharingStarted.Eagerly, false) ?: MutableStateFlow(false)

    override val remoteHandoffUrl: StateFlow<String?> =
        hub?.remoteHandoffActivity?.map { it?.url }
            ?.stateIn(bridgeScope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)

    private fun mirrorHost(): String {
        val h = hub ?: return "127.0.0.1"
        val pinned = h.pinnedPeers.value.map { it.deviceId }
        val peer = h.node.mdnsDiscovery.peers.value.firstOrNull { it.deviceId in pinned }
        return peer?.addresses?.firstOrNull() ?: "127.0.0.1"
    }

    override fun startScreenMirror() {
        val s = appState.settings.value
        hub?.startScreenMirror(
            host = mirrorHost(),
            maxFps = s.mirrorMaxFps,
            bitrateKbps = s.mirrorBitrateMbps * 1_000,
            audioEnabled = s.mirrorAudio,
            turnScreenOff = s.mirrorTurnScreenOff,
        )
    }

    override fun stopScreenMirror() {
        hub?.stopScreenMirror()
    }

    override fun startCamera() {
        val s = appState.settings.value
        hub?.startContinuityCamera(
            host = mirrorHost(),
            resolution = s.cameraResolution,
            fps = s.cameraFps,
            codec = s.cameraCodec,
            frontCamera = _cameraFront.value,
        )
    }

    override fun stopCamera() {
        hub?.stopContinuityCamera()
    }

    override fun switchCameraLens() {
        val next = !_cameraFront.value
        _cameraFront.value = next
        hub?.sendCameraControl(CamDroidControlCommand.SwitchCamera)
    }

    override fun setCameraZoom(zoomX100: Int) {
        val clamped = zoomX100.coerceIn(100, 1000)
        _cameraZoom.value = clamped
        hub?.sendCameraControl(CamDroidControlCommand.SetZoom(clamped.toLong()))
    }

    override fun setCameraFlash(enabled: Boolean) {
        _cameraFlash.value = enabled
        hub?.sendCameraControl(CamDroidControlCommand.SetFlash(enabled))
    }

    override fun toggleVirtualMic() {
        hub?.toggleVirtualMic()
    }

    override fun setAutoSyncClipboard(enabled: Boolean) {
        hub?.setAutoSyncClipboard(enabled)
    }

    override fun pushClipboardNow() {
        hub?.syncLocalClipboardNow()
    }

    override fun sendNotificationReply(id: String, text: String) {
        hub?.sendNotificationReply(id, text = text)
    }

    override fun dismissNotification(id: String) {
        hub?.dismissNotification(id)
    }

    override fun copyOtpCode(code: String) {
        hub?.copyExtractedOtpCode(code)
    }

    override fun sendMediaControl(action: String, volumePct: Int?) {
        hub?.sendMediaControl(action, volumePct)
    }

    override fun triggerFindMyPhone(start: Boolean) {
        hub?.triggerFindMyPhone(start)
    }

    override fun toggleUsbTunnel() {
        val h = hub ?: return
        if (h.usbTunnelStatus.value != null) {
            h.tearDownUsbTunnel()
        } else {
            h.probeAndConnectUsbTunnel()
        }
    }

    override fun openRemoteHandoff() {
        hub?.openRemoteHandoffOnLinux()
    }

    override fun sendHandoffUrl(url: String) {
        hub?.sendUrlHandoffToPhone(url)
    }

    override fun installDesktopIntegration() {
        hub?.installLinuxAirDropIntegration()
    }

    private val _v4l2Preflight = MutableStateFlow<String?>(null)
    override val v4l2PreflightText: StateFlow<String?> = _v4l2Preflight.asStateFlow()

    private val localTileSupport: Set<EngineCapability> = setOf(
        EngineCapability.MIRROR,
        EngineCapability.CAMERA,
        EngineCapability.FILE,
        EngineCapability.CLIPBOARD,
        EngineCapability.NOTIFICATION,
        EngineCapability.INPUT,
        EngineCapability.MEDIA_CONTROL,
        EngineCapability.FIND_MY,
    )

    override val tiles: StateFlow<List<CapabilityTile>> =
        if (hub == null) {
            MutableStateFlow(emptyList())
        } else {
            hub.peerPresence.map { presence ->
                computeTileRegistry(
                    peerCapabilities = presence.mapValues { (_, beacon) ->
                        beacon.capabilities.map { it.wireName }.toSet()
                    },
                    localSupport = localTileSupport.map { it.wireName }.toSet(),
                    onlineByDevice = presence.keys.associateWith { true },
                )
            }.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList())
        }

    private fun mirrorHostFor(deviceId: String): String {
        val h = hub ?: return "127.0.0.1"
        val exact = h.node.mdnsDiscovery.peers.value
            .firstOrNull { it.deviceId == deviceId }?.addresses?.firstOrNull()
        return exact ?: mirrorHost()
    }

    override fun startMirrorFor(deviceId: String) {
        val s = appState.settings.value
        hub?.startScreenMirror(
            host = mirrorHostFor(deviceId),
            maxFps = s.mirrorMaxFps,
            bitrateKbps = s.mirrorBitrateMbps * 1_000,
            audioEnabled = s.mirrorAudio,
            turnScreenOff = s.mirrorTurnScreenOff,
        )
    }

    override fun startCameraFor(deviceId: String) {
        val s = appState.settings.value
        hub?.startContinuityCamera(
            host = mirrorHostFor(deviceId),
            resolution = s.cameraResolution,
            fps = s.cameraFps,
            codec = s.cameraCodec,
            frontCamera = _cameraFront.value,
        )
    }

    private val vfsDesktopService = ec0.core.engine.services.VfsDesktopService(
        destinationDir = java.nio.file.Path.of(
            appState.settings.value.downloadDir.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/Downloads"),
        ),
    )

    init {
        val hubNode = hub?.node
        if (hubNode != null) {
            hubNode.controlEnvelopeInterceptor = { _, envelope ->
                vfsDesktopService.handleControlEnvelope(envelope)
            }
        }
    }

    override val vfsBrowseState: StateFlow<VfsBrowseState?> = combine(
        vfsDesktopService.browseState,
        vfsDesktopService.error,
        vfsDesktopService.lastDownloadedPath,
        vfsDesktopService.lastUploadedPath,
    ) { reply, err, savedPath, uploadedPath ->
        if (reply == null && err == null && savedPath == null && uploadedPath == null) {
            null
        } else {
            VfsBrowseState(
                path = reply?.path,
                entries = reply?.entries?.map { dto ->
                    VfsEntry(
                        name = dto.name,
                        isDir = dto.isDir,
                        size = dto.size,
                        thumbB64 = dto.thumbB64,
                        mimeHint = dto.mimeHint,
                    )
                }.orEmpty(),
                error = err,
                lastDownloadedPath = savedPath,
                lastUploadedPath = uploadedPath,
            )
        }
    }.stateIn(bridgeScope, SharingStarted.Eagerly, null)

    override fun vfsDownload(path: String) {
        val h = hub ?: return
        val deviceId = h.peerPresence.value.keys.firstOrNull { nodeId ->
            h.node.activeSessionFor(nodeId) != null
        } ?: h.pinnedPeers.value.firstOrNull { p ->
            h.node.activeSessionFor(p.deviceId) != null
        }?.deviceId ?: return
        val session = h.node.activeSessionFor(deviceId) ?: return
        vfsDesktopService.download(
            sender = { cmdType, body ->
                session.sendCmd(
                    cmdType = cmdType,
                    cmdBody = body,
                    category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS,
                )
            },
            path = path,
        )
    }

    override fun vfsUpload(remoteDirPath: String, fileRef: Any) {
        val files = extractFiles(fileRef).filter { it.isFile }
        if (files.isEmpty()) return
        val h = hub ?: return
        val deviceId = h.peerPresence.value.keys.firstOrNull { nodeId ->
            h.node.activeSessionFor(nodeId) != null
        } ?: h.pinnedPeers.value.firstOrNull { p ->
            h.node.activeSessionFor(p.deviceId) != null
        }?.deviceId ?: return
        val session = h.node.activeSessionFor(deviceId) ?: return
        bridgeScope.launch(Dispatchers.IO) {
            for (file in files) {
                vfsDesktopService.upload(
                    sender = { cmdType, body ->
                        session.sendCmd(
                            cmdType = cmdType,
                            cmdBody = body,
                            category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS,
                        )
                    },
                    remoteDirPath = remoteDirPath,
                    localFile = file,
                )
            }
            // Refresh listing after upload
            vfsBrowse(remoteDirPath)
        }
    }

    override fun vfsBrowse(path: String) {
        val h = hub ?: return
        val deviceId = h.peerPresence.value.keys.firstOrNull { nodeId ->
            h.node.activeSessionFor(nodeId) != null
        } ?: h.pinnedPeers.value.firstOrNull { p ->
            h.node.activeSessionFor(p.deviceId) != null
        }?.deviceId ?: return
        val session = h.node.activeSessionFor(deviceId) ?: return
        vfsDesktopService.browse(
            sender = { cmdType, body ->
                session.sendCmd(
                    cmdType = cmdType,
                    cmdBody = body,
                    category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS,
                )
            },
            path = path,
        )
    }

    override fun setupV4l2Loopback() {
        val coordinator = hub?.node?.cameraCoordinator ?: run {
            _v4l2Preflight.value = "Engine offline — start the hub first"
            return
        }
        bridgeScope.launch(Dispatchers.IO) {
            _v4l2Preflight.value = "Checking virtual camera driver…"
            val result = coordinator.v4l2Preflight()
            _v4l2Preflight.value = when (result.outcome) {
                ec0.core.engine.services.V4l2Outcome.READY ->
                    "Virtual camera ready (/dev/video10)"
                ec0.core.engine.services.V4l2Outcome.NEEDS_PASSWORD ->
                    "Run: ${result.remediationCommand}"
                ec0.core.engine.services.V4l2Outcome.MODPROBE_FAILED ->
                    "Driver load failed. Run: ${result.remediationCommand}"
                ec0.core.engine.services.V4l2Outcome.SCRCPY_UNSUPPORTED ->
                    "scrcpy ${result.scrcpyVersionText ?: "not found"} — need >= 2.2"
            }
        }
    }

    init {
        bridgeScope.launch {
            while (isActive) {
                delay(120_000L)
                runCatching {
                    hub?.node?.smartConnectCoordinator?.rotateNonceIdAndInviteToken()
                    _smartConnectPairingQr.value = buildSmartConnectPairingQr()
                }
            }
        }
    }

    private fun buildSmartConnectPairingQr(): SmartConnectPairingQr? {
        val coordinator = hub?.node?.smartConnectCoordinator ?: return null
        return runCatching {
            val nowMs = System.currentTimeMillis()
            val payload = coordinator.buildPairingQrPayload(nowMs)
            val matrix = coordinator.buildPairingQrMatrix(nowMs)
            SmartConnectPairingQr(
                payload = payload,
                size = matrix.size,
                modules = matrix.toRowMajorList(),
                wsPort = smartConnectWsPort ?: coordinator.boundPort,
                deviceName = coordinator.deviceName,
            )
        }.getOrNull()
    }

    override val capabilities: Capabilities
        get() = Capabilities(
            canReceive = true,
            clipboardSync = clipboardSyncEnabled.value,
            notifications = notificationRelayEnabled.value,
            photoSync = false,
            remoteInput = true,
        )

    override val clipboardSyncEnabled: StateFlow<Boolean>
        get() = hub?.autoSyncClipboard ?: MutableStateFlow(true)

    override fun setClipboardSyncEnabled(enabled: Boolean) {
        hub?.setAutoSyncClipboard(enabled)
    }

    override val notificationRelayEnabled: StateFlow<Boolean>
        get() = hub?.forwardNotificationsToDesktop ?: MutableStateFlow(true)

    override fun setNotificationRelayEnabled(enabled: Boolean) {
        hub?.setForwardNotificationsToDesktop(enabled)
    }

    override fun startScan() = appState.startScan()

    override fun cancelTransfer(id: String) {
        if (id.startsWith("ec0:")) {
            hub?.cancelFileTransfer(id.removePrefix("ec0:"))
        } else {
            appState.cancelTransfer(id)
        }
    }

    override fun clearFinishedTransfers() {
        appState.clearFinished()
        hub?.clearFinishedTransfers()
    }

    override fun updateSettings(transform: (SettingsState) -> SettingsState) =
        appState.updateSettings(transform)

    override val incomingRequest: StateFlow<ec0.ui.models.IncomingRequest?> = appState.incomingRequest

    override fun acceptIncoming(trust: Boolean) = appState.acceptIncoming(trust)

    override fun declineIncoming() = appState.declineIncoming()

    /**
     * Encrypted-first file routing: if [device] corresponds to a live paired ec0-engine
     * session (matching deviceId, peerRemoteIp, or alias), route through ChaCha20-Poly1305
     * resumable `FileTransferService`; otherwise fall back to direct LAN transfer.
     */
    override fun sendFile(fileRef: Any, device: UiDevice) {
        val files = extractFiles(fileRef)
        if (files.isEmpty()) return

        val pairedId = resolveLivePairedDeviceId(device)
        if (pairedId != null && hub != null) {
            hub.sendFilesToConnectedPeers(files, targetDeviceId = pairedId)
            return
        }
        for (file in files) {
            appState.sendFile(file, device)
        }
    }

    override fun sendFileToPairedPeer(fileRef: Any, deviceId: String) {
        val files = extractFiles(fileRef)
        if (files.isEmpty()) return
        hub?.sendFilesToConnectedPeers(files, targetDeviceId = deviceId)
    }

    private fun extractFiles(fileRef: Any): List<File> = when (fileRef) {
        is File -> listOf(fileRef)
        is List<*> -> fileRef.filterIsInstance<File>()
        else -> emptyList()
    }

    private fun resolveLivePairedDeviceId(device: UiDevice): String? {
        val h = hub ?: return null
        val pinned = h.pinnedPeers.value
        val byId = pinned.firstOrNull {
            it.deviceId.equals(device.id, ignoreCase = true) && h.node.activeSessionFor(it.deviceId) != null
        }
        if (byId != null) return byId.deviceId

        val byIp = device.hostAddress.takeIf { it.isNotBlank() }?.let { host ->
            pinned.firstOrNull { peer ->
                val session = h.node.activeSessionFor(peer.deviceId)
                session != null && session.peerRemoteIp == host
            }
        }
        if (byIp != null) return byIp.deviceId

        val byAlias = pinned.firstOrNull {
            it.alias.equals(device.alias, ignoreCase = true) && h.node.activeSessionFor(it.deviceId) != null
        }
        return byAlias?.deviceId
    }

    // ---- Ecosystem Pro Features (#1..#15) Desktop Bridge Implementation ----

    override val audioSinkActive: StateFlow<Boolean> =
        hub?.audioRouteStatus?.map { it.active && it.mode == "media_sink" }
            ?.stateIn(bridgeScope, SharingStarted.Eagerly, false) ?: MutableStateFlow(false)

    override val callAudioOnPc: StateFlow<Boolean> =
        hub?.audioRouteStatus?.map { it.active && it.mode == "call_duplex" }
            ?.stateIn(bridgeScope, SharingStarted.Eagerly, false) ?: MutableStateFlow(false)

    override fun togglePhoneAudioSink() {
        hub?.togglePhoneAudioSink()
    }

    override fun toggleCallAudioHandoff() {
        hub?.toggleCallAudioHandoff()
    }

    override fun requestPhoneCapture(mode: String) {
        hub?.requestPhoneCapture(mode)
    }

    override val webDavMountUrl: StateFlow<String?> =
        hub?.webDavMountUrl ?: MutableStateFlow(null)

    override fun toggleWebDavPhoneMount() {
        val h = hub ?: return
        if (h.webDavMountUrl.value != null) {
            h.stopWebDavPhoneMount()
        } else {
            val ds = object : ec0.core.engine.services.VfsMountDataSource {
                override fun listDirectory(relativePath: String): List<ec0.core.engine.protocol.VfsEntryDto>? {
                    val norm = if (relativePath.isBlank()) "/" else "/$relativePath"
                    vfsBrowse(norm)
                    val st = vfsDesktopService.browseState.value ?: return emptyList()
                    return st.entries
                }

                override fun readFileBytes(relativePath: String): ByteArray? {
                    val norm = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
                    vfsDownload(norm)
                    val savedPath = vfsDesktopService.lastDownloadedPath.value ?: return null
                    val f = File(savedPath)
                    return if (f.isFile && f.length() <= 8L * 1024L * 1024L) runCatching { f.readBytes() }.getOrNull() else null
                }
            }
            h.startWebDavPhoneMount(ds)
        }
    }

    override val sidecarActive: StateFlow<Boolean> =
        hub?.sidecarActive ?: MutableStateFlow(false)

    override fun toggleSidecarDisplay() {
        hub?.toggleSidecarDisplay()
    }

    override val smsThreads: StateFlow<List<SmsThreadUi>> =
        hub?.smsThreads?.map { list ->
            list.map { t ->
                SmsThreadUi(
                    threadId = t.threadId,
                    address = t.address,
                    displayName = t.contactName.ifBlank { t.address },
                    lastBody = t.latestBody,
                    lastTimestampMs = t.latestTimestampMs,
                    messageCount = t.messages.size,
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override val pendingOtpCode: StateFlow<String?> =
        hub?.pendingOtpCode ?: MutableStateFlow(null)

    override fun requestSmsSync() {
        hub?.requestSmsSync()
    }

    override fun sendSmsMessage(address: String, body: String) {
        hub?.sendSmsMessage(address, body)
    }

    override fun autoTypeLatestOtp() {
        hub?.autoTypeLatestOtp()
    }

    override val focusMode: StateFlow<String> =
        hub?.focusState?.map { it.mode }?.stateIn(bridgeScope, SharingStarted.Eagerly, "off")
            ?: MutableStateFlow("off")

    override val meetingAutoFocusEnabled: StateFlow<Boolean> =
        hub?.meetingAutoFocusEnabled ?: MutableStateFlow(true)

    override fun setFocusMode(mode: String) {
        hub?.setFocusMode(mode)
    }

    override fun setMeetingAutoFocusEnabled(enabled: Boolean) {
        hub?.setMeetingAutoFocusEnabled(enabled)
    }

    override val activeBrowserTabHandoff: StateFlow<BrowserTabHandoffUi?> =
        hub?.activeRemoteBrowserTab?.map { tab ->
            tab?.let {
                BrowserTabHandoffUi(
                    url = it.url,
                    title = it.title,
                    resumeUrl = ec0.core.engine.services.BrowserTabHandoffService.buildResumeUrl(it),
                    mediaPositionSec = it.mediaPositionSec.toLong(),
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)

    override fun openActiveBrowserTabHandoff() {
        hub?.openActiveRemoteBrowserTab()
    }

    override val wifiDirectTurboActive: StateFlow<Boolean> =
        hub?.wifiDirectTurboActive ?: MutableStateFlow(false)

    override fun toggleWifiDirectTurbo() {
        hub?.toggleWifiDirectTurbo()
    }

    override val cameraEffects: StateFlow<CameraEffectsUi> =
        hub?.cameraStudioEffects?.map { fx ->
            CameraEffectsUi(
                centerStage = fx.centerStage,
                portraitBlur = fx.portraitBlur,
                deskView = fx.deskView,
                hwDecode = fx.hwDecodePreference,
                targetBitrateKbps = fx.targetBitrateKbps,
            )
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, CameraEffectsUi()) ?: MutableStateFlow(CameraEffectsUi())

    override fun toggleCameraCenterStage() {
        hub?.updateCameraStudioEffects { it.copy(centerStage = !it.centerStage) }
    }

    override fun toggleCameraPortraitBlur() {
        hub?.updateCameraStudioEffects { it.copy(portraitBlur = !it.portraitBlur) }
    }

    override fun toggleCameraDeskView() {
        hub?.updateCameraStudioEffects { it.copy(deskView = !it.deskView) }
    }

    override val clipboardShelf: StateFlow<List<ClipboardShelfItemUi>> =
        hub?.clipboardShelfEntries?.map { entries ->
            entries.map { e ->
                ClipboardShelfItemUi(
                    id = e.id,
                    preview = e.previewText,
                    mime = e.mime,
                    sourceDevice = e.sourceAlias,
                    pinned = e.pinned,
                    sensitive = e.isSensitive,
                    timestampMs = e.createdAtMs,
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override val clipboardToast: StateFlow<ClipboardToastUi?> =
        hub?.clipboardToast?.map { ev ->
            ev?.let { ClipboardToastUi(id = it.id, message = it.message, canUndo = it.canUndo) }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)

    override fun toggleClipboardPin(entryId: String) {
        hub?.toggleClipboardShelfPin(entryId)
    }

    override fun copyClipboardShelfEntry(entryId: String) {
        hub?.copyClipboardShelfEntry(entryId)
    }

    override fun undoLastRemoteClipboard() {
        hub?.undoLastRemoteClipboard()
    }

    override fun dismissClipboardToast() {
        hub?.dismissClipboardToast()
    }

    override val keystoreBackendLabel: StateFlow<String> =
        hub?.keySealerBackend?.map { it.displayName }
            ?.stateIn(bridgeScope, SharingStarted.Eagerly, "Local 0600 Key File")
            ?: MutableStateFlow("Local 0600 Key File")

    override val diagnosticChecks: StateFlow<List<DiagnosticCheckUi>> =
        hub?.diagnosticChecks?.map { list ->
            list.map { c ->
                DiagnosticCheckUi(
                    id = c.id,
                    title = c.title,
                    severity = c.severity.name,
                    summary = c.summary,
                    fixHint = c.fixHint,
                )
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    override fun runHealthDiagnostics() {
        hub?.runHealthDiagnostics()
    }

    override fun exportRedactedSupportBundle() {
        hub?.exportRedactedSupportBundle()
    }
}
