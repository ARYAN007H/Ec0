/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Walled-garden unpair tests (audit H3): the wire `unpair` command acts ONLY on
 * the sender's own pairing — peer A can never unpair peer B — and a successful
 * unpair removes the sender's pin and closes its session.
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ec0EngineUnpairTest {

    private class NoopMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): MdnsDatagram? = null
        override fun close() = Unit
    }

    private fun node(displayName: String): Ec0EngineNode = Ec0EngineNode(
        localIdentity = Ed25519Identity.generateKeyPair(),
        displayName = displayName,
        listenPort = 0,
        downloadDir = Files.createTempDirectory("ec0-unpair").toFile(),
        pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
        mdnsTransportFactory = { NoopMdnsTransport() },
    )

    private fun unpairEnvelope(deviceIdField: String? = null): Ec0Envelope = Ec0Envelope(
        msgType = "cmd",
        msgBody = buildJsonObject {
            put("cmd_type", EngineConstants.CMD_UNPAIR)
            put(
                "cmd_body",
                buildJsonObject {
                    put("reason", "user_unpair")
                    if (deviceIdField != null) {
                        put("device_id", deviceIdField)
                    }
                },
            )
        },
        srcRole = "phone",
        category = EngineConstants.CATEGORY_SYSTEM,
        timestamp = 0L,
        seq = 1L,
        nonce = 0L,
        sig = "test",
    )

    /** Real paired session so the dispatch chain runs exactly as production. */
    private fun pairTwoNodes(): Pair<Ec0EngineNode, Ec0EngineNode> {
        val desktop = node("audit-desktop")
        val phone = node("audit-phone")
        desktop.startServer(startMdns = false)
        phone.startServer(startMdns = false)
        val qr = phone.generatePairingQr(ips = listOf("127.0.0.1"))
        desktop.pairAndConnectWithQrUri(qr.toUri())
        var attempts = 0
        while (desktop.activeSessionFor(phone.localIdentity.deviceId) == null && attempts < 50) {
            Thread.sleep(20)
            attempts++
        }
        assertNotNull(desktop.activeSessionFor(phone.localIdentity.deviceId), "desktop session registered")
        return desktop to phone
    }

    @Test
    fun wireUnpairRemovesSenderPinAndClosesSession() {
        val (desktop, phone) = pairTwoNodes()
        val phoneId = phone.localIdentity.deviceId
        val session = desktop.activeSessionFor(phoneId)!!

        // The sender (phone) sends an authorized self-unpair over the live session.
        desktop.routeControlEnvelope(
            peerDeviceId = phoneId,
            envelope = unpairEnvelope(deviceIdField = phoneId),
            session = session,
        )

        assertNull(desktop.pinStore.get(phoneId), "sender's pin removed")
        assertNull(desktop.activeSessionFor(phoneId), "sender's session closed")
        assertTrue(
            desktop.pinnedPeers.value.none { it.deviceId == phoneId },
            "UI pin list refreshed",
        )
    }

    @Test
    fun wireUnpairWithForeignDeviceIdIsRejected() {
        val (desktop, phone) = pairTwoNodes()
        val phoneId = phone.localIdentity.deviceId
        val session = desktop.activeSessionFor(phoneId)!!

        val thirdParty = Ed25519Identity.generateKeyPair()
        desktop.routeControlEnvelope(
            peerDeviceId = phoneId,
            envelope = unpairEnvelope(deviceIdField = thirdParty.deviceId),
            session = session,
        )

        assertNotNull(
            desktop.pinStore.get(phoneId),
            "peer A cannot unpair peer B — sender's pin intact",
        )
        assertEquals(
            EngineConnectionState.CONNECTED,
            desktop.connectionStates.value[phoneId],
            "sender session survives the rejected command",
        )
    }

    @Test
    fun wireUnpairWithoutDeviceIdTargetsTheSenderOnly() {
        val (desktop, phone) = pairTwoNodes()
        val phoneId = phone.localIdentity.deviceId
        val session = desktop.activeSessionFor(phoneId)!!

        val bystander = Ed25519Identity.generateKeyPair()
        desktop.pinStore.verifyOrPin(
            deviceId = bystander.deviceId,
            publicKeyRaw = bystander.publicKeyRaw,
            alias = "innocent-bystander",
            trustLevel = TrustLevel.TRUSTED_QR,
        )

        desktop.routeControlEnvelope(
            peerDeviceId = phoneId,
            envelope = unpairEnvelope(deviceIdField = null),
            session = session,
        )

        assertNull(desktop.pinStore.get(phoneId), "sender unpinned")
        assertNotNull(desktop.pinStore.get(bystander.deviceId), "bystander pin untouched")
    }
}
