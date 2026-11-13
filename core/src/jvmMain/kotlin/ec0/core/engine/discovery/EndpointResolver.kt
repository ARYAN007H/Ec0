/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * 4-Tier Endpoint Resolver for ec0-engine (design.md §5.3):
 *   1. Direct QR / Manual Pairing Override (when explicitly supplied for immediate dial)
 *   2. Live mDNS Cache (`_ec0._tcp.local.`: SRV port first, then port-walk fallback)
 *   3. Persisted Last-Known Peer IP (last successful port first, then port-walk fallback)
 *   4. SoftAP / Wi-Fi Direct Gateway Fallback (`192.168.137.1`, `192.168.49.1`, `192.168.43.1`)
 */
package ec0.core.engine.discovery

import ec0.core.engine.EngineConstants
import ec0.core.engine.PairingQrPayload
import java.util.concurrent.ConcurrentHashMap

data class EndpointCandidate(
    val host: String,
    val port: Int,
    val tier: Tier,
) {
    enum class Tier {
        USB_ADB_TUNNEL,
        QR_OVERRIDE,
        MDNS_LIVE,
        LAST_KNOWN_IP,
        GATEWAY_FALLBACK,
    }
}

data class LastKnownEndpoint(
    val host: String,
    val port: Int,
    val updatedAtMs: Long,
)

class EndpointResolver(
    private val mdnsDiscovery: Ec0MdnsDiscovery? = null,
    private val portWalkList: List<Int> = EngineConstants.DEFAULT_PORT_LIST,
    private val fallbackGateways: List<String> = EngineConstants.FALLBACK_GATEWAY_IPS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastKnownByDevice = ConcurrentHashMap<String, LastKnownEndpoint>()

    /**
     * Records a successful connection to [deviceId] at `[host]:[port]` so
     * future reconnects after multicast loss or ColorOS screen-off can dial
     * the known IP directly before falling back to SoftAP/P2P gateways.
     */
    fun recordSuccess(deviceId: String, host: String, port: Int, nowMs: Long = clockMs()) {
        lastKnownByDevice[deviceId.lowercase()] = LastKnownEndpoint(host, port, nowMs)
    }

    fun lastKnownFor(deviceId: String): LastKnownEndpoint? =
        lastKnownByDevice[deviceId.lowercase()]

    /**
     * Produces a deduplicated, priority-ordered list of `(host, port)` dial targets
     * for [deviceId].
     */
    fun resolveCandidates(
        deviceId: String,
        qrOverride: PairingQrPayload? = null,
        explicitHostOverride: String? = null,
        explicitPortOverride: Int? = null,
        usbForwardedPort: Int? = null,
        includeGatewayFallback: Boolean = true,
        nowMs: Long = clockMs(),
    ): List<EndpointCandidate> {
        val seen = HashSet<Pair<String, Int>>()
        val out = ArrayList<EndpointCandidate>()

        fun addCandidate(host: String, port: Int, tier: EndpointCandidate.Tier) {
            val trimmed = host.trim()
            if (trimmed.isEmpty() || port !in 1..65535) return
            if (seen.add(trimmed to port)) {
                out.add(EndpointCandidate(trimmed, port, tier))
            }
        }

        fun addHostWithPortWalk(host: String, preferredPort: Int?, tier: EndpointCandidate.Tier) {
            if (preferredPort != null && preferredPort in 1..65535) {
                addCandidate(host, preferredPort, tier)
            }
            for (p in portWalkList) {
                addCandidate(host, p, tier)
            }
        }

        // Tier 0a: USB-C adb forward deterministic tunnel (`127.0.0.1:<usbForwardedPort>`)
        if (usbForwardedPort != null && usbForwardedPort in 1..65535) {
            addCandidate("127.0.0.1", usbForwardedPort, EndpointCandidate.Tier.USB_ADB_TUNNEL)
        }

        // Tier 0b: Explicit QR or manual override
        if (qrOverride != null) {
            for (ip in qrOverride.ips) {
                if (ip.isNotBlank()) {
                    addHostWithPortWalk(ip, qrOverride.port, EndpointCandidate.Tier.QR_OVERRIDE)
                }
            }
        }
        if (!explicitHostOverride.isNullOrBlank()) {
            addHostWithPortWalk(explicitHostOverride, explicitPortOverride, EndpointCandidate.Tier.QR_OVERRIDE)
        }

        // Tier 1: Live mDNS cache
        val livePeer = mdnsDiscovery?.currentPeer(deviceId, nowMs)
        if (livePeer != null) {
            for (addr in livePeer.addresses) {
                addHostWithPortWalk(addr, livePeer.port, EndpointCandidate.Tier.MDNS_LIVE)
            }
        }

        // Tier 2: Persisted last-known peer endpoint
        val lastKnown = lastKnownByDevice[deviceId.lowercase()]
        if (lastKnown != null) {
            addHostWithPortWalk(lastKnown.host, lastKnown.port, EndpointCandidate.Tier.LAST_KNOWN_IP)
        }

        // Tier 3: SoftAP / Wi-Fi Direct Gateway Fallback (192.168.137.1, 192.168.49.1, 192.168.43.1)
        if (includeGatewayFallback) {
            for (gateway in fallbackGateways) {
                addHostWithPortWalk(gateway, null, EndpointCandidate.Tier.GATEWAY_FALLBACK)
            }
        }

        return out
    }
}
