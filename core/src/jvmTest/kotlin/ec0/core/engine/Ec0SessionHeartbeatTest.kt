/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Session liveness: both transports enforce a read timeout (50 s in
 * production), so a session with a silent peer dies at exactly that timeout —
 * observed live as a ~60 s churn masked by the reconnect supervisor's
 * re-dialing. The RFC 6455 PING heartbeat (both roles) keeps idle sessions
 * alive; these tests pin the contract with shrunk timers.
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ec0SessionHeartbeatTest {

    private class NoOpMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): MdnsDatagram? = null
        override fun close() = Unit
    }

    private val openNodes = mutableListOf<Ec0EngineNode>()

    @AfterTest
    fun closeNodes() {
        openNodes.forEach { runCatching { it.close() } }
        openNodes.clear()
    }

    private fun node(
        displayName: String,
        role: String,
        pingIntervalMs: Long,
        ioTimeoutMs: Int,
    ): Ec0EngineNode = Ec0EngineNode(
        localIdentity = Ed25519Identity.generate(),
        displayName = displayName,
        role = role,
        listenPort = 0,
        downloadDir = Files.createTempDirectory("ec0-heartbeat").toFile(),
        pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
        mdnsTransportFactory = { NoOpMdnsTransport() },
        sessionPingIntervalMs = pingIntervalMs,
        sessionIoTimeoutMs = ioTimeoutMs,
    ).also { openNodes.add(it) }

    @Test
    fun idleSessionSurvivesMultipleReadTimeoutsWhilePinging() {
        val phone = node(
            EngineConstants.ROLE_PHONE, EngineConstants.ROLE_PHONE,
            pingIntervalMs = 100L, ioTimeoutMs = 400,
        )
        val desktop = node(
            "Aryan Arch Linux", EngineConstants.ROLE_DESKTOP,
            pingIntervalMs = 100L, ioTimeoutMs = 400,
        )
        phone.startServer(startMdns = false)
        desktop.startServer(startMdns = false)

        val phoneId = phone.localIdentity.deviceId
        runCatching {
            desktop.pairAndConnectWithQrUri(phone.generatePairingQr(ips = listOf("127.0.0.1")).toUri())
        }.getOrThrow()

        // 1.5 s idle = ~3.75× the read timeout. Without the heartbeat both
        // readers starve and the session dies within ~400 ms of the last byte.
        Thread.sleep(1_500L)
        assertNotNull(desktop.activeSessionFor(phoneId), "desktop session must survive idling while pinging")
        val desktopId = desktop.localIdentity.deviceId
        assertNotNull(phone.activeSessionFor(desktopId), "phone session must survive idling while pinging")
    }

    @Test
    fun silentPeerDiesAtTheReadTimeout() {
        // Control: heartbeat OFF (the pre-fix construction) — the dialer's
        // reader starves and the session dies at the read timeout. This pins
        // the timeout semantics the heartbeat exists to satisfy.
        val phone = node(
            EngineConstants.ROLE_PHONE, EngineConstants.ROLE_PHONE,
            pingIntervalMs = 0L, ioTimeoutMs = 400,
        )
        val desktop = node(
            "Aryan Arch Linux", EngineConstants.ROLE_DESKTOP,
            pingIntervalMs = 0L, ioTimeoutMs = 400,
        )
        phone.startServer(startMdns = false)
        desktop.startServer(startMdns = false)

        val phoneId = phone.localIdentity.deviceId
        runCatching {
            desktop.pairAndConnectWithQrUri(phone.generatePairingQr(ips = listOf("127.0.0.1")).toUri())
        }.getOrThrow()
        val session = assertNotNull(
            desktop.activeSessionFor(phoneId),
            "session must be live immediately after pairing",
        )

        Thread.sleep(1_200L)
        assertNull(
            desktop.activeSessionFor(phoneId),
            "without a heartbeat an idle session must die at the read timeout",
        )
        assertTrue(
            session.isClosed,
            "the abandoned session's socket must be closed by the receive loop, not leaked in CLOSE_WAIT",
        )
    }

    @Test
    fun replacedSessionClosesItsSocketInsteadOfLeaking() {
        // Receive-loop hygiene: the abandoned session's socket must close, not
        // linger in CLOSE_WAIT until the process dies.
        val phone = node(
            EngineConstants.ROLE_PHONE, EngineConstants.ROLE_PHONE,
            pingIntervalMs = 100L, ioTimeoutMs = 50_000,
        )
        val desktop = node(
            "Aryan Arch Linux", EngineConstants.ROLE_DESKTOP,
            pingIntervalMs = 100L, ioTimeoutMs = 50_000,
        )
        phone.startServer(startMdns = false)
        desktop.startServer(startMdns = false)

        val phoneId = phone.localIdentity.deviceId
        val first = runCatching {
            desktop.pairAndConnectWithQrUri(phone.generatePairingQr(ips = listOf("127.0.0.1")).toUri())
        }.getOrThrow()
        val second = runCatching {
            desktop.reconnectToPeer(phoneId)
        }.getOrThrow()

        assertTrue(second !== first, "reconnect must establish a fresh session object")
        assertTrue(first.isClosed, "the replaced session must be closed, not orphaned open")
        assertTrue(desktop.activeSessionFor(phoneId) === second, "the live session must be the newest one")
        assertEquals(second, desktop.activeSessionFor(phoneId))
    }
}
