/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure grant-state mapping for the phone-camera Settings row ("Phone camera").
 * Inputs are exactly what the Activity can observe about the CAMERA runtime
 * grant; output is the honest [PhoneCameraGrant] the Settings row renders —
 * including the Android 11+ "blocked" verdict, which must route to app
 * settings instead of a system dialog that silently auto-denies.
 */

package ec0.android

import ec0.ui.models.PhoneCameraGrant

/**
 * The three observable facts about the CAMERA grant:
 *  - [granted]: `ContextCompat.checkSelfPermission` verdict.
 *  - [shouldShowRationale]: `shouldShowRequestPermissionRationale` verdict —
 *    true means Android will still show the dialog after a decline.
 *  - [deniedAtLeastOnce]: a request result came back DENIED at least once.
 *    Needed because rationale is ALSO false before the first denial — without
 *    it, a fresh install would look "blocked". Crucially this flips on the
 *    RESULT, not on launching the dialog: a request the user dismissed by
 *    navigating away never denies, so it must not count here.
 */
data class CameraGrantInputs(
    val granted: Boolean,
    val shouldShowRationale: Boolean,
    val deniedAtLeastOnce: Boolean,
)

/**
 * Maps the observable grant facts onto the honest Settings-row state. No
 * nagging: a declined grant gets a calm one-tap Enable, a blocked grant gets
 * routed to system settings — never a loop of silent auto-denying dialogs.
 */
fun mapPhoneCameraGrant(inputs: CameraGrantInputs): PhoneCameraGrant = when {
    inputs.granted -> PhoneCameraGrant(
        granted = true,
        needsSystemSettings = false,
        stateLine = "On",
        detailLine = "Streams to your paired PC when you start it from there — " +
            "nothing runs until then, and you can revoke it in Android settings",
    )
    !inputs.deniedAtLeastOnce -> PhoneCameraGrant(
        granted = false,
        needsSystemSettings = false,
        stateLine = "Off",
        detailLine = "Your PC only sees this camera after you allow it here",
    )
    inputs.shouldShowRationale -> PhoneCameraGrant(
        granted = false,
        needsSystemSettings = false,
        stateLine = "Declined",
        detailLine = "Enable any time — the desktop's camera tile lights up once Android allows it",
    )
    else -> PhoneCameraGrant(
        granted = false,
        needsSystemSettings = true,
        stateLine = "Blocked",
        detailLine = "Android stopped asking — allow Camera under Settings → Apps → ec0 → Permissions",
    )
}
