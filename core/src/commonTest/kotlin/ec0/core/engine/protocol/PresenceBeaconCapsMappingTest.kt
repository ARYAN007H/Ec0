/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Presence-beacon capability mapping tests — beacon caps → canonical wireName
 * set consumed by computeTileRegistry, plus the caps CSV round-trip for the
 * mirror/camera capabilities the phone beacon newly advertises (seam fix:
 * without MIRROR/CAM in the phone's beacon, the desktop's mirror/camera tiles
 * never negotiated).
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceBeaconCapsMappingTest {

    @Test
    fun wireNamesCoverEveryCanonicalTileId() {
        val mapped = beaconCapabilityWireNames(EngineCapability.entries)
        // Every canonical tile order id must be producible from the enum's
        // wire names (registry can never silently drop a canonical tile).
        for (canonical in listOf("mirror", "cam", "file", "clip", "notif", "input", "battery")) {
            assertTrue(canonical in mapped, "canonical wire name '$canonical' missing from enum wire names")
        }
    }

    @Test
    fun desktopBeaconSetMapsToHonestWireNames() {
        val desktop = setOf(
            EngineCapability.FILE,
            EngineCapability.CLIPBOARD,
            EngineCapability.NOTIFICATION,
            EngineCapability.INPUT,
            EngineCapability.MEDIA_CONTROL,
        )
        assertEquals(setOf("file", "clip", "notif", "input", "media"), beaconCapabilityWireNames(desktop))
    }

    @Test
    fun mirrorAndCameraMapToMirrorAndCam() {
        assertEquals(
            setOf("mirror", "cam"),
            beaconCapabilityWireNames(setOf(EngineCapability.MIRROR, EngineCapability.CAMERA)),
        )
    }

    @Test
    fun mappingIsDeduplicatedAndOrderIndependent() {
        val caps = listOf(
            EngineCapability.MIRROR,
            EngineCapability.FILE,
            EngineCapability.MIRROR,
        )
        assertEquals(setOf("mirror", "file"), beaconCapabilityWireNames(caps))
    }

    @Test
    fun emptyCapabilitiesMapToEmptySet() {
        assertTrue(beaconCapabilityWireNames(emptyList()).isEmpty())
    }

    @Test
    fun beaconJsonRoundTripsMirrorAndCameraCaps() {
        val beacon = PresenceBeaconInfo(
            batteryPct = 84,
            isCharging = false,
            screenOn = true,
            capabilities = setOf(
                EngineCapability.MIRROR,
                EngineCapability.CAMERA,
                EngineCapability.CLIPBOARD,
                EngineCapability.BATTERY,
            ),
        )
        val decoded = PresenceBeaconInfo.fromJsonObject(beacon.toJsonObject())
        assertEquals(beacon.capabilities, decoded.capabilities)
        assertTrue(EngineCapability.MIRROR in decoded.capabilities)
        assertTrue(EngineCapability.CAMERA in decoded.capabilities)
    }

    @Test
    fun phoneBeaconWireNamesFeedRegistryIntersection() {
        // Phone beacon caps (incl. the new MIRROR/CAM) ∩ desktop local support —
        // the exact data flow DesktopBridge.tiles consumes.
        val phoneBeacon = setOf(
            EngineCapability.MIRROR,
            EngineCapability.CAMERA,
            EngineCapability.CLIPBOARD,
            EngineCapability.FILE,
            EngineCapability.REMOTE_INPUT,
        )
        val desktopSupport = setOf("mirror", "cam", "file", "clip", "notif", "input")
        val intersection = beaconCapabilityWireNames(phoneBeacon).intersect(desktopSupport)
        assertEquals(setOf("mirror", "cam", "file", "clip"), intersection)
        assertFalse("remote_input" in intersection)
    }
}
