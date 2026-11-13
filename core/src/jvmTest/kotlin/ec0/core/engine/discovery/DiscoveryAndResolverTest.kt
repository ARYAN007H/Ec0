/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.discovery

import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConstants
import ec0.core.engine.MdnsCodec
import ec0.core.engine.PairingQrPayload
import ec0.core.engine.PeerInfo
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryAndResolverTest {

    private val desktopId = "f097da1fa8f0eb3bc16e00465ccbd67c9d19801d219cf90d94609a043d09bf4b"
    private val phoneId = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4"

    private val desktopPeer = PeerInfo(
        deviceId = desktopId,
        displayName = "Aryan Arch Linux",
        role = EngineConstants.ROLE_DESKTOP,
        protocolVersion = 1,
        capabilities = setOf(EngineCapability.CLIPBOARD, EngineCapability.FILE),
        shortFingerprint = desktopId.take(12),
        pairingOpen = false,
        addresses = listOf("192.168.1.10"),
        port = 49880,
        lastSeenMs = 1_000_000L,
    )

    private val phonePeer = PeerInfo(
        deviceId = phoneId,
        displayName = "OnePlus 12",
        role = EngineConstants.ROLE_PHONE,
        protocolVersion = 1,
        capabilities = setOf(EngineCapability.CLIPBOARD, EngineCapability.FILE, EngineCapability.NOTIF),
        shortFingerprint = phoneId.take(12),
        pairingOpen = true,
        addresses = listOf("192.168.1.42"),
        port = 39888,
        lastSeenMs = 1_000_000L,
    )

    private class FakeMdnsTransport : MdnsDatagramTransport {
        val outbound = LinkedBlockingQueue<ByteArray>()
        val inbound = LinkedBlockingQueue<MdnsDatagram>()

        override fun send(payload: ByteArray) {
            outbound.offer(payload)
        }

        override fun receive(): MdnsDatagram? = inbound.poll(20, TimeUnit.MILLISECONDS)

        override fun close() {
            inbound.clear()
        }
    }

    @Test
    fun discoveryCachesRemotePeerIgnoresSelfAndEvictsOnGoodbyeOrTtl() {
        var now = 1_000_000L
        val fakeTransport = FakeMdnsTransport()
        val discovery = Ec0MdnsDiscovery(
            localPeer = desktopPeer,
            transportFactory = { fakeTransport },
            clockMs = { now },
            ttlSeconds = 60L,
        )

        val notified = mutableListOf<PeerInfo>()
        discovery.addPeerListener { notified.add(it) }

        // Self announcement must be ignored
        discovery.ingestDatagram(
            payload = MdnsCodec.encodeAnnouncement(desktopPeer, ttlSeconds = 60L),
            senderIp = "192.168.1.10",
            nowMs = now,
        )
        assertTrue(discovery.peers.value.isEmpty())

        // Remote phone announcement is cached and notifies listener
        discovery.ingestDatagram(
            payload = MdnsCodec.encodeAnnouncement(phonePeer, ttlSeconds = 60L),
            senderIp = "192.168.1.42",
            nowMs = now,
        )
        assertEquals(1, discovery.peers.value.size)
        assertEquals(phoneId, discovery.peers.value[0].deviceId)
        assertEquals(1, notified.size)
        assertNotNull(discovery.currentPeer(phoneId, nowMs = now + 30_000L))

        // Advance past 60s TTL -> pruned
        now += 61_000L
        discovery.pruneExpired(now)
        assertTrue(discovery.peers.value.isEmpty())
        assertNull(discovery.currentPeer(phoneId, nowMs = now))

        // Re-announce then send TTL=0 Goodbye -> immediate eviction
        discovery.ingestDatagram(
            payload = MdnsCodec.encodeAnnouncement(phonePeer, ttlSeconds = 120L),
            senderIp = "192.168.1.42",
            nowMs = now,
        )
        assertEquals(1, discovery.peers.value.size)

        discovery.ingestDatagram(
            payload = MdnsCodec.encodeGoodbye(phonePeer),
            senderIp = "192.168.1.42",
            nowMs = now + 500L,
        )
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun endpointResolverOrdersTiersAndDeduplicatesPortWalk() {
        var now = 2_000_000L
        val fakeTransport = FakeMdnsTransport()
        val discovery = Ec0MdnsDiscovery(
            localPeer = desktopPeer,
            transportFactory = { fakeTransport },
            clockMs = { now },
            ttlSeconds = 120L,
        )

        // Populate live mDNS with phone at 192.168.1.42:39888
        discovery.ingestDatagram(
            payload = MdnsCodec.encodeAnnouncement(phonePeer, ttlSeconds = 120L),
            senderIp = "192.168.1.42",
            nowMs = now,
        )

        val resolver = EndpointResolver(
            mdnsDiscovery = discovery,
            clockMs = { now },
        )
        // Record a previous last-known IP on a different subnet/port
        resolver.recordSuccess(phoneId, host = "192.168.1.99", port = 49888, nowMs = now - 10_000L)

        val qrOverride = PairingQrPayload(
            version = 1,
            deviceId = phoneId,
            publicKeyRaw = ec0.core.engine.EngineEncoding.fromBase64Url("3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08"),
            alias = "OnePlus 12",
            nonce = ec0.core.engine.EngineEncoding.fromHex("aabbccddeeff00112233445566778899"),
            port = 59888,
            ips = listOf("10.0.0.55"),
        )

        val candidates = resolver.resolveCandidates(
            deviceId = phoneId,
            qrOverride = qrOverride,
            includeGatewayFallback = true,
            nowMs = now,
        )

        // 1st group: QR override host 10.0.0.55 starting with QR port 59888 then remaining port walk [49880, 39888, 49888]
        assertEquals(EndpointCandidate("10.0.0.55", 59888, EndpointCandidate.Tier.QR_OVERRIDE), candidates[0])
        assertEquals(EndpointCandidate("10.0.0.55", 49880, EndpointCandidate.Tier.QR_OVERRIDE), candidates[1])
        assertEquals(EndpointCandidate("10.0.0.55", 39888, EndpointCandidate.Tier.QR_OVERRIDE), candidates[2])
        assertEquals(EndpointCandidate("10.0.0.55", 49888, EndpointCandidate.Tier.QR_OVERRIDE), candidates[3])

        // 2nd group: Live mDNS 192.168.1.42 starting with SRV port 39888 then [49880, 49888, 59888]
        assertEquals(EndpointCandidate("192.168.1.42", 39888, EndpointCandidate.Tier.MDNS_LIVE), candidates[4])
        assertEquals(EndpointCandidate("192.168.1.42", 49880, EndpointCandidate.Tier.MDNS_LIVE), candidates[5])
        assertEquals(EndpointCandidate("192.168.1.42", 49888, EndpointCandidate.Tier.MDNS_LIVE), candidates[6])
        assertEquals(EndpointCandidate("192.168.1.42", 59888, EndpointCandidate.Tier.MDNS_LIVE), candidates[7])

        // 3rd group: Last-known IP 192.168.1.99 starting with port 49888 then [49880, 39888, 59888]
        assertEquals(EndpointCandidate("192.168.1.99", 49888, EndpointCandidate.Tier.LAST_KNOWN_IP), candidates[8])
        assertEquals(EndpointCandidate("192.168.1.99", 49880, EndpointCandidate.Tier.LAST_KNOWN_IP), candidates[9])
        assertEquals(EndpointCandidate("192.168.1.99", 39888, EndpointCandidate.Tier.LAST_KNOWN_IP), candidates[10])
        assertEquals(EndpointCandidate("192.168.1.99", 59888, EndpointCandidate.Tier.LAST_KNOWN_IP), candidates[11])

        // 4th group: Gateways 192.168.137.1, 192.168.49.1, 192.168.43.1 each walked across [49880, 39888, 49888, 59888]
        assertEquals(EndpointCandidate("192.168.137.1", 49880, EndpointCandidate.Tier.GATEWAY_FALLBACK), candidates[12])
        assertEquals(24, candidates.size)
        // Verify zero duplicate (host, port) pairs
        assertEquals(candidates.size, candidates.map { it.host to it.port }.toSet().size)
    }
}
