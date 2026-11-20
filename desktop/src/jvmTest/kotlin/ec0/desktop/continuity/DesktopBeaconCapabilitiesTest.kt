/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop presence-beacon capability set tests — the desktop must advertise
 * what it genuinely offers (seam fix: without the desktop's own beacon the
 * phone's tile registry rendered permanently empty). Pure mapping, same
 * commit as the ContinuityHubController beacon leg.
 */
package ec0.desktop.continuity

import ec0.core.engine.EngineCapability
import ec0.core.engine.protocol.beaconCapabilityWireNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBeaconCapabilitiesTest {

    @Test
    fun baseSetIsFileClipboardNotificationInput() {
        val caps = desktopBeaconCapabilities(mediaControlSupported = false)
        assertEquals(
            setOf(
                EngineCapability.FILE,
                EngineCapability.CLIPBOARD,
                EngineCapability.NOTIFICATION,
                EngineCapability.INPUT,
                EngineCapability.AUDIO_HANDOFF,
                EngineCapability.CAPTURE,
                EngineCapability.SIDECAR,
                EngineCapability.SMS,
                EngineCapability.FOCUS,
            ),
            caps,
        )
    }

    @Test
    fun mediaControlAddedOnlyWhenMPrisSupported() {
        val withMedia = desktopBeaconCapabilities(mediaControlSupported = true)
        assertTrue(EngineCapability.MEDIA_CONTROL in withMedia)
        // The base set is preserved when media control joins.
        assertEquals(
            desktopBeaconCapabilities(mediaControlSupported = false) + EngineCapability.MEDIA_CONTROL,
            withMedia,
        )
    }

    @Test
    fun desktopNeverAdvertisesPhoneOnlyCapabilities() {
        // The desktop does not mirror itself or expose a phone camera; the
        // phone's beacon carries mirror/cam (consumer direction is reversed).
        val caps = desktopBeaconCapabilities(mediaControlSupported = true)
        assertFalse(EngineCapability.MIRROR in caps)
        assertFalse(EngineCapability.CAMERA in caps)
        assertFalse(EngineCapability.BATTERY in caps)
        assertFalse(EngineCapability.VFS_READ in caps)
    }

    @Test
    fun desktopBeaconWireNamesNegotiateAgainstPhoneLocalSupport() {
        // Phone local support (AndroidAppState.tiles): the desktop beacon ∩
        // this set must produce exactly the tiles the phone can act on.
        val desktopWire = beaconCapabilityWireNames(desktopBeaconCapabilities(mediaControlSupported = true))
        val phoneSupport = setOf("clip", "file", "notif", "input", "media", "find_my")
        assertEquals(
            setOf("file", "clip", "notif", "input", "media"),
            desktopWire.intersect(phoneSupport),
        )
    }
}
