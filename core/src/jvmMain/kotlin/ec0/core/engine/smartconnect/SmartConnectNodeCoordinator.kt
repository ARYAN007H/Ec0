package ec0.core.engine.smartconnect

import ec0.core.engine.DiscoveredReadyForPeer
import ec0.core.engine.MdnsCodec
import ec0.core.engine.QrCodeMatrix
import ec0.core.engine.smartconnect.protocol.SmartConnectCapRegistry
import ec0.core.engine.smartconnect.protocol.SmartConnectDiscoveryInvitePayload
import ec0.core.engine.smartconnect.protocol.SmartConnectHandshakeCodec
import ec0.core.engine.smartconnect.protocol.SmartConnectPeerStatusSnapshot
import ec0.core.engine.smartconnect.protocol.SmartConnectStatusCodec
import ec0.core.engine.transport.PinnedTlsWebSocketListener
import ec0.core.engine.transport.PinnedTlsWebSocketSession
import ec0.core.engine.transport.PinnedTlsWebSocketTransport
import ec0.core.engine.transport.SealedDiscoveryFrameCodec
import ec0.core.engine.transport.SmartConnectTlsIdentity
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Persisted local Smart Connect PC identity (`smartconnect_identity.json`, mode `0600`).
 */
@Serializable
data class SmartConnectLocalIdentityRecord(
    val realDeviceIdBase64: String,
    val serialNumber: String,
    val deviceName: String
)

/**
 * Persisted Smart Connect paired phone record (`smartconnect_paired.json`, mode `0600`, §5.2).
 */
@Serializable
data class SmartConnectPairedPeerRecord(
    val realDeviceId: String,
    val deviceName: String,
    val certFingerprintSha256: String,
    val perPairKey: String,
    val phoneKey: String? = null,
    val lastIp: String = "",
    val lastPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val lastSeenMs: Long = 0L,
    val pairedAtMs: Long = 0L
)

@Serializable
private data class SmartConnectPairedStorePayload(
    val pairedPeers: List<SmartConnectPairedPeerRecord> = emptyList()
)

/**
 * Listener for Smart Connect service frames (`LocalDaemonIpcServer` / future `S-B`..`S-E` handlers).
 */
fun interface SmartConnectServiceFrameObserver {
    fun onServiceFrame(peerRealDeviceId: String?, envelope: SmartConnectEnvelope)
}

/**
 * Coordinator for Motorola Smart Connect Slice `S-A` (`docs/engine/smart-connect-design.md` §3-S-A & §4).
 *
 * Responsibilities in Slice `S-A`:
 * 1. Hosts single-port TLS WebSocket server (`PinnedTlsWebSocketTransport`) with `/readyfor/`
 *    (control channel) and `/readyfor/mdns` (sealed discovery channel).
 * 2. Manages persistent `realDeviceId`, rotating salted-hash `nonceId`, non-empty UUID `sn`,
 *    180s multi-use invite tokens, and `motorolardpconnection{...}` QR generation.
 * 3. Runs the 6-step `svc 0` handshake state machine (`cmd 0`/`3` Hello -> `cmd 0` Verify Reply ->
 *    immediate `svc 25 cmd 1` replace-whole cap map -> `cmd 1` `EXCHANGE_KEY` with both IDs set to
 *    `<pcRealId>` + 2s unprompted fallback timer -> persist paired record -> 5s post-pair discovery
 *    refresh).
 * 4. Handles `svc 25` device status (`cmd 1`), MD5-deduped phone wallpaper (`cmd 2`), and DND
 *    (`cmd 5`), plus connect-burst absorbers (`svc 1 cmd 31`, `svc 16 cmd 14`).
 * 5. Handles `/readyfor/mdns` sealed discovery triggers (`cmd 0`) and dial-back invites (`cmd 1`)
 *    without ever emitting `0x02` or `0x03` unpair-hazard frames.
 */
class SmartConnectNodeCoordinator(
    private val storageDir: File,
    initialDeviceName: String = "EC0-PC",
    private val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    private val localAddressesProvider: () -> List<String> = { listOf("127.0.0.1") },
    private val onMdnsRefreshRequested: ((ByteArray) -> Unit)? = null,
    private val unpromptedExchangeKeyDelayMs: Long = SmartConnectConstants.UNPROMPTED_EXCHANGE_KEY_DELAY_MS,
    private val postPairRefreshDelayMs: Long = SmartConnectConstants.POST_PAIR_DISCOVERY_REFRESH_DELAY_MS,
    initialCapMap: Map<String, Int> = SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP,
    private val trustConfirmationCallback: ((SmartConnectPairedPeerRecord) -> Boolean)? = null
) : AutoCloseable {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    val tlsIdentity: SmartConnectTlsIdentity = SmartConnectTlsIdentity.loadOrGenerate(
        storageDir = storageDir,
        commonName = initialDeviceName
    )

    val transport: PinnedTlsWebSocketTransport = PinnedTlsWebSocketTransport(
        tlsIdentity = tlsIdentity,
        maxFrameBytes = SmartConnectConstants.MAX_WS_FRAME_BYTES
    )

    val capRegistry: SmartConnectCapRegistry = SmartConnectCapRegistry(initialCapMap)
    val inviteTokenStore: SmartConnectInviteTokenStore = SmartConnectInviteTokenStore()

    private val identityFile = File(storageDir, "smartconnect_identity.json")
    private val pairedFile = File(storageDir, "smartconnect_paired.json")

    private var localIdentity: SmartConnectLocalIdentityRecord = loadOrCreateLocalIdentity(initialDeviceName)

    @Volatile
    var currentNonceIdBase64: String = SmartConnectIdentity.generateNonceIdBase64(localIdentity.realDeviceIdBase64)
        private set

    @Volatile
    var activeInviteToken: String = inviteTokenStore.issueToken(System.currentTimeMillis())
        private set

    private val pairedPeersByRealId = ConcurrentHashMap<String, SmartConnectPairedPeerRecord>()
    private val peerStatusByRealId = ConcurrentHashMap<String, SmartConnectPeerStatusSnapshot>()
    private val activeControlSessions = ConcurrentHashMap<String, SessionHandshakeContext>()
    private val serviceObservers = CopyOnWriteArrayList<SmartConnectServiceFrameObserver>()
    private val msgIdCounter = AtomicInteger(1)
    private val started = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ec0-smartconnect-coordinator").apply { isDaemon = true }
    }

    private val _pairedPeersFlow = MutableStateFlow<List<SmartConnectPairedPeerRecord>>(emptyList())
    val pairedPeersFlow: StateFlow<List<SmartConnectPairedPeerRecord>> = _pairedPeersFlow.asStateFlow()

    private val _peerStatusFlow = MutableStateFlow<Map<String, SmartConnectPeerStatusSnapshot>>(emptyMap())
    val peerStatusFlow: StateFlow<Map<String, SmartConnectPeerStatusSnapshot>> = _peerStatusFlow.asStateFlow()

    private val _connectedPeerIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeerIdsFlow: StateFlow<Set<String>> = _connectedPeerIdsFlow.asStateFlow()

    init {
        storageDir.mkdirs()
        loadPairedPeersFromDisk()
        transport.registerPathHandler(SmartConnectConstants.WS_CONTROL_PATH, ControlChannelListener())
        transport.registerPathHandler(SmartConnectConstants.WS_DISCOVERY_PATH, DiscoveryChannelListener())
    }

    val realDeviceIdBase64: String
        get() = localIdentity.realDeviceIdBase64

    val serialNumber: String
        get() = localIdentity.serialNumber

    val deviceName: String
        get() = localIdentity.deviceName

    val certFingerprintHex: String
        get() = tlsIdentity.fingerprintHex

    val boundPort: Int
        get() = transport.boundPort.takeIf { it > 0 } ?: wsPort

    /**
     * Starts the TLS WebSocket server on [wsPort] (or ephemeral port if `wsPort == 0`).
     */
    fun start(): Int {
        val actualPort = transport.startServer(wsPort)
        started.set(true)
        return actualPort
    }

    /**
     * Rotates the salted-hash `nonceId` and issues a fresh 180s invite token.
     */
    fun rotateNonceIdAndInviteToken(nowMs: Long = System.currentTimeMillis()): String {
        currentNonceIdBase64 = SmartConnectIdentity.generateNonceIdBase64(localIdentity.realDeviceIdBase64)
        activeInviteToken = inviteTokenStore.issueToken(nowMs)
        return currentNonceIdBase64
    }

    /**
     * Builds the `motorolardpconnection{"wsData":{...},"version":"3.0.0"}` QR payload string.
     */
    fun buildPairingQrPayload(nowMs: Long = System.currentTimeMillis()): String {
        val token = inviteTokenStore.issueToken(nowMs, activeInviteToken)
        activeInviteToken = token
        val ips = localAddressesProvider().ifEmpty { listOf("127.0.0.1") }
        return SmartConnectIdentity.buildQrPayloadString(
            ips = ips,
            wsPort = boundPort,
            nonceIdBase64 = currentNonceIdBase64,
            deviceName = localIdentity.deviceName,
            certFingerprintHex = tlsIdentity.fingerprintHex,
            invitation = token,
            timestampMs = nowMs
        )
    }

    /**
     * Encodes the current `motorolardpconnection{...}` QR payload into a [QrCodeMatrix].
     */
    fun buildPairingQrMatrix(nowMs: Long = System.currentTimeMillis()): QrCodeMatrix {
        val payload = buildPairingQrPayload(nowMs)
        return QrCodeMatrix.encodeText(payload)
    }

    /**
     * Builds the `_readyformdns._tcp.local.` mDNS announcement packet for the current `nonceId`.
     */
    fun buildReadyForMdnsAnnouncement(
        nowMs: Long = System.currentTimeMillis(),
        ttlSeconds: Long = 120L
    ): ByteArray {
        val ips = localAddressesProvider().ifEmpty { listOf("127.0.0.1") }
        return MdnsCodec.encodeReadyForAnnouncement(
            nonceIdBase64 = currentNonceIdBase64,
            deviceName = localIdentity.deviceName,
            port = boundPort,
            addresses = ips,
            certFingerprintHex = tlsIdentity.fingerprintHex,
            timestampMs = nowMs,
            type = SmartConnectConstants.PC_TYPE_LAPTOP,
            os = SmartConnectConstants.PC_OS_WINDOWS,
            appType = SmartConnectConstants.PC_APP_TYPE_DESKTOP,
            cap = SmartConnectConstants.PC_BASE_CAP,
            ttlSeconds = ttlSeconds
        )
    }

    /**
     * Called when an mDNS `_readyformdns._tcp.local.` packet from a phone is observed on the LAN.
     * If `peer.deviceId` (`nonceId`) matches a paired peer that is not currently connected, sends
     * a sealed `cmd=1` dial-back invite to `wss://<phoneIp>:<phonePort>/readyfor/mdns`.
     */
    fun onReadyForMdnsPeerDiscovered(
        peer: DiscoveredReadyForPeer,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (peer.isGoodbye || peer.addresses.isEmpty()) return false
        val matched = SmartConnectIdentity.matchNonceIdBase64ToPairedPeer(
            candidateNonceIdBase64 = peer.deviceId,
            pairedPeers = pairedPeersByRealId.values,
            realDeviceIdSelector = { it.realDeviceId }
        ) ?: return false

        // Already connected on control channel?
        if (_connectedPeerIdsFlow.value.contains(matched.realDeviceId)) {
            return false
        }

        val targetIp = peer.addresses.first()
        val targetPort = peer.port.takeIf { it in 1..65535 } ?: SmartConnectConstants.DEFAULT_WS_PORT
        val expectedFp = peer.fp.takeIf { it.length == 64 } ?: matched.certFingerprintSha256.takeIf { it.length == 64 }
        return sendSealedDiscoveryInviteToPeer(
            pairedPeer = matched,
            phoneHost = targetIp,
            phonePort = targetPort,
            expectedPhoneFingerprintHex = expectedFp,
            nowMs = nowMs
        )
    }

    /**
     * Sends an outbound sealed `cmd=1` discovery invite to a paired phone's `/readyfor/mdns` endpoint.
     */
    fun sendSealedDiscoveryInviteToPeer(
        pairedPeer: SmartConnectPairedPeerRecord,
        phoneHost: String = pairedPeer.lastIp,
        phonePort: Int = pairedPeer.lastPort,
        expectedPhoneFingerprintHex: String? = pairedPeer.certFingerprintSha256.takeIf { it.length == 64 },
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (phoneHost.isBlank()) return false
        val perPairKey32 = runCatching { Base64.getDecoder().decode(pairedPeer.perPairKey) }.getOrNull()
            ?: return false
        if (perPairKey32.size != 32) return false
        val pcNonceRaw16 = SmartConnectIdentity.decodeId16(currentNonceIdBase64)
        val invite = buildDiscoveryInvitePayload(nowMs)
        return SmartConnectDiscoveryAndBle.sendOutboundSealedDiscoveryInvite(
            transport = transport,
            phoneHost = phoneHost,
            phonePort = phonePort,
            expectedPhoneFingerprintHex = expectedPhoneFingerprintHex,
            pcNonceIdRaw16 = pcNonceRaw16,
            perPairKey32 = perPairKey32,
            invite = invite
        )
    }

    /**
     * Updates the PC's capability map in [capRegistry] and immediately broadcasts the full
     * replace-whole `svc 25 cmd 1` frame to all connected peers.
     */
    fun updateCapabilityAndBroadcast(key: String, value: Int) {
        val fullCap = capRegistry.registerCapability(key, value)
        broadcastStatusAndCapMap(fullCap)
    }

    /**
     * Broadcasts the full replace-whole `svc 25 cmd 1` status + capability map frame to all active
     * control sessions.
     */
    fun broadcastStatusAndCapMap(capMap: Map<String, Int> = capRegistry.snapshot()) {
        for ((_, ctx) in activeControlSessions) {
            if (ctx.session.isOpen && ctx.verified) {
                val frame = SmartConnectStatusCodec.buildStatusAndCapFrame(
                    pcNonceIdBase64 = currentNonceIdBase64,
                    msgId = msgIdCounter.getAndIncrement(),
                    capMap = capMap
                )
                runCatching { ctx.session.sendText(frame) }
            }
        }
    }

    /**
     * Sends an arbitrary service frame to a connected peer (or all connected peers if
     * [targetRealDeviceId] is null) — used by `LocalDaemonIpcServer` and Slice `S-B`+ services.
     */
    fun sendServiceEnvelope(
        serviceId: Int,
        innerData: kotlinx.serialization.json.JsonElement,
        targetRealDeviceId: String? = null
    ): Int {
        var sentCount = 0
        for ((_, ctx) in activeControlSessions) {
            if (!ctx.session.isOpen) continue
            if (targetRealDeviceId != null && ctx.peerRealDeviceId != targetRealDeviceId) continue
            val env = SmartConnectEnvelope(
                deviceId = currentNonceIdBase64,
                srcServiceId = serviceId,
                serviceId = serviceId,
                msgId = msgIdCounter.getAndIncrement(),
                data = innerData,
                ver = SmartConnectConstants.ENVELOPE_VERSION
            )
            val raw = SmartConnectEnvelopeCodec.encodeEnvelope(env)
            if (runCatching { ctx.session.sendText(raw) }.isSuccess) {
                sentCount++
            }
        }
        return sentCount
    }

    /**
     * Sends `svc 0 cmd 6` (`{"cmd":6,"data":{"type":0}}`) followed by WebSocket close code `3123`
     * (`WS_CLOSE_CODE_USER_DISCONNECT`) so the phone cleanly disconnects without showing a crash
     * toast or deleting the pairing record.
     */
    fun disconnectPeer(realDeviceId: String? = null) {
        for ((id, ctx) in activeControlSessions) {
            if (realDeviceId != null && ctx.peerRealDeviceId != realDeviceId) continue
            ctx.cancelTimers()
            if (ctx.session.isOpen) {
                val disconnectFrame = SmartConnectHandshakeCodec.buildUserDisconnectFrame(
                    pcNonceIdBase64 = currentNonceIdBase64,
                    msgId = msgIdCounter.getAndIncrement()
                )
                runCatching { ctx.session.sendText(disconnectFrame) }
                runCatching {
                    ctx.session.sendClose(
                        statusCode = SmartConnectConstants.WS_CLOSE_CODE_USER_DISCONNECT,
                        reason = "User disconnect"
                    )
                }
                ctx.session.close()
            }
            activeControlSessions.remove(id)
        }
        refreshConnectedPeerIdsFlow()
    }

    fun registerServiceFrameObserver(observer: SmartConnectServiceFrameObserver) {
        serviceObservers.add(observer)
    }

    fun unregisterServiceFrameObserver(observer: SmartConnectServiceFrameObserver) {
        serviceObservers.remove(observer)
    }

    fun getPairedPeers(): List<SmartConnectPairedPeerRecord> = pairedPeersByRealId.values.toList()

    private inner class ControlChannelListener : PinnedTlsWebSocketListener {
        override fun onConnected(session: PinnedTlsWebSocketSession) {
            activeControlSessions[session.sessionId] = SessionHandshakeContext(session = session)
        }

        override fun onTextMessage(session: PinnedTlsWebSocketSession, text: String) {
            val ctx = activeControlSessions[session.sessionId] ?: return
            val envelope = runCatching { SmartConnectEnvelopeCodec.decodeEnvelope(text) }.getOrNull() ?: return
            when (envelope.serviceId) {
                SmartConnectConstants.SVC_HANDSHAKE -> handleHandshakeFrame(ctx, envelope)
                SmartConnectConstants.SVC_DEVICE_STATUS -> handleDeviceStatusFrame(ctx, envelope)
                SmartConnectConstants.SVC_MIRROR,
                SmartConnectConstants.SVC_APP_STREAM -> handleConnectBurstDisplayFrame(ctx, envelope)
                else -> {
                    for (observer in serviceObservers) {
                        runCatching { observer.onServiceFrame(ctx.peerRealDeviceId, envelope) }
                    }
                }
            }
        }

        override fun onClosed(session: PinnedTlsWebSocketSession, code: Int, reason: String) {
            val removed = activeControlSessions.remove(session.sessionId)
            removed?.cancelTimers()
            refreshConnectedPeerIdsFlow()
        }

        override fun onError(session: PinnedTlsWebSocketSession, cause: Throwable) {
            val removed = activeControlSessions.remove(session.sessionId)
            removed?.cancelTimers()
            refreshConnectedPeerIdsFlow()
        }
    }

    private inner class DiscoveryChannelListener : PinnedTlsWebSocketListener {
        override fun onBinaryMessage(session: PinnedTlsWebSocketSession, bytes: ByteArray) {
            val candidates = pairedPeersByRealId.values.mapNotNull { record ->
                val key32 = runCatching { Base64.getDecoder().decode(record.perPairKey) }.getOrNull()
                if (key32 == null || key32.size != 32) return@mapNotNull null
                val phoneKey32 = record.phoneKey?.let {
                    runCatching { Base64.getDecoder().decode(it) }.getOrNull()?.takeIf { b -> b.size == 32 }
                }
                SealedDiscoveryFrameCodec.CandidatePeerKey(
                    realDeviceIdBase64 = record.realDeviceId,
                    perPairKey32 = key32,
                    phoneKey32 = phoneKey32
                )
            }

            // CRITICAL UNPAIR GUARD: If decryption fails for all paired peers, close socket
            // silently and NEVER send 0x02 or 0x03!
            val opened = SealedDiscoveryFrameCodec.openFromPairedCandidates(bytes, candidates)
            if (opened == null) {
                session.close()
                return
            }

            val matchedRealId = opened.matchedPeerRealDeviceId ?: run {
                session.close()
                return
            }
            val pairedPeer = pairedPeersByRealId[matchedRealId] ?: run {
                session.close()
                return
            }
            val jsonObj = SmartConnectDiscoveryAndBle.parseDecryptedDiscoveryJson(opened.plaintextUtf8)
            val cmd = jsonObj?.let { SmartConnectDiscoveryAndBle.extractDiscoveryCmd(it) }

            if (cmd == SmartConnectConstants.DISCOVERY_CMD_TRIGGER ||
                cmd == SmartConnectConstants.DISCOVERY_CMD_INVITE
            ) {
                val perPairKey32 = Base64.getDecoder().decode(pairedPeer.perPairKey)
                val pcNonceRaw16 = SmartConnectIdentity.decodeId16(currentNonceIdBase64)
                val invite = buildDiscoveryInvitePayload(System.currentTimeMillis())
                val replyBytes = SmartConnectDiscoveryAndBle.buildSealedInviteFrame(
                    pcNonceIdRaw16 = pcNonceRaw16,
                    perPairKey32 = perPairKey32,
                    invite = invite
                )
                runCatching { session.sendBinary(replyBytes) }
            }
        }
    }

    private fun handleHandshakeFrame(ctx: SessionHandshakeContext, envelope: SmartConnectEnvelope) {
        val cmd = SmartConnectEnvelopeCodec.extractCmd(envelope) ?: return
        when (cmd) {
            SmartConnectConstants.CMD_HELLO_PAIR,
            SmartConnectConstants.CMD_HELLO_RECONNECT -> {
                val hello = runCatching { SmartConnectHandshakeCodec.parsePhoneHello(envelope) }.getOrNull()
                    ?: return
                val nowMs = System.currentTimeMillis()
                val helloData = hello.data

                // Match against existing paired peer (by nonceId or direct realDeviceId)
                val existingPeer = SmartConnectIdentity.matchNonceIdBase64ToPairedPeer(
                    candidateNonceIdBase64 = helloData.deviceId,
                    pairedPeers = pairedPeersByRealId.values,
                    realDeviceIdSelector = { it.realDeviceId }
                ) ?: pairedPeersByRealId[helloData.deviceId]

                if (cmd == SmartConnectConstants.CMD_HELLO_PAIR && existingPeer == null) {
                    val tokenValid = inviteTokenStore.isValid(helloData.invitation, nowMs)
                    if (!tokenValid && !helloData.invitation.isNullOrBlank()) {
                        // Reject expired/invalid QR invite token on unknown peer
                        ctx.session.close()
                        return
                    }
                }

                val perPairKeyB64 = existingPeer?.perPairKey
                    ?: SmartConnectIdentity.generatePerPairKeyBase64()
                val provisionalRealId = existingPeer?.realDeviceId ?: helloData.deviceId
                val certFp = helloData.fp.ifBlank {
                    ctx.session.peerCertificateFingerprintHex.orEmpty()
                }.lowercase()

                ctx.peerNonceOrRealId = helloData.deviceId
                ctx.peerRealDeviceId = provisionalRealId
                ctx.peerDeviceName = helloData.deviceName.ifBlank { "Motorola Phone" }
                ctx.peerIp = helloData.ip.ifBlank { ctx.session.remoteAddress }
                ctx.peerPort = helloData.wsPort.takeIf { it in 1..65535 } ?: SmartConnectConstants.DEFAULT_WS_PORT
                ctx.peerCertFp = certFp
                ctx.perPairKeyBase64 = perPairKeyB64
                ctx.phoneKeyBase64 = helloData.key ?: existingPeer?.phoneKey
                ctx.verified = true

                val allIps = localAddressesProvider().ifEmpty { listOf("127.0.0.1") }
                val primaryIp = allIps.first()

                // Step 2: Send svc 0 cmd 0 PC Verify Reply
                val verifyReplyFrame = SmartConnectHandshakeCodec.buildPcVerifyReplyFrame(
                    pcNonceIdBase64 = currentNonceIdBase64,
                    pcDeviceName = localIdentity.deviceName,
                    primaryIp = primaryIp,
                    allIps = allIps,
                    wsPort = boundPort,
                    serialNumber = localIdentity.serialNumber,
                    perPairKeyBase64 = perPairKeyB64,
                    msgId = envelope.msgId
                )
                ctx.session.sendText(verifyReplyFrame)

                // Step 3: Immediately send svc 25 cmd 1 Device Status & Replace-Whole Cap Map
                val capFrame = SmartConnectStatusCodec.buildStatusAndCapFrame(
                    pcNonceIdBase64 = currentNonceIdBase64,
                    msgId = msgIdCounter.getAndIncrement(),
                    capMap = capRegistry.snapshot()
                )
                ctx.session.sendText(capFrame)

                if (existingPeer != null) {
                    val updated = existingPeer.copy(
                        deviceName = ctx.peerDeviceName,
                        certFingerprintSha256 = certFp.ifBlank { existingPeer.certFingerprintSha256 },
                        lastIp = ctx.peerIp,
                        lastPort = ctx.peerPort,
                        lastSeenMs = nowMs
                    )
                    persistPairedPeer(updated)
                }

                // Step 4 fallback: schedule 2s unprompted EXCHANGE_KEY timer if phone doesn't send cmd=1
                ctx.unpromptedExchangeKeyFuture?.cancel(false)
                ctx.unpromptedExchangeKeyFuture = scheduler.schedule(
                    {
                        if (ctx.session.isOpen && !ctx.exchangeKeySent.get()) {
                            sendExchangeKeyAndFinalizePair(ctx, msgIdCounter.getAndIncrement())
                        }
                    },
                    unpromptedExchangeKeyDelayMs,
                    TimeUnit.MILLISECONDS
                )
            }

            SmartConnectConstants.CMD_EXCHANGE_KEY -> {
                val exch = runCatching { SmartConnectHandshakeCodec.parseExchangeKey(envelope) }.getOrNull()
                    ?: return
                val learnedRealId = exch.data.device.realDeviceId.ifBlank { exch.data.device.deviceId }
                if (learnedRealId.isNotBlank()) {
                    ctx.peerRealDeviceId = learnedRealId
                }
                if (exch.data.device.deviceName.isNotBlank()) {
                    ctx.peerDeviceName = exch.data.device.deviceName
                }
                if (exch.data.key.isNotBlank()) {
                    ctx.phoneKeyBase64 = exch.data.key
                }
                sendExchangeKeyAndFinalizePair(ctx, envelope.msgId)
            }

            SmartConnectConstants.CMD_USER_DISCONNECT -> {
                ctx.cancelTimers()
                ctx.session.close()
                activeControlSessions.remove(ctx.session.sessionId)
                refreshConnectedPeerIdsFlow()
            }
        }
    }

    private fun sendExchangeKeyAndFinalizePair(ctx: SessionHandshakeContext, replyMsgId: Int) {
        if (!ctx.exchangeKeySent.compareAndSet(false, true)) {
            // Still update paired record if phone sent EXCHANGE_KEY after our unprompted timer
            finalizePairedRecord(ctx)
            return
        }
        ctx.unpromptedExchangeKeyFuture?.cancel(false)

        val perPairKeyB64 = ctx.perPairKeyBase64 ?: SmartConnectIdentity.generatePerPairKeyBase64()
        ctx.perPairKeyBase64 = perPairKeyB64

        // Step 4: Send svc 0 cmd 1 EXCHANGE_KEY with BOTH deviceId and realDeviceId = <pcRealId>
        val exchangeKeyFrame = SmartConnectHandshakeCodec.buildPcExchangeKeyFrame(
            pcNonceIdBase64 = currentNonceIdBase64,
            pcRealIdBase64 = localIdentity.realDeviceIdBase64,
            pcDeviceName = localIdentity.deviceName,
            perPairKeyBase64 = perPairKeyB64,
            msgId = replyMsgId
        )
        runCatching { ctx.session.sendText(exchangeKeyFrame) }

        // Step 5: Persist paired peer record
        finalizePairedRecord(ctx)

        // Step 6: Schedule 5s post-pair discovery refresh (rotate nonceId + re-announce mDNS)
        ctx.postPairRefreshFuture?.cancel(false)
        ctx.postPairRefreshFuture = scheduler.schedule(
            {
                rotateNonceIdAndInviteToken()
                val packet = buildReadyForMdnsAnnouncement()
                onMdnsRefreshRequested?.invoke(packet)
            },
            postPairRefreshDelayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun finalizePairedRecord(ctx: SessionHandshakeContext) {
        val realId = ctx.peerRealDeviceId ?: ctx.peerNonceOrRealId ?: return
        val perPairKeyB64 = ctx.perPairKeyBase64 ?: return
        val nowMs = System.currentTimeMillis()
        val existing = pairedPeersByRealId[realId]
        val candidateRecord = SmartConnectPairedPeerRecord(
            realDeviceId = realId,
            deviceName = ctx.peerDeviceName.ifBlank { existing?.deviceName ?: "Motorola Phone" },
            certFingerprintSha256 = ctx.peerCertFp.ifBlank { existing?.certFingerprintSha256.orEmpty() },
            perPairKey = perPairKeyB64,
            phoneKey = ctx.phoneKeyBase64 ?: existing?.phoneKey,
            lastIp = ctx.peerIp.ifBlank { existing?.lastIp.orEmpty() },
            lastPort = ctx.peerPort,
            lastSeenMs = nowMs,
            pairedAtMs = existing?.pairedAtMs ?: nowMs
        )
        val cb = trustConfirmationCallback
        if (existing == null && cb != null) {
            val trusted = runCatching { cb.invoke(candidateRecord) }.getOrDefault(false)
            if (!trusted) {
                ctx.session.close()
                return
            }
        }
        persistPairedPeer(candidateRecord)
        refreshConnectedPeerIdsFlow()
    }

    private fun handleDeviceStatusFrame(ctx: SessionHandshakeContext, envelope: SmartConnectEnvelope) {
        val cmd = SmartConnectEnvelopeCodec.extractCmd(envelope) ?: return
        val peerId = ctx.peerRealDeviceId ?: envelope.deviceId
        val nowMs = System.currentTimeMillis()
        val prev = peerStatusByRealId[peerId] ?: SmartConnectPeerStatusSnapshot(
            realDeviceId = peerId,
            deviceName = ctx.peerDeviceName
        )

        when (cmd) {
            SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY -> {
                val parsed = runCatching { SmartConnectStatusCodec.parseStatusAndCap(envelope) }.getOrNull()
                    ?: return
                val d = parsed.data
                val updated = prev.copy(
                    deviceName = ctx.peerDeviceName.ifBlank { prev.deviceName },
                    battery = d.battery,
                    charge = d.charge,
                    volume = d.volume,
                    mute = d.mute,
                    dnd = d.dnd,
                    camera = d.camera,
                    peerCap = d.cap,
                    updatedAtMs = nowMs
                )
                peerStatusByRealId[peerId] = updated
                _peerStatusFlow.value = HashMap(peerStatusByRealId)
            }

            SmartConnectConstants.STATUS_CMD_WALLPAPER -> {
                val parsed = runCatching { SmartConnectStatusCodec.parseWallpaper(envelope) }.getOrNull()
                    ?: return
                val b64 = parsed.data.wallpaper.trim()
                if (b64.isEmpty()) return
                val declaredMd5 = parsed.data.md5.trim().lowercase()
                if (declaredMd5.isNotEmpty() && declaredMd5 == prev.wallpaperMd5 && prev.wallpaperPath != null) {
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                val actualMd5 = bytesToHexLower(MessageDigest.getInstance("MD5").digest(bytes))
                val effectiveMd5 = declaredMd5.ifEmpty { actualMd5 }
                val safeIdSuffix = bytesToHexLower(
                    MessageDigest.getInstance("SHA-256").digest(peerId.toByteArray())
                ).take(12)
                val wallpaperFile = File(storageDir, "wallpaper_$safeIdSuffix.jpg")
                runCatching { wallpaperFile.writeBytes(bytes) }
                val updated = prev.copy(
                    wallpaperMd5 = effectiveMd5,
                    wallpaperPath = wallpaperFile.absolutePath,
                    updatedAtMs = nowMs
                )
                peerStatusByRealId[peerId] = updated
                _peerStatusFlow.value = HashMap(peerStatusByRealId)
            }

            SmartConnectConstants.STATUS_CMD_DND -> {
                val parsed = runCatching { SmartConnectStatusCodec.parseDnd(envelope) }.getOrNull()
                    ?: return
                val updated = prev.copy(
                    dnd = parsed.data.dnd,
                    updatedAtMs = nowMs
                )
                peerStatusByRealId[peerId] = updated
                _peerStatusFlow.value = HashMap(peerStatusByRealId)
            }
        }
    }

    private fun handleConnectBurstDisplayFrame(ctx: SessionHandshakeContext, envelope: SmartConnectEnvelope) {
        val peerId = ctx.peerRealDeviceId ?: envelope.deviceId
        val dataObj = runCatching { SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data) }.getOrNull()
            ?: return
        val innerData = (dataObj["data"] as? JsonObject) ?: dataObj
        val width = (innerData["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val height = (innerData["height"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val dpi = (innerData["dpi"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (width > 0 && height > 0) {
            val prev = peerStatusByRealId[peerId] ?: SmartConnectPeerStatusSnapshot(
                realDeviceId = peerId,
                deviceName = ctx.peerDeviceName
            )
            peerStatusByRealId[peerId] = prev.copy(
                displayWidth = width,
                displayHeight = height,
                displayDpi = dpi,
                updatedAtMs = System.currentTimeMillis()
            )
            _peerStatusFlow.value = HashMap(peerStatusByRealId)
        }
    }

    private fun buildDiscoveryInvitePayload(nowMs: Long): SmartConnectDiscoveryInvitePayload {
        val token = inviteTokenStore.issueToken(nowMs, activeInviteToken)
        activeInviteToken = token
        val allIps = localAddressesProvider().ifEmpty { listOf("127.0.0.1") }
        return SmartConnectDiscoveryInvitePayload(
            cmd = SmartConnectConstants.DISCOVERY_CMD_INVITE,
            ip = allIps.first(),
            ips = allIps,
            wsPort = boundPort,
            deviceId = currentNonceIdBase64,
            deviceName = localIdentity.deviceName,
            fp = tlsIdentity.fingerprintHex,
            invitation = token,
            type = SmartConnectConstants.PC_TYPE_LAPTOP,
            os = SmartConnectConstants.PC_OS_WINDOWS,
            appType = SmartConnectConstants.PC_APP_TYPE_DESKTOP,
            scType = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
            cap = SmartConnectConstants.PC_BASE_CAP,
            timestamp = nowMs,
            ver = SmartConnectConstants.ENVELOPE_VERSION
        )
    }

    private fun persistPairedPeer(record: SmartConnectPairedPeerRecord) {
        pairedPeersByRealId[record.realDeviceId] = record
        val list = pairedPeersByRealId.values.sortedByDescending { it.lastSeenMs }
        _pairedPeersFlow.value = list
        runCatching {
            val payload = SmartConnectPairedStorePayload(pairedPeers = list)
            pairedFile.writeText(prettyJson.encodeToString(payload))
            SmartConnectTlsIdentity.enforceOwnerOnlyPermissions(pairedFile)
        }
    }

    private fun loadPairedPeersFromDisk() {
        if (!pairedFile.isFile) return
        runCatching {
            val payload = prettyJson.decodeFromString<SmartConnectPairedStorePayload>(pairedFile.readText())
            for (record in payload.pairedPeers) {
                if (record.realDeviceId.isNotBlank() && record.perPairKey.isNotBlank()) {
                    pairedPeersByRealId[record.realDeviceId] = record
                }
            }
            _pairedPeersFlow.value = pairedPeersByRealId.values.sortedByDescending { it.lastSeenMs }
        }
    }

    private fun loadOrCreateLocalIdentity(defaultDeviceName: String): SmartConnectLocalIdentityRecord {
        storageDir.mkdirs()
        if (identityFile.isFile) {
            val loaded = runCatching {
                prettyJson.decodeFromString<SmartConnectLocalIdentityRecord>(identityFile.readText())
            }.getOrNull()
            if (loaded != null && loaded.realDeviceIdBase64.isNotBlank()) {
                val safeSn = SmartConnectIdentity.ensureNonEmptySerialNumber(loaded.serialNumber)
                val normalized = loaded.copy(
                    serialNumber = safeSn,
                    deviceName = loaded.deviceName.ifBlank { defaultDeviceName.ifBlank { "EC0-PC" } }
                )
                if (normalized != loaded) {
                    saveLocalIdentity(normalized)
                }
                return normalized
            }
        }
        val created = SmartConnectLocalIdentityRecord(
            realDeviceIdBase64 = SmartConnectIdentity.generateRealDeviceIdBase64(),
            serialNumber = SmartConnectIdentity.ensureNonEmptySerialNumber(null),
            deviceName = defaultDeviceName.ifBlank { "EC0-PC" }
        )
        saveLocalIdentity(created)
        return created
    }

    private fun saveLocalIdentity(record: SmartConnectLocalIdentityRecord) {
        runCatching {
            identityFile.writeText(prettyJson.encodeToString(record))
            SmartConnectTlsIdentity.enforceOwnerOnlyPermissions(identityFile)
        }
    }

    private fun refreshConnectedPeerIdsFlow() {
        val ids = activeControlSessions.values
            .filter { it.session.isOpen && it.verified }
            .mapNotNull { it.peerRealDeviceId }
            .toSet()
        _connectedPeerIdsFlow.value = ids
    }

    private fun bytesToHexLower(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0f]
        }
        return out.concatToString()
    }

    override fun close() {
        started.set(false)
        for ((_, ctx) in activeControlSessions) {
            ctx.cancelTimers()
        }
        activeControlSessions.clear()
        runCatching { scheduler.shutdownNow() }
        transport.close()
    }

    private class SessionHandshakeContext(
        val session: PinnedTlsWebSocketSession
    ) {
        @Volatile
        var verified: Boolean = false

        @Volatile
        var peerNonceOrRealId: String? = null

        @Volatile
        var peerRealDeviceId: String? = null

        @Volatile
        var peerDeviceName: String = "Motorola Phone"

        @Volatile
        var peerIp: String = session.remoteAddress

        @Volatile
        var peerPort: Int = SmartConnectConstants.DEFAULT_WS_PORT

        @Volatile
        var peerCertFp: String = session.peerCertificateFingerprintHex.orEmpty()

        @Volatile
        var perPairKeyBase64: String? = null

        @Volatile
        var phoneKeyBase64: String? = null

        val exchangeKeySent = AtomicBoolean(false)

        @Volatile
        var unpromptedExchangeKeyFuture: ScheduledFuture<*>? = null

        @Volatile
        var postPairRefreshFuture: ScheduledFuture<*>? = null

        fun cancelTimers() {
            runCatching { unpromptedExchangeKeyFuture?.cancel(false) }
            runCatching { postPairRefreshFuture?.cancel(false) }
        }
    }
}
