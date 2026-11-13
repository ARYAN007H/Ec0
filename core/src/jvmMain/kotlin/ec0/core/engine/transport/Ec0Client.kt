/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Outbound ec0-engine WebSocket connector with 4-tier EndpointResolver port walk
 * and full-jitter exponential backoff (`docs/engine/design.md` §5.3).
 */
package ec0.core.engine.transport

import ec0.core.engine.EngineConstants
import ec0.core.engine.OfflineFlushDeduplicator
import ec0.core.engine.PairingQrPayload
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.HandshakeSecurityException
import ec0.core.engine.crypto.KeyMismatchException
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.discovery.EndpointCandidate
import ec0.core.engine.discovery.EndpointResolver
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

class Ec0Client(
    private val localIdentity: Ed25519KeyPair,
    private val localAlias: String,
    private val pairingCoordinator: PairingCoordinator,
    private val endpointResolver: EndpointResolver = EndpointResolver(),
    private val offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
    private val connectTimeoutMs: Int = 3_000,
    private val ioTimeoutMs: Int = 50_000,
    private val pingIntervalMs: Long = 5_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Connects to [peerDeviceId] by walking candidates from [endpointResolver]
     * (QR override -> live mDNS -> last-known IP -> SoftAP/P2P gateways).
     * Records the winning `(host, port)` in [endpointResolver] upon authentication.
     */
    fun connectToPeer(
        peerDeviceId: String,
        qrPayload: PairingQrPayload? = null,
        explicitHost: String? = null,
        explicitPort: Int? = null,
        includeGatewayFallback: Boolean = true,
    ): Ec0Session {
        val candidates = endpointResolver.resolveCandidates(
            deviceId = peerDeviceId,
            qrOverride = qrPayload,
            explicitHostOverride = explicitHost,
            explicitPortOverride = explicitPort,
            includeGatewayFallback = includeGatewayFallback,
            nowMs = nowMs(),
        )
        if (candidates.isEmpty()) {
            throw IOException("No endpoint candidates available for peer $peerDeviceId")
        }

        val qrTarget: Pair<ByteArray, ByteArray>? = qrPayload?.let {
            it.nonce to it.publicKeyRaw
        }

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val session = connectDirect(
                    host = candidate.host,
                    port = candidate.port,
                    qrPairingTarget = qrTarget,
                )
                endpointResolver.recordSuccess(
                    deviceId = session.peerDeviceId,
                    host = candidate.host,
                    port = candidate.port,
                    nowMs = nowMs(),
                )
                return session
            } catch (e: KeyMismatchException) {
                // Fail closed immediately on pinned-key mismatch
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        when (val err = lastError) {
            is HandshakeSecurityException -> throw err
            is IOException -> throw err
            else -> throw IOException("Exhausted ${candidates.size} endpoint candidates for $peerDeviceId", err)
        }
    }

    /**
     * Dials `[host]:[port]`, performs the RFC 6455 WebSocket upgrade on `/ec0/v1`,
     * and executes the 4-message mutual authentication handshake.
     */
    fun connectDirect(
        host: String,
        port: Int,
        qrPairingTarget: Pair<ByteArray, ByteArray>? = null,
        path: String = EngineConstants.WS_PATH,
    ): Ec0Session {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = ioTimeoutMs

            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val clientKey = Ec0WsCodec.newClientKey()
            val req = Ec0WsCodec.buildClientUpgradeRequest(host, port, path, clientKey)
            output.write(req.toByteArray(Charsets.US_ASCII))
            output.flush()

            val respHeader = Ec0WsCodec.readHttpHeaderBlock(input)
            val (statusCode, headers) = Ec0WsCodec.parseServerUpgradeResponse(respHeader)
            if (statusCode != 101) {
                throw IOException("WebSocket upgrade rejected by $host:$port with HTTP $statusCode")
            }
            val expectedAccept = Ec0WsCodec.acceptKey(clientKey)
            if (headers["sec-websocket-accept"] != expectedAccept) {
                throw IOException("WebSocket upgrade Sec-WebSocket-Accept mismatch from $host:$port")
            }

            val session = Ec0Session.performInitiatorHandshake(
                socket = socket,
                input = input,
                output = output,
                localIdentity = localIdentity,
                localAlias = localAlias,
                pairingCoordinator = pairingCoordinator,
                qrPairingTarget = qrPairingTarget,
                offlineDeduplicator = offlineDeduplicator,
                nowMs = nowMs,
            )
            session.startPingLoop(pingIntervalMs)
            return session
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    companion object {
        private val jitterRandom = SecureRandom()

        /**
         * Full-jitter exponential backoff (`docs/engine/design.md` §5.3):
         *   `cap = min(maxDelayMs, baseDelayMs * 2^attempt)`
         *   `sleepMs = floor(randomFraction * cap)`
         */
        fun computeFullJitterBackoffMs(
            attempt: Int,
            baseDelayMs: Long = 500L,
            maxDelayMs: Long = 30_000L,
            randomFraction: Double = jitterRandom.nextDouble(),
        ): Long {
            require(attempt >= 0) { "Backoff attempt must be >= 0" }
            require(baseDelayMs > 0L && maxDelayMs >= baseDelayMs) { "Invalid backoff bounds" }
            val clampedFraction = randomFraction.coerceIn(0.0, 1.0)
            val shift = attempt.coerceAtMost(20)
            val expDelay = baseDelayMs * (1L shl shift)
            val cap = minOf(maxDelayMs, expDelay)
            return (clampedFraction * cap.toDouble()).toLong()
        }
    }
}
