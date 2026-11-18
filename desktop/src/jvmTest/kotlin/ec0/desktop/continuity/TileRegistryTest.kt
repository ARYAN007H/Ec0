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

    @Test
    fun desktopBeaconCapsWireNamesFeedRegistryEndToEnd() {
        // The exact desktop→phone data flow: DesktopBridge.tiles maps the
        // PHONE's beacon capabilities through beaconCapabilityWireNames and
        // intersects with the desktop's local support. Before the seam fix
        // the desktop never broadcast its own beacon and the phone's registry
        // was empty; here the phone's (now mirror/cam-carrying) beacon
        // negotiates mirror + camera tiles.
        val phoneBeacon = ec0.core.engine.protocol.PresenceBeaconInfo(
            batteryPct = 66,
            isCharging = false,
            screenOn = true,
            capabilities = setOf(
                ec0.core.engine.EngineCapability.MIRROR,
                ec0.core.engine.EngineCapability.CAMERA,
                ec0.core.engine.EngineCapability.CLIPBOARD,
                ec0.core.engine.EngineCapability.BATTERY,
            ),
        )
        val desktopSupport = setOf(
            ec0.core.engine.EngineCapability.MIRROR,
            ec0.core.engine.EngineCapability.CAMERA,
            ec0.core.engine.EngineCapability.FILE,
            ec0.core.engine.EngineCapability.CLIPBOARD,
            ec0.core.engine.EngineCapability.NOTIFICATION,
            ec0.core.engine.EngineCapability.INPUT,
        )
        val tiles = computeTileRegistry(
            peerCapabilities = mapOf(
                "phone-a" to ec0.core.engine.protocol.beaconCapabilityWireNames(phoneBeacon.capabilities),
            ),
            localSupport = desktopSupport.map { it.wireName }.toSet(),
            onlineByDevice = mapOf("phone-a" to true),
        )
        // BATTERY is intentionally absent (renders in the header, not a tile).
        assertEquals(listOf("mirror", "cam", "clip"), tiles.map { it.capabilityId })
        assertTrue(tiles.all { it.deviceId == "phone-a" && it.enabled })
    }
}
