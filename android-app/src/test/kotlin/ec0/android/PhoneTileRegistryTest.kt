/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone-side tile registry tests — the phone's dashboard tiles = the
 * DESKTOP's beacon capabilities ∩ phone local support (seam fix: AndroidAppState
 * previously kept the UiBridge empty-list default, so the phone rendered no
 * tiles at all). Pure mapping, same commit as the AndroidAppState.tiles override.
 */
package ec0.android

import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.protocol.PresenceBeaconInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneTileRegistryTest {

    /** What the desktop's presence beacon genuinely advertises (hub beacon leg). */
    private val desktopBeacon = PresenceBeaconInfo(
        batteryPct = 100,
        isCharging = true,
        screenOn = true,
        capabilities = setOf(
            EngineCapability.FILE,
            EngineCapability.CLIPBOARD,
            EngineCapability.NOTIFICATION,
            EngineCapability.INPUT,
            EngineCapability.MEDIA_CONTROL,
        ),
    )

    @Test
    fun desktopBeaconNegotiatesTilesAgainstPhoneSupport() {
        val tiles = mapPhoneTileRegistry(
            peerPresence = mapOf("desktop-1" to desktopBeacon),
            connectionStates = mapOf("desktop-1" to EngineConnectionState.CONNECTED),
        )
        // Canonical bento order: file < clip < notif < input, media last
        // (non-canonical ids sort after the canonical seven).
        assertEquals(
            listOf("file", "clip", "notif", "input", "media"),
            tiles.map { it.capabilityId },
        )
        assertTrue(tiles.all { it.deviceId == "desktop-1" && it.enabled })
    }

    @Test
    fun noDesktopBeaconYetRendersEmptyRegistry() {
        // Pre-beacon state (desktop not running the beacon leg): empty, not crash.
        val tiles = mapPhoneTileRegistry(
            peerPresence = emptyMap(),
            connectionStates = mapOf("desktop-1" to EngineConnectionState.CONNECTED),
        )
        assertTrue(tiles.isEmpty())
    }

    @Test
    fun disconnectedDesktopKeepsTilesButDisabled() {
        val tiles = mapPhoneTileRegistry(
            peerPresence = mapOf("desktop-1" to desktopBeacon),
            connectionStates = mapOf("desktop-1" to EngineConnectionState.BACKOFF),
        )
        assertEquals(5, tiles.size)
        assertTrue(tiles.all { !it.enabled })
    }

    @Test
    fun peerWithoutConnectionStateDefaultsToEnabled() {
        // Beaconed but connectionStates entry not emitted yet — registry keeps
        // the tile actionable rather than hiding it.
        val tiles = mapPhoneTileRegistry(
            peerPresence = mapOf("desktop-1" to desktopBeacon),
            connectionStates = emptyMap(),
        )
        assertTrue(tiles.all { it.enabled })
    }

    @Test
    fun capabilitiesOutsidePhoneSupportNeverProduceTiles() {
        // A beacon advertising everything still only lights up tiles the phone
        // can actually act on (mirror/cam ride the desktop's own registry).
        val everything = PresenceBeaconInfo(
            batteryPct = 100,
            isCharging = true,
            screenOn = true,
            capabilities = EngineCapability.entries.toSet(),
        )
        val tiles = mapPhoneTileRegistry(
            peerPresence = mapOf("desktop-1" to everything),
            connectionStates = mapOf("desktop-1" to EngineConnectionState.CONNECTED),
        )
        assertEquals(
            setOf("file", "clip", "notif", "input", "media", "find_my"),
            tiles.map { it.capabilityId }.toSet(),
        )
    }

    @Test
    fun phoneLocalSupportExcludesDesktopConsumerCapabilities() {
        val wire = ec0.core.engine.protocol.beaconCapabilityWireNames(phoneLocalTileSupport)
        assertEquals(
            setOf("clip", "file", "notif", "input", "media", "find_my"),
            wire,
        )
        assertTrue("mirror" !in wire)
        assertTrue("cam" !in wire)
    }
}
