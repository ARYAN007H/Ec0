/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * mDNS (`_ec0._tcp.local.`) announcer, listener, and TTL-aware peer cache.
 * Supports both real UDP multicast (`224.0.0.251:5353`) and deterministic
 * injected transports for unit/integration testing.
 */
package ec0.core.engine.discovery

import ec0.core.engine.DiscoveredCamDroidService
import ec0.core.engine.DiscoveredPeerRecord
import ec0.core.engine.DiscoveredReadyForPeer
import ec0.core.engine.EngineConstants
import ec0.core.engine.MdnsCodec
import ec0.core.engine.PeerInfo
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.smartconnect.SmartConnectConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class MdnsDatagram(
    val payload: ByteArray,
    val senderIp: String?,
)

interface MdnsDatagramTransport : Closeable {
    fun send(payload: ByteArray)
    fun receive(): MdnsDatagram?
}

/**
 * Real UDP multicast transport bound to `224.0.0.251:5353`.
 */
class MulticastMdnsTransport(
    private val groupAddress: String = EngineConstants.MDNS_MULTICAST_IPV4,
    private val port: Int = EngineConstants.MDNS_PORT,
    private val soTimeoutMs: Int = 1_000,
) : MdnsDatagramTransport {
    private val group: InetAddress = InetAddress.getByName(groupAddress)
    private val socket: MulticastSocket = MulticastSocket(null).apply {
        reuseAddress = true
        soTimeout = soTimeoutMs
        bind(InetSocketAddress(port))
        joinAllMulticastInterfaces(group, port)
    }
    private val closed = AtomicBoolean(false)

    override fun send(payload: ByteArray) {
        if (closed.get()) return
        val packet = DatagramPacket(payload, payload.size, group, port)
        socket.send(packet)
    }

    override fun receive(): MdnsDatagram? {
        if (closed.get()) return null
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        return try {
            socket.receive(packet)
            MdnsDatagram(
                payload = buf.copyOf(packet.length),
                senderIp = packet.address?.hostAddress,
            )
        } catch (_: java.net.SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }

    companion object {
        private fun MulticastSocket.joinAllMulticastInterfaces(group: InetAddress, port: Int) {
            val socketAddr = InetSocketAddress(group, port)
            var joinedAny = false
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
                .getOrDefault(emptyList())
            for (nif in interfaces) {
                val usable = runCatching { nif.isUp && nif.supportsMulticast() && !nif.isLoopback }
                    .getOrDefault(false)
                if (usable) {
                    runCatching {
                        joinGroup(socketAddr, nif)
                        joinedAny = true
                    }
                }
            }
            if (!joinedAny) {
                runCatching { joinGroup(group) }
            }
        }

        /**
         * Enumerates non-loopback IPv4 addresses on active network interfaces.
         */
        fun detectLocalIpv4Addresses(): List<String> {
            val result = mutableListOf<String>()
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
                .getOrDefault(emptyList())
            for (nif in interfaces) {
                val up = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
                if (!up) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
            return result.distinct()
        }
    }
}

class Ec0MdnsDiscovery(
    @Volatile var localPeer: PeerInfo,
    private val transportFactory: () -> MdnsDatagramTransport = { MulticastMdnsTransport() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlSeconds: Long = EngineConstants.MDNS_DEFAULT_TTL_SECONDS.toLong(),
) : Closeable {

    private val cache = ConcurrentHashMap<String, DiscoveredPeerRecord>()
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val camDroidCache = ConcurrentHashMap<String, DiscoveredCamDroidService>()
    private val _camDroidServices = MutableStateFlow<List<DiscoveredCamDroidService>>(emptyList())
    val camDroidServices: StateFlow<List<DiscoveredCamDroidService>> = _camDroidServices.asStateFlow()

    private val readyForCache = ConcurrentHashMap<String, DiscoveredReadyForPeer>()
    private val _readyForPeers = MutableStateFlow<List<DiscoveredReadyForPeer>>(emptyList())
    val readyForPeers: StateFlow<List<DiscoveredReadyForPeer>> = _readyForPeers.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(PeerInfo) -> Unit>()
    private val readyForListeners = CopyOnWriteArrayList<(DiscoveredReadyForPeer) -> Unit>()
    private val running = AtomicBoolean(false)
    private var transport: MdnsDatagramTransport? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null
    private var burstJob: Job? = null

    fun addPeerListener(listener: (PeerInfo) -> Unit) {
        listeners.add(listener)
    }

    fun removePeerListener(listener: (PeerInfo) -> Unit) {
        listeners.remove(listener)
    }

    fun addReadyForPeerListener(listener: (DiscoveredReadyForPeer) -> Unit) {
        readyForListeners.add(listener)
    }

    fun removeReadyForPeerListener(listener: (DiscoveredReadyForPeer) -> Unit) {
        readyForListeners.remove(listener)
    }

    fun sendRawPacket(payload: ByteArray) {
        val t = transport ?: return
        runCatching { t.send(payload) }
    }

    /**
     * Starts the mDNS listener and schedules the RFC 6762 startup burst
     * (`100ms, 500ms, 2000ms`).
     */
    fun start(burstDelaysMs: List<Long> = EngineConstants.MDNS_BURST_DELAYS_MS) {
        if (!running.compareAndSet(false, true)) return
        val activeTransport = transportFactory()
        transport = activeTransport
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = activeScope

        receiveJob = activeScope.launch {
            while (isActive && running.get()) {
                val dgram = activeTransport.receive()
                if (dgram != null) {
                    ingestDatagram(dgram.payload, dgram.senderIp, clockMs())
                }
                pruneExpired(clockMs())
            }
        }

        burstJob = activeScope.launch {
            var previousDelay = 0L
            for (targetDelay in burstDelaysMs) {
                val waitMs = (targetDelay - previousDelay).coerceAtLeast(0L)
                if (waitMs > 0L) delay(waitMs)
                previousDelay = targetDelay
                if (!isActive || !running.get()) break
                runCatching {
                    announceNow()
                    queryNow()
                }
            }
        }
    }

    /**
     * Broadcasts an immediate `_ec0._tcp.local.` PTR query and announcement.
     */
    fun triggerBurstNow() {
        runCatching {
            announceNow()
            queryNow()
        }
    }

    fun queryNow() {
        val t = transport ?: return
        val queryBytes = MdnsCodec.encodePtrQuery(EngineConstants.MDNS_SERVICE_TYPE)
        t.send(queryBytes)
        val camQueryBytes = MdnsCodec.encodePtrQuery(CamDroidConstants.MDNS_SERVICE_TYPE)
        t.send(camQueryBytes)
        val readyForQueryBytes = MdnsCodec.encodePtrQuery(SmartConnectConstants.MDNS_SERVICE_TYPE)
        t.send(readyForQueryBytes)
    }

    fun announceNow() {
        val t = transport ?: return
        val announceBytes = MdnsCodec.encodeAnnouncement(localPeer, ttlSeconds)
        t.send(announceBytes)
    }

    fun sendGoodbye() {
        val t = transport ?: return
        val goodbyeBytes = MdnsCodec.encodeGoodbye(localPeer)
        runCatching { t.send(goodbyeBytes) }
    }

    /**
     * Processes a raw DNS/mDNS UDP payload. Ignores malformed packets cleanly.
     * Replies to incoming `_ec0._tcp.local.` PTR queries and updates the peer cache
     * as well as the `_camdroid._tcp.local.` camera service cache.
     */
    fun ingestDatagram(
        payload: ByteArray,
        senderIp: String? = null,
        nowMs: Long = clockMs(),
    ) {
        val packet = try {
            MdnsCodec.decodePacket(payload)
        } catch (_: IllegalArgumentException) {
            return
        }

        val normalizedService = MdnsCodec.normalizeDomain(EngineConstants.MDNS_SERVICE_TYPE)
        val hasServiceQuery = packet.questions.any { q ->
            q.name.equals(normalizedService, ignoreCase = true) &&
                (q.qType == MdnsCodec.TYPE_PTR || q.qType == MdnsCodec.TYPE_ANY)
        }
        if (hasServiceQuery) {
            runCatching { announceNow() }
        }

        val camServices = MdnsCodec.extractCamDroidServices(
            packet = packet,
            nowMs = nowMs,
            fallbackSenderIp = senderIp,
            serviceType = CamDroidConstants.MDNS_SERVICE_TYPE,
        )
        if (camServices.isNotEmpty()) {
            for (svc in camServices) {
                val key = svc.instanceName.lowercase()
                if (svc.isGoodbye) {
                    camDroidCache.remove(key)
                } else {
                    camDroidCache[key] = svc
                }
            }
            _camDroidServices.value = camDroidCache.values
                .filter { !it.isExpired(nowMs) }
                .sortedBy { it.deviceName }
        }

        val readyForExtracted = MdnsCodec.extractReadyForPeers(
            packet = packet,
            nowMs = nowMs,
            serviceType = SmartConnectConstants.MDNS_SERVICE_TYPE,
            fallbackSenderIp = senderIp,
        )
        if (readyForExtracted.isNotEmpty()) {
            for (rfPeer in readyForExtracted) {
                val key = rfPeer.deviceId
                if (rfPeer.isGoodbye) {
                    readyForCache.remove(key)
                } else {
                    readyForCache[key] = rfPeer
                    for (rfListener in readyForListeners) {
                        runCatching { rfListener(rfPeer) }
                    }
                }
            }
            _readyForPeers.value = readyForCache.values
                .filter { !it.isExpired(nowMs) }
                .sortedBy { it.deviceName }
        }

        val extracted = MdnsCodec.extractPeers(
            packet = packet,
            nowMs = nowMs,
            fallbackSenderIp = senderIp,
            serviceType = EngineConstants.MDNS_SERVICE_TYPE,
        )
        if (extracted.isEmpty()) return

        var changed = false
        for (record in extracted) {
            val peerId = record.peer.deviceId
            if (peerId.equals(localPeer.deviceId, ignoreCase = true)) {
                continue // Ignore our own announcements
            }
            if (record.isGoodbye) {
                if (cache.remove(peerId) != null) {
                    changed = true
                }
            } else {
                cache[peerId] = record
                changed = true
                for (listener in listeners) {
                    runCatching { listener(record.peer) }
                }
            }
        }
        if (changed) {
            publishSnapshot(nowMs)
        }
    }

    /**
     * Evicts any cached peers whose TTL has elapsed as of [nowMs].
     */
    fun pruneExpired(nowMs: Long = clockMs()): List<PeerInfo> {
        var removedAny = false
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(nowMs)) {
                iterator.remove()
                removedAny = true
            }
        }
        if (removedAny) {
            publishSnapshot(nowMs)
        }
        return _peers.value
    }

    fun currentPeer(deviceId: String, nowMs: Long = clockMs()): PeerInfo? {
        val record = cache[deviceId.lowercase()] ?: return null
        if (record.isExpired(nowMs)) {
            cache.remove(deviceId.lowercase())
            publishSnapshot(nowMs)
            return null
        }
        return record.peer
    }

    private fun publishSnapshot(nowMs: Long) {
        val active = cache.values
            .filter { !it.isExpired(nowMs) }
            .map { it.peer }
            .sortedBy { it.deviceId }
        _peers.value = active
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            sendGoodbye()
            burstJob?.cancel()
            receiveJob?.cancel()
            scope?.cancel()
            runCatching { transport?.close() }
            transport = null
        }
    }
}
