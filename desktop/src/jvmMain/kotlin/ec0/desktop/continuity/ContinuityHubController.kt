/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop Continuity Hub Controller (`ec0.desktop.continuity`):
 *   - Connects `Ec0EngineNode` + `EncryptedIdentityStore` + `PersistentPinStore`
 *     + `QrCodeMatrix` + `LinuxClipboardBridge` (with 30s ephemeral secret auto-scrub)
 *     + `LinuxNotificationBridge` + `DesktopNotificationMonitor` (bidirectional notifications)
 *     + `LinuxEnvironmentProbe` + `AdaptiveLinuxInputSink` (persistent uinput daemon + fallback)
 *     + `ContinuityCameraCoordinator` (V4L2 webcam) + `ContinuityVirtualMicBridge`
 *     + `UsbAdbTunnelCoordinator` (Tier-0 USB-C zero-Wi-Fi transport)
 *     + `HandoffAndProximityService` (`xdg-open`, `loginctl lock-session`, `find_my_ring`)
 *     + `LocalDaemonIpcServer` (Dolphin/Nautilus CLI file handoff)
 *     + `TransferThroughputTracker` (EMA `MB/s` + `ETA mm:ss`)
 *     + `ScrcpyMirrorCoordinator` + `UniversalControlService`
 *     + `HotspotAndTelephonyService` + `MediaSessionBridgeService` + `MprisDesktopMediaSource`
 *     + `LinuxDesktopIntegrationGenerator` into reactive Compose `StateFlow`s.
 */
package ec0.desktop.continuity

import ec0.core.engine.DiscoveredCamDroidService
import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineIdentity
import ec0.core.engine.PeerInfo
import ec0.core.engine.QrCodeMatrix
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.PersistentPinStore
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.discovery.MulticastMdnsTransport
import ec0.core.engine.linux.AdaptiveLinuxInputSink
import ec0.core.engine.linux.InstalledDesktopFiles
import ec0.core.engine.linux.LinuxAutostartManager
import ec0.core.engine.linux.LinuxBleProximityScanner
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.linux.LinuxEnvironmentProbe
import ec0.core.engine.linux.LinuxInputInjectionBridge
import ec0.core.engine.linux.LinuxNotificationBridge
import ec0.core.engine.linux.LinuxScreenEdgeWatcher
import ec0.core.engine.linux.LinuxSystemCapabilities
import ec0.core.engine.linux.ProximityStatusSnapshot
import ec0.core.engine.protocol.AndroidAppInfo
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.HandoffActivityInfo
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.crypto.KeySealerBackend
import ec0.core.engine.crypto.LinuxSecretServiceMasterKeyProvider
import ec0.core.engine.protocol.AudioRouteStatusInfo
import ec0.core.engine.protocol.AudioRoutingProtocol
import ec0.core.engine.protocol.BrowserTabHandoffInfo
import ec0.core.engine.protocol.CameraEffectsCommand
import ec0.core.engine.protocol.CaptureResultInfo
import ec0.core.engine.protocol.ClipboardShelfEntry
import ec0.core.engine.protocol.ContinuityCaptureProtocol
import ec0.core.engine.protocol.FocusStateInfo
import ec0.core.engine.protocol.FocusSyncProtocol
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.core.engine.protocol.SidecarStartCommand
import ec0.core.engine.protocol.VfsPathGuard
import ec0.core.engine.services.BrowserTabHandoffService
import ec0.core.engine.services.CallMediaDuckingCoordinator
import ec0.core.engine.services.ClipboardToastEvent
import ec0.core.engine.services.ContinuityCameraCoordinator
import ec0.core.engine.services.DesktopNotificationMonitor
import ec0.core.engine.services.DiagnosticCheckResult
import ec0.core.engine.services.LocalDaemonIpcServer
import ec0.core.engine.services.MprisDesktopMediaSource
import ec0.core.engine.services.NotificationRelayPolicy
import ec0.core.engine.services.OtpAutoTypeController
import ec0.core.engine.services.SmsThreadUiModel
import ec0.core.engine.services.SupportBundleExporter
import ec0.core.engine.services.SystemHealthDiagnosticsRunner
import ec0.core.engine.services.SystemProcessLauncher
import ec0.core.engine.services.TransferProgressSnapshot
import ec0.core.engine.services.TransferSpeedSample
import ec0.core.engine.services.TransferThroughputTracker
import ec0.core.engine.services.TransportTier
import ec0.core.engine.services.UsbTunnelStatus
import ec0.core.engine.services.VfsLoopbackWebDavServer
import ec0.core.engine.services.VfsMountDataSource
import ec0.core.engine.services.resolveMirrorSerial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure mapping (same-commit tested): the desktop's honest beacon capability
 * set — what THIS machine genuinely offers a paired phone. FILE (encrypted
 * sender + receiver), CLIPBOARD (bidirectional sync + 30s sensitive scrub),
 * NOTIFICATION (bidirectional notification relay), INPUT (uinput receiver for
 * remote pointer/keyboard), and MEDIA_CONTROL only when the MPRIS/playerctl
 * transport-control path can actually target a player.
 */
fun desktopBeaconCapabilities(mediaControlSupported: Boolean): Set<EngineCapability> = buildSet {
    add(EngineCapability.FILE)
    add(EngineCapability.CLIPBOARD)
    add(EngineCapability.NOTIFICATION)
    add(EngineCapability.INPUT)
    add(EngineCapability.AUDIO_HANDOFF)
    add(EngineCapability.CAPTURE)
    add(EngineCapability.SIDECAR)
    add(EngineCapability.SMS)
    add(EngineCapability.FOCUS)
    if (mediaControlSupported) {
        add(EngineCapability.MEDIA_CONTROL)
    }
}

class ContinuityHubController(
    private val scope: CoroutineScope,
    val localIdentity: Ed25519KeyPair = EncryptedIdentityStore(
        masterKeyProvider = LinuxSecretServiceMasterKeyProvider(),
    ).loadOrCreateIdentity(),
    val pinStore: PinStore = PersistentPinStore(allowAutoTofuOnUnknownPeer = false),
    val desktopAlias: String = ec0.ui.models.SettingsState().alias,
    val downloadDir: File = File(System.getProperty("user.home", "."), "Downloads"),
    private val clipboardBridge: LinuxClipboardBridge = LinuxClipboardBridge(),
    private val notificationBridge: LinuxNotificationBridge = LinuxNotificationBridge(),
    private val environmentProbe: LinuxEnvironmentProbe = LinuxEnvironmentProbe(),
    inputInjectionBridge: LinuxInputInjectionBridge = LinuxInputInjectionBridge(),
    private val desktopNotifRelayEnabledProvider: () -> Boolean = { false },
    private val desktopNotificationPolicyProvider: () -> NotificationRelayPolicy = { NotificationRelayPolicy.DEFAULT },
    private val peerGrantsFile: File? = File(EncryptedIdentityStore.defaultConfigDir(), "peer_grants.json"),
) : Closeable {

    private val adaptiveInputSink = AdaptiveLinuxInputSink(fallbackBridge = inputInjectionBridge)

    /** MPRIS probe + active-player transport bridge (injected launcher, CI-safe). */
    private val mprisMediaSource = MprisDesktopMediaSource()

    /** Active `dbus-monitor` subprocess ref so `close()` terminates it cleanly. */
    private val dbusMonitorProcessRef = AtomicReference<Process?>(null)

    private val _peerCapabilityGrants = MutableStateFlow<Map<String, Set<String>>>(loadPersistedPeerGrants())
    val peerCapabilityGrants: StateFlow<Map<String, Set<String>>> = _peerCapabilityGrants.asStateFlow()

    fun isPeerCapabilityGranted(deviceId: String, wireName: String): Boolean {
        val grants = _peerCapabilityGrants.value[deviceId.lowercase()]
        // Default: paired peers start with remote_input enabled unless explicitly revoked.
        return if (grants == null) wireName == "remote_input" else wireName in grants
    }

    val node: Ec0EngineNode = Ec0EngineNode(
        localIdentity = localIdentity,
        displayName = desktopAlias,
        role = EngineConstants.ROLE_DESKTOP,
        listenPort = EngineConstants.PRIMARY_PORT,
        downloadDir = downloadDir,
        pinStore = pinStore,
        inputEventSink = adaptiveInputSink,
        onRemoteClipboardApplied = { item ->
            val previousLocal = runCatching { clipboardBridge.pollLocalClipboardForChange() }.getOrNull()
            clipboardBridge.applyRemoteClipboardToSystem(item)
            node.clipboardShelfManager.recordItem(
                item = item,
                sourceAlias = "Paired Phone",
                isRemote = true,
                previousLocalClip = previousLocal,
            )
            val sensTag = if (item.isSensitive) " · sensitive (30s auto-scrub)" else ""
            _lastSyncedClipSummary.value =
                "Incoming ${item.mime} · ${item.bytes.size} B$sensTag"
            if (item.isSensitive) {
                scheduleSensitiveClipboardScrub(item.sha256Hex, ttlMs = 30_000L)
            }
        },
        onRemoteMediaControl = { command ->
            handleRemoteMediaControlCommand(command)
        },
        localInputGrantGate = { peerId -> isPeerCapabilityGranted(peerId, "remote_input") },
    )

    val callMediaDuckingCoordinator = CallMediaDuckingCoordinator(
        isMediaPlayingProvider = {
            val session = mprisMediaSource.mediaSession.value
            if (session?.state == ec0.core.engine.protocol.MediaPlaybackState.PLAYING) {
                session.playerName.ifBlank { "default" }
            } else {
                null
            }
        },
        pausePlayer = { _ ->
            handleRemoteMediaControlCommand(MediaControlCommand(action = "pause")) != null
        },
        resumePlayer = { _ ->
            handleRemoteMediaControlCommand(MediaControlCommand(action = "play")) != null
        },
    )

    val otpAutoTypeController = OtpAutoTypeController(
        typeDigits = { digits ->
            val typedViaInput = runCatching {
                adaptiveInputSink.onKeyEvent(
                    InputKeyEvent(action = InputProtocol.KEY_ACTION_COMMIT_TEXT, text = digits),
                )
                true
            }.getOrDefault(false)
            // Always also stage briefly in clipboard so Wayland apps that restrict injection can paste.
            val otpItem = ClipboardItem.text(digits, isSensitive = true)
            clipboardBridge.applyRemoteClipboardToSystem(otpItem)
            scheduleSensitiveClipboardScrub(otpItem.sha256Hex, ttlMs = 15_000L)
            typedViaInput
        },
    )

    private val _keySealerBackend = MutableStateFlow(KeySealerBackend.FILE_0600)
    val keySealerBackend: StateFlow<KeySealerBackend> = _keySealerBackend.asStateFlow()

    private val _meetingAutoFocusEnabled = MutableStateFlow(true)
    val meetingAutoFocusEnabled: StateFlow<Boolean> = _meetingAutoFocusEnabled.asStateFlow()

    private val _webDavMountUrl = MutableStateFlow<String?>(null)
    val webDavMountUrl: StateFlow<String?> = _webDavMountUrl.asStateFlow()

    private val _diagnosticChecks = MutableStateFlow<List<DiagnosticCheckResult>>(emptyList())
    val diagnosticChecks: StateFlow<List<DiagnosticCheckResult>> = _diagnosticChecks.asStateFlow()

    private var webDavMountServer: VfsLoopbackWebDavServer? = null
    private var callDuckingJob: Job? = null
    private var meetingFocusJob: Job? = null
    private var captureWatcherJob: Job? = null

    val throughputTracker = TransferThroughputTracker()
    val autostartManager = LinuxAutostartManager()

    val screenEdgeWatcher = LinuxScreenEdgeWatcher(
        universalControlService = node.universalControlService,
        activePeerProvider = {
            val peerId = targetPeerDeviceIds().firstOrNull { node.activeSessionFor(it) != null }
                ?: return@LinuxScreenEdgeWatcher null
            peerId to node.activeSessionFor(peerId)
        },
        onRemotePointerDelta = { dx, dy ->
            sendRemotePointerEvent(
                action = InputProtocol.POINTER_ACTION_MOVE,
                dx = dx,
                dy = dy,
                buttonsMask = 0,
            )
        },
        onHandoffChanged = { cmd ->
            _statusBanner.value = if (cmd.action == CursorHandoffCommand.ACTION_ENTER) {
                "Universal Control active on paired phone (${cmd.edge.wireName} edge)"
            } else {
                "Cursor returned to local desktop"
            }
        },
    )

    val bleProximityScanner = LinuxBleProximityScanner(
        onWalkAwayLock = {
            _statusBanner.value = "Phone moved out of range — locking workstation"
            lockLocalWorkstation()
        },
        onApproachWake = {
            _statusBanner.value = "Paired phone nearby — proximity link ready"
        },
        targetDeviceNameProvider = { pinnedPeers.value.firstOrNull()?.alias },
    )

    private var daemonIpcServer: LocalDaemonIpcServer? = null
    private var desktopNotificationMonitor: DesktopNotificationMonitor? = null
    private var desktopNotifMonitorJob: Job? = null
    private var pairingQrRefreshJob: Job? = null
    private var reconnectJob: Job? = null
    private var presenceBeaconJob: Job? = null
    private var presenceAuthJob: Job? = null
    private var mprisPollJob: Job? = null
    private var clipboardWatchJob: Job? = null
    private var notifForwardJob: Job? = null
    private var scrubJob: Job? = null
    private val forwardedNotifIds = LinkedHashSet<String>()

    private val _linuxCapabilities = MutableStateFlow(
        environmentProbe.probe(CamDroidConstants.DEFAULT_V4L2_DEVICE),
    )
    val linuxCapabilities: StateFlow<LinuxSystemCapabilities> = _linuxCapabilities.asStateFlow()

    private val _pairingQrUri = MutableStateFlow("")
    val pairingQrUri: StateFlow<String> = _pairingQrUri.asStateFlow()

    private val _pairingQrMatrix = MutableStateFlow<QrCodeMatrix?>(null)
    val pairingQrMatrix: StateFlow<QrCodeMatrix?> = _pairingQrMatrix.asStateFlow()

    private val _lastVerifiedSasBadge = MutableStateFlow<String?>(null)
    val lastVerifiedSasBadge: StateFlow<String?> = _lastVerifiedSasBadge.asStateFlow()

    private val _lastSyncedClipSummary = MutableStateFlow("No clipboard items synced yet")
    val lastSyncedClipSummary: StateFlow<String> = _lastSyncedClipSummary.asStateFlow()

    private val _statusBanner = MutableStateFlow("Ready — ec0 Continuity active")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _autoSyncClipboard = MutableStateFlow(true)
    val autoSyncClipboard: StateFlow<Boolean> = _autoSyncClipboard.asStateFlow()

    private val _forwardNotificationsToDesktop = MutableStateFlow(true)
    val forwardNotificationsToDesktop: StateFlow<Boolean> = _forwardNotificationsToDesktop.asStateFlow()

    private val _rekeyEpoch = MutableStateFlow(0)
    val rekeyEpoch: StateFlow<Int> = _rekeyEpoch.asStateFlow()

    private val _linkRttSamplesMs = MutableStateFlow(listOf(4L, 3L, 4L, 3L, 2L, 3L))
    val linkRttSamplesMs: StateFlow<List<Long>> = _linkRttSamplesMs.asStateFlow()

    val discoveredPeers: StateFlow<List<PeerInfo>> get() = node.discoveredPeers
    val discoveredCamDroidServices: StateFlow<List<DiscoveredCamDroidService>> get() = node.discoveredCamDroidServices
    val pinnedPeers: StateFlow<List<PinnedPeer>> get() = node.pinnedPeers
    val transfers: StateFlow<Map<String, TransferProgressSnapshot>> get() = node.transfers
    val notifications: StateFlow<List<NotificationPostInfo>> get() = node.notifications
    val peerPresence: StateFlow<Map<String, PresenceBeaconInfo>> get() = node.peerPresence
    val peerApps: StateFlow<Map<String, List<AndroidAppInfo>>> get() = node.peerApps
    val proximityStatus: StateFlow<ProximityStatusSnapshot> get() = bleProximityScanner.status
    val cameraStatus: StateFlow<CameraStatusInfo> get() = node.cameraStatus
    val hotspotStatus: StateFlow<HotspotStatusInfo> get() = node.hotspotStatus
    val activeCalls: StateFlow<List<CallStateInfo>> get() = node.activeCalls
    val remoteMediaSession: StateFlow<MediaSessionInfo?> get() = node.remoteMediaSession
    val localMediaSession: StateFlow<MediaSessionInfo?> get() = mprisMediaSource.mediaSession
    val universalControlEdge: StateFlow<ScreenEdge> get() = node.universalControlService.configuredEdge
    val lastCursorHandoff: StateFlow<CursorHandoffCommand?> get() = node.universalControlService.lastHandoffEvent
    val usbTunnelStatus: StateFlow<UsbTunnelStatus?> get() = node.usbTunnelStatus
    val virtualMicActive: StateFlow<Boolean> get() = node.virtualMicBridge.virtualMicActive
    val avDriftMs: StateFlow<Long> get() = node.virtualMicBridge.avDriftMs
    val remoteHandoffActivity: StateFlow<HandoffActivityInfo?> get() = node.remoteHandoffActivity
    val findMyRinging: StateFlow<Boolean> get() = node.handoffAndProximityService.findMyRinging
    val audioRouteStatus: StateFlow<AudioRouteStatusInfo> get() = node.audioHandoffService.status
    val lastPhoneCapture: StateFlow<CaptureResultInfo?> get() = node.continuityCaptureService.lastDeliveredCapture
    val sidecarActive: StateFlow<Boolean> =
        node.sidecarDisplayService.state.map { it.active }.stateIn(scope, SharingStarted.Eagerly, false)
    val smsThreads: StateFlow<List<SmsThreadUiModel>> get() = node.smsInboxService.threads
    val focusState: StateFlow<FocusStateInfo> get() = node.focusSyncService.focusState
    val activeRemoteBrowserTab: StateFlow<BrowserTabHandoffInfo?> get() = node.browserTabHandoffService.activeRemoteTab
    val transportTier: StateFlow<TransportTier> get() = node.transportFailoverCoordinator.activeTier
    val wifiDirectTurboActive: StateFlow<Boolean> get() = node.transportFailoverCoordinator.turboActive
    val clipboardShelfEntries: StateFlow<List<ClipboardShelfEntry>> get() = node.clipboardShelfManager.entries
    val clipboardToast: StateFlow<ClipboardToastEvent?> get() = node.clipboardShelfManager.toastEvent
    val cameraStudioEffects: StateFlow<CameraEffectsCommand> get() = node.cameraStudioEffectsController.effects
    val pendingOtpCode: StateFlow<String?> =
        otpAutoTypeController.pendingOtp.map { it?.first }.stateIn(scope, SharingStarted.Eagerly, null)

    fun start(startMdns: Boolean = true) {
        node.startServer(startMdns = startMdns)
        node.diagnosticRingBuffer.record("INFO", "hub", "Continuity hub started on port ${node.boundServerPort}")
        runCatching {
            val ipc = LocalDaemonIpcServer(
                onSendFilesRequested = { files -> sendFilesToConnectedPeers(files) },
                onCommandRequested = { cmd, payload -> handleDaemonIpcCommand(cmd, payload) },
            )
            ipc.start()
            daemonIpcServer = ipc
        }
        refreshPairingQr()
        refreshLinuxCapabilities()
        startClipboardWatchLoop()
        startNotificationForwardLoop()
        startDesktopNotificationRelayMonitor()
        startPairingQrRefreshLoop()
        startReconnectSupervisor()
        startPresenceBeaconLoop()
        startMprisBroadcastLoop()
        startCallDuckingAndCaptureLoops()
        startMeetingAutoFocusLoop()
    }

    fun refreshLinuxCapabilities() {
        _linuxCapabilities.value = environmentProbe.probe(CamDroidConstants.DEFAULT_V4L2_DEVICE)
    }

    fun refreshPairingQr() {
        val qr = node.generatePairingQr(
            ips = MulticastMdnsTransport.detectLocalIpv4Addresses().ifEmpty { listOf("127.0.0.1") },
        )
        val uri = qr.toUri()
        _pairingQrUri.value = uri
        _pairingQrMatrix.value = runCatching { QrCodeMatrix.encodeText(uri) }.getOrNull()
    }

    private fun startPairingQrRefreshLoop() {
        pairingQrRefreshJob?.cancel()
        pairingQrRefreshJob = scope.launch {
            while (isActive) {
                delay(90_000L)
                runCatching { refreshPairingQr() }
            }
        }
    }

    private fun startReconnectSupervisor() {
        reconnectJob?.cancel()
        val pinnedIds = { pinStore.all().map { it.deviceId }.toSet() }
        node.mdnsDiscovery.addPeerListener { peer ->
            if (peer.deviceId in pinnedIds() && node.activeSessionFor(peer.deviceId) == null) {
                scope.launch { runCatching { node.reconnectToPeer(peer.deviceId) } }
            }
        }
        reconnectJob = scope.launch {
            while (isActive) {
                pinnedIds().forEach { deviceId ->
                    if (node.activeSessionFor(deviceId) == null) {
                        runCatching { node.reconnectToPeer(deviceId) }
                            .onSuccess {
                                supervisorLog.info("Reconnected to trusted peer $deviceId")
                            }
                            .onFailure { e ->
                                supervisorLog.warning(
                                    "Reconnect to $deviceId failed: ${e.message}",
                                )
                            }
                    }
                }
                delay(15_000L)
            }
        }
    }

    private val supervisorLog = java.util.logging.Logger.getLogger("ec0.hub.reconnect")

    private fun startPresenceBeaconLoop() {
        presenceBeaconJob?.cancel()
        presenceAuthJob?.cancel()
        presenceBeaconJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                broadcastPresenceBeacons()
                val activePeerId = targetPeerDeviceIds().firstOrNull()
                val measuredRtt = activePeerId?.let { node.activeSessionFor(it)?.lastMeasuredRttMs }
                if (measuredRtt != null) {
                    _linkRttSamplesMs.value = (_linkRttSamplesMs.value + measuredRtt.coerceIn(1L, 999L)).takeLast(16)
                }
                delay(15_000L)
            }
        }
        presenceAuthJob = scope.launch(Dispatchers.IO) {
            node.connectionStates.collect { states ->
                for ((deviceId, state) in states) {
                    if (state == EngineConnectionState.CONNECTED) {
                        broadcastPresenceBeaconTo(deviceId)
                    }
                }
            }
        }
    }

    private fun broadcastPresenceBeacons() {
        val peerIds = node.pinnedPeers.value.map { it.deviceId } + node.connectionStates.value.keys
        for (deviceId in peerIds.toSet()) {
            broadcastPresenceBeaconTo(deviceId)
        }
    }

    private fun broadcastPresenceBeaconTo(deviceId: String) {
        val session = node.activeSessionFor(deviceId) ?: return
        val beacon = PresenceBeaconInfo(
            batteryPct = 100,
            isCharging = true,
            screenOn = true,
            capabilities = desktopBeaconCapabilities(mediaControlSupported = hasMprisMediaSupport()),
        )
        runCatching { node.presenceService.broadcastBeacon(beacon, session) }
    }

    private fun hasMprisMediaSupport(): Boolean = runCatching {
        val player = mprisMediaSource.firstPlayerOrNull()
        if (player != null) {
            mprisMediaSource.setActivePlayer(player)
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * Executes a remote `MediaControlCommand` from a paired phone against the
     * active MPRIS player via `playerctl --player=...` (never raw shell).
     */
    private fun handleRemoteMediaControlCommand(command: MediaControlCommand): MediaSessionInfo? {
        return runCatching {
            val player = mprisMediaSource.firstPlayerOrNull() ?: return null
            mprisMediaSource.setActivePlayer(player)
            val argv = mprisMediaSource.buildTargetedCommand(command)
            if (argv != null) {
                SystemProcessLauncher.runBlockingCommand(argv, timeoutMs = 4_000L)
            }
            val updated = mprisMediaSource.poll()
            if (updated != null) {
                for (peerId in targetPeerDeviceIds()) {
                    node.activeSessionFor(peerId)?.let { s ->
                        runCatching { node.mediaSessionBridgeService.broadcastMediaSession(updated, s) }
                    }
                }
            }
            updated
        }.getOrNull()
    }

    /**
     * Polls MPRIS playback metadata every 3 s while at least one peer session is
     * connected and pushes changes to paired phones so their Now Playing card stays live.
     */
    private fun startMprisBroadcastLoop() {
        mprisPollJob?.cancel()
        mprisPollJob = scope.launch(Dispatchers.IO) {
            var lastBroadcastKey = ""
            while (isActive) {
                delay(3_000L)
                val activeSessions = targetPeerDeviceIds().mapNotNull { node.activeSessionFor(it) }
                if (activeSessions.isEmpty()) continue
                val info = runCatching {
                    val player = mprisMediaSource.firstPlayerOrNull()
                    mprisMediaSource.setActivePlayer(player)
                    if (player != null) mprisMediaSource.poll() else null
                }.getOrNull() ?: continue

                val key = "${info.playerName}|${info.title}|${info.artist}|${info.state}|${info.volumePct}"
                if (key != lastBroadcastKey) {
                    lastBroadcastKey = key
                    for (session in activeSessions) {
                        runCatching { node.mediaSessionBridgeService.broadcastMediaSession(info, session) }
                    }
                }
            }
        }
    }

    /**
     * Starts `DesktopNotificationMonitor` (dbus-monitor --session) to relay Linux
     * desktop alerts to connected phones whenever the user enables it in Continuity
     * settings. Skips `ec0`-originated notifications to prevent echo loops.
     */
    private fun startDesktopNotificationRelayMonitor() {
        desktopNotifMonitorJob?.cancel()
        val monitor = DesktopNotificationMonitor(
            policyProvider = desktopNotificationPolicyProvider,
            monitorRunner = { argv, onLine ->
                runCatching {
                    val proc = ProcessBuilder(argv)
                        .redirectErrorStream(true)
                        .start()
                    dbusMonitorProcessRef.getAndSet(proc)?.destroyForcibly()
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!scope.isActive) break
                            onLine(line)
                        }
                    }
                }
            },
            sender = { info ->
                if (!desktopNotifRelayEnabledProvider()) return@DesktopNotificationMonitor
                // Echo-loop guard: never relay notifications posted by ec0 itself.
                val appLower = info.appName.lowercase()
                if (appLower == "ec0" || appLower.startsWith("ec0 ")) return@DesktopNotificationMonitor
                for (peerId in targetPeerDeviceIds()) {
                    val session = node.activeSessionFor(peerId) ?: continue
                    runCatching { node.notificationRelayService.postNotification(info, session) }
                }
            },
        )
        desktopNotificationMonitor = monitor
        desktopNotifMonitorJob = scope.launch(Dispatchers.IO) {
            runCatching { monitor.start() }
        }
    }

    fun setAutoSyncClipboard(enabled: Boolean) {
        _autoSyncClipboard.value = enabled
    }

    fun setForwardNotificationsToDesktop(enabled: Boolean) {
        _forwardNotificationsToDesktop.value = enabled
    }

    fun setPeerCapability(deviceId: String, wireName: String, granted: Boolean) {
        val id = deviceId.lowercase()
        val current = _peerCapabilityGrants.value.toMutableMap()
        val existing = current[id] ?: setOf("remote_input")
        current[id] = if (granted) existing + wireName else existing - wireName
        _peerCapabilityGrants.value = current
        persistPeerGrants(current)
    }

    private fun loadPersistedPeerGrants(): Map<String, Set<String>> {
        val f = peerGrantsFile ?: return emptyMap()
        if (!f.exists()) return emptyMap()
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
                as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            buildMap {
                for ((peerId, elem) in root) {
                    val arr = elem as? kotlinx.serialization.json.JsonArray ?: continue
                    val caps = arr.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    put(peerId.lowercase(), caps)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistPeerGrants(grants: Map<String, Set<String>>) {
        val f = peerGrantsFile ?: return
        runCatching {
            f.parentFile?.let { EncryptedIdentityStore.ensureDirectoryPermissions(it) }
            val json = buildJsonObject {
                for ((peerId, caps) in grants) {
                    put(
                        peerId.lowercase(),
                        kotlinx.serialization.json.buildJsonArray {
                            for (c in caps.sorted()) add(JsonPrimitive(c))
                        },
                    )
                }
            }
            EncryptedIdentityStore.atomicWriteText(f, json.toString())
        }
    }

    fun probeAndConnectUsbTunnel() {
        scope.launch(Dispatchers.IO) {
            val status = node.usbAdbTunnelCoordinator.probeAndEstablishUsbTunnel()
            if (status != null) {
                _linkRttSamplesMs.value = (_linkRttSamplesMs.value + 1L).takeLast(16)
                _statusBanner.value =
                    "USB-C Direct Link active: ${status.model} (${status.serial})"
            } else {
                _statusBanner.value = "No USB-C Android device detected — using encrypted Wi-Fi link"
            }
        }
    }

    fun tearDownUsbTunnel() {
        scope.launch(Dispatchers.IO) {
            node.usbAdbTunnelCoordinator.tearDownUsbTunnel()
            _statusBanner.value = "USB-C Direct Link disconnected — returned to Wi-Fi"
        }
    }

    fun toggleVirtualMic() {
        scope.launch(Dispatchers.IO) {
            val currentlyActive = node.virtualMicBridge.virtualMicActive.value
            val ok = if (currentlyActive) {
                node.virtualMicBridge.disableVirtualMic()
            } else {
                node.virtualMicBridge.enableVirtualMic()
            }
            _statusBanner.value = if (!currentlyActive && ok) {
                "Loaded virtual microphone (PipeWire/PulseAudio 48 kHz)"
            } else if (currentlyActive) {
                "Unloaded virtual microphone"
            } else {
                "Virtual mic requires pactl (PipeWire-Pulse or PulseAudio)"
            }
        }
    }

    fun openRemoteHandoffOnLinux() {
        scope.launch(Dispatchers.IO) {
            val activity = node.remoteHandoffActivity.value ?: return@launch
            val opened = node.handoffAndProximityService.openActiveRemoteHandoffLocally()
            _statusBanner.value = if (opened) {
                "Opened handoff link: ${activity.url}"
            } else {
                "Validated link ready: ${activity.url}"
            }
        }
    }

    fun sendUrlHandoffToPhone(rawUrl: String, title: String = "Desktop Tab") {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val session = activeSessionOrNull()
                node.handoffAndProximityService.sendHandoffOpen(
                    url = trimmed,
                    title = title,
                    session = session,
                )
                _statusBanner.value = "Sent link to paired device: $trimmed"
            } catch (e: Exception) {
                _statusBanner.value = "Rejected unsafe link: ${e.message}"
            }
        }
    }

    fun copyExtractedOtpCode(otpCode: String) {
        if (otpCode.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val item = ClipboardItem.text(otpCode.trim(), isSensitive = true)
            clipboardBridge.applyRemoteClipboardToSystem(item)
            broadcastClipboardItem(item)
            _statusBanner.value = "Copied 2FA code '$otpCode' — auto-scrubbing clipboard in 30s"
            scheduleSensitiveClipboardScrub(item.sha256Hex, ttlMs = 30_000L)
        }
    }

    private fun scheduleSensitiveClipboardScrub(expectedSha256Hex: String, ttlMs: Long) {
        scrubJob?.cancel()
        scrubJob = scope.launch(Dispatchers.IO) {
            delay(ttlMs)
            val scrubbed = clipboardBridge.scrubIfUnchanged(expectedSha256Hex)
            if (scrubbed) {
                _lastSyncedClipSummary.value = "Scrubbed expired sensitive clipboard item"
                _statusBanner.value = "Auto-scrubbed sensitive 2FA code from clipboard"
            }
        }
    }

    fun triggerFindMyPhone(start: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.handoffAndProximityService.triggerFindMyPhoneRing(
                start = start,
                durationSeconds = 15,
                session = session,
            )
            _statusBanner.value = if (start) {
                "Ringing paired phone at max alarm volume"
            } else {
                "Stopped Find My Phone ring"
            }
        }
    }

    fun lockLocalWorkstation() {
        scope.launch(Dispatchers.IO) {
            val ok = node.handoffAndProximityService.lockLocalWorkstation()
            _statusBanner.value = if (ok) {
                "Locked workstation session"
            } else {
                "Requested workstation lock"
            }
        }
    }

    fun rotateActiveSessionKeys() {
        val nextEpoch = _rekeyEpoch.value + 1
        _rekeyEpoch.value = nextEpoch
        _statusBanner.value = "Rotated session keys to epoch #$nextEpoch"
    }

    fun formatTransferThroughput(snap: TransferProgressSnapshot): TransferSpeedSample =
        throughputTracker.recordProgress(
            transferId = snap.transferId,
            transferredBytes = snap.transferredBytes,
            totalBytes = snap.totalBytes,
        )

    fun pairWithQrUri(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val session = node.pairAndConnectWithQrUri(uri.trim())
                val sas = EngineIdentity.verificationBadge(localIdentity.publicKeyRaw, session.peerPublicKeyRaw)
                _lastVerifiedSasBadge.value = sas
                _statusBanner.value = "Paired & verified ${session.peerDeviceId.take(12)}… (SAS: $sas)"
            } catch (e: Exception) {
                _statusBanner.value = "Pairing failed: ${e.message}"
            }
        }
    }

    /**
     * Two-way unpair: sends `CMD_UNPAIR` over the active authenticated session FIRST
     * while the socket is still alive so the remote device immediately revokes our pin too,
     * then drops the local pin and closes the session.
     */
    fun unpairPeer(deviceId: String) {
        val normalized = deviceId.lowercase()
        node.activeSessionFor(normalized)?.let { session ->
            runCatching {
                session.sendCmd(
                    cmdType = EngineConstants.CMD_UNPAIR,
                    cmdBody = buildJsonObject {
                        put("reason", JsonPrimitive("user_unpair"))
                        put("device_id", JsonPrimitive(localIdentity.deviceId))
                    },
                    category = EngineConstants.CATEGORY_SYSTEM,
                    timestampMs = System.currentTimeMillis(),
                )
            }
        }
        val removed = node.unpairPeer(normalized)
        val remainingGrants = _peerCapabilityGrants.value - normalized
        if (remainingGrants.size != _peerCapabilityGrants.value.size) {
            _peerCapabilityGrants.value = remainingGrants
            persistPeerGrants(remainingGrants)
        }
        if (removed) {
            _statusBanner.value = "Unpaired device ${normalized.take(12)}…"
        }
    }

    fun syncLocalClipboardNow() {
        scope.launch(Dispatchers.IO) {
            val item = clipboardBridge.pollLocalClipboardForChange() ?: return@launch
            broadcastClipboardItem(item)
        }
    }

    fun sendCopiedFilesFromClipboard() {
        scope.launch(Dispatchers.IO) {
            val files = clipboardBridge.pollCopiedFilesFromClipboard()
            if (files.isEmpty()) {
                _statusBanner.value = "No copied files found in clipboard"
                return@launch
            }
            sendFilesToConnectedPeers(files)
        }
    }

    fun sendFilesToConnectedPeers(files: List<File>, targetDeviceId: String? = null) {
        val validFiles = files.filter { it.exists() }
        if (validFiles.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var sentCount = 0
            val targets = if (!targetDeviceId.isNullOrBlank()) {
                setOf(targetDeviceId.lowercase())
            } else {
                targetPeerDeviceIds()
            }
            val rttMs = _linkRttSamplesMs.value.lastOrNull() ?: 10L
            val softAp = hotspotStatus.value.enabled
            for (peerId in targets) {
                if (node.activeSessionFor(peerId) != null) {
                    for (f in validFiles) {
                        runCatching {
                            node.sendFileOrDirectory(peerId, f, rttMs = rttMs, isSoftApLink = softAp)
                            sentCount++
                        }
                    }
                }
            }
            _statusBanner.value = if (sentCount > 0) {
                "Sending ${validFiles.size} file(s) over encrypted ec0 link"
            } else {
                "Connect a paired device first to send ${validFiles.size} file(s)"
            }
        }
    }

    /**
     * Safely opens a completed incoming transfer (or reveals its containing folder)
     * via argv-only `xdg-open`, strictly verifying canonical containment inside
     * [downloadDir] to prevent arbitrary file/URL execution from untrusted paths.
     */
    fun openLocalDownloadedFile(
        rawPath: String,
        revealInFolder: Boolean = false,
        launcher: ec0.core.engine.services.ExternalProcessLauncher = SystemProcessLauncher,
    ): Boolean {
        if (rawPath.isBlank() || rawPath.any { it.code < 32 || it == '\u0000' }) return false
        val rootCanonical = runCatching { downloadDir.canonicalFile }.getOrNull() ?: return false
        val target = File(rawPath)
        if (!target.exists()) return false
        val targetCanonical = runCatching { target.canonicalFile }.getOrNull() ?: return false
        if (!VfsPathGuard.isInsideRoot(rootCanonical.path, targetCanonical.path)) {
            _statusBanner.value = "Blocked opening path outside Downloads folder"
            return false
        }
        val toOpen = if (revealInFolder && targetCanonical.isFile) {
            val parent = targetCanonical.parentFile ?: rootCanonical
            if (VfsPathGuard.isInsideRoot(rootCanonical.path, parent.canonicalPath)) parent else rootCanonical
        } else {
            targetCanonical
        }
        val ok = runCatching {
            launcher.runBlockingCommand(listOf("xdg-open", toOpen.path), timeoutMs = 4_000L).exitCode == 0
        }.getOrDefault(false)
        if (!ok) {
            _statusBanner.value = "Saved at ${toOpen.path}"
        }
        return ok
    }

    fun sendFilePath(rawPath: String) {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return
        val file = File(trimmed)
        if (!file.exists()) {
            _statusBanner.value = "Path does not exist: $trimmed"
            return
        }
        sendFilesToConnectedPeers(listOf(file))
    }

    fun cancelFileTransfer(transferId: String) {
        scope.launch(Dispatchers.IO) {
            val cancelled = node.fileTransferService.cancelTransfer(transferId)
            if (cancelled) {
                throughputTracker.remove(transferId)
                _statusBanner.value = "Cancelled transfer"
            }
        }
    }

    fun clearFinishedTransfers() {
        node.fileTransferService.clearFinishedTransfers()
    }

    fun sendRemotePointerEvent(
        action: String,
        dx: Int = 0,
        dy: Int = 0,
        scrollY: Int = 0,
        buttonsMask: Int = 1,
    ) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull() ?: return@launch
            val event = InputPointerEvent(
                action = action,
                dx = dx,
                dy = dy,
                scrollY = scrollY,
                buttonsMask = buttonsMask,
            )
            node.inputShareService.sendPointerEvent(event, session)
        }
    }

    fun sendRemoteTextCommit(text: String) {
        if (text.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val event = InputKeyEvent(
                action = InputProtocol.KEY_ACTION_COMMIT_TEXT,
                text = text,
            )
            if (session != null) {
                node.inputShareService.sendKeyEvent(event, session)
                _statusBanner.value = "Typed ${text.length} char(s) on paired device"
            } else {
                _statusBanner.value = "Connect a paired device to forward keyboard input"
            }
        }
    }

    fun pushTextClipboard(text: String, isSensitive: Boolean = false) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val item = ClipboardItem.text(text, isSensitive = isSensitive)
            clipboardBridge.applyRemoteClipboardToSystem(item)
            broadcastClipboardItem(item)
            if (isSensitive) {
                scheduleSensitiveClipboardScrub(item.sha256Hex, ttlMs = 30_000L)
            }
        }
    }

    private fun broadcastClipboardItem(item: ClipboardItem) {
        node.clipboardShelfManager.recordItem(
            item = item,
            sourceAlias = desktopAlias,
            isRemote = false,
        )
        val targets = targetPeerDeviceIds()
        for (peerId in targets) {
            node.sendOrQueueClipboard(peerId, item)
        }
        val sensTag = if (item.isSensitive) " · sensitive (30s TTL)" else ""
        _lastSyncedClipSummary.value =
            "Synced ${item.bytes.size} B to paired device$sensTag"
    }

    fun setUniversalControlEdge(edge: ScreenEdge) {
        node.universalControlService.setConfiguredEdge(edge)
        screenEdgeWatcher.configureEdge(edge)
        _statusBanner.value = "Universal Control screen edge set to ${edge.wireName.uppercase()}"
    }

    fun configureUniversalControlEdge(edgeWire: String) {
        val normalized = edgeWire.trim().lowercase()
        if (normalized == "off" || normalized.isEmpty()) {
            screenEdgeWatcher.configureEdge(null)
            _statusBanner.value = "Universal Control screen-edge barrier disabled"
        } else {
            val edge = runCatching { ScreenEdge.fromWireName(normalized) }.getOrNull() ?: return
            setUniversalControlEdge(edge)
        }
    }

    fun setWalkAwayAutoLock(enabled: Boolean) {
        bleProximityScanner.setEnabled(enabled)
        _statusBanner.value = if (enabled) {
            "Walk-Away Auto-Lock enabled (BLE proximity monitoring)"
        } else {
            "Walk-Away Auto-Lock disabled"
        }
    }

    fun setAutostartOnLogin(enabled: Boolean): Boolean {
        val ok = runCatching { autostartManager.setAutostartEnabled(enabled) }.getOrDefault(false)
        _statusBanner.value = when {
            enabled && ok -> "Enabled silent background launch at login"
            !enabled && ok -> "Disabled background launch at login"
            else -> "Could not update login autostart entry"
        }
        return ok
    }

    fun triggerEdgeCursorHandoff(positionPermille: Int = 500) {
        scope.launch(Dispatchers.IO) {
            val peerId = targetPeerDeviceIds().firstOrNull() ?: "paired-peer"
            val session = node.activeSessionFor(peerId)
            val edge = node.universalControlService.configuredEdge.value
            val (x, y) = when (edge) {
                ScreenEdge.LEFT -> 0 to (positionPermille * 1080 / 1000)
                ScreenEdge.RIGHT -> 1919 to (positionPermille * 1080 / 1000)
                ScreenEdge.TOP -> (positionPermille * 1920 / 1000) to 0
                ScreenEdge.BOTTOM -> (positionPermille * 1920 / 1000) to 1079
            }
            val cmd = node.universalControlService.evaluateLocalPointerAtEdge(
                x = x,
                y = y,
                screenWidth = 1920,
                screenHeight = 1080,
                peerDeviceId = peerId,
                session = session,
            )
            if (cmd != null) {
                _statusBanner.value = "Cursor handoff (${cmd.action}) across ${cmd.edge.wireName} edge"
            }
        }
    }

    fun returnCursorToLocalDesktop() {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.universalControlService.returnCursorToLocal(session = session)
            _statusBanner.value = "Returned cursor focus to local desktop"
        }
    }

    fun toggleInstantHotspot(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.hotspotAndTelephonyService.requestHotspotToggle(enable = enable, session = session)
            _statusBanner.value = if (enable) {
                "Requested Instant Hotspot from phone"
            } else {
                "Requested Instant Hotspot shutdown"
            }
        }
    }

    fun joinPhoneHotspotViaNmcli() {
        scope.launch(Dispatchers.IO) {
            val joined = node.hotspotAndTelephonyService.joinHotspotOnLinux()
            _statusBanner.value = if (joined) {
                "Connected to phone hotspot '${hotspotStatus.value.ssid}'"
            } else {
                "Failed to connect to phone hotspot via NetworkManager"
            }
        }
    }

    fun sendTelephonyCallAction(command: CallActionCommand) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.hotspotAndTelephonyService.sendCallAction(command, session)
            _statusBanner.value = "Sent call action '${command.action}'"
        }
    }

    fun sendMediaControl(action: String, volumePct: Int? = null) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val cmd = MediaControlCommand(action = action, volumePct = volumePct)
            node.mediaSessionBridgeService.sendMediaControl(cmd, session)
            _statusBanner.value = "Sent media control '${cmd.action}'"
        }
    }

    fun sendNotificationReply(notifId: String, action: String = "reply", text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val cmd = NotificationReplyCommand(
                notifId = notifId,
                action = action,
                replyText = trimmed,
            )
            var sent = false
            for (peerId in targetPeerDeviceIds()) {
                val session = node.activeSessionFor(peerId)
                if (session != null) {
                    node.notificationRelayService.sendInlineReply(cmd, session)
                    sent = true
                }
            }
            _statusBanner.value = if (sent) {
                "Sent quick reply"
            } else {
                "No active peer session to deliver reply"
            }
        }
    }

    fun dismissNotification(notifId: String) {
        scope.launch(Dispatchers.IO) {
            for (peerId in targetPeerDeviceIds()) {
                val session = node.activeSessionFor(peerId)
                if (session != null) {
                    node.notificationRelayService.dismissNotification(notifId, session)
                }
            }
        }
    }

    fun sendNotificationAction(notifId: String, actionIndex: Int, actionLabel: String = "") {
        scope.launch(Dispatchers.IO) {
            var delivered = false
            for (peerId in targetPeerDeviceIds()) {
                if (node.sendNotificationAction(peerId, notifId, actionIndex, actionLabel)) {
                    delivered = true
                }
            }
            if (delivered) {
                _statusBanner.value = "Triggered notification action '${actionLabel.ifBlank { "#$actionIndex" }}'"
            }
        }
    }

    fun launchPhoneApp(packageName: String) {
        val cleanPkg = packageName.trim()
        if (!ScrcpyMirrorStartCommand.isValidPackageName(cleanPkg)) {
            _statusBanner.value = "Invalid Android package name"
            return
        }
        startScreenMirror(
            startApp = cleanPkg,
            newDisplay = "1080x1920/420",
        )
    }

    fun startScreenMirror(
        host: String = "",
        adbPort: Int = 5555,
        maxFps: Int = 60,
        bitrateKbps: Int = 8_000,
        audioEnabled: Boolean = true,
        turnScreenOff: Boolean = false,
        startApp: String? = null,
        newDisplay: String? = null,
    ) {
        val mirrorLog = java.util.logging.Logger.getLogger("ec0.hub.mirror")
        scope.launch(Dispatchers.IO) {
            try {
                mirrorLog.info("Mirror: requested (host hint='$host', fps=$maxFps, audio=$audioEnabled, screenOff=$turnScreenOff, app=$startApp)")
                val adbDevices = node.scrcpyCoordinator.adbDevicesList()
                val peer = node.pinnedPeers.value
                    .firstOrNull { node.activeSessionFor(it.deviceId) != null }
                    ?: node.pinnedPeers.value.firstOrNull()
                    ?: throw IOException("No paired phone — pair first, then mirror")
                val peerIp = node.activeSessionFor(peer.deviceId)?.peerRemoteIp
                val serial = resolveMirrorSerial(adbDevices, peer.alias, peerIp)
                    ?: run {
                        if (host.isNotBlank() && adbDevices.isNotBlank()) {
                            null
                        } else {
                            throw IOException(
                                "No adb device matching '${peer.alias}' — enable Wireless debugging on the phone or connect USB-C",
                            )
                        }
                    }
                val title = if (startApp != null) "ec0 App · $startApp" else "ec0 Mirror (${peer.alias})"
                val cmd = ScrcpyMirrorStartCommand(
                    host = host.ifBlank { peerIp ?: "127.0.0.1" },
                    adbPort = adbPort,
                    maxFps = maxFps,
                    bitrateKbps = bitrateKbps,
                    windowTitle = title,
                    audioEnabled = audioEnabled,
                    turnScreenOff = turnScreenOff,
                    startApp = startApp,
                    newDisplay = newDisplay,
                )
                node.scrcpyCoordinator.launchMirrorSession(cmd, directSerial = serial)
                _statusBanner.value = when {
                    startApp != null -> "Streaming Android app $startApp in virtual display"
                    serial != null -> "Mirroring ${peer.alias} (${maxFps} fps${if (audioEnabled) ", audio" else ", muted"})"
                    else -> "Mirroring phone screen (${cmd.serial})"
                }
            } catch (e: Exception) {
                mirrorLog.warning("Mirror failed: ${e.javaClass.simpleName}: ${e.message}")
                _statusBanner.value = "Screen mirror failed: ${e.message}"
            }
        }
    }

    fun stopScreenMirror() {
        node.scrcpyCoordinator.stopLocalMirror()
        _statusBanner.value = "Screen mirror stopped"
    }

    fun startContinuityCamera(
        host: String,
        port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        backend: CameraBackend = CameraBackend.CAMDROID_NATIVE,
        resolution: String = "1080p",
        fps: Int = 30,
        codec: String = "mjpeg",
        frontCamera: Boolean = false,
        audio: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val camAdvertised = node.peerPresence.value.values.any { beacon ->
                EngineCapability.CAMERA in beacon.capabilities
            }
            val preflight = node.cameraCoordinator.streamPreflight(
                sessionConnected = session != null,
                phoneCameraAdvertised = camAdvertised,
                v4l2DevicePath = CamDroidConstants.DEFAULT_V4L2_DEVICE,
            )
            if (!preflight.allClear) {
                _statusBanner.value = preflight.bannerText()
                return@launch
            }

            val streamToken = ContinuityCameraCoordinator.newStreamToken()
            val wireFps = if (fps >= 45) 60 else 30
            val wireResolution = if (resolution.lowercase() in CamDroidConstants.SUPPORTED_RESOLUTIONS) {
                resolution.lowercase()
            } else {
                "1080p"
            }
            val armCommand = CamDroidControlCommand.Start(
                codec = codec,
                resolution = wireResolution,
                fps = wireFps,
                audio = audio,
                token = streamToken,
            )
            try {
                session?.sendCmd(
                    cmdType = ContinuityCameraCoordinator.CMD_CAMERA_CONTROL,
                    cmdBody = armCommand.toJsonObject(),
                    category = ContinuityCameraCoordinator.CATEGORY_CAMERA,
                )
            } catch (e: Exception) {
                _statusBanner.value = "Camera arm command failed: ${e.message}"
                return@launch
            }

            val effectiveHost = if (usbTunnelStatus.value != null) "127.0.0.1" else host
            val status = node.cameraCoordinator.startCameraStream(
                host = effectiveHost,
                port = port,
                backend = backend,
                v4l2DevicePath = CamDroidConstants.DEFAULT_V4L2_DEVICE,
                codec = codec,
                resolution = resolution,
                fps = fps,
                audio = audio,
                frontCamera = frontCamera,
                streamToken = streamToken,
            )
            _statusBanner.value = when (status.state) {
                CameraStreamState.STREAMING ->
                    "Continuity Camera live: ${status.resolution}@${status.fps}fps → ${status.v4l2DevicePath}"
                else -> status.reason
                    ?: "Continuity Camera ${status.state.wireName} → ${status.v4l2DevicePath}"
            }
        }
    }

    fun setupV4l2LoopbackWithPkexec() {
        scope.launch(Dispatchers.IO) {
            val ok = node.cameraCoordinator.setupV4l2WithPkexec()
            refreshLinuxCapabilities()
            _statusBanner.value = if (ok) {
                "Loaded v4l2loopback kernel module (/dev/video10 ready)"
            } else {
                "v4l2loopback setup cancelled or failed. Run: ${node.cameraCoordinator.v4l2SetupCommand()}"
            }
        }
    }

    fun sendCameraControl(command: CamDroidControlCommand) {
        scope.launch(Dispatchers.IO) {
            val activeSession = activeSessionOrNull()
            node.cameraCoordinator.sendControlCommand(command, activeSession)
        }
    }

    fun stopContinuityCamera() {
        scope.launch(Dispatchers.IO) {
            val activeSession = activeSessionOrNull()
            node.cameraCoordinator.stopCameraStream(activeSession)
            _statusBanner.value = "Continuity Camera stopped"
        }
    }

    fun installLinuxAirDropIntegration(): InstalledDesktopFiles? {
        return try {
            val installed = LinuxDesktopIntegrationGenerator.install()
            _statusBanner.value =
                "Installed file manager menus: ${installed.dolphinServiceMenu.name}, ${installed.nautilusScript.name}"
            installed
        } catch (e: Exception) {
            _statusBanner.value = "Failed to install desktop integration: ${e.message}"
            null
        }
    }

    private fun startClipboardWatchLoop() {
        clipboardWatchJob?.cancel()
        clipboardWatchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_200L)
                if (_autoSyncClipboard.value) {
                    val changed = clipboardBridge.pollLocalClipboardForChange()
                    if (changed != null) {
                        broadcastClipboardItem(changed)
                    }
                }
            }
        }
    }

    private fun startNotificationForwardLoop() {
        notifForwardJob?.cancel()
        notifForwardJob = scope.launch(Dispatchers.IO) {
            node.notifications.collect { list ->
                if (!_forwardNotificationsToDesktop.value) return@collect
                for (notif in list) {
                    val dedupeKey = "${notif.notifId}:${notif.timestampMs}"
                    val isNew = synchronized(forwardedNotifIds) {
                        val added = forwardedNotifIds.add(dedupeKey)
                        while (forwardedNotifIds.size > 512) {
                            forwardedNotifIds.iterator().let { it.next(); it.remove() }
                        }
                        added
                    }
                    if (isNew) {
                        notificationBridge.showDesktopNotification(notif)
                        val otp = OtpCodeExtractor.extractOtpCode(notif.title, notif.body)
                        if (otp != null) {
                            otpAutoTypeController.recordOtp(otp, notif.appName)
                        }
                    }
                }
            }
        }
    }

    // ---- Features #1..#15 Continuity Pro Actions & Background Monitors ----

    private fun startCallDuckingAndCaptureLoops() {
        callDuckingJob?.cancel()
        callDuckingJob = scope.launch(Dispatchers.IO) {
            var previouslyDucked: String? = null
            node.activeCalls.collect { calls ->
                for (call in calls) {
                    val nowDucked = callMediaDuckingCoordinator.onCallStateUpdate(call)
                    if (previouslyDucked == null && nowDucked != null) {
                        _statusBanner.value = "Auto-paused desktop media ($nowDucked) for incoming call (${call.callerName})"
                    } else if (previouslyDucked != null && nowDucked == null) {
                        _statusBanner.value = "Call ended — resumed desktop media"
                    }
                    previouslyDucked = nowDucked
                }
            }
        }
        captureWatcherJob?.cancel()
        captureWatcherJob = scope.launch(Dispatchers.IO) {
            node.continuityCaptureService.lastDeliveredCapture.collect { capture ->
                val rawBytes = capture?.inlineBase64Url?.let { runCatching { it.decodeBase64Url() }.getOrNull() }
                if (capture != null && rawBytes != null) {
                    val ext = when (capture.mime) {
                        "application/pdf" -> "pdf"
                        "image/png" -> "png"
                        else -> "jpg"
                    }
                    val outDir = File(downloadDir, "ec0-captures").apply { mkdirs() }
                    val outFile = File(outDir, "capture-${capture.requestId.take(8)}.$ext")
                    runCatching { outFile.writeBytes(rawBytes) }
                    _statusBanner.value = "Saved phone ${capture.mode} to ${outFile.name}"
                }
            }
        }
    }

    private fun startMeetingAutoFocusLoop() {
        meetingFocusJob?.cancel()
        meetingFocusJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5_000L)
                if (_meetingAutoFocusEnabled.value) {
                    runCatching { node.focusSyncService.pollMeetingAutoFocus(activeSessionOrNull()) }
                }
                val latestRtt = _linkRttSamplesMs.value.lastOrNull()
                if (latestRtt != null && node.cameraStatus.value.state == CameraStreamState.STREAMING) {
                    node.cameraStudioEffectsController.recordLinkRtt(latestRtt)
                }
                node.clipboardShelfManager.pruneExpired()
            }
        }
    }

    fun togglePhoneAudioSink() {
        scope.launch(Dispatchers.IO) {
            val active = node.audioHandoffService.status.value.active
            val session = activeSessionOrNull()
            if (active) {
                node.audioHandoffService.stopDesktopAudioRoute(session)
                _statusBanner.value = "Stopped Phone-to-PC Audio Sink"
            } else {
                val st = node.audioHandoffService.startDesktopAudioRoute(
                    mode = "media_sink",
                    session = session,
                )
                _statusBanner.value = if (st.active) {
                    "Phone Audio Sink active (PipeWire ${st.sampleRateHz} Hz)"
                } else {
                    "Audio Sink requires PipeWire/PulseAudio (pactl)"
                }
            }
        }
    }

    fun toggleCallAudioHandoff() {
        scope.launch(Dispatchers.IO) {
            val st = node.audioHandoffService.status.value
            val session = activeSessionOrNull()
            if (st.active && st.mode == "call_duplex") {
                node.audioHandoffService.stopDesktopAudioRoute(session)
                _statusBanner.value = "Returned call audio to phone"
            } else {
                val res = node.audioHandoffService.startDesktopAudioRoute(
                    mode = "call_duplex",
                    session = session,
                )
                _statusBanner.value = if (res.active) {
                    "Full-duplex call audio routed to PC headset"
                } else {
                    "Call audio routing requires PipeWire/PulseAudio"
                }
            }
        }
    }

    fun requestPhoneCapture(mode: String, autoPaste: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val req = node.continuityCaptureService.requestCaptureFromPhone(
                mode = mode,
                autoPaste = autoPaste,
                session = session,
            )
            _statusBanner.value = if (session != null) {
                "Requested $mode from paired phone (${req.requestId.take(6)})"
            } else {
                "Connect a paired phone to insert $mode"
            }
        }
    }

    fun toggleSidecarDisplay(width: Int = 1920, height: Int = 1080, fps: Int = 60) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            if (node.sidecarDisplayService.state.value.active) {
                node.sidecarDisplayService.stopSidecar(session)
                _statusBanner.value = "Stopped Sidecar wireless second display"
            } else {
                val state = node.sidecarDisplayService.startSidecar(
                    cmd = SidecarStartCommand(width = width, height = height, fps = fps),
                    session = session,
                )
                _statusBanner.value = if (state.active) {
                    "Sidecar second display active (${width}x${height}@${fps}fps)"
                } else {
                    "Sidecar requires ffmpeg + connected Android tablet/phone"
                }
            }
        }
    }

    fun requestSmsSync() {
        scope.launch(Dispatchers.IO) {
            node.smsInboxService.requestSync(activeSessionOrNull())
        }
    }

    fun sendSmsMessage(address: String, body: String): Boolean {
        val ok = runCatching {
            node.smsInboxService.sendSms(
                recipientAddress = address,
                body = body,
                session = activeSessionOrNull(),
            )
            true
        }.getOrDefault(false)
        _statusBanner.value = if (ok) "Sent SMS to $address" else "Could not send SMS (check number/connection)"
        return ok
    }

    fun autoTypeLatestOtp(): Boolean {
        val ok = otpAutoTypeController.consumeAndAutoType()
        _statusBanner.value = if (ok) {
            "Auto-typed 2FA OTP code (ephemeral)"
        } else {
            "No fresh 2FA OTP code waiting (expires after 60s)"
        }
        return ok
    }

    fun setFocusMode(mode: String) {
        scope.launch(Dispatchers.IO) {
            val st = node.focusSyncService.setLocalFocusMode(
                mode = mode,
                source = "manual",
                session = activeSessionOrNull(),
            )
            _statusBanner.value = "Focus mode synced: ${st.mode.uppercase()}"
        }
    }

    fun setMeetingAutoFocusEnabled(enabled: Boolean) {
        _meetingAutoFocusEnabled.value = enabled
        _statusBanner.value = if (enabled) {
            "Meeting Auto-Focus enabled (monitors active PipeWire mic streams)"
        } else {
            "Meeting Auto-Focus disabled"
        }
    }

    fun publishLocalBrowserTab(url: String, title: String = "", mediaPositionSec: Long = 0L) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val info = BrowserTabHandoffInfo(
                    tabId = "tab-${System.currentTimeMillis()}",
                    url = url,
                    title = title,
                    mediaPositionSec = mediaPositionSec.toInt().coerceAtLeast(0),
                    timestampMs = System.currentTimeMillis(),
                )
                node.browserTabHandoffService.publishLocalActiveTab(
                    info = info,
                    session = activeSessionOrNull(),
                )
                _statusBanner.value = "Handed off browser tab to paired device"
            }
        }
    }

    fun openActiveRemoteBrowserTab() {
        val tab = node.browserTabHandoffService.activeRemoteTab.value ?: return
        val resumeUrl = BrowserTabHandoffService.buildResumeUrl(tab)
        scope.launch(Dispatchers.IO) {
            runCatching {
                SystemProcessLauncher.runBlockingCommand(listOf("xdg-open", resumeUrl), timeoutMs = 4_000L)
            }
            _statusBanner.value = "Resumed tab: ${tab.title.ifBlank { resumeUrl }}"
        }
    }

    fun toggleWifiDirectTurbo() {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.transportFailoverCoordinator.requestWifiDirectTurbo(session)
            _statusBanner.value = if (session != null) {
                "Requested Wi-Fi Direct 5 GHz Turbo channel upgrade"
            } else {
                "Connect a paired device to enable Wi-Fi Direct Turbo"
            }
        }
    }

    fun updateCameraStudioEffects(transform: (CameraEffectsCommand) -> CameraEffectsCommand) {
        scope.launch(Dispatchers.IO) {
            val updated = node.cameraStudioEffectsController.updateEffects(transform)
            activeSessionOrNull()?.let { s ->
                runCatching { node.cameraStudioEffectsController.sendEffects(updated, s) }
            }
            _statusBanner.value = "Updated Continuity Camera Studio Effects"
        }
    }

    fun toggleClipboardShelfPin(entryId: String) {
        node.clipboardShelfManager.togglePin(entryId)
    }

    fun copyClipboardShelfEntry(entryId: String) {
        val entry = node.clipboardShelfManager.entries.value.firstOrNull { it.id == entryId } ?: return
        pushTextClipboard(entry.fullText ?: entry.previewText, isSensitive = entry.isSensitive)
    }

    fun undoLastRemoteClipboard() {
        val restored = node.clipboardShelfManager.consumeUndoSnapshot() ?: return
        scope.launch(Dispatchers.IO) {
            clipboardBridge.applyRemoteClipboardToSystem(restored)
            _statusBanner.value = "Restored previous desktop clipboard"
        }
    }

    fun dismissClipboardToast() {
        node.clipboardShelfManager.dismissToast()
    }

    fun startWebDavPhoneMount(dataSource: VfsMountDataSource) {
        scope.launch(Dispatchers.IO) {
            runCatching { webDavMountServer?.close() }
            val srv = VfsLoopbackWebDavServer(dataSource = dataSource)
            srv.start()
            webDavMountServer = srv
            _webDavMountUrl.value = srv.mountUrl
            _statusBanner.value = "Mounted Phone VFS WebDAV on loopback ${srv.mountUrl}"
        }
    }

    fun stopWebDavPhoneMount() {
        runCatching { webDavMountServer?.close() }
        webDavMountServer = null
        _webDavMountUrl.value = null
        _statusBanner.value = "Unmounted Phone VFS loopback WebDAV"
    }

    fun runHealthDiagnostics() {
        scope.launch(Dispatchers.IO) {
            val runner = SystemHealthDiagnosticsRunner(
                keystoreBackendProvider = { _keySealerBackend.value },
            )
            val results = runner.runAllChecks()
            _diagnosticChecks.value = results
            val warns = results.count { it.severity != ec0.core.engine.services.DiagnosticSeverity.OK }
            _statusBanner.value = if (warns == 0) {
                "All ${results.size} system health checks passed"
            } else {
                "Health diagnostics: ${results.size - warns}/${results.size} ready ($warns action items)"
            }
        }
    }

    fun exportRedactedSupportBundle(): File? {
        return runCatching {
            val checks = _diagnosticChecks.value.ifEmpty {
                SystemHealthDiagnosticsRunner(keystoreBackendProvider = { _keySealerBackend.value }).runAllChecks()
            }
            val file = SupportBundleExporter.exportRedactedBundle(
                configDir = EncryptedIdentityStore.defaultConfigDir(),
                appVersion = "1.0.0",
                checks = checks,
                logs = node.diagnosticRingBuffer.snapshot(),
            )
            _statusBanner.value = "Exported PII-redacted support bundle: ${file.name}"
            file
        }.getOrNull()
    }

    private fun handleDaemonIpcCommand(cmd: String, payload: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        return when (cmd) {
            "status" -> buildJsonObject {
                put("ok", true)
                put("alias", desktopAlias)
                put("port", node.boundServerPort)
                put("paired_count", node.pinnedPeers.value.size)
                put("connected_count", node.connectionStates.value.values.count { it == EngineConnectionState.CONNECTED })
                put("focus_mode", node.focusSyncService.focusState.value.mode)
                put("audio_sink_active", node.audioHandoffService.status.value.active)
                put("sidecar_active", node.sidecarDisplayService.state.value.active)
                put("webdav_url", _webDavMountUrl.value ?: "")
            }
            "auto_type_otp" -> {
                val ok = autoTypeLatestOtp()
                buildJsonObject { put("ok", ok); put("message", _statusBanner.value) }
            }
            "set_focus" -> {
                val mode = (payload["mode"] as? JsonPrimitive)?.content ?: FocusSyncProtocol.MODE_DND
                setFocusMode(mode)
                buildJsonObject { put("ok", true); put("mode", mode) }
            }
            "handoff_tab" -> {
                val url = (payload["url"] as? JsonPrimitive)?.content ?: ""
                val title = (payload["title"] as? JsonPrimitive)?.content ?: ""
                val pos = (payload["media_position_sec"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                publishLocalBrowserTab(url, title, pos)
                buildJsonObject { put("ok", true); put("url", url) }
            }
            "ring_phone" -> {
                triggerFindMyPhone(true)
                buildJsonObject { put("ok", true) }
            }
            "capture_photo" -> {
                requestPhoneCapture(ContinuityCaptureProtocol.MODE_PHOTO)
                buildJsonObject { put("ok", true) }
            }
            "capture_scan" -> {
                requestPhoneCapture(ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN)
                buildJsonObject { put("ok", true) }
            }
            "diagnose" -> {
                runHealthDiagnostics()
                buildJsonObject { put("ok", true) }
            }
            "unmount_vfs" -> {
                stopWebDavPhoneMount()
                buildJsonObject { put("ok", true) }
            }
            else -> buildJsonObject { put("ok", false); put("error", "unknown_command") }
        }
    }

    private fun activeSessionOrNull() =
        targetPeerDeviceIds().firstNotNullOfOrNull { node.activeSessionFor(it) }

    private fun targetPeerDeviceIds(): Set<String> {
        val pinned = node.pinnedPeers.value.map { it.deviceId.lowercase() }
        val connected = node.connectionStates.value.keys
        return (pinned + connected).toSet()
    }

    override fun close() {
        pairingQrRefreshJob?.cancel()
        reconnectJob?.cancel()
        presenceBeaconJob?.cancel()
        presenceAuthJob?.cancel()
        mprisPollJob?.cancel()
        clipboardWatchJob?.cancel()
        notifForwardJob?.cancel()
        desktopNotifMonitorJob?.cancel()
        callDuckingJob?.cancel()
        meetingFocusJob?.cancel()
        captureWatcherJob?.cancel()
        scrubJob?.cancel()
        runCatching { webDavMountServer?.close() }
        runCatching { desktopNotificationMonitor?.stop() }
        runCatching { dbusMonitorProcessRef.getAndSet(null)?.destroyForcibly() }
        runCatching { screenEdgeWatcher.close() }
        runCatching { bleProximityScanner.close() }
        runCatching { adaptiveInputSink.close() }
        runCatching { daemonIpcServer?.close() }
        node.close()
    }
}
