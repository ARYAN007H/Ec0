/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from kdeconnect-android (backends/lan/LanLinkProvider.kt constants
 * and handshake contract, upstream SPDX: GPL-2.0-only OR GPL-3.0-only OR
 * LicenseRef-KDE-Accepted-GPL; GPL-3.0-only option taken).
 *
 * This is the discovery MODEL: constants, packet shapes, and the handshake
 * contract. Live UDP/TCP sockets (ktor-network, already on core's classpath)
 * are wired in the platform/transport layer — commonMain stays socket-free.
 */
package ec0.core.kdeconnect

/**
 * LAN discovery + connection contract (upstream LanLinkProvider):
 *
 * 1. Each device opens a TCP listener on [MIN_PORT] (falls back to a free
 *    port up to [MAX_PORT]) and a UDP listener on [UDP_PORT].
 * 2. It periodically broadcasts its identity packet — with `tcpPort` set to
 *    the TCP listener port — to 255.255.255.255 and to each interface's
 *    broadcast address, on port [UDP_PORT]. Self-packets are filtered.
 * 3. On receiving a broadcast: validate identity, validate `tcpPort` in
 *    [MIN_PORT]..[MAX_PORT], open TCP to (source ip, tcpPort) and immediately
 *    send own identity as the first TCP message.
 * 4. The TCP listener treats the first received packet as the peer's
 *    identity packet (may carry `targetDeviceId` when the connection was
 *    made in response to a specific device's broadcast).
 * 5. Rate-limit: at most one UDP packet per IP per
 *    [MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE] ms window; non-local
 *    source addresses are discarded.
 */
object Discovery {
    const val UDP_PORT: Int = 1716
    const val MIN_PORT: Int = 1716
    const val MAX_PORT: Int = 1764
    const val PAYLOAD_TRANSFER_MIN_PORT: Int = 1739
    const val MAX_IDENTITY_PACKET_SIZE: Int = 1024 * 512
    const val MAX_UDP_PACKET_SIZE: Int = 1024 * 512
    const val DEFAULT_BROADCAST_ADDRESS: String = "255.255.255.255"
    const val MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE: Long = 1000L

    /**
     * Builds the UDP broadcast frame: identity packet body + tcpPort.
     * Note: upstream sends the serialized packet *without* the trailing
     * newline over UDP (DatagramPacket of serialize().trimEnd bytes is
     * equivalent; unserialize ignores the terminator either way).
     */
    fun buildBroadcastFrame(identity: DeviceIdentity, tcpPort: Int): String =
        identity.copy(tcpPort = tcpPort).toIdentityPacket().serialize()

    /**
     * Parses a received UDP/TCP identity frame; returns null if it is not a
     * valid identity packet (type check + name/deviceId validation, upstream
     * DeviceInfo.isValidIdentityPacket).
     */
    fun parseIdentityFrame(frame: String): DeviceIdentity? {
        val np = try {
            NetworkPacket.unserialize(frame)
        } catch (_: kotlinx.serialization.SerializationException) {
            return null
        }
        if (!IdentityPacketCompanionAccess.isValid(np)) return null
        return DeviceIdentity.fromIdentityPacket(np)
    }

    /** Upstream tcpPort sanity check before opening a TCP connection. */
    fun isValidTcpPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
}

internal object IdentityPacketCompanionAccess {
    fun isValid(np: NetworkPacket): Boolean = DeviceIdentity.isValidIdentityPacket(np)
}
