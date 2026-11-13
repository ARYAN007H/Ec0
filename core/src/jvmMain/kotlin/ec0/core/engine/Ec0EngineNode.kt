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
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.discovery.Ec0MdnsDiscovery
import ec0.core.engine.discovery.EndpointResolver
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.discovery.MulticastMdnsTransport
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardSyncPayload
import ec0.core.engine.protocol.FileOfferCommand
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.services.ClipboardPushOutcome
import ec0.core.engine.services.ClipboardService
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.FileTransferService
import ec0.core.engine.services.InputEventSink
import ec0.core.engine.services.InputShareService
import ec0.core.engine.services.NotificationRelayService
import ec0.core.engine.services.PresenceService
import ec0.core.engine.services.ScrcpyMirrorCoordinator
import ec0.core.engine.services.SystemProcessLauncher
import ec0.core.engine.services.TransferProgressSnapshot
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
    )

    val notificationRelayService = NotificationRelayService(
        onInlineReplyReceived = onInlineNotificationReply,
        onRemoteDismissReceived = onRemoteNotificationDismiss,
    )

    val presenceService = PresenceService()

    val scrcpyCoordinator = ScrcpyMirrorCoordinator(
        launcher = processLauncher,
    )

    private val client = Ec0Client(
        localIdentity = localIdentity,
        localAlias = displayName,
        pairingCoordinator = pairingCoordinator,
        endpointResolver = endpointResolver,
        offlineDeduplicator = offlineDeduplicator,
        pingIntervalMs = 0L,
        nowMs = clockMs,
    )

    private val server = Ec0Server(
        localIdentity = localIdentity,
        localAlias = displayName,
        pairingCoordinator = pairingCoordinator,
        bindPort = listenPort,
        offlineDeduplicator = offlineDeduplicator,
        pingIntervalMs = 0L,
        nowMs = clockMs,
        onSessionAuthenticated = { session -> attachAuthenticatedSession(session) },
    )

    private val sessionsByPeer = ConcurrentHashMap<String, Ec0Session>()
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
    val transfers: StateFlow<Map<String, TransferProgressSnapshot>> get() = fileTransferService.transfers
    val notifications: StateFlow<List<NotificationPostInfo>> get() = notificationRelayService.notifications
    val peerPresence: StateFlow<Map<String, PresenceBeaconInfo>> get() = presenceService.peerPresence

    val boundServerPort: Int
        get() = server.boundPort

    fun startServer(startMdns: Boolean = false) {
        server.start()
        mdnsDiscovery.localPeer = mdnsDiscovery.localPeer.copy(port = server.boundPort)
        if (startMdns) {
            mdnsDiscovery.start()
        }
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

    fun activeSessionFor(peerDeviceId: String): Ec0Session? =
        sessionsByPeer[peerDeviceId.lowercase()]?.takeIf { !it.isClosed }

    fun disconnectPeer(peerDeviceId: String) {
        val removed = sessionsByPeer.remove(peerDeviceId.lowercase())
        removed?.close()
        setConnectionState(peerDeviceId, EngineConnectionState.DISCONNECTED)
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

        if (clipboardService.handleControlEnvelope(envelope)) return
        if (fileTransferService.handleControlEnvelope(envelope, fileTransport)) return
        if (inputShareService.handleControlEnvelope(envelope)) return
        if (notificationRelayService.handleControlEnvelope(envelope)) return
        if (presenceService.handleControlEnvelope(peerDeviceId, envelope)) return
        if (scrcpyCoordinator.handleControlEnvelope(envelope)) return
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
        sessionExecutor.shutdownNow()
    }

    companion object {
        const val CATEGORY_SYS: String = "sys"
        const val CMD_OFFLINE_FLUSH: String = "offline_flush"
    }
}
