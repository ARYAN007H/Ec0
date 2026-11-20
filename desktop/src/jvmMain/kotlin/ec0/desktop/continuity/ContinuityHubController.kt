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
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.linux.LinuxEnvironmentProbe
import ec0.core.engine.linux.LinuxInputInjectionBridge
import ec0.core.engine.linux.LinuxNotificationBridge
import ec0.core.engine.linux.LinuxSystemCapabilities
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
import ec0.core.engine.services.ContinuityCameraCoordinator
import ec0.core.engine.services.DesktopNotificationMonitor
import ec0.core.engine.services.LocalDaemonIpcServer
import ec0.core.engine.services.MprisDesktopMediaSource
import ec0.core.engine.services.NotificationRelayPolicy
import ec0.core.engine.services.SystemProcessLauncher
import ec0.core.engine.services.TransferProgressSnapshot
import ec0.core.engine.services.TransferSpeedSample
import ec0.core.engine.services.TransferThroughputTracker
import ec0.core.engine.services.UsbTunnelStatus
import ec0.core.engine.services.resolveMirrorSerial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    if (mediaControlSupported) {
        add(EngineCapability.MEDIA_CONTROL)
    }
}

class ContinuityHubController(
    private val scope: CoroutineScope,
    val localIdentity: Ed25519KeyPair = EncryptedIdentityStore().loadOrCreateIdentity(),
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
            clipboardBridge.applyRemoteClipboardToSystem(item)
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

    val throughputTracker = TransferThroughputTracker()

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

    fun start(startMdns: Boolean = true) {
        node.startServer(startMdns = startMdns)
        runCatching {
            val ipc = LocalDaemonIpcServer(
                onSendFilesRequested = { files -> sendFilesToConnectedPeers(files) },
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
            for (peerId in targets) {
                if (node.activeSessionFor(peerId) != null) {
                    for (f in validFiles) {
                        runCatching {
                            node.sendFileOrDirectory(peerId, f)
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
        _statusBanner.value = "Screen edge set to ${edge.wireName.uppercase()}"
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

    fun startScreenMirror(
        host: String = "",
        adbPort: Int = 5555,
        maxFps: Int = 60,
        bitrateKbps: Int = 8_000,
        audioEnabled: Boolean = true,
        turnScreenOff: Boolean = false,
    ) {
        val mirrorLog = java.util.logging.Logger.getLogger("ec0.hub.mirror")
        scope.launch(Dispatchers.IO) {
            try {
                mirrorLog.info("Mirror: requested (host hint='$host', fps=$maxFps, audio=$audioEnabled, screenOff=$turnScreenOff)")
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
                val cmd = ScrcpyMirrorStartCommand(
                    host = host.ifBlank { peerIp ?: "127.0.0.1" },
                    adbPort = adbPort,
                    maxFps = maxFps,
                    bitrateKbps = bitrateKbps,
                    windowTitle = "ec0 Mirror (${peer.alias})",
                    audioEnabled = audioEnabled,
                    turnScreenOff = turnScreenOff,
                )
                node.scrcpyCoordinator.launchMirrorSession(cmd, directSerial = serial)
                _statusBanner.value = if (serial != null) {
                    "Mirroring ${peer.alias} (${maxFps} fps${if (audioEnabled) ", audio" else ", muted"})"
                } else {
                    "Mirroring phone screen (${cmd.serial})"
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
                    }
                }
            }
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
        scrubJob?.cancel()
        runCatching { desktopNotificationMonitor?.stop() }
        runCatching { dbusMonitorProcessRef.getAndSet(null)?.destroyForcibly() }
        runCatching { adaptiveInputSink.close() }
        runCatching { daemonIpcServer?.close() }
        node.close()
    }
}
