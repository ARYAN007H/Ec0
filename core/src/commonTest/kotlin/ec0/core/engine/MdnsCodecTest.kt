/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdnsCodecTest {

    private val sampleDeviceId = "fedcba9876548bd43a3a080882657023c6ef4be26cf9736dbca4a9a7fffe329f"

    private val samplePeer = PeerInfo(
        deviceId = sampleDeviceId,
        displayName = "OnePlus 12",
        role = EngineConstants.ROLE_PHONE,
        protocolVersion = EngineConstants.PROTOCOL_VERSION,
        capabilities = setOf(
            EngineCapability.CLIPBOARD,
            EngineCapability.FILE,
            EngineCapability.INPUT,
            EngineCapability.NOTIF,
            EngineCapability.PRESENCE,
            EngineCapability.SCRCPY,
        ),
        shortFingerprint = "fedcba987654",
        pairingOpen = true,
        addresses = listOf("192.168.1.42", "fe80:0:0:0:1234:5678:9abc:def0"),
        port = 49880,
        lastSeenMs = 1762905600000L,
    )

    @Test
    fun ptrQueryMatchesExactRfc6762WireBytes() {
        val encoded = MdnsCodec.encodePtrQuery()
        assertEquals(33, encoded.size)
        assertEquals(
            "000000000001000000000000045f656330045f746370056c6f63616c00000c0001",
            EngineEncoding.encodeHex(encoded),
        )

        val decoded = MdnsCodec.decodePacket(encoded)
        assertEquals(0, decoded.header.flags)
        assertEquals(1, decoded.questions.size)
        assertEquals("_ec0._tcp.local.", decoded.questions[0].name)
        assertEquals(MdnsCodec.TYPE_PTR, decoded.questions[0].qType)
        assertEquals(MdnsCodec.CLASS_IN, decoded.questions[0].qClass)

        val quEncoded = MdnsCodec.encodePtrQuery(unicastResponse = true)
        assertEquals(
            "000000000001000000000000045f656330045f746370056c6f63616c00000c8001",
            EngineEncoding.encodeHex(quEncoded),
        )
    }

    @Test
    fun announcementRoundtripExtractsFullPeerInfo() {
        val wire = MdnsCodec.encodeAnnouncement(samplePeer, ttlSeconds = 120L)
        val packet = MdnsCodec.decodePacket(wire)

        assertEquals(MdnsCodec.FLAGS_RESPONSE_AA, packet.header.flags)
        assertEquals(5, packet.answers.size) // PTR, SRV, TXT, A, AAAA

        val extracted = MdnsCodec.extractPeers(packet, nowMs = 1762905600500L)
        assertEquals(1, extracted.size)
        val record = extracted[0]
        assertFalse(record.isGoodbye)
        assertEquals(120L, record.ttlSeconds)

        val peer = record.peer
        assertEquals(sampleDeviceId, peer.deviceId)
        assertEquals("OnePlus 12", peer.displayName)
        assertEquals(EngineConstants.ROLE_PHONE, peer.role)
        assertEquals("fedcba987654", peer.shortFingerprint)
        assertTrue(peer.pairingOpen)
        assertEquals(49880, peer.port)
        assertEquals(listOf("192.168.1.42", "fe80:0:0:0:1234:5678:9abc:def0"), peer.addresses)
        assertEquals(samplePeer.capabilities, peer.capabilities)
        assertFalse(record.isExpired(1762905600500L + 119_000L))
        assertTrue(record.isExpired(1762905600500L + 120_000L))
    }

    @Test
    fun goodbyePacketSetsZeroTtlAndMarksGoodbye() {
        val wire = MdnsCodec.encodeGoodbye(samplePeer)
        val packet = MdnsCodec.decodePacket(wire)
        val extracted = MdnsCodec.extractPeers(packet, nowMs = 1762905600500L)
        assertEquals(1, extracted.size)
        assertTrue(extracted[0].isGoodbye)
        assertTrue(extracted[0].isExpired(1762905600500L))
    }

    @Test
    fun decodesRfc1035LabelCompressionPointersAndRejectsLoops() {
        val compressedWire = EngineEncoding.fromHex(
            "000084000000000100000000045f656330045f746370056c6f63616c00000c000100000078000f0c666564636261393837363534c00c",
        )
        val decoded = MdnsCodec.decodePacket(compressedWire)
        assertEquals(1, decoded.answers.size)
        val ptr = decoded.answers[0] as MdnsResourceRecord.PtrRecord
        assertEquals("_ec0._tcp.local.", ptr.name)
        assertEquals("fedcba987654._ec0._tcp.local.", ptr.ptrDomainName)
        assertEquals(120L, ptr.ttlSeconds)

        // Malicious self-referencing pointer at offset 12 (0xC0 0x0C) must fail closed
        val cyclicWire = EngineEncoding.fromHex("000000000001000000000000c00c000c0001")
        assertFailsWith<IllegalArgumentException> {
            MdnsCodec.decodePacket(cyclicWire)
        }

        // Truncated header (< 12 bytes) must fail closed
        assertFailsWith<IllegalArgumentException> {
            MdnsCodec.decodePacket(byteArrayOf(0x00, 0x01, 0x02))
        }
    }
}
