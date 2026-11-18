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
 *
 * ColorOS reality (verified on the A5x): even with the app focused, clipboard
 * *reads* from the foreground SERVICE can be denied while the Activity's own read
 * succeeds. The listener path stays for live change events, but on focus the
 * Activity reads the clip itself and hands the text over via
 * [AndroidClipboardWatcher.pushText] — both paths share the policy/echo/dedupe
 * tail in [ClipboardPushSender].
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

/**
 * Single source of truth for the phone's clipboard-sync toggle — ONE boolean
 * behind the SyncPanel card, the HOME Auto-sync tile and the QS tile:
 *   - phone→PC pushes honor it via [AndroidClipboardWatcher],
 *   - PC→phone applies honor it via [AndroidHonestPlatformCallbacks].
 * The persistent twin lives in DataStore ([AndroidSettingsStore]); hosts seed
 * this switch from the store at startup and persist on every flip. A plain
 * mutable holder because both consumers are plain runtime services, not flows.
 */
class ClipboardSyncSwitch(initial: Boolean = true) {
    @Volatile
    var enabled: Boolean = initial
}

/** Pure, Android-free decision core for phone→PC clipboard pushes (unit-tested). */
object ClipboardPushPolicy {

    data class Decision(val push: Boolean, val reason: String) {
        companion object {
            val SKIP_EMPTY = Decision(false, "empty-clip")
            val SKIP_SYNC_PAUSED = Decision(false, "sync-paused")
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
        syncEnabled: Boolean = true,
    ): Decision = when {
        text.isNullOrEmpty() -> Decision.SKIP_EMPTY
        !syncEnabled -> Decision.SKIP_SYNC_PAUSED
        isSensitive -> Decision.SKIP_SENSITIVE
        !appFocused -> Decision.SKIP_NOT_FOCUSED
        candidateSha256 == lastAppliedFromDesktopSha256 -> Decision.SKIP_ECHO
        candidateSha256 == lastPushedSha256 -> Decision.SKIP_DUPLICATE
        else -> Decision.PUSH()
    }
}

/**
 * Policy decision + engine-send tail shared by both push paths:
 *  - the system-clipboard listener ([AndroidClipboardWatcher.onPrimaryClipChanged]),
 *  - the Activity-read path ([AndroidClipboardWatcher.pushText], ColorOS workaround).
 * Unit-testable without android.jar — same-commit tests in [ClipboardPushPolicyTest].
 */
class ClipboardPushSender(
    private val echoGuard: ClipboardEchoGuard,
    private val isAppFocused: () -> Boolean,
    private val pushScope: CoroutineScope,
    /**
     * The phone's clipboard-sync switch ([ClipboardSyncSwitch.enabled]) — the
     * ONE gate for BOTH push paths (listener + Activity focus read). Without
     * it, "Sync paused" would still push from the focus path, because the
     * listener-unregistration gate only covers the listener path.
     */
    private val isSyncEnabled: () -> Boolean = { true },
    private val log: Logger = Logger.getLogger("ec0.android.clipboard"),
    private val onPush: (ClipboardItem) -> Unit,
) {
    /** Content hash of the most recent clip this side pushed (dedupe window). */
    @Volatile
    var lastPushedSha256: String? = null
        private set

    /**
     * Runs [ClipboardPushPolicy] over a candidate and, on PUSH, claims the dedupe
     * slot before the async engine send (a rapid double event must not send twice).
     */
    fun pushCandidate(text: String, sensitive: Boolean) {
        if (text.isEmpty()) return
        val candidateSha = Sha256.digest(text.encodeToByteArray()).toHex()

        val decision = ClipboardPushPolicy.evaluate(
            text = text,
            isSensitive = sensitive,
            appFocused = isAppFocused(),
            lastAppliedFromDesktopSha256 = echoGuard.lastAppliedFromDesktopSha256,
            lastPushedSha256 = lastPushedSha256,
            candidateSha256 = candidateSha,
            syncEnabled = isSyncEnabled(),
        )
        log.info("Clipboard push decision=${decision.reason} chars=${text.length}")
        if (!decision.push) return

        lastPushedSha256 = candidateSha
        val item = ClipboardItem.text(text)
        pushScope.launch {
            runCatching { onPush(item) }
                .onFailure { log.warning("Clipboard push failed: $it") }
        }
    }
}

/**
 * Registers a [ClipboardManager.OnPrimaryClipChangedListener] and pushes text clips
 * to every paired desktop through [AndroidCompanionRuntime.onAndroidClipboardChanged]
 * (offline desktops are handled by the engine outbox — pushes never block on send).
 *
 * Honors the phone→PC sync contract surfaced in the UI:
 *  - pushes nothing while the sync switch is off ([ClipboardSyncSwitch] — both
 *    the listener and the Activity focus path gate in [ClipboardPushSender]),
 *  - pushes only while ec0 is the focused app ([isAppFocused]; Android 10+ read grant),
 *  - never transmits `EXTRA_IS_SENSITIVE` clips (password managers),
 *  - never echoes a clip that was just applied from the desktop ([ClipboardEchoGuard]).
 *
 * ColorOS note: the listener's own reads can be denied even while focused (see the
 * file header) — MainActivity reads the clip with the Activity context on focus and
 * routes the text through [pushText], which shares this class's policy tail.
 */
class AndroidClipboardWatcher(
    private val appContext: Context,
    private val runtime: AndroidCompanionRuntime,
    private val echoGuard: ClipboardEchoGuard,
    private val isAppFocused: () -> Boolean,
    private val pushScope: CoroutineScope,
    /**
     * Shared clipboard-sync toggle ([ClipboardSyncSwitch]) — the SyncPanel /
     * HOME Auto-sync / QS-tile switch writes here, phone→PC pushes honor it,
     * and the PC→phone apply path reads the same instance.
     */
    private val syncSwitch: ClipboardSyncSwitch = ClipboardSyncSwitch(true),
) : ClipboardManager.OnPrimaryClipChangedListener {

    // java.util.logging routes to logcat on Android — the skip reason is the only
    // observable trace of the push policy in the field.
    private val log = Logger.getLogger("ec0.android.clipboard")

    private val clipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val sender = ClipboardPushSender(
        echoGuard = echoGuard,
        isAppFocused = isAppFocused,
        pushScope = pushScope,
        isSyncEnabled = { enabled },
        log = log,
        onPush = { runtime.onAndroidClipboardChanged(it) },
    )

    private val started = AtomicBoolean(false)

    /** Exposed for the dashboard honesty card / tests. */
    val lastPushedSha256: String? get() = sender.lastPushedSha256

    private val enabled: Boolean get() = syncSwitch.enabled

    /** SyncPanel / QS-tile toggle: enables or pauses phone→PC clipboard pushes. */
    fun setEnabled(value: Boolean) {
        syncSwitch.enabled = value
        if (value) start() else stop()
    }

    fun isStarted(): Boolean = started.get() && enabled

    /** Idempotent: safe to call alongside [CompanionServiceHost] start. */
    fun start() {
        if (enabled && started.compareAndSet(false, true)) {
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

    /**
     * Push a clip the **Activity** already read successfully (ColorOS denies service
     * reads even while focused — the focused-app UID grant covers the Activity's own
     * read). Shares the exact policy/pause/echo/dedupe/send tail with the listener
     * path; [isSensitive] keeps the password-manager suppression honest.
     */
    fun pushText(text: String, isSensitive: Boolean = false) {
        sender.pushCandidate(text, isSensitive)
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
        sender.pushCandidate(text, sensitive)
    }
}
