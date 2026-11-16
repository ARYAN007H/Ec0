/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */

package ec0.android

/*
 * Phone→PC clipboard push — the reverse half of clipboard sync. The desktop→phone
 * direction lands via [AndroidHonestPlatformCallbacks.onApplyRemoteClipboard]; this
 * file watches Android's system clipboard and mirrors local copies to the paired
 * desktop while ec0 is the focused app (Android 10+ only grants clipboard *read*
 * access to the focused app's UID — the engine service shares that UID, so reads
 * succeed exactly while the ec0 Activity is resumed).
 */

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.kdeconnect.Sha256
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Records the content hash of the most recent clip *applied from the desktop*
 * (`onApplyRemoteClipboard`). Applying a remote clip fires the system clipboard
 * listener too — without this guard every PC→phone sync would echo straight back
 * to the desktop as if the phone had copied it.
 */
class ClipboardEchoGuard {
    @Volatile
    var lastAppliedFromDesktopSha256: String? = null
}

/** Pure, Android-free decision core for phone→PC clipboard pushes (unit-tested). */
object ClipboardPushPolicy {

    data class Decision(val push: Boolean, val reason: String) {
        companion object {
            val SKIP_EMPTY = Decision(false, "empty-clip")
            val SKIP_SENSITIVE = Decision(false, "sensitive-clip")
            val SKIP_NOT_FOCUSED = Decision(false, "app-not-focused")
            val SKIP_ECHO = Decision(false, "echo-of-desktop-apply")
            val SKIP_DUPLICATE = Decision(false, "duplicate-of-last-push")
            fun PUSH() = Decision(true, "push")
        }
    }

    fun evaluate(
        text: String?,
        isSensitive: Boolean,
        appFocused: Boolean,
        lastAppliedFromDesktopSha256: String?,
        lastPushedSha256: String?,
        candidateSha256: String,
    ): Decision = when {
        text.isNullOrEmpty() -> Decision.SKIP_EMPTY
        isSensitive -> Decision.SKIP_SENSITIVE
        !appFocused -> Decision.SKIP_NOT_FOCUSED
        candidateSha256 == lastAppliedFromDesktopSha256 -> Decision.SKIP_ECHO
        candidateSha256 == lastPushedSha256 -> Decision.SKIP_DUPLICATE
        else -> Decision.PUSH()
    }
}

/**
 * Registers a [ClipboardManager.OnPrimaryClipChangedListener] and pushes text clips
 * to every paired desktop through [AndroidCompanionRuntime.onAndroidClipboardChanged]
 * (offline desktops are handled by the engine outbox — pushes never block on send).
 *
 * Honors the phone→PC sync contract surfaced in the UI:
 *  - pushes only while ec0 is the focused app ([isAppFocused]; Android 10+ read grant),
 *  - never transmits `EXTRA_IS_SENSITIVE` clips (password managers),
 *  - never echoes a clip that was just applied from the desktop ([ClipboardEchoGuard]).
 */
class AndroidClipboardWatcher(
    private val appContext: Context,
    private val runtime: AndroidCompanionRuntime,
    private val echoGuard: ClipboardEchoGuard,
    private val isAppFocused: () -> Boolean,
    private val pushScope: CoroutineScope,
) : ClipboardManager.OnPrimaryClipChangedListener {

    // java.util.logging routes to logcat on Android — the skip reason is the only
    // observable trace of the push policy in the field.
    private val log = Logger.getLogger("ec0.android.clipboard")

    private val clipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val started = AtomicBoolean(false)

    @Volatile
    private var lastPushedSha256: String? = null

    /** Idempotent: safe to call alongside [CompanionServiceHost] start. */
    fun start() {
        if (started.compareAndSet(false, true)) {
            clipboardManager?.addPrimaryClipChangedListener(this)
        }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            clipboardManager?.removePrimaryClipChangedListener(this)
        }
    }

    /**
     * Android 10+ only grants clipboard reads while the app is focused — a copy made
     * in another app never fired a pushable event. On focus (re)gain, re-check the
     * current clip so "copy on phone → open ec0" still syncs.
     */
    fun onFocusGained() {
        checkAndPush()
    }

    override fun onPrimaryClipChanged() {
        checkAndPush()
    }

    private fun checkAndPush() {
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val description = clip.description
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val text = runCatching {
            clip.getItemAt(0).coerceToText(appContext)?.toString()
        }.getOrNull().orEmpty()
        val candidateSha = Sha256.digest(text.encodeToByteArray()).toHex()

        val decision = ClipboardPushPolicy.evaluate(
            text = text,
            isSensitive = sensitive,
            appFocused = isAppFocused(),
            lastAppliedFromDesktopSha256 = echoGuard.lastAppliedFromDesktopSha256,
            lastPushedSha256 = lastPushedSha256,
            candidateSha256 = candidateSha,
        )
        log.info("Clipboard push decision=${decision.reason} chars=${text?.length ?: 0}")
        if (!decision.push) return

        // Claim the slot before the async push so a rapid double event can't send twice.
        lastPushedSha256 = candidateSha
        val item = ClipboardItem.text(text)
        pushScope.launch {
            runCatching { runtime.onAndroidClipboardChanged(item) }
                .onFailure { log.warning("Clipboard push failed: $it") }
        }
    }
}
