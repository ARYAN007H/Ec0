/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B3 tile-registry tests — capability ∩ local-support intersection, canonical
 * ordering, offline peers stay (disabled), unknown capabilities sort last.
 */
package ec0.desktop.continuity

import ec0.core.engine.EngineCapability
import ec0.ui.models.computeTileRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileRegistryTest {

    @Test
    fun intersectionProducesOnlySharedCapabilities() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "phone-a" to setOf(
                    EngineCapability.MIRROR, EngineCapability.CAMERA, EngineCapability.BATTERY,
                    EngineCapability.NOTIFICATION,
                ),
            ),
            localSupport = setOf(EngineCapability.MIRROR, EngineCapability.CAMERA, EngineCapability.FILE),
        )
        val capabilities = tiles.map { it.capability }
        assertEquals(
            listOf(EngineCapability.MIRROR, EngineCapability.CAMERA),
            capabilities,
        )
        assertTrue(tiles.all { it.deviceId == "phone-a" })
    }

    @Test
    fun canonicalOrderAndOneTilePerCapabilityDevicePair() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "p1" to setOf(EngineCapability.FILE, EngineCapability.MIRROR, EngineCapability.INPUT),
            ),
            localSupport = EngineCapability.entries.toSet(),
        )
        // canonical order: MIRROR → FILE → INPUT (design-board bento order)
        assertEquals(
            listOf(EngineCapability.MIRROR, EngineCapability.FILE, EngineCapability.INPUT),
            tiles.map { it.capability },
        )
        assertTrue(tiles.all { it.deviceId == "p1" })
    }

    @Test
    fun offlinePeerKeepsTilesDisabled() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf("p1" to setOf(EngineCapability.MIRROR)),
            localSupport = setOf(EngineCapability.MIRROR),
            onlineByDevice = mapOf("p1" to false),
        )
        assertEquals(listOf(false), tiles.map { it.enabled })
    }

    @Test
    fun unknownCapabilitiesSortLast() {
        val exotic = EngineCapability.entries.last()
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf("p1" to setOf(exotic, EngineCapability.MIRROR)),
            localSupport = EngineCapability.entries.toSet(),
        )
        assertEquals(EngineCapability.MIRROR, tiles.first().capability)
        assertEquals(exotic, tiles.last().capability)
    }

    @Test
    fun multiPeerRegistrySplitsByDevice() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "p1" to setOf(EngineCapability.MIRROR),
                "p2" to setOf(EngineCapability.MIRROR),
            ),
            localSupport = setOf(EngineCapability.MIRROR),
        )
        assertEquals(2, tiles.size)
        assertEquals(listOf("p1", "p2"), tiles.map { it.deviceId })
    }
}
