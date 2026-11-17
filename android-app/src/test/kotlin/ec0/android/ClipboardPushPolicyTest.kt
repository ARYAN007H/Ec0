/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone→PC clipboard push policy: honest Android 10+ focus contract, sensitive-clip
 * suppression, echo suppression (a clip just applied from the desktop must not be
 * pushed straight back), and duplicate suppression.
 */
package ec0.android

import ec0.core.engine.protocol.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Feature 1 (ColorOS workaround): the Activity reads the clipboard on focus and
 * hands text through [AndroidClipboardWatcher.pushText] → [ClipboardPushSender].
 * These tests pin the pushText path end-to-end over the shared sender — policy,
 * echo guard, dedupe claim, and the engine send — without android.jar.
 */
class ClipboardPushSenderTest {

    private class SenderHarness(appFocused: Boolean = true) {
        val echoGuard = ClipboardEchoGuard()
        val pushed = mutableListOf<ClipboardItem>()
        val sender = ClipboardPushSender(
            echoGuard = echoGuard,
            isAppFocused = { appFocused },
            // Unconfined: launch() executes inline, so onPush is observable synchronously.
            pushScope = CoroutineScope(Dispatchers.Unconfined),
            onPush = { item -> pushed.add(item) },
        )

        fun sha(text: String): String =
            ec0.core.kdeconnect.Sha256.digest(text.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun pushTextPushesFocusedNonSensitiveTextToTheEngine() {
        val h = SenderHarness()
        h.sender.pushCandidate("copied on the phone", sensitive = false)
        assertEquals(1, h.pushed.size)
        assertEquals("copied on the phone", h.pushed.single().asUtf8Text())
    }

    @Test
    fun pushTextSuppressesEchoOfDesktopAppliedClip() {
        val h = SenderHarness()
        val text = "clip that came from the desktop"
        h.echoGuard.lastAppliedFromDesktopSha256 = h.sha(text)
        h.sender.pushCandidate(text, sensitive = false)
        assertEquals(0, h.pushed.size)
    }

    @Test
    fun pushTextSuppressesSensitiveClips() {
        val h = SenderHarness()
        h.sender.pushCandidate("password from the manager", sensitive = true)
        assertEquals(0, h.pushed.size)
    }

    @Test
    fun pushTextDedupesConsecutiveIdenticalClips() {
        val h = SenderHarness()
        h.sender.pushCandidate("same text", sensitive = false)
        h.sender.pushCandidate("same text", sensitive = false)
        assertEquals(1, h.pushed.size)
        assertEquals("same text", h.pushed.single().asUtf8Text())
    }

    @Test
    fun pushTextSkipsEmptyAndUnfocusedCandidates() {
        val unfocused = SenderHarness(appFocused = false)
        unfocused.sender.pushCandidate("text while unfocused", sensitive = false)
        assertEquals(0, unfocused.pushed.size)

        val h = SenderHarness()
        h.sender.pushCandidate("", sensitive = false)
        assertEquals(0, h.pushed.size)
    }

    @Test
    fun newContentAfterEchoStillPushesAndUpdatesDedupeSlot() {
        val h = SenderHarness()
        val echo = "desktop echo"
        h.echoGuard.lastAppliedFromDesktopSha256 = h.sha(echo)
        h.sender.pushCandidate(echo, sensitive = false)
        h.sender.pushCandidate("fresh user copy", sensitive = false)
        assertEquals(1, h.pushed.size)
        assertEquals("fresh user copy", h.pushed.single().asUtf8Text())
    }
}
