/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unified ec0-engine node (`docs/engine/design.md`, `docs/engine/coloros-resilience.md`):
 *   - Integrates Ed25519 identity, QR/TOFU pairing, mDNS (`_ec0._tcp.local.`),
 *     4-tier EndpointResolver, authenticated RFC 6455 + ChaCha20-Poly1305 sessions,
 *     and all 6 continuity services (Clipboard, File/Folder Transfer, Input Share,
 *     Notification Relay, Presence Beacons, Scrcpy Mirror Coordinator).
 *   - Implements the ColorOS-resilient reconnect state machine (`DISCONNECTED ->
 *     DISCOVERING -> CONNECTING -> AUTHENTICATING -> CONNECTED -> BACKOFF`) and
 *     bounded OfflineOutboxQueue (`N = 64`, 1-hour TTL) with cross-session
 *     deduplicated `offline_flush` batching (Amendment 2).
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.EnvelopeSigner
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.crypto.PersistentPinStore
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.discovery.Ec0MdnsDiscovery
import ec0.core.engine.discovery.EndpointResolver
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.discovery.MulticastMdnsTransport
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardSyncPayload
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.FileOfferCommand
import ec0.core.engine.protocol.HandoffActivityInfo
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.services.CamDroidControlTransport
import ec0.core.engine.services.ClipboardPushOutcome
import ec0.core.engine.services.ClipboardService
import ec0.core.engine.services.ContinuityCameraCoordinator
import ec0.core.engine.services.ContinuityVirtualMicBridge
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.FileTransferService
import ec0.core.engine.services.HandoffAndProximityService
import ec0.core.engine.services.HotspotAndTelephonyService
import ec0.core.engine.services.InputEventSink
import ec0.core.engine.services.InputShareService
import ec0.core.engine.services.MediaSessionBridgeService
import ec0.core.engine.services.NotificationRelayService
import ec0.core.engine.services.PresenceService
import ec0.core.engine.services.ScrcpyMirrorCoordinator
import ec0.core.engine.services.SystemProcessLauncher
import ec0.core.engine.services.TcpCamDroidControlTransport
import ec0.core.engine.services.TransferProgressSnapshot
import ec0.core.engine.services.UniversalControlService
import ec0.core.engine.services.UsbAdbTunnelCoordinator
import ec0.core.engine.services.UsbTunnelStatus
import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectNodeCoordinator
import ec0.core.engine.smartconnect.SmartConnectPairedPeerRecord
import ec0.core.engine.smartconnect.protocol.SmartConnectPeerStatusSnapshot
import ec0.core.engine.transport.BulkStreamFrame
import ec0.core.engine.transport.Ec0Client
import ec0.core.engine.transport.Ec0Server
import ec0.core.engine.transport.Ec0Session
import ec0.core.engine.transport.Ec0SessionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class EngineConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    BACKOFF,
}

/**
 * Bounded per-peer FIFO queue (`maxCapacity = 64`, `ttlMs = 3_600_000L`) for signed
 * clipboard and notification envelopes generated while a paired peer is unreachable.
 */
class OfflineOutboxQueue(
    private val maxCapacity: Int = 64,
    private val ttlMs: Long = EngineConstants.OFFLINE_TTL_MS,
) {
    private val lock = ReentrantLock()
    private val queuesByPeer = HashMap<String, ArrayDeque<Ec0Envelope>>()
    private val offlineSeqByPeer = HashMap<String, AtomicLong>()

    fun nextOfflineSeq(peerDeviceId: String): Long = lock.withLock {
        offlineSeqByPeer.getOrPut(peerDeviceId.lowercase()) { AtomicLong(0L) }.incrementAndGet()
    }

    fun enqueue(peerDeviceId: String, envelope: Ec0Envelope, nowMs: Long) {
        lock.withLock {
            val q = queuesByPeer.getOrPut(peerDeviceId.lowercase()) { ArrayDeque() }
            pruneExpiredLocked(q, nowMs)
            while (q.size >= maxCapacity) {
                q.removeFirst()
            }
            q.addLast(envelope)
        }
    }

    fun drainFresh(peerDeviceId: String, nowMs: Long): List<Ec0Envelope> = lock.withLock {
        val q = queuesByPeer[peerDeviceId.lowercase()] ?: return emptyList()
        pruneExpiredLocked(q, nowMs)
        val items = q.toList()
        q.clear()
        items
    }

    fun pendingCount(peerDeviceId: String, nowMs: Long): Int = lock.withLock {
        val q = queuesByPeer[peerDeviceId.lowercase()] ?: return 0
        pruneExpiredLocked(q, nowMs)
        q.size
    }

    private fun pruneExpiredLocked(q: ArrayDeque<Ec0Envelope>, nowMs: Long) {
        val iter = q.iterator()
        while (iter.hasNext()) {
            val env = iter.next()
            val age = if (nowMs >= env.timestamp) nowMs - env.timestamp else env.timestamp - nowMs
            if (age > ttlMs) {
                iter.remove()
            }
        }
    }
}

class Ec0EngineNode(
    val localIdentity: Ed25519KeyPair,
    val displayName: String,
    val role: String = EngineConstants.ROLE_DESKTOP,
    val listenPort: Int = EngineConstants.PRIMARY_PORT,
    val downloadDir: File = Files.createTempDirectory("ec0-downloads").toFile(),
    val pinStore: PinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
    val offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
    val outboxQueue: OfflineOutboxQueue = OfflineOutboxQueue(),
    private val mdnsTransportFactory: (() -> MdnsDatagramTransport)? = null,
    private val processLauncher: ExternalProcessLauncher = SystemProcessLauncher,
    private val onRemoteClipboardApplied: (ClipboardItem) -> Unit = {},
    private val onInlineNotificationReply: (NotificationReplyCommand) -> Unit = {},
    private val onRemoteNotificationDismiss: (String) -> Unit = {},
    private val inputEventSink: InputEventSink = object : InputEventSink {
        override fun onKeyEvent(event: InputKeyEvent) = Unit
        override fun onPointerEvent(event: InputPointerEvent) = Unit
    },
    private val camDroidTransportFactory: () -> CamDroidControlTransport = { TcpCamDroidControlTransport() },
    private val onExecuteCameraCommand: ((CamDroidControlCommand) -> CameraStatusInfo?)? = null,
    private val onRemoteCursorHandoff: (CursorHandoffCommand) -> Unit = {},
    private val onRemoteHotspotCommand: (HotspotControlCommand) -> HotspotStatusInfo? = { null },
    private val onRemoteCallAction: (CallActionCommand) -> Boolean = { false },
    private val onRemoteMediaControl: (MediaControlCommand) -> MediaSessionInfo? = { null },
    /**
     * RFC 6455 PING heartbeat for BOTH roles' sessions. Never run this at 0:
     * both transports enforce a [sessionIoTimeoutMs] read timeout, and with a
     * silent peer (no clipboard/notification/beacon traffic) every session dies
     * at exactly that timeout — observed live as a ~60 s session churn where the
     * reconnect supervisor kept masking the outage by re-dialing.
     */
    private val sessionPingIntervalMs: Long = 5_000L,
    /** Read timeout for both roles' sessions; pings refresh it 10× per interval. */
    private val sessionIoTimeoutMs: Int = 50_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : Closeable {

    val pairingCoordinator = PairingCoordinator(
        identity = localIdentity,
        pinStore = pinStore,
    )

    val mdnsDiscovery = Ec0MdnsDiscovery(
        localPeer = PeerInfo(
            deviceId = localIdentity.deviceId,
            displayName = displayName,
            role = role,
            port = listenPort,
            addresses = MulticastMdnsTransport.detectLocalIpv4Addresses().ifEmpty { listOf("127.0.0.1") },
        ),
        transportFactory = mdnsTransportFactory ?: { MulticastMdnsTransport() },
        clockMs = clockMs,
    )

    val endpointResolver = EndpointResolver(
        mdnsDiscovery = mdnsDiscovery,
        clockMs = clockMs,
    )

    val clipboardService = ClipboardService(
        onRemoteClipboardApplied = onRemoteClipboardApplied,
    )

    val fileTransferService = FileTransferService(
        downloadDir = downloadDir,
    )

    val inputShareService = InputShareService(
        sink = inputEventSink,
        // Walled-garden capability gate (audit H1): input injection is scoped to
        // peers whose VERIFIED presence beacon advertises an input capability.
        // The lambda defers the read to dispatch time (presenceService is
        // constructed below) and allows peers that have not sent a beacon yet.
        capabilityGate = { peerDeviceId ->
            val beacon = presenceService.peerPresence.value[peerDeviceId]
            beacon == null ||
                EngineCapability.INPUT in beacon.capabilities ||
                EngineCapability.REMOTE_INPUT in beacon.capabilities
        },
    )

    val notificationRelayService = NotificationRelayService(
        onInlineReplyReceived = onInlineNotificationReply,
        onRemoteDismissReceived = onRemoteNotificationDismiss,
    )

    val presenceService = PresenceService()

    val scrcpyCoordinator = ScrcpyMirrorCoordinator(
        launcher = processLauncher,
    )

    val cameraCoordinator = ContinuityCameraCoordinator(
        launcher = processLauncher,
        camDroidTransportFactory = camDroidTransportFactory,
        onExecuteCameraCommand = onExecuteCameraCommand,
    )

    val universalControlService = UniversalControlService(
        onRemoteCursorHandoff = onRemoteCursorHandoff,
    )

    val hotspotAndTelephonyService = HotspotAndTelephonyService(
        launcher = processLauncher,
        onRemoteHotspotCommand = onRemoteHotspotCommand,
        onRemoteCallAction = onRemoteCallAction,
    )

    val mediaSessionBridgeService = MediaSessionBridgeService(
        launcher = processLauncher,
        onRemoteMediaControl = onRemoteMediaControl,
    )

    val usbAdbTunnelCoordinator = UsbAdbTunnelCoordinator(
        launcher = processLauncher,
    )

    val virtualMicBridge = ContinuityVirtualMicBridge(
        launcher = processLauncher,
    )

    val handoffAndProximityService = HandoffAndProximityService(
        launcher = processLauncher,
    )

    // Lazy: constructing SmartConnectNodeCoordinator generates an RSA-2048 identity and
    // touches disk. Phone-side nodes (ec0↔ec0 only, Moto parity descoped) must not pay
    // that cost at startup — first touch of the coordinator is the desktop SC server start.
    val smartConnectCoordinator: SmartConnectNodeCoordinator by lazy {
        SmartConnectNodeCoordinator(
            storageDir = if (downloadDir.name.startsWith("ec0-downloads")) {
                File(downloadDir, "smartconnect-${localIdentity.deviceId.take(12)}")
            } else {
                File(
                    ec0.core.engine.crypto.EncryptedIdentityStore.defaultConfigDir(),
                    "smartconnect-${localIdentity.deviceId.take(12)}"
                )
            },
            initialDeviceName = displayName,
            wsPort = SmartConnectConstants.DEFAULT_WS_PORT,
            localAddressesProvider = {
                MulticastMdnsTransport.detectLocalIpv4Addresses().ifEmpty { listOf("127.0.0.1") }
            },
            onMdnsRefreshRequested = { mdnsPacket ->
                mdnsDiscovery.sendRawPacket(mdnsPacket)
            },
        )
    }

    init {
        // The readyFor-peer listener registers eagerly (a cheap closure) so discovery-driven
        // reconnect behaves exactly as with an eager coordinator — but the closure only
        // touches the lazy coordinator when an _readyformdns peer actually appears.
        mdnsDiscovery.addReadyForPeerListener { rfPeer ->
            smartConnectCoordinator.onReadyForMdnsPeerDiscovered(rfPeer, clockMs())
        }
    }

    private val client = Ec0Client(
        localIdentity = localIdentity,
        localAlias = displayName,
        pairingCoordinator = pairingCoordinator,
        endpointResolver = endpointResolver,
        offlineDeduplicator = offlineDeduplicator,
        ioTimeoutMs = sessionIoTimeoutMs,
        pingIntervalMs = sessionPingIntervalMs,
        nowMs = clockMs,
        onEndpointLearned = { deviceId, host, port ->
            (pinStore as? PersistentPinStore)?.recordKnownEndpoint(deviceId, host, port, clockMs())
        },
    )

    private val server = Ec0Server(
        localIdentity = localIdentity,
        localAlias = displayName,
        pairingCoordinator = pairingCoordinator,
        bindPort = listenPort,
        offlineDeduplicator = offlineDeduplicator,
        ioTimeoutMs = sessionIoTimeoutMs,
        pingIntervalMs = sessionPingIntervalMs,
        nowMs = clockMs,
        onSessionAuthenticated = { session ->
            engineLog.info(
                "Session authenticated: peer=${session.peerAlias} id=${session.peerDeviceId}",
            )
            attachAuthenticatedSession(session)
        },
        // Handshake rejections were invisible before — every server-side failure
        // (bad pairing proof, timeout, protocol error) must say why.
        onHandshakeError = { t ->
            engineLog.warning(
                "Server handshake failed: ${t.javaClass.simpleName}: ${t.message}",
            )
        },
    )

    private val engineLog = java.util.logging.Logger.getLogger("ec0.engine.node")

    /**
     * Walled-garden dispatch hook (audit H2): lets the host application route
     * reply-category envelopes (e.g. B6 VFS `vfs_dir`/`vfs_chunk`) that no core
     * service owns, WITHOUT widening the engine's own attack surface — the
     * interceptor runs only after the caller has applied its own rate limits
     * and containment checks. First interceptor returning `true` consumes the
     * envelope. Registered by the desktop UI bridge for the VFS reply path.
     */
    @Volatile
    var controlEnvelopeInterceptor: ((peerDeviceId: String, envelope: Ec0Envelope) -> Boolean)? = null

    private val sessionsByPeer = ConcurrentHashMap<String, Ec0Session>()

    init {
        // Resolver Tier 2 hydration: endpoints persist in the pin store precisely so a
        // restarted node (in-memory resolver lost) can still dial trusted peers.
        (pinStore as? PersistentPinStore)?.allKnownEndpoints()?.forEach { (deviceId, endpoint) ->
            endpointResolver.recordSuccess(deviceId, endpoint.host, endpoint.port, endpoint.updatedAtMs)
        }
    }

    /**
     * Last IP each peer authenticated from — captured on the wire in
     * [attachAuthenticatedSession]. Powers the reconnect ladder's last-known tier when
     * mDNS multicast is filtered (hotspot client isolation drops it routinely).
     */
    private val lastKnownPeerIps = ConcurrentHashMap<String, String>()
    private val batchSeqByPeer = ConcurrentHashMap<String, AtomicLong>()
    private val sessionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ec0-node-session-${localIdentity.deviceId.take(8)}").apply { isDaemon = true }
    }
    private val stateLock = ReentrantLock()
    private val closed = AtomicBoolean(false)

    private val _connectionStates = MutableStateFlow<Map<String, EngineConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, EngineConnectionState>> = _connectionStates.asStateFlow()

    private val _pinnedPeers = MutableStateFlow<List<PinnedPeer>>(emptyList())
    val pinnedPeers: StateFlow<List<PinnedPeer>> = _pinnedPeers.asStateFlow()

    val discoveredPeers: StateFlow<List<PeerInfo>> get() = mdnsDiscovery.peers
    val discoveredCamDroidServices: StateFlow<List<DiscoveredCamDroidService>> get() = mdnsDiscovery.camDroidServices
    val discoveredReadyForPeers: StateFlow<List<DiscoveredReadyForPeer>> get() = mdnsDiscovery.readyForPeers
    val smartConnectPairedPeers: StateFlow<List<SmartConnectPairedPeerRecord>> get() = smartConnectCoordinator.pairedPeersFlow
    val smartConnectPeerStatus: StateFlow<Map<String, SmartConnectPeerStatusSnapshot>> get() = smartConnectCoordinator.peerStatusFlow
    val transfers: StateFlow<Map<String, TransferProgressSnapshot>> get() = fileTransferService.transfers
    val notifications: StateFlow<List<NotificationPostInfo>> get() = notificationRelayService.notifications
    val peerPresence: StateFlow<Map<String, PresenceBeaconInfo>> get() = presenceService.peerPresence
    val cameraStatus: StateFlow<CameraStatusInfo> get() = cameraCoordinator.status
    val hotspotStatus: StateFlow<HotspotStatusInfo> get() = hotspotAndTelephonyService.hotspotStatus
    val activeCalls: StateFlow<List<CallStateInfo>> get() = hotspotAndTelephonyService.activeCalls
    val remoteMediaSession: StateFlow<MediaSessionInfo?> get() = mediaSessionBridgeService.remoteMediaSession
    val usbTunnelStatus: StateFlow<UsbTunnelStatus?> get() = usbAdbTunnelCoordinator.activeTunnel
    val remoteHandoffActivity: StateFlow<HandoffActivityInfo?> get() = handoffAndProximityService.remoteHandoffActivity

    val boundServerPort: Int
        get() = server.boundPort

    fun startServer(startMdns: Boolean = false) {
        server.start()
        mdnsDiscovery.localPeer = mdnsDiscovery.localPeer.copy(port = server.boundPort)
        if (startMdns) {
            mdnsDiscovery.start()
        }
    }

    fun startSmartConnectServer(announceMdns: Boolean = false): Int {
        val port = smartConnectCoordinator.start()
        if (announceMdns) {
            mdnsDiscovery.sendRawPacket(smartConnectCoordinator.buildReadyForMdnsAnnouncement(clockMs()))
        }
        return port
    }

    /**
     * Generates a single-use QR pairing payload for this node's active listener port.
     */
    fun generatePairingQr(
        ips: List<String> = listOf("127.0.0.1"),
    ): PairingQrPayload = pairingCoordinator.generateQrPayload(
        alias = displayName,
        port = server.boundPort,
        ips = ips,
        nowMs = clockMs(),
    )

    /**
     * Scans a peer's `ec0://pair?...` URI, pins the peer in [pinStore], and connects immediately.
     */
    fun pairAndConnectWithQrUri(uri: String): Ec0Session {
        val qrPayload = pairingCoordinator.acceptScannedQrUri(uri, nowMs = clockMs())
        refreshPinnedPeers()
        return connectToPeer(qrPayload.deviceId, qrPayload = qrPayload)
    }

    /**
     * Connects to [peerDeviceId] through the state machine (`DISCOVERING -> CONNECTING ->
     * AUTHENTICATING -> CONNECTED` or `BACKOFF` on failure).
     */
    fun connectToPeer(
        peerDeviceId: String,
        qrPayload: PairingQrPayload? = null,
        explicitHost: String? = null,
        explicitPort: Int? = null,
    ): Ec0Session {
        setConnectionState(peerDeviceId, EngineConnectionState.DISCOVERING)
        setConnectionState(peerDeviceId, EngineConnectionState.CONNECTING)
        return try {
            setConnectionState(peerDeviceId, EngineConnectionState.AUTHENTICATING)
            val session = client.connectToPeer(
                peerDeviceId = peerDeviceId,
                qrPayload = qrPayload,
                explicitHost = explicitHost,
                explicitPort = explicitPort,
                includeGatewayFallback = false,
            )
            attachAuthenticatedSession(session)
            session
        } catch (e: Exception) {
            setConnectionState(peerDeviceId, EngineConnectionState.BACKOFF)
            throw e
        }
    }

    /**
     * Reconnect ladder for trusted peers: normal mDNS resolution first, then — when
     * multicast is filtered and no candidates exist — blind dials to the peer's
     * last-known IP across the standard port tier (both ec0 ends bind the tier since
     * the port-walk change, so `ip × DEFAULT_PORT_LIST` reaches a restarted peer even
     * with zero mDNS). Testable via the injected [fallbackPorts].
     */
    fun reconnectToPeer(
        peerDeviceId: String,
        fallbackHosts: List<String> = listOfNotNull(
            lastKnownPeerIps[peerDeviceId.lowercase()],
            endpointResolver.lastKnownFor(peerDeviceId)?.host,
        ).distinct(),
        fallbackPorts: List<Int> = EngineConstants.DEFAULT_PORT_LIST,
    ): Ec0Session {
        return try {
            connectToPeer(peerDeviceId)
        } catch (discoveryFailed: Exception) {
            var lastError = discoveryFailed
            for (host in fallbackHosts) {
                for (port in fallbackPorts) {
                    try {
                        return connectToPeer(
                            peerDeviceId,
                            explicitHost = host,
                            explicitPort = port,
                        )
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            setConnectionState(peerDeviceId, EngineConnectionState.BACKOFF)
            throw lastError
        }
    }

    fun activeSessionFor(peerDeviceId: String): Ec0Session? =
        sessionsByPeer[peerDeviceId.lowercase()]?.takeIf { !it.isClosed }

    fun disconnectPeer(peerDeviceId: String) {
        val removed = sessionsByPeer.remove(peerDeviceId.lowercase())
        removed?.close()
        setConnectionState(peerDeviceId, EngineConnectionState.DISCONNECTED)
    }

    fun unpairPeer(peerDeviceId: String): Boolean {
        disconnectPeer(peerDeviceId)
        val removed = pinStore.unpair(peerDeviceId.lowercase())
        refreshPinnedPeers()
        return removed
    }

    /**
     * Sends a clipboard item immediately if connected to [peerDeviceId], or signs and
     * queues it in [outboxQueue] if the peer is currently offline (for inline items).
     */
    fun sendOrQueueClipboard(
        peerDeviceId: String,
        item: ClipboardItem,
        timestampMs: Long = clockMs(),
    ): ClipboardPushOutcome {
        val session = activeSessionFor(peerDeviceId)
        if (session != null) {
            return clipboardService.pushLocalClipboard(item, session, timestampMs)
        }
        // Offline outbox queuing for inline items
        return clipboardService.pushLocalClipboard(
            item = item,
            sendControl = { bodyJson ->
                val seq = outboxQueue.nextOfflineSeq(peerDeviceId)
                val signed = EnvelopeSigner.signEnvelope(
                    identity = localIdentity,
                    msgType = Ec0EnvelopeCodec.TYPE_CMD,
                    msgBody = Ec0EnvelopeCodec.buildCmdBody(
                        cmdType = ClipboardSyncPayload.CMD_CLIPBOARD_SYNC,
                        cmdBody = bodyJson,
                    ),
                    category = ClipboardSyncPayload.CATEGORY_CLIPBOARD,
                    timestampMs = timestampMs,
                    seq = seq,
                )
                outboxQueue.enqueue(peerDeviceId, signed, timestampMs)
            },
        )
    }

    /**
     * Sends a notification post immediately if connected to [peerDeviceId], or signs
     * and queues it in [outboxQueue] if the peer is currently offline.
     */
    fun sendOrQueueNotification(
        peerDeviceId: String,
        notif: NotificationPostInfo,
    ) {
        val session = activeSessionFor(peerDeviceId)
        if (session != null) {
            notificationRelayService.postNotification(notif, session)
            return
        }
        val seq = outboxQueue.nextOfflineSeq(peerDeviceId)
        val signed = EnvelopeSigner.signEnvelope(
            identity = localIdentity,
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = Ec0EnvelopeCodec.buildInfoBody(
                infoType = NotificationProtocol.INFO_NOTIF_POST,
                infoBody = notif.toJsonObject(),
            ),
            category = NotificationProtocol.CATEGORY_NOTIF,
            timestampMs = notif.timestampMs,
            seq = seq,
        )
        outboxQueue.enqueue(peerDeviceId, signed, clockMs())
    }

    /**
     * Initiates a file or directory transfer to an actively connected [peerDeviceId].
     */
    fun sendFileOrDirectory(
        peerDeviceId: String,
        fileOrDir: File,
        transferId: String = java.util.UUID.randomUUID().toString(),
    ): FileOfferCommand {
        val session = activeSessionFor(peerDeviceId)
            ?: throw IllegalStateException("Peer $peerDeviceId is not connected")
        val offer = fileTransferService.createOfferFromDirectoryOrFile(fileOrDir, transferId = transferId)
        fileTransferService.sendOffer(offer, sessionFileTransport(session))
        return offer
    }

    /**
     * Broadcasts a signed battery/presence beacon to [peerDeviceId].
     */
    fun sendPresenceBeacon(peerDeviceId: String, beacon: PresenceBeaconInfo) {
        val session = activeSessionFor(peerDeviceId) ?: return
        presenceService.broadcastBeacon(beacon, session, clockMs())
    }

    /**
     * Flushes any pending offline envelopes for [peerDeviceId] over [session] using
     * the normative `offline_flush` batch command (`docs/engine/design.md` §4.4, Amendment 2).
     */
    fun flushOfflineOutbox(peerDeviceId: String, session: Ec0Session): Int {
        val items = outboxQueue.drainFresh(peerDeviceId, clockMs())
        if (items.isEmpty()) return 0

        val batchSeq = batchSeqByPeer.getOrPut(session.sessionIdHex) { AtomicLong(0L) }.incrementAndGet()
        val batchBody = buildJsonObject {
            put("batch_seq", batchSeq)
            put(
                "items",
                buildJsonArray {
                    for (env in items) {
                        add(Ec0EnvelopeCodec.json.encodeToJsonElement(Ec0Envelope.serializer(), env))
                    }
                },
            )
        }
        session.sendCmd(
            cmdType = CMD_OFFLINE_FLUSH,
            cmdBody = batchBody,
            category = CATEGORY_SYS,
            timestampMs = clockMs(),
        )
        return items.size
    }

    private fun attachAuthenticatedSession(session: Ec0Session) {
        val peerId = session.peerDeviceId.lowercase()
        val previous = sessionsByPeer.put(peerId, session)
        if (previous != null && previous !== session) {
            runCatching { previous.close() }
        }
        session.peerRemoteIp?.let { remoteIp ->
            lastKnownPeerIps[peerId] = remoteIp
            // The peer dialed US, so our client-side resolver never learned its endpoint —
            // record it for the reconnect ladder (port is walked across the standard tier).
            (pinStore as? PersistentPinStore)?.recordKnownEndpoint(
                peerId,
                remoteIp,
                EngineConstants.PRIMARY_PORT,
                clockMs(),
            )
        }
        refreshPinnedPeers()
        setConnectionState(peerId, EngineConnectionState.CONNECTED)

        // Start background receive loop BEFORE flushing outbox
        sessionExecutor.execute {
            runSessionReceiveLoop(session)
        }

        // Flush any queued offline clipboard/notification items
        runCatching { flushOfflineOutbox(peerId, session) }
    }

    private fun runSessionReceiveLoop(session: Ec0Session) {
        val peerId = session.peerDeviceId.lowercase()
        val fileTransport = sessionFileTransport(session)
        try {
            while (!closed.get() && !session.isClosed) {
                val event = session.receiveEvent(clockMs) ?: break
                when (event) {
                    is Ec0SessionEvent.ControlEnvelope -> {
                        routeControlEnvelope(peerId, event.envelope, session, fileTransport)
                    }
                    is Ec0SessionEvent.BulkChunk -> {
                        if (!clipboardService.handleBulkChunk(event.frame)) {
                            fileTransferService.handleBulkChunk(event.frame, fileTransport)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Connection dropped or closed
        } finally {
            // Abandoned sessions MUST close their socket: the loop exits on a
            // read timeout or EOF while the TCP connection lingers, leaking a
            // CLOSE_WAIT socket on both ends per churn cycle (observed piling
            // up on the phone's /proc/net/tcp6).
            runCatching { session.close() }
            if (sessionsByPeer.remove(peerId, session)) {
                setConnectionState(peerId, EngineConnectionState.DISCONNECTED)
            }
        }
    }

    internal fun routeControlEnvelope(
        peerDeviceId: String,
        envelope: Ec0Envelope,
        session: Ec0Session,
        fileTransport: FileTransferService.OutboundTransport = sessionFileTransport(session),
    ) {
        if (envelope.bodyType == CMD_OFFLINE_FLUSH) {
            val innerObj = envelope.innerBodyObject ?: return
            val batchSeq = (innerObj["batch_seq"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Missing 'batch_seq' in offline_flush")
            val itemsArr = innerObj["items"] as? JsonArray
                ?: throw IllegalArgumentException("Missing 'items' array in offline_flush")
            val innerEnvelopes = itemsArr.map { el ->
                Ec0EnvelopeCodec.json.decodeFromJsonElement(Ec0Envelope.serializer(), el)
            }
            val accepted = session.envelopeProcessor.verifyOfflineBatch(
                batchSeq = batchSeq,
                innerEnvelopes = innerEnvelopes,
                nowMs = clockMs(),
            )
            for (inner in accepted) {
                routeControlEnvelope(peerDeviceId, inner, session, fileTransport)
            }
            return
        }

        // Host-registered interceptor (VFS replies) — runs first, consumes the envelope.
        controlEnvelopeInterceptor?.let { interceptor ->
            if (interceptor(peerDeviceId, envelope)) return
        }

        // Walled-garden unpair receiver (audit H3): the wire `unpair` command is
        // authorized for the sender's OWN pairing only. A device_id field, when
        // present, must match the authenticated session's peer id — peer A can
        // never unpair peer B. On success: session closed + engine pin removed.
        if (envelope.bodyType == EngineConstants.CMD_UNPAIR) {
            handleUnpairCommand(peerDeviceId, envelope)
            return
        }

        if (clipboardService.handleControlEnvelope(envelope)) return
        if (fileTransferService.handleControlEnvelope(envelope, fileTransport)) return
        if (inputShareService.handleControlEnvelope(peerDeviceId, envelope)) return
        if (notificationRelayService.handleControlEnvelope(envelope)) return
        if (presenceService.handleControlEnvelope(peerDeviceId, envelope)) return
        if (scrcpyCoordinator.handleControlEnvelope(envelope)) return
        if (cameraCoordinator.handleControlEnvelope(envelope)) return
        if (universalControlService.handleControlEnvelope(envelope)) return
        if (hotspotAndTelephonyService.handleControlEnvelope(envelope)) return
        if (mediaSessionBridgeService.handleControlEnvelope(envelope)) return
        if (handoffAndProximityService.handleControlEnvelope(envelope)) return
    }

    /**
     * Handles a verified `unpair` command (category "system") from [senderDeviceId].
     * Authorization: the sender may only unpair itself; a `device_id` body field
     * that names a DIFFERENT peer is rejected (logged, ignored). Completeness:
     * drops the session and removes the sender's engine pin + recorded endpoint.
     * LocalSend trusted fingerprints and VFS root grants are separate registries
     * (see docs/security-audit.md — per-peer mapping is a tracked follow-up).
     */
    private fun handleUnpairCommand(senderDeviceId: String, envelope: Ec0Envelope) {
        val body = envelope.innerBodyObject
        if (body != null) {
            val claimedDeviceId = (body["device_id"] as? JsonPrimitive)?.content
            if (claimedDeviceId != null && !claimedDeviceId.equals(senderDeviceId, ignoreCase = true)) {
                engineLog.warning(
                    "Rejected unpair from $senderDeviceId: device_id field names a different peer",
                )
                return
            }
        }
        val removed = unpairPeer(senderDeviceId)
        if (removed) {
            engineLog.info("Peer $senderDeviceId unpaired itself (wire unpair command)")
        }
    }

    private fun sessionFileTransport(session: Ec0Session): FileTransferService.OutboundTransport =
        object : FileTransferService.OutboundTransport {
            override fun sendCmd(cmdType: String, cmdBody: JsonObject) {
                session.sendCmd(
                    cmdType = cmdType,
                    cmdBody = cmdBody,
                    category = "file",
                    timestampMs = clockMs(),
                )
            }

            override fun sendInfo(infoType: String, infoBody: JsonObject) {
                session.sendInfo(
                    infoType = infoType,
                    infoBody = infoBody,
                    category = "file",
                    timestampMs = clockMs(),
                )
            }

            override fun sendChunk(frame: BulkStreamFrame) {
                session.sendBulkChunk(frame)
            }
        }

    private fun refreshPinnedPeers() {
        _pinnedPeers.value = pinStore.all()
    }

    private fun setConnectionState(peerDeviceId: String, state: EngineConnectionState) {
        stateLock.withLock {
            val next = LinkedHashMap(_connectionStates.value)
            next[peerDeviceId.lowercase()] = state
            _connectionStates.value = next
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (s in sessionsByPeer.values) {
            runCatching { s.close() }
        }
        sessionsByPeer.clear()
        runCatching { server.close() }
        runCatching { mdnsDiscovery.close() }
        runCatching { scrcpyCoordinator.close() }
        runCatching { cameraCoordinator.close() }
        runCatching { smartConnectCoordinator.close() }
        sessionExecutor.shutdownNow()
    }

    companion object {
        const val CATEGORY_SYS: String = "sys"
        const val CMD_OFFLINE_FLUSH: String = "offline_flush"
    }
}
