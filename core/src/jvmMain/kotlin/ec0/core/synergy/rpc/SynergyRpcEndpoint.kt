/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * gRPC endpoint resolution over the T10 pinned-TLS transport: walks the
 * profile's port list (SynergyPortWalk) and TLS-handshake-probes each port
 * with the profile's pinned trust (SynergyTrust). The returned (host, port)
 * feeds a ManagedChannel in the transport layer — grpc-java needs a transport
 * artifact (okhttp/netty, not on core's classpath) for that wiring, which
 * lands with the desktop module (T13); adapters here are Channel-injected so
 * they are transport-agnostic.
 */
package ec0.core.synergy.rpc

import ec0.core.synergy.SynergyPortWalk
import ec0.core.synergy.SynergyTrust
import ec0.core.synergy.SynergyWsClient
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

object SynergyRpcEndpoint {

    data class Endpoint(val host: String, val port: Int)

    /**
     * Finds the first port of the profile's walk that completes a TLS
     * handshake against the profile's pinned trust. Throws IOException when
     * the walk is exhausted (same failure shape as T10's WS connect).
     */
    fun resolve(
        profile: SynergyWsClient.Profile,
        host: String,
        connectTimeoutMs: Int = 3_000,
        handshakeTimeoutMs: Int = 10_000,
    ): Endpoint {
        val walk = when (profile) {
            SynergyWsClient.Profile.COMPUTER -> SynergyPortWalk.computer()
            SynergyWsClient.Profile.PAD -> SynergyPortWalk.pad()
        }
        val factory = SynergyTrust.sslSocketFactory(profile)
        var lastError: IOException? = null
        for (port in walk.attempts()) {
            var socket: SSLSocket? = null
            try {
                socket = (factory.createSocket() as SSLSocket).also { SynergyTrust.configure(it) }
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = handshakeTimeoutMs
                socket.startHandshake() // validates the pinned cert at TLS level
                return Endpoint(host, port)
            } catch (e: IOException) {
                lastError = e
                runCatching { socket?.close() }
            }
        }
        throw IOException("Synergy gRPC endpoint walk exhausted for $host (profile=$profile)", lastError)
    }
}
