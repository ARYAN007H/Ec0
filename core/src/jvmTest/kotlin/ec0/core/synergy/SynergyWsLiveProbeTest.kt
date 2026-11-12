/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Live probe against the real Synergy engine — IGNORED until T4 (dynamic
 * capture) gives us: the real WS URL path, the first-message handshake, and
 * the reachable host (SoftAP gateway 192.168.137.1 requires the PC to be on
 * the phone's hotspot; Wi-Fi Direct / LAN addresses differ).
 */
package ec0.core.synergy

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore("T4 — needs live Synergy peer + real URL path/handshake from capture")
class SynergyWsLiveProbeTest {

    @Test
    fun portWalkReachesPhoneHotspotGateway() {
        val client = SynergyWsClient(
            host = SynergyTransport.SOFTAP_GATEWAY_HOTSPOT, // 192.168.137.1
            profile = SynergyWsClient.Profile.COMPUTER,
            handshake = SynergyHandshake.None,
        )
        val session = client.connect()
        try {
            val first = session.receive()
            assertTrue(first != null, "expected the peer's first envelope after upgrade")
        } finally {
            session.close()
        }
    }
}
