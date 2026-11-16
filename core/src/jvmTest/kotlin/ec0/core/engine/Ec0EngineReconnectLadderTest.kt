/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Reconnect ladder: when mDNS multicast is filtered (hotspot client isolation), a
 * trusted peer that restarts must still be reachable via its last-known IP dialed
 * across the standard port tier. Also covers the server's bind walk onto the fallback
 * tier when the primary port is squatted.
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.transport.Ec0Server
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Ec0EngineReconnectLadderTest {

    private class NoopMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): MdnsDatagram? = null
        override fun close() = Unit
    }

    private val openNodes = mutableListOf<Ec0EngineNode>()

    private fun node(
        displayName: String,
        role: String,
        pinStore: InMemoryPinStore,
        identity: ec0.core.engine.crypto.Ed25519KeyPair = Ed25519Identity.generateKeyPair(),
    ): Ec0EngineNode = Ec0EngineNode(
        localIdentity = identity,
        displayName = displayName,
        role = role,
        listenPort = 0,
        downloadDir = java.nio.file.Files.createTempDirectory("ec0-ladder").toFile(),
        pinStore = pinStore,
        mdnsTransportFactory = { NoopMdnsTransport() },
    ).also { openNodes.add(it) }

    @AfterTest
    fun closeNodes() {
        openNodes.forEach { runCatching { it.close() } }
        openNodes.clear()
    }

    @Test
    fun reconnectLadderDialsLastKnownIpAcrossInjectedPorts() {
        val phonePinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true)
        val desktopPinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true)

        val phoneIdentity = Ed25519Identity.generateKeyPair()
        val phoneNode1 = node("CPH2733", EngineConstants.ROLE_PHONE, phonePinStore, phoneIdentity)
        val desktopNode = node("Aryan Arch Linux", EngineConstants.ROLE_DESKTOP, desktopPinStore)
        phoneNode1.startServer(startMdns = false)
        desktopNode.startServer(startMdns = false)

        // First contact: QR pairing pins both sides and captures the phone's IP.
        val qr = phoneNode1.generatePairingQr(ips = listOf("127.0.0.1"))
        desktopNode.pairAndConnectWithQrUri(qr.toUri())
        val phoneDeviceId = phoneIdentity.deviceId
        assertSessionConnected(desktopNode, phoneDeviceId)
        assertSessionConnected(phoneNode1, desktopNode.localIdentity.deviceId)

        // Simulate a phone restart: server gone, same identity back on a fresh port.
        desktopNode.disconnectPeer(phoneDeviceId)
        runCatching { phoneNode1.close() }
        val phoneNode2 = Ec0EngineNode(
            localIdentity = phoneIdentity,
            displayName = "CPH2733",
            role = EngineConstants.ROLE_PHONE,
            listenPort = 0,
            downloadDir = java.nio.file.Files.createTempDirectory("ec0-ladder-2").toFile(),
            pinStore = phonePinStore,
            mdnsTransportFactory = { NoopMdnsTransport() },
        ).also { openNodes.add(it) }
        phoneNode2.startServer(startMdns = false)

        // No mDNS anywhere — the ladder must dial 127.0.0.1 × the restarted port.
        val reconnected = desktopNode.reconnectToPeer(
            phoneDeviceId,
            fallbackPorts = listOf(phoneNode2.boundServerPort),
        )
        assertEquals(phoneDeviceId, reconnected.peerDeviceId)
        assertSessionConnected(desktopNode, phoneDeviceId)
        assertSessionConnected(phoneNode2, desktopNode.localIdentity.deviceId)
    }

    @Test
    fun serverWalksFallbackTierWhenPrimaryPortIsSquatted() {
        val squatter = ServerSocket(0)
        val occupiedPort = squatter.localPort
        val server = Ec0Server(
            localIdentity = Ed25519Identity.generateKeyPair(),
            localAlias = "tier-walk-test",
            pairingCoordinator = PairingCoordinator(Ed25519Identity.generateKeyPair()),
            bindPort = occupiedPort,
        )
        try {
            server.start()
            assertNotEquals(occupiedPort, server.boundPort, "must not fail startup on a busy primary port")
            assertTrue(server.boundPort > 0, "must bind a usable tier or ephemeral port")
        } finally {
            runCatching { server.close() }
            runCatching { squatter.close() }
        }
    }

    private fun assertSessionConnected(node: Ec0EngineNode, peerDeviceId: String) {
        var attempts = 0
        while (node.activeSessionFor(peerDeviceId) == null && attempts < 50) {
            Thread.sleep(20)
            attempts++
        }
        assertTrue(
            node.activeSessionFor(peerDeviceId) != null,
            "expected an authenticated session between ${node.displayName} and $peerDeviceId",
        )
    }
}
