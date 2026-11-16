/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone→PC clipboard push policy: honest Android 10+ focus contract, sensitive-clip
 * suppression, echo suppression (a clip just applied from the desktop must not be
 * pushed straight back), and duplicate suppression.
 */
package ec0.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardPushPolicyTest {

    private fun evaluate(
        text: String? = "hello from the phone",
        isSensitive: Boolean = false,
        appFocused: Boolean = true,
        lastApplied: String? = null,
        lastPushed: String? = null,
        candidate: String = "sha-candidate",
    ) = ClipboardPushPolicy.evaluate(
        text = text,
        isSensitive = isSensitive,
        appFocused = appFocused,
        lastAppliedFromDesktopSha256 = lastApplied,
        lastPushedSha256 = lastPushed,
        candidateSha256 = candidate,
    )

    @Test
    fun pushesFreshTextWhileFocused() {
        val decision = evaluate()
        assertTrue(decision.push, "fresh focused text must push, got ${decision.reason}")
    }

    @Test
    fun skipsWhileAppNotFocused() {
        assertFalse(evaluate(appFocused = false).push)
        assertEquals(
            "app-not-focused",
            evaluate(appFocused = false).reason,
            "the honest Android 10+ contract is visible in the reason",
        )
    }

    @Test
    fun neverPushesSensitiveClips() {
        assertFalse(evaluate(isSensitive = true).push)
    }

    @Test
    fun suppressesEchoOfDesktopApply() {
        val decision = evaluate(lastApplied = "sha-candidate")
        assertFalse(decision.push)
        assertEquals("echo-of-desktop-apply", decision.reason)
    }

    @Test
    fun suppressesDuplicateOfLastPush() {
        assertFalse(evaluate(lastPushed = "sha-candidate").push)
    }

    @Test
    fun emptyOrNullClipSkips() {
        assertFalse(evaluate(text = "").push)
        assertFalse(evaluate(text = null).push)
    }

    @Test
    fun desktopEchoGuardExpiresWhenUserCopiesSomethingElse() {
        // After the echo clip, the user copies new content — that must push even
        // though the guard still holds the desktop-applied sha.
        assertTrue(
            evaluate(lastApplied = "sha-old-desktop-clip", candidate = "sha-new-user-clip").push,
        )
    }
}
