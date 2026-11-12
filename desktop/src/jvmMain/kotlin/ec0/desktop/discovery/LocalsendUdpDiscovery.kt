package ec0.desktop.discovery

import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.dto.MulticastMessageV2
import ec0.desktop.state.SettingsState
import ec0.desktop.state.UiDevice
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
            val announceBytes =
                LocalsendV2.json.encodeToString(MulticastMessageV2.serializer(), announce).encodeToByteArray()
            val group = InetAddress.getByName(MULTICAST_GROUP)

            val socket = MulticastSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(LocalsendV2.DISCOVERY_PORT))
                socket.broadcast = true
                socket.soTimeout = 250
                joinOnInterfaces(socket, group)

                // announce (multicast + broadcast)
                socket.send(DatagramPacket(announceBytes, announceBytes.size, group, LocalsendV2.DISCOVERY_PORT))
                runCatching {
                    socket.send(
                        DatagramPacket(announceBytes, announceBytes.size,
                            InetAddress.getByName("255.255.255.255"), LocalsendV2.DISCOVERY_PORT),
                    )
                }

                val found = LinkedHashMap<String, UiDevice>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(65_536)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val msg = runCatching {
                        LocalsendV2.json.decodeFromString(MulticastMessageV2.serializer(), text)
                    }.getOrNull() ?: continue
                    if (msg.fingerprint == ownFingerprint()) continue // our own echo
                    val key = "${packet.address.hostAddress}:${msg.port}:${msg.fingerprint}"
                    found[key] = UiDevice(
                        id = "localsend-${msg.fingerprint.take(16)}",
                        alias = msg.alias,
                        deviceModel = msg.deviceModel,
                        deviceType = msg.deviceType,
                        protocol = ec0.desktop.state.ProtocolFamily.LOCALSEND,
                        port = msg.port,
                        fingerprint = msg.fingerprint,
                        hostAddress = packet.address.hostAddress ?: "",
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
            }
        }
    }

    companion object {
        /** Reference: packages/core/src/multicast/mod.rs:28 */
        const val MULTICAST_GROUP = "224.0.0.167"
        const val BROADCAST = "255.255.255.255"
    }
}
