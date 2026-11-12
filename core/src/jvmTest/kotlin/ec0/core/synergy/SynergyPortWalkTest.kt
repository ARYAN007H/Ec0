/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Port-walk tests — pin both profiles to the T2 catalog's reviewer-verified
 * constants (WSCM:267 computer / :481 pad, retry = list.size()).
 */
package ec0.core.synergy

import kotlin.test.Test
import kotlin.test.assertEquals

class SynergyPortWalkTest {

    @Test
    fun computerProfileWalk() {
        val walk = SynergyPortWalk.computer()
        assertEquals(5, walk.retryCount) // retry = list.size() at call sites
        // Faithful: initial attempt + SERVERS[i] per round (dup 8080s are real)
        assertEquals(
            listOf(8080, 8080, 39888, 8080, 49888, 59888),
            walk.attempts(),
        )
        // Catalog effective order (distinct, first-attempted-first)
        assertEquals(listOf(8080, 39888, 49888, 59888), walk.effectiveOrder())
    }

    @Test
    fun padProfileWalk() {
        val walk = SynergyPortWalk.pad()
        assertEquals(5, walk.retryCount)
        assertEquals(
            listOf(8792, 8792, 39888, 8792, 49888, 59888),
            walk.attempts(),
        )
        assertEquals(listOf(8792, 39888, 49888, 59888), walk.effectiveOrder())
    }

    @Test
    fun padProfileStartsAtScenePort() {
        // 8792 (WebSocketScene.java:190 synergy.mServerPort) replaces 8080
        assertEquals(8792, SynergyPortWalk.pad().firstPort)
        assertEquals(8080, SynergyPortWalk.computer().firstPort)
    }

    @Test
    fun transportConstantsMatchCatalog() {
        // Cross-check against SynergyTransport (commonMain) — T2 catalog values
        assertEquals(listOf(8080, 39888, 8080, 49888, 59888), SynergyTransport.COMPUTER_PORT_LIST)
        assertEquals(listOf(8792, 39888, 8792, 49888, 59888), SynergyTransport.PAD_PORT_LIST)
    }
}
