/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop Continuity Hub Controller (`ec0.desktop.continuity`):
 *   - Connects `Ec0EngineNode` + `LinuxClipboardBridge` + `LinuxNotificationBridge`
 *     + `LinuxEnvironmentProbe` + `ContinuityCameraCoordinator` (`CamDroid` + V4L2)
 *     + `ScrcpyMirrorCoordinator` + `LinuxDesktopIntegrationGenerator` into
 *     reactive Compose `StateFlow`s for the Linux Desktop UI.
 */
package ec0.desktop.continuity

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineIdentity
import ec0.core.engine.PeerInfo
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.linux.InstalledDesktopFiles
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.linux.LinuxEnvironmentProbe
import ec0.core.engine.linux.LinuxNotificationBridge
import ec0.core.engine.linux.LinuxSystemCapabilities
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
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

class ContinuityHubController(
    private val scope: CoroutineScope,
    val localIdentity: Ed25519KeyPair = Ed25519Identity.generate(),
    val desktopAlias: String = "ec0-linux-desktop",
    val downloadDir: File = File(System.getProperty("user.home", "."), "Downloads"),
    private val clipboardBridge: LinuxClipboardBridge = LinuxClipboardBridge(),
    private val notificationBridge: LinuxNotificationBridge = LinuxNotificationBridge(),
    private val environmentProbe: LinuxEnvironmentProbe = LinuxEnvironmentProbe(),
) : Closeable {

    val node: Ec0EngineNode = Ec0EngineNode(
        localIdentity = localIdentity,
        displayName = desktopAlias,
        role = EngineConstants.ROLE_DESKTOP,
        listenPort = 0,
        downloadDir = downloadDir,
        onRemoteClipboardApplied = { item ->
            clipboardBridge.applyRemoteClipboardToSystem(item)
            _lastSyncedClipSummary.value =
                "Incoming ${item.mime} (${item.bytes.size} B, sha256=${item.sha256Hex.take(12)}…)"
        },
    )

    private val _linuxCapabilities = MutableStateFlow(
        environmentProbe.probe(CamDroidConstants.DEFAULT_V4L2_DEVICE),
    )
    val linuxCapabilities: StateFlow<LinuxSystemCapabilities> = _linuxCapabilities.asStateFlow()

    private val _pairingQrUri = MutableStateFlow("")
    val pairingQrUri: StateFlow<String> = _pairingQrUri.asStateFlow()

    private val _lastSyncedClipSummary = MutableStateFlow("No clipboard items synced yet")
    val lastSyncedClipSummary: StateFlow<String> = _lastSyncedClipSummary.asStateFlow()

    private val _statusBanner = MutableStateFlow("Ready — ec0-engine Continuity Hub active")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _autoSyncClipboard = MutableStateFlow(true)
    val autoSyncClipboard: StateFlow<Boolean> = _autoSyncClipboard.asStateFlow()

    private val _forwardNotificationsToDesktop = MutableStateFlow(true)
    val forwardNotificationsToDesktop: StateFlow<Boolean> = _forwardNotificationsToDesktop.asStateFlow()

    val discoveredPeers: StateFlow<List<PeerInfo>> get() = node.discoveredPeers
    val pinnedPeers: StateFlow<List<PinnedPeer>> get() = node.pinnedPeers
    val notifications: StateFlow<List<NotificationPostInfo>> get() = node.notifications
    val peerPresence: StateFlow<Map<String, PresenceBeaconInfo>> get() = node.peerPresence
    val cameraStatus: StateFlow<CameraStatusInfo> get() = node.cameraStatus

    private var clipboardWatchJob: Job? = null
    private var notifForwardJob: Job? = null
    private val forwardedNotifIds = HashSet<String>()

    fun start(startMdns: Boolean = true) {
        node.startServer(startMdns = startMdns)
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
        _pairingQrUri.value = qr.toUri()
    }

    fun setAutoSyncClipboard(enabled: Boolean) {
        _autoSyncClipboard.value = enabled
    }

    fun setForwardNotificationsToDesktop(enabled: Boolean) {
        _forwardNotificationsToDesktop.value = enabled
    }

    /**
     * Pairs with a phone by accepting its `ec0://pair?v=1...` URI and opening an
     * authenticated X25519+Ed25519+ChaCha20-Poly1305 session.
     */
    fun pairWithQrUri(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val session = node.pairAndConnectWithQrUri(uri.trim())
                val sas = EngineIdentity.verificationBadge(localIdentity.publicKeyRaw, session.peerPublicKeyRaw)
                _statusBanner.value = "Connected & verified peer ${session.peerDeviceId.take(12)}… (SAS: $sas)"
            } catch (e: Exception) {
                _statusBanner.value = "Pairing failed: ${e.message}"
            }
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

    fun pushTextClipboard(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val item = ClipboardItem.text(text)
            clipboardBridge.applyRemoteClipboardToSystem(item)
            broadcastClipboardItem(item)
        }
    }

    private fun broadcastClipboardItem(item: ClipboardItem) {
        val targets = targetPeerDeviceIds()
        for (peerId in targets) {
            node.sendOrQueueClipboard(peerId, item)
        }
        _lastSyncedClipSummary.value =
            "Outgoing ${item.mime} (${item.bytes.size} B, sha256=${item.sha256Hex.take(12)}…)"
    }

    /**
     * Sends an inline quick-reply (`notif_reply`) to an Android notification.
     */
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

    /**
     * Dismisses an Android notification locally and sends `notif.dismiss.cmd` to the phone.
     */
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

    /**
     * Starts a 1-click `scrcpy` screen mirroring session after verifying
     * `adb connect <host>:<port>` (Amendment 5).
     */
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

    /**
     * Starts Continuity Camera using either `CamDroid` native (`<host>:4747`) or
     * `scrcpy --video-source=camera --v4l2-sink=/dev/video10`.
     */
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
            val status = node.cameraCoordinator.startCameraStream(
                host = host,
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
                ?: "Continuity Camera ${status.state.wireName} (${status.backend.wireName} -> ${status.v4l2DevicePath})"
        }
    }

    fun sendCameraControl(command: CamDroidControlCommand) {
        scope.launch(Dispatchers.IO) {
            val activeSession = targetPeerDeviceIds().firstNotNullOfOrNull { node.activeSessionFor(it) }
            node.cameraCoordinator.sendControlCommand(command, activeSession)
        }
    }

    fun stopContinuityCamera() {
        scope.launch(Dispatchers.IO) {
            val activeSession = targetPeerDeviceIds().firstNotNullOfOrNull { node.activeSessionFor(it) }
            node.cameraCoordinator.stopCameraStream(activeSession)
            _statusBanner.value = "Continuity Camera stopped"
        }
    }

    /**
     * Installs Freedesktop, KDE Dolphin, and GNOME Nautilus "AirDrop" context-menu
     * integrations under `~/.local/share`.
     */
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

    private fun targetPeerDeviceIds(): Set<String> {
        val pinned = node.pinnedPeers.value.map { it.deviceId.lowercase() }
        val connected = node.connectionStates.value.keys
        return (pinned + connected).toSet()
    }

    override fun close() {
        clipboardWatchJob?.cancel()
        notifForwardJob?.cancel()
        node.close()
    }
}
