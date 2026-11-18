/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Same-commit tests for the phone-camera grant mapping: every observable
 * (granted / rationale / denied-once) combination maps to the honest
 * Settings-row state — including the Android 11+ blocked verdict that must
 * route to app settings instead of a silently auto-denying dialog.
 */
package ec0.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraGrantUiTest {

    @Test
    fun grantedGrantRendersOnWithoutAction() {
        val grant = mapPhoneCameraGrant(
            CameraGrantInputs(granted = true, shouldShowRationale = false, deniedAtLeastOnce = true),
        )
        assertTrue(grant.granted)
        assertFalse(grant.needsSystemSettings)
        assertEquals("On", grant.stateLine)
    }

    @Test
    fun neverDeniedAsksInApp() {
        val grant = mapPhoneCameraGrant(
            CameraGrantInputs(granted = false, shouldShowRationale = false, deniedAtLeastOnce = false),
        )
        assertFalse(grant.granted)
        // rationale is ALSO false before the first denial — the mapping must
        // not confuse a fresh install (or a dialog dismissed by navigating
        // away, which never denies) with "Android stopped asking".
        assertFalse(grant.needsSystemSettings, "a never-denied grant must offer the in-app dialog, not bounce to settings")
        assertEquals("Off", grant.stateLine)
    }

    @Test
    fun declinedGrantStaysAskable() {
        val grant = mapPhoneCameraGrant(
            CameraGrantInputs(granted = false, shouldShowRationale = true, deniedAtLeastOnce = true),
        )
        assertFalse(grant.granted)
        assertFalse(grant.needsSystemSettings, "a plain decline still gets the system dialog on the next Enable")
        assertEquals("Declined", grant.stateLine)
    }

    @Test
    fun blockedGrantRoutesToSystemSettings() {
        // Android 11+: denied twice → shouldShowRationale false → re-launching
        // the dialog auto-denies silently; Enable must open app settings instead.
        val grant = mapPhoneCameraGrant(
            CameraGrantInputs(granted = false, shouldShowRationale = false, deniedAtLeastOnce = true),
        )
        assertFalse(grant.granted)
        assertTrue(grant.needsSystemSettings)
        assertEquals("Blocked", grant.stateLine)
    }

    @Test
    fun grantedVerdictWinsOverStaleDenialFlags() {
        // The grant was flipped on in system settings while stale flags said
        // "blocked" — granted is the only state that matters once true.
        val grant = mapPhoneCameraGrant(
            CameraGrantInputs(granted = true, shouldShowRationale = false, deniedAtLeastOnce = false),
        )
        assertTrue(grant.granted)
        assertFalse(grant.needsSystemSettings)
        assertEquals("On", grant.stateLine)
    }
}
