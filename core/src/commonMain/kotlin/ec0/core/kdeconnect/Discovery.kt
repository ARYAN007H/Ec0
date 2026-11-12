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
 *    broadcast address, on port [UDP_PORT]. Self-packets are filtered. The
 *    datagram is `identity.serialize()` bytes — INCLUDING the trailing
 *    newline (LanLinkProvider:541-543; upstream TODO notes protocol v8 may
 *    drop identity from this packet entirely since v8 re-exchanges identity
 *    after the socket is encrypted).
 * 3. On receiving a broadcast: validate identity, validate `tcpPort` in
 *    [MIN_PORT]..[MAX_PORT], open TCP to (source ip, tcpPort) and immediately
 *    send own identity as the first TCP message — augmented with
 *    `targetDeviceId` (the peer's deviceId) and `targetProtocolVersion` (the
 *    peer's protocolVersion echoed as a string) per LanLinkProvider:259-261.
 *    The listener rejects connections whose `targetProtocolVersion` differs
 *    from its own (:180-187).
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
     * Wire-identical to upstream LanLinkProvider:539-543 — the trailing
     * newline from serialize() is part of the datagram.
     */
    fun buildBroadcastFrame(identity: DeviceIdentity, tcpPort: Int): String =
        identity.copy(tcpPort = tcpPort).toIdentityPacket().serialize()

    /**
     * Builds the first-packet-over-TCP identity (upstream LanLinkProvider:
     * 255-262): own identity + `targetDeviceId` + `targetProtocolVersion`
     * (the peer's protocolVersion, echoed — upstream writes it as a string).
     */
    fun buildTcpHandshakeFrame(self: DeviceIdentity, peer: DeviceIdentity): String =
        self.copy(
            targetDeviceId = peer.id,
            targetProtocolVersion = peer.protocolVersion,
        ).toIdentityPacket().serialize()

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
        if (!DeviceIdentity.isValidIdentityPacket(np)) return null
        return DeviceIdentity.fromIdentityPacket(np)
    }

    /** Upstream tcpPort sanity check before opening a TCP connection. */
    fun isValidTcpPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
}
