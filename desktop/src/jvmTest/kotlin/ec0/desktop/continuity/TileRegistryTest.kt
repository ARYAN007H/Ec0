/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B3 tile-registry tests — capability ∩ local-support intersection, canonical
 * ordering, offline peers stay (disabled), unknown capabilities sort last.
 */
package ec0.desktop.continuity

import ec0.ui.models.computeTileRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileRegistryTest {

    @Test
    fun intersectionProducesOnlySharedCapabilities() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "phone-a" to setOf("mirror", "cam", "battery", "notif"),
            ),
            localSupport = setOf("mirror", "cam", "file"),
        )
        val capabilities = tiles.map { it.capabilityId }
        assertEquals(listOf("mirror", "cam"), capabilities)
        assertTrue(tiles.all { it.deviceId == "phone-a" })
    }

    @Test
    fun canonicalOrderAndOneTilePerCapabilityDevicePair() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf("p1" to setOf("file", "mirror", "input")),
            localSupport = setOf("mirror", "cam", "file", "clip", "notif", "input", "battery"),
        )
        // canonical order: MIRROR → FILE → INPUT (design-board bento order)
        assertEquals(
            listOf("mirror", "file", "input"),
            tiles.map { it.capabilityId },
        )
        assertTrue(tiles.all { it.deviceId == "p1" })
    }

    @Test
    fun offlinePeerKeepsTilesDisabled() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf("p1" to setOf("mirror")),
            localSupport = setOf("mirror"),
            onlineByDevice = mapOf("p1" to false),
        )
        assertEquals(listOf(false), tiles.map { it.enabled })
    }

    @Test
    fun unknownCapabilitiesSortLast() {
        val exotic = "weird"
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf("p1" to setOf(exotic, "mirror")),
            localSupport = setOf("mirror", "weird"),
        )
        assertEquals("mirror", tiles.first().capabilityId)
        assertEquals(exotic, tiles.last().capabilityId)
    }

    @Test
    fun multiPeerRegistrySplitsByDevice() {
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "p1" to setOf("mirror"),
                "p2" to setOf("mirror"),
            ),
            localSupport = setOf("mirror"),
        )
        assertEquals(2, tiles.size)
        assertEquals(listOf("p1", "p2"), tiles.map { it.deviceId })
    }
}
