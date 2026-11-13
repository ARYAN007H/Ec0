package ec0.core.localsend.client

import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.dto.MulticastMessageV2
import ec0.core.uimodels.SettingsState
import ec0.core.uimodels.UiDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID

/**
 * Real LocalSend v2 UDP discovery (fallback transport, works today).
 *
 * Wire behavior per the reference Rust core (`packages/core/src/multicast/`):
 * - announcements are sent to the multicast group **224.0.0.167:53317**
 *   (`DEFAULT_MULTICAST_GROUP`, verified in `multicast/mod.rs:28`) — the
 *   broadcast address 255.255.255.255:53317 is added for older peers;
 * - announcements are never responses — listening peers answer over HTTP;
 * - our own announcement is ignored by fingerprint comparison.
 *
 * Uses java.net.MulticastSocket directly (stdlib) instead of ktor-network so
 * this PR needs no lead-owned gradle changes.
 */
class LocalsendUdpDiscovery(
    private val settingsProvider: () -> SettingsState,
    private val ownFingerprint: () -> String,
    /** Optional diagnostic sink (Android app passes Log.d; desktop may no-op). */
    private val debugLog: (String) -> Unit = {},
) {
    fun buildAnnounce(): MulticastMessageV2 {
        val s = settingsProvider()
        return MulticastMessageV2(
            alias = s.alias,
            version = LocalsendV2.PROTOCOL_VERSION,
            deviceModel = s.deviceModel,
            deviceType = s.deviceType,
            fingerprint = ownFingerprint(),
            port = s.port,
            // We do not host the HTTP server yet (server PR lands later) —
            // announce HTTP and download=false honestly.
            protocol = ProtocolType.HTTP,
            download = false,
        )
    }

    /** One announce-then-listen sweep. Returns peers heard on :53317. */
    suspend fun discoverSweep(timeoutMs: Int = 3_500): List<UiDevice> =
        withContext(Dispatchers.IO) {
            val announce = buildAnnounce()
            // Reference wraps every announce in the legacy v2 flag:
            // `AnnouncedMessage { #[serde(flatten)] message, announce: bool }`
            // (multicast/mod.rs:174-181). Desktop has no serialization codegen
            // plugin, so the wrapper is spliced into the serialized body —
            // byte-identical to the reference wire shape. Incoming messages
            // parse fine either way (ignoreUnknownKeys).
            val messageJson = LocalsendV2.json.encodeToString(MulticastMessageV2.serializer(), announce)
            val announceBytes =
                ("""{"announce":true,""" + messageJson.substring(1)).encodeToByteArray()
            val group = InetAddress.getByName(MULTICAST_GROUP)

            val socket = MulticastSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(LocalsendV2.DISCOVERY_PORT))
                socket.soTimeout = 250
                joinOnInterfaces(socket, group)
                debugLog("sweep: bound :53317, joined ${socket.networkInterface?.name ?: "?"}+all")

                // Announce burst: 3 datagrams at 100/500/2000ms (reference
                // ANNOUNCE_DELAYS, multicast/mod.rs:45-49) interleaved with
                // listening. Multicast only — the reference has no broadcast.
                val announceTimesMs = listOf(100L, 500L, 2000L)
                val start = System.currentTimeMillis()
                var nextAnnounce = 0
                val found = LinkedHashMap<String, UiDevice>()
                val deadline = start + timeoutMs
                val buf = ByteArray(65_536)
                while (System.currentTimeMillis() < deadline) {
                    if (nextAnnounce < announceTimesMs.size &&
                        System.currentTimeMillis() - start >= announceTimesMs[nextAnnounce]
                    ) {
                        // Send a copy PER INTERFACE: multicast egresses the
                        // default route otherwise, which on phones with mobile
                        // data up is the carrier interface — the announce never
                        // touches the LAN.
                        sendAnnounceOnAllInterfaces(socket, announceBytes, group)
                        nextAnnounce++
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    debugLog("recv ${packet.address?.hostAddress}:${packet.length}B ${text.take(80)}")
                    val msg = runCatching {
                        LocalsendV2.json.decodeFromString(MulticastMessageV2.serializer(), text)
                    }.getOrElse {
                        debugLog("parse-fail: ${it.message}")
                        continue
                    }
                    if (msg.fingerprint == ownFingerprint()) continue // our own echo
                    val key = "${packet.address.hostAddress}:${msg.port}:${msg.fingerprint}"
                    found[key] = UiDevice(
                        id = "localsend-${msg.fingerprint.take(16)}",
                        alias = msg.alias,
                        deviceModel = msg.deviceModel,
                        deviceType = msg.deviceType,
                        protocol = ec0.core.uimodels.ProtocolFamily.LOCALSEND,
                        port = msg.port,
                        fingerprint = msg.fingerprint,
                        hostAddress = packet.address.hostAddress ?: "",
                        wireProtocol = msg.protocol.wire,
                        download = msg.download,
                        online = true,
                    )
                }
                found.values.toList()
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun joinOnInterfaces(socket: MulticastSocket, group: InetAddress) {
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                runCatching { socket.joinGroup(InetSocketAddress(group, LocalsendV2.DISCOVERY_PORT), nif) }
                    .onFailure { debugLog("join fail ${nif.name}: ${it.message}") }
            }
        }
    }

    /**
     * One announce copy per up multicast-capable interface. Sending without
     * setting the outgoing interface uses the default route, which on phones
     * with mobile data up is the carrier interface — announces then never
     * reach the LAN (observed live on the A5x hotspot: 0 packets heard).
     */
    private fun sendAnnounceOnAllInterfaces(socket: MulticastSocket, bytes: ByteArray, group: InetAddress) {
        val packet = DatagramPacket(bytes, bytes.size, group, LocalsendV2.DISCOVERY_PORT)
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback || !nif.supportsMulticast()) continue
            runCatching {
                socket.networkInterface = nif
                socket.send(packet)
            }.onFailure { debugLog("send fail ${nif.name}: ${it.message}") }
        }
    }

    companion object {
        /** Reference: packages/core/src/multicast/mod.rs:28 */
        const val MULTICAST_GROUP = "224.0.0.167"
    }
}
