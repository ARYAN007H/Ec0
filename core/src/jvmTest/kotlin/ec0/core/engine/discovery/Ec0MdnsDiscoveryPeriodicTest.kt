/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Periodic re-announce: startup bursts alone leave late-joining peers blind
 * (hotspot client-isolation drops our queries) — the discovery must keep
 * announcing on a cadence so the reconnect supervisor can find peers again.
 */
package ec0.core.engine.discovery

import ec0.core.engine.EngineConstants
import ec0.core.engine.PeerInfo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class Ec0MdnsDiscoveryPeriodicTest {

    private class RecordingTransport : MdnsDatagramTransport {
        val sent = CopyOnWriteArrayList<ByteArray>()
        override fun send(payload: ByteArray) {
            sent.add(payload.copyOf())
        }

        override fun receive(): MdnsDatagram? {
            // Stand-in for the real transport's 1 s receive timeout — keeps the
            // receive loop from spinning hot under test.
            Thread.sleep(20)
            return null
        }

        override fun close() {}
    }

    @Test
    fun periodicAnnouncesKeepFiringAfterTheStartupBurst() = runBlocking {
        val transport = RecordingTransport()
        val discovery = Ec0MdnsDiscovery(
            localPeer = PeerInfo(
                deviceId = "test-device",
                displayName = "ec0-test",
                role = EngineConstants.ROLE_DESKTOP,
                port = 49_880,
                addresses = listOf("127.0.0.1"),
            ),
            transportFactory = { transport },
            clockMs = { System.currentTimeMillis() },
        )
        try {
            discovery.start(
                burstDelaysMs = listOf(10L, 20L),
                periodicAnnounceIntervalMs = 60L,
            )
            // Startup: 2 bursts × (announce + query). Then the periodic loop must keep
            // adding sends — assert growth well past the startup count.
            withTimeout(2_000) {
                while (transport.sent.size < 10) delay(20)
            }
            val atStartupSettled = transport.sent.size
            delay(200)
            assertTrue(
                transport.sent.size >= atStartupSettled + 4,
                "expected periodic re-announces after the startup burst, " +
                    "got ${transport.sent.size} (was $atStartupSettled at settle)",
            )
        } finally {
            discovery.close()
        }
    }
}
