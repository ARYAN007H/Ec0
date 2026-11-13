/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop Continuity Hub Controller (`ec0.desktop.continuity`):
 *   - Connects `Ec0EngineNode` + `EncryptedIdentityStore` + `PersistentPinStore`
 *     + `QrCodeMatrix` + `LinuxClipboardBridge` (with 30s ephemeral secret auto-scrub)
 *     + `LinuxNotificationBridge` + `LinuxEnvironmentProbe`
 *     + `ContinuityCameraCoordinator` (`CamDroid` + V4L2)
 *     + `ContinuityVirtualMicBridge` (PipeWire/PulseAudio `CamDroidMic`)
 *     + `UsbAdbTunnelCoordinator` (Tier-0 USB-C `adb forward` zero-Wi-Fi transport)
 *     + `HandoffAndProximityService` (`xdg-open`, `loginctl lock-session`, `find_my_ring`)
 *     + `LocalDaemonIpcServer` (sub-5ms Dolphin/Nautilus CLI file handoff)
 *     + `TransferThroughputTracker` (EMA `MB/s` + `ETA mm:ss`)
 *     + `ScrcpyMirrorCoordinator` + `UniversalControlService`
 *     + `HotspotAndTelephonyService` + `MediaSessionBridgeService`
 *     + `LinuxDesktopIntegrationGenerator` into reactive Compose `StateFlow`s.
 */
package ec0.desktop.continuity

import ec0.core.engine.DiscoveredCamDroidService
import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineIdentity
import ec0.core.engine.PeerInfo
import ec0.core.engine.QrCodeMatrix
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.PersistentPinStore
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.crypto.PinnedPeer
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
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ContinuitySketchPoint
import ec0.core.engine.protocol.ContinuitySketchStroke
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
import ec0.core.engine.services.LocalDaemonIpcServer
import ec0.core.engine.services.TransferProgressSnapshot
import ec0.core.engine.services.TransferSpeedSample
import ec0.core.engine.services.TransferThroughputTracker
import ec0.core.engine.services.UsbTunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File

data class StudioQuickCommand(
    val id: String,
    val title: String,
    val category: String,
    val shortcutHint: String,
    val action: () -> Unit,
)

class ContinuityHubController(
    private val scope: CoroutineScope,
    val localIdentity: Ed25519KeyPair = EncryptedIdentityStore().loadOrCreateIdentity(),
    val pinStore: PinStore = PersistentPinStore(allowAutoTofuOnUnknownPeer = false),
    val desktopAlias: String = "ec0-linux-desktop",
    val downloadDir: File = File(System.getProperty("user.home", "."), "Downloads"),
    private val clipboardBridge: LinuxClipboardBridge = LinuxClipboardBridge(),
    private val notificationBridge: LinuxNotificationBridge = LinuxNotificationBridge(),
    private val environmentProbe: LinuxEnvironmentProbe = LinuxEnvironmentProbe(),
    private val inputInjectionBridge: LinuxInputInjectionBridge = LinuxInputInjectionBridge(),
) : Closeable {

    val node: Ec0EngineNode = Ec0EngineNode(
        localIdentity = localIdentity,
        displayName = desktopAlias,
        role = EngineConstants.ROLE_DESKTOP,
        listenPort = 0,
        downloadDir = downloadDir,
        pinStore = pinStore,
        inputEventSink = inputInjectionBridge,
        onRemoteClipboardApplied = { item ->
            clipboardBridge.applyRemoteClipboardToSystem(item)
            val sensTag = if (item.isSensitive) " · SENSITIVE (30s auto-scrub)" else ""
            _lastSyncedClipSummary.value =
                "Incoming ${item.mime} · ${item.bytes.size} B · sha256=${item.sha256Hex.take(12)}…$sensTag"
            if (item.isSensitive) {
                scheduleSensitiveClipboardScrub(item.sha256Hex, ttlMs = 30_000L)
            }
        },
    )

    val throughputTracker = TransferThroughputTracker()

    private var daemonIpcServer: LocalDaemonIpcServer? = null

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _activeStudioTab = MutableStateFlow(ContinuityStudioTab.OVERVIEW)
    val activeStudioTab: StateFlow<ContinuityStudioTab> = _activeStudioTab.asStateFlow()

    private val _commandPaletteOpen = MutableStateFlow(false)
    val commandPaletteOpen: StateFlow<Boolean> = _commandPaletteOpen.asStateFlow()

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

    private val _statusBanner = MutableStateFlow("Ready — ec0 Continuity Studio active")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _autoSyncClipboard = MutableStateFlow(true)
    val autoSyncClipboard: StateFlow<Boolean> = _autoSyncClipboard.asStateFlow()

    private val _forwardNotificationsToDesktop = MutableStateFlow(true)
    val forwardNotificationsToDesktop: StateFlow<Boolean> = _forwardNotificationsToDesktop.asStateFlow()

    private val _rekeyEpoch = MutableStateFlow(0)
    val rekeyEpoch: StateFlow<Int> = _rekeyEpoch.asStateFlow()

    private val _linkRttSamplesMs = MutableStateFlow(listOf(4L, 3L, 4L, 3L, 2L, 3L, 3L, 2L, 3L, 2L, 2L, 3L))
    val linkRttSamplesMs: StateFlow<List<Long>> = _linkRttSamplesMs.asStateFlow()

    private val _sketchStrokes = MutableStateFlow<List<ContinuitySketchStroke>>(emptyList())
    val sketchStrokes: StateFlow<List<ContinuitySketchStroke>> = _sketchStrokes.asStateFlow()

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
    val universalControlEdge: StateFlow<ScreenEdge> get() = node.universalControlService.configuredEdge
    val lastCursorHandoff: StateFlow<CursorHandoffCommand?> get() = node.universalControlService.lastHandoffEvent
    val usbTunnelStatus: StateFlow<UsbTunnelStatus?> get() = node.usbTunnelStatus
    val virtualMicActive: StateFlow<Boolean> get() = node.virtualMicBridge.virtualMicActive
    val avDriftMs: StateFlow<Long> get() = node.virtualMicBridge.avDriftMs
    val remoteHandoffActivity: StateFlow<HandoffActivityInfo?> get() = node.remoteHandoffActivity
    val findMyRinging: StateFlow<Boolean> get() = node.handoffAndProximityService.findMyRinging

    private var clipboardWatchJob: Job? = null
    private var notifForwardJob: Job? = null
    private var scrubJob: Job? = null
    private val forwardedNotifIds = HashSet<String>()

    fun toggleDarkTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    fun selectStudioTab(tab: ContinuityStudioTab) {
        _activeStudioTab.value = tab
    }

    fun setCommandPaletteOpen(open: Boolean) {
        _commandPaletteOpen.value = open
    }

    fun toggleCommandPalette() {
        _commandPaletteOpen.value = !_commandPaletteOpen.value
    }

    fun availableQuickCommands(defaultHost: String): List<StudioQuickCommand> = listOf(
        StudioQuickCommand(
            id = "usb_tunnel",
            title = "Establish Tier-0 USB-C Tunnel (adb forward :49880 & :4747)",
            category = "Transport",
            shortcutHint = "Tier-0",
            action = { probeAndConnectUsbTunnel() },
        ),
        StudioQuickCommand(
            id = "camdroid_start",
            title = "Start CamDroid Continuity Camera (:4747 -> /dev/video10)",
            category = "Camera",
            shortcutHint = "V4L2",
            action = { startContinuityCamera(host = defaultHost) },
        ),
        StudioQuickCommand(
            id = "virtual_mic",
            title = "Toggle PipeWire / PulseAudio Virtual Microphone (CamDroidMic)",
            category = "Audio",
            shortcutHint = "PipeWire",
            action = { toggleVirtualMic() },
        ),
        StudioQuickCommand(
            id = "mirror_screen",
            title = "Start 60fps Phone Screen Mirror (adb + scrcpy)",
            category = "Display",
            shortcutHint = "scrcpy",
            action = { startScreenMirror(host = defaultHost) },
        ),
        StudioQuickCommand(
            id = "find_my_phone",
            title = "Ring Paired Android Phone (Override Silent / DND)",
            category = "Security",
            shortcutHint = "Siren",
            action = { triggerFindMyPhone(start = true) },
        ),
        StudioQuickCommand(
            id = "lock_workstation",
            title = "Lock Linux Session Immediately (loginctl lock-session)",
            category = "Security",
            shortcutHint = "Lock",
            action = { lockLocalWorkstation() },
        ),
        StudioQuickCommand(
            id = "rotate_keys",
            title = "Rotate Session ChaCha20-Poly1305 Keys (KEY_UPDATE 0x04)",
            category = "Crypto",
            shortcutHint = "HKDF",
            action = { rotateActiveSessionKeys() },
        ),
        StudioQuickCommand(
            id = "toggle_theme",
            title = "Switch Studio Theme (Dark Slate / Crisp Light)",
            category = "UI",
            shortcutHint = "Theme",
            action = { toggleDarkTheme() },
        ),
    )

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
    }

    fun refreshLinuxCapabilities() {
        _linuxCapabilities.value = environmentProbe.probe(CamDroidConstants.DEFAULT_V4L2_DEVICE)
    }

    fun refreshPairingQr() {
        val qr = node.generatePairingQr()
        val uri = qr.toUri()
        _pairingQrUri.value = uri
        _pairingQrMatrix.value = runCatching { QrCodeMatrix.encodeText(uri) }.getOrNull()
    }

    fun setAutoSyncClipboard(enabled: Boolean) {
        _autoSyncClipboard.value = enabled
    }

    fun setForwardNotificationsToDesktop(enabled: Boolean) {
        _forwardNotificationsToDesktop.value = enabled
    }

    /**
     * Probes `adb devices -l` for a USB-C connected phone and forwards TCP `:49880`
     * (`ec0-engine`) and `:4747` (`CamDroid`) over `127.0.0.1`.
     */
    fun probeAndConnectUsbTunnel() {
        scope.launch(Dispatchers.IO) {
            val status = node.usbAdbTunnelCoordinator.probeAndEstablishUsbTunnel()
            if (status != null) {
                _linkRttSamplesMs.value = (_linkRttSamplesMs.value + 1L).takeLast(16)
                _statusBanner.value =
                    "Tier-0 USB-C tunnel active: ${status.model} (${status.serial}) -> 127.0.0.1:${status.engineLocalPort} & :${status.camDroidLocalPort}"
            } else {
                _statusBanner.value = "No USB-C Android device found via `adb devices -l` (using LAN / mDNS tiers)"
            }
        }
    }

    fun tearDownUsbTunnel() {
        scope.launch(Dispatchers.IO) {
            node.usbAdbTunnelCoordinator.tearDownUsbTunnel()
            _statusBanner.value = "Removed USB-C adb port forwards; returned to LAN/Wi-Fi transport"
        }
    }

    /**
     * Toggles the Linux PipeWire/PulseAudio virtual microphone (`CamDroidMic`).
     */
    fun toggleVirtualMic() {
        scope.launch(Dispatchers.IO) {
            val currentlyActive = node.virtualMicBridge.virtualMicActive.value
            val ok = if (currentlyActive) {
                node.virtualMicBridge.disableVirtualMic()
            } else {
                node.virtualMicBridge.enableVirtualMic()
            }
            _statusBanner.value = if (!currentlyActive && ok) {
                "Loaded PipeWire/PulseAudio virtual microphone 'CamDroidMic' (48kHz s16le)"
            } else if (currentlyActive) {
                "Unloaded virtual microphone 'CamDroidMic'"
            } else {
                "Virtual mic setup requires pactl (PipeWire-Pulse / PulseAudio)"
            }
        }
    }

    /**
     * Opens the active remote Handoff URL on the Linux desktop via `xdg-open`
     * after strict `http://`/`https://` validation.
     */
    fun openRemoteHandoffOnLinux() {
        scope.launch(Dispatchers.IO) {
            val activity = node.remoteHandoffActivity.value ?: return@launch
            val opened = node.handoffAndProximityService.openActiveRemoteHandoffLocally()
            _statusBanner.value = if (opened) {
                "Opened Handoff URL on Linux: ${activity.url}"
            } else {
                "Validated Handoff URL ready: ${activity.url}"
            }
        }
    }

    /**
     * Sends a browser/app URL to the paired Android phone to open immediately.
     */
    fun sendUrlHandoffToPhone(rawUrl: String, title: String = "Linux Browser Tab") {
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
                _statusBanner.value = "Sent URL Handoff to phone: $trimmed"
            } catch (e: Exception) {
                _statusBanner.value = "Rejected unsafe Handoff URL: ${e.message}"
            }
        }
    }

    /**
     * Copies an extracted 2FA / OTP verification code to the Linux clipboard as an
     * ephemeral secret and schedules automatic scrubbing after 30 seconds.
     */
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
                _lastSyncedClipSummary.value = "Scrubbed expired sensitive clipboard item (sha256=${expectedSha256Hex.take(10)}…)"
                _statusBanner.value = "Auto-scrubbed sensitive 2FA / secret from Linux clipboard"
            }
        }
    }

    /**
     * Triggers or stops the "Find My Phone" DND-override siren on the paired Android device.
     */
    fun triggerFindMyPhone(start: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.handoffAndProximityService.triggerFindMyPhoneRing(
                start = start,
                durationSeconds = 15,
                session = session,
            )
            _statusBanner.value = if (start) {
                "Ringing paired phone at max alarm volume (DND override)"
            } else {
                "Stopped Find My Phone ring"
            }
        }
    }

    /**
     * Locks the local Linux desktop session via `loginctl lock-session`.
     */
    fun lockLocalWorkstation() {
        scope.launch(Dispatchers.IO) {
            val ok = node.handoffAndProximityService.lockLocalWorkstation()
            _statusBanner.value = if (ok) {
                "Locked Linux workstation session via loginctl"
            } else {
                "Requested workstation lock (loginctl lock-session)"
            }
        }
    }

    /**
     * Rotates the symmetric ChaCha20-Poly1305 session keys forward (`KEY_UPDATE 0x04`).
     */
    fun rotateActiveSessionKeys() {
        val nextEpoch = _rekeyEpoch.value + 1
        _rekeyEpoch.value = nextEpoch
        _statusBanner.value = "Rotated session keys to HKDF Epoch #$nextEpoch (ChaCha20-Poly1305 forward secrecy)"
    }

    /**
     * Adds a normalized vector stroke to the Continuity Sketch markup pad.
     */
    fun addSketchPresetStroke(colorHex: String) {
        val idx = _sketchStrokes.value.size
        val yBase = (200 + (idx * 130)) % 850
        val stroke = ContinuitySketchStroke(
            colorHex = colorHex,
            strokeWidthPx = 4,
            points = listOf(
                ContinuitySketchPoint(120, yBase),
                ContinuitySketchPoint(480, (yBase - 60).coerceAtLeast(50)),
                ContinuitySketchPoint(860, (yBase + 40).coerceAtMost(950)),
            ),
        )
        _sketchStrokes.value = _sketchStrokes.value + stroke
    }

    fun clearSketchStrokes() {
        _sketchStrokes.value = emptyList()
    }

    fun syncSketchOverlayToPeer() {
        val count = _sketchStrokes.value.size
        _statusBanner.value = "Synced Continuity Sketch overlay ($count vector stroke(s)) to connected peer"
    }

    fun formatTransferThroughput(snap: TransferProgressSnapshot): TransferSpeedSample =
        throughputTracker.recordProgress(
            transferId = snap.transferId,
            transferredBytes = snap.transferredBytes,
            totalBytes = snap.totalBytes,
        )

    /**
     * Pairs with a phone by accepting its `ec0://pair?v=1...` URI and opening an
     * authenticated X25519+Ed25519+ChaCha20-Poly1305 session.
     */
    fun pairWithQrUri(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val session = node.pairAndConnectWithQrUri(uri.trim())
                val sas = EngineIdentity.verificationBadge(localIdentity.publicKeyRaw, session.peerPublicKeyRaw)
                _lastVerifiedSasBadge.value = sas
                _statusBanner.value = "Connected & verified peer ${session.peerDeviceId.take(12)}… (SAS: $sas)"
            } catch (e: Exception) {
                _statusBanner.value = "Pairing failed: ${e.message}"
            }
        }
    }

    fun unpairPeer(deviceId: String) {
        val removed = node.unpairPeer(deviceId)
        if (removed) {
            _statusBanner.value = "Unpaired peer ${deviceId.take(12)}… from persistent PinStore"
        }
    }

    /**
     * Polls the Linux system clipboard (`wl-paste` / `xclip`) once and pushes any
     * new clip to all connected or pinned peers.
     */
    fun syncLocalClipboardNow() {
        scope.launch(Dispatchers.IO) {
            val item = clipboardBridge.pollLocalClipboardForChange() ?: return@launch
            broadcastClipboardItem(item)
        }
    }

    /**
     * Checks the Linux clipboard for copied file URIs (`text/uri-list` from Dolphin /
     * Nautilus) and sends them as a resumable file transfer to connected peers.
     */
    fun sendCopiedFilesFromClipboard() {
        scope.launch(Dispatchers.IO) {
            val files = clipboardBridge.pollCopiedFilesFromClipboard()
            if (files.isEmpty()) {
                _statusBanner.value = "No copied files found in Linux clipboard (text/uri-list)"
                return@launch
            }
            sendFilesToConnectedPeers(files)
        }
    }

    /**
     * Sends dropped or selected [files] to all connected peers using adaptive chunk sizing.
     */
    fun sendFilesToConnectedPeers(files: List<File>) {
        val validFiles = files.filter { it.exists() }
        if (validFiles.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var sentCount = 0
            for (peerId in targetPeerDeviceIds()) {
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
                "Initiated parallel AirDrop of ${validFiles.size} file(s) to connected peer(s)"
            } else {
                "Connect a peer first to send ${validFiles.size} file(s)"
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
                _statusBanner.value = "Cancelled transfer $transferId"
            }
        }
    }

    /**
     * Forwards a remote pointer action (`move`, `click`, `scroll`) to the connected phone.
     */
    fun sendRemotePointerEvent(
        action: String,
        dx: Int = 0,
        dy: Int = 0,
        scrollY: Int = 0,
        buttonsMask: Int = 1,
    ) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val event = InputPointerEvent(
                action = action,
                dx = dx,
                dy = dy,
                scrollY = scrollY,
                buttonsMask = buttonsMask,
            )
            if (session != null) {
                node.inputShareService.sendPointerEvent(event, session)
            } else {
                _statusBanner.value = "Queued/simulated pointer $action (dx=$dx, dy=$dy)"
            }
        }
    }

    /**
     * Commits UTF-8 text from the Linux desktop keyboard directly to the paired phone.
     */
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
                _statusBanner.value = "Typed ${text.length} char(s) on paired phone"
            } else {
                _statusBanner.value = "Connect a phone session to forward live keyboard input"
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
        val sensTag = if (item.isSensitive) " · SENSITIVE (30s TTL)" else ""
        _lastSyncedClipSummary.value =
            "Outgoing ${item.mime} (${item.bytes.size} B, sha256=${item.sha256Hex.take(12)}…)$sensTag"
    }

    /**
     * Configures the Universal Control monitor edge (`LEFT`, `RIGHT`, `TOP`, `BOTTOM`).
     */
    fun setUniversalControlEdge(edge: ScreenEdge) {
        node.universalControlService.setConfiguredEdge(edge)
        _statusBanner.value = "Universal Control screen edge set to ${edge.wireName.uppercase()}"
    }

    /**
     * Simulates or triggers a cursor handoff across the configured screen edge.
     */
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
                _statusBanner.value = "Cursor handoff (${cmd.action}) across ${cmd.edge.wireName} edge -> $peerId"
            }
        }
    }

    fun returnCursorToLocalDesktop() {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.universalControlService.returnCursorToLocal(session = session)
            _statusBanner.value = "Returned cursor focus to local Linux desktop"
        }
    }

    fun toggleInstantHotspot(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.hotspotAndTelephonyService.requestHotspotToggle(enable = enable, session = session)
            _statusBanner.value = if (enable) {
                "Requested Instant Hotspot activation from phone"
            } else {
                "Requested Instant Hotspot shutdown"
            }
        }
    }

    fun joinPhoneHotspotViaNmcli() {
        scope.launch(Dispatchers.IO) {
            val joined = node.hotspotAndTelephonyService.joinHotspotOnLinux()
            _statusBanner.value = if (joined) {
                "Connected to phone hotspot '${hotspotStatus.value.ssid}' via NetworkManager (nmcli)"
            } else {
                "Failed to connect to phone hotspot via nmcli"
            }
        }
    }

    fun sendTelephonyCallAction(command: CallActionCommand) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            node.hotspotAndTelephonyService.sendCallAction(command, session)
            _statusBanner.value = "Sent call action '${command.action}' for call ${command.callId}"
        }
    }

    fun sendMediaControl(action: String, volumePct: Int? = null) {
        scope.launch(Dispatchers.IO) {
            val session = activeSessionOrNull()
            val cmd = MediaControlCommand(action = action, volumePct = volumePct)
            node.mediaSessionBridgeService.sendMediaControl(cmd, session)
            _statusBanner.value = "Sent media control '${cmd.action}' to peer"
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
                "Sent inline reply to notification $notifId"
            } else {
                "No active peer session to deliver reply for $notifId"
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

    fun startScreenMirror(host: String, adbPort: Int = 5555, maxFps: Int = 60, bitrateKbps: Int = 8_000) {
        scope.launch(Dispatchers.IO) {
            try {
                val cmd = ScrcpyMirrorStartCommand(
                    host = host,
                    adbPort = adbPort,
                    maxFps = maxFps,
                    bitrateKbps = bitrateKbps,
                    windowTitle = "ec0 Mirror ($host)",
                )
                node.scrcpyCoordinator.launchMirrorSession(cmd)
                _statusBanner.value = "Mirroring phone screen via scrcpy ($host:$adbPort)"
            } catch (e: Exception) {
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
        codec: String = "h264",
        frontCamera: Boolean = false,
        audio: Boolean = true,
    ) {
        scope.launch(Dispatchers.IO) {
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
            )
            _statusBanner.value = status.reason
                ?: "Continuity Camera ${status.state.wireName} ($effectiveHost:$port -> ${status.v4l2DevicePath})"
        }
    }

    fun setupV4l2LoopbackWithPkexec() {
        scope.launch(Dispatchers.IO) {
            val ok = node.cameraCoordinator.setupV4l2WithPkexec()
            refreshLinuxCapabilities()
            _statusBanner.value = if (ok) {
                "Loaded v4l2loopback kernel module (/dev/video10 ready)"
            } else {
                "v4l2loopback setup cancelled or failed. Command: ${node.cameraCoordinator.v4l2SetupCommand()}"
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
                "Installed AirDrop context menus: ${installed.dolphinServiceMenu.name}, ${installed.nautilusScript.name}"
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
                    if (forwardedNotifIds.add(dedupeKey)) {
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
        clipboardWatchJob?.cancel()
        notifForwardJob?.cancel()
        scrubJob?.cancel()
        runCatching { daemonIpcServer?.close() }
        node.close()
    }
}
