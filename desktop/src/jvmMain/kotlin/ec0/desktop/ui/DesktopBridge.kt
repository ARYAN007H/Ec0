/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop host for the shared UI (:ui). Thin adapter over AppState.
 */
package ec0.desktop.ui

import ec0.core.engine.EngineConnectionState
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.ui.models.Capabilities
import ec0.ui.models.NotificationItem
import ec0.ui.models.PairingQr
import ec0.ui.models.PeerLinkState
import ec0.ui.models.CapabilityTile
import ec0.ui.models.SettingsState
import ec0.ui.models.computeTileRegistry
import ec0.ui.models.SmartConnectPairingQr
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
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
    override val transfers: StateFlow<List<ec0.ui.models.TransferItem>> = appState.transfers
    override val settings: StateFlow<SettingsState> = appState.settings
    override val scanning: StateFlow<Boolean> = appState.scanning

    private val _smartConnectPairingQr: MutableStateFlow<SmartConnectPairingQr?> = MutableStateFlow(
        buildSmartConnectPairingQr()
    )
    override val smartConnectPairingQr: StateFlow<SmartConnectPairingQr?> =
        _smartConnectPairingQr.asStateFlow()

    override val pairingQr: StateFlow<ec0.ui.models.PairingQr?> =
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

    // ---- Dashboard (HOME tiles): live hub state, honest OFF defaults ----

    /** scrcpy's running state is a plain getter — poll it; 3 s is plenty for a tile. */
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

    override val peerLink: StateFlow<PeerLinkState?> =
        if (hub == null) {
            MutableStateFlow(null)
        } else {
            combine(hub.usbTunnelStatus, hub.node.connectionStates) { tunnel, states ->
                val engineConnected = states.values.any { it == EngineConnectionState.CONNECTED }
                when {
                    tunnel != null -> PeerLinkState(connected = true, transport = "USB tunnel")
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
                NotificationItem(it.notifId, it.appName, it.title, it.body, it.canReply)
            }
        }?.stateIn(bridgeScope, SharingStarted.Eagerly, emptyList()) ?: MutableStateFlow(emptyList())

    /** Mirror/camera target: the trusted peer's mDNS Wi-Fi host, else loopback via the USB tunnel. */
    private fun mirrorHost(): String {
        val h = hub ?: return "127.0.0.1"
        val pinned = h.pinnedPeers.value.map { it.deviceId }
        val peer = h.node.mdnsDiscovery.peers.value.firstOrNull { it.deviceId in pinned }
        return peer?.addresses?.firstOrNull() ?: "127.0.0.1"
    }

    override fun startScreenMirror() {
        hub?.startScreenMirror(mirrorHost())
    }

    override fun stopScreenMirror() {
        hub?.stopScreenMirror()
    }

    override fun startCamera() {
        hub?.startContinuityCamera(mirrorHost())
    }

    override fun stopCamera() {
        hub?.stopContinuityCamera()
    }

    override fun setAutoSyncClipboard(enabled: Boolean) {
        hub?.setAutoSyncClipboard(enabled)
    }

    override fun sendNotificationReply(id: String, text: String) {
        hub?.sendNotificationReply(id, text = text)
    }

    private val _v4l2Preflight = MutableStateFlow<String?>(null)
    override val v4l2PreflightText: StateFlow<String?> = _v4l2Preflight.asStateFlow()

    // B3 tile registry: peer capabilities ∩ desktop support (MIRROR, CAMERA,
    // FILE, CLIPBOARD, NOTIFICATION, INPUT) — BATTERY renders in the header.
    private val localTileSupport: Set<ec0.core.engine.EngineCapability> = setOf(
        ec0.core.engine.EngineCapability.MIRROR,
        ec0.core.engine.EngineCapability.CAMERA,
        ec0.core.engine.EngineCapability.FILE,
        ec0.core.engine.EngineCapability.CLIPBOARD,
        ec0.core.engine.EngineCapability.NOTIFICATION,
        ec0.core.engine.EngineCapability.INPUT,
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
        return exact ?: mirrorHost() // falls back to the live-session resolver
    }

    override fun startMirrorFor(deviceId: String) {
        val settings = appState.settings.value
        hub?.startScreenMirror(
            mirrorHostFor(deviceId),
            maxFps = settings.mirrorMaxFps,
            bitrateKbps = settings.mirrorBitrateMbps * 1_000,
        )
    }

    override fun startCameraFor(deviceId: String) {
        hub?.startContinuityCamera(mirrorHostFor(deviceId))
    }

    // B6 VFS browser — requests ride the live engine session; replies arrive
    // through vfsDesktopService.handleControlEnvelope (node dispatch = the
    // documented agent-0 one-liner handoff).
    private val vfsDesktopService = ec0.core.engine.services.VfsDesktopService(
        destinationDir = java.nio.file.Path.of(
            appState.settings.value.downloadDir.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/Downloads"),
        ),
    )

    override val vfsBrowseState: StateFlow<ec0.core.engine.services.VfsBrowseState?> =
        vfsDesktopService.browseState

    override fun vfsDownload(path: String) {
        val deviceId = hub?.peerPresence?.value?.keys?.firstOrNull { nodeId ->
            hub.node.activeSessionFor(nodeId) != null
        } ?: return
        val session = hub.node.activeSessionFor(deviceId) ?: return
        vfsDesktopService.download(
            sender = { cmdType, body ->
                session.sendCmd(cmdType = cmdType, cmdBody = body, category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS)
            },
            path = path,
        )
    }

    override fun vfsBrowse(path: String) {
        val deviceId = hub?.peerPresence?.value?.keys?.firstOrNull { nodeId ->
            hub.node.activeSessionFor(nodeId) != null
        } ?: return
        val session = hub.node.activeSessionFor(deviceId) ?: return
        vfsDesktopService.browse(
            sender = { cmdType, body ->
                session.sendCmd(cmdType = cmdType, cmdBody = body, category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS)
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
            _v4l2Preflight.value = "Running v4l2/scrcpy pre-flight…"
            val result = coordinator.v4l2Preflight()
            _v4l2Preflight.value = when (result.outcome) {
                ec0.core.engine.services.V4l2Outcome.READY ->
                    "v4l2loopback ready (/dev/video10) · scrcpy ${result.scrcpyVersionText ?: "?"}"
                ec0.core.engine.services.V4l2Outcome.NEEDS_PASSWORD ->
                    "Run: ${result.remediationCommand}"
                ec0.core.engine.services.V4l2Outcome.MODPROBE_FAILED ->
                    "modprobe failed. Run: ${result.remediationCommand}"
                ec0.core.engine.services.V4l2Outcome.SCRCPY_UNSUPPORTED ->
                    "scrcpy ${result.scrcpyVersionText ?: "not found"} — need >= 2.2 with --video-source=camera"
            }
        }
    }

    init {
        // The Smart Connect invite token expires after ~180 s — rotate and rebuild so the
        // rendered QR never goes stale mid-session.
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

    // Honest capability flags: the continuity service attaches via the tray
    // path; receiver + sync slices land in later PRs.
    override val capabilities: Capabilities = Capabilities(
        canReceive = true,
        clipboardSync = false,
        notifications = false,
        photoSync = false,
        remoteInput = false,
    )

    override fun startScan() = appState.startScan()
    override fun cancelTransfer(id: String) = appState.cancelTransfer(id)
    override fun updateSettings(transform: (SettingsState) -> SettingsState) =
        appState.updateSettings(transform)

    override val incomingRequest: StateFlow<ec0.ui.models.IncomingRequest?> = appState.incomingRequest

    override fun acceptIncoming(trust: Boolean) = appState.acceptIncoming(trust)

    override fun declineIncoming() = appState.declineIncoming()

    override fun sendFile(fileRef: Any, device: UiDevice) {
        if (fileRef is File) appState.sendFile(fileRef, device)
    }
}

