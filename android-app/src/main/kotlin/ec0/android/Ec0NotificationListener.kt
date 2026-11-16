/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone Notification Relay (`Ec0NotificationListener`):
 *   - Listens to Android system notifications via [NotificationListenerService].
 *   - Maps [StatusBarNotification] into `:core`'s [NotificationPostInfo] relay model,
 *     stripping notification titles of any raw app-package text.
 *   - Relays posted notifications through [AndroidCompanionRuntime.onAndroidNotificationPosted]
 *     into the engine's [ec0.core.engine.services.NotificationRelayService].
 *   - Caches [RemoteInput] actions per notification and executes inline replies
 *     via [sendInlineReply].
 *   - Provides [NotificationAccessHelper] to detect notification listener access
 *     and deep-link to the system Notification Access grant screen.
 */
package ec0.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.crypto.EnvelopeSigner
import ec0.core.engine.protocol.NotificationDismissCommand
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Snapshot of a single [RemoteInput]-capable action on an active Android notification.
 */
data class RegisteredReplyAction(
    val actionTitle: String,
    val resultKeys: List<String>,
    val remoteInputs: Array<RemoteInput> = emptyArray(),
    val actionIntent: PendingIntent? = null,
    val customReplyExecutor: ((replyText: String) -> Boolean)? = null,
)

/**
 * Extracted, platform-decoupled representation of a [StatusBarNotification] used by
 * [NotificationRelayMapper] so mapping logic can be tested both with real/stubbed
 * [StatusBarNotification] instances and pure JVM fixtures.
 */
data class ExtractedStatusBarNotification(
    val key: String?,
    val id: Int,
    val tag: String?,
    val packageName: String,
    val appName: String?,
    val rawTitle: String?,
    val rawText: String?,
    val rawBigText: String?,
    val postTimeMs: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val replyActions: List<RegisteredReplyAction> = emptyList(),
    val actionLabels: List<String> = emptyList(),
)

/**
 * Pure mapping & sanitization logic converting Android [StatusBarNotification]s into
 * `:core` [NotificationPostInfo] relay models.
 */
object NotificationRelayMapper {

    /**
     * Matches reverse-DNS Android package identifiers such as `com.whatsapp`,
     * `org.telegram.messenger`, `net.signal.app`, `io.element.android.x`, etc.
     */
    private val RAW_PACKAGE_TOKEN_REGEX = Regex(
        """(?i)\b(?:com|org|net|io|de|app|me|co|edu|gov|uk|fr|jp|ru|ch|nl|se|no|fi|dk|it|es|pt|br|au|ca|in|cn|kr|tw|SG|nz|za|pl|cz|at|be|ie|il|mx|ar|tr|ua|id|my|th|vn|ph)\.[a-z0-9_]+(?:\.[a-z0-9_]+)+\b"""
    )

    private val TWO_SEGMENT_KNOWN_PACKAGE_REGEX = Regex(
        """(?i)\b(?:com|org|net|io|app|me|de)\.[a-z][a-z0-9_]{1,30}\b"""
    )

    private val LEADING_OR_TRAILING_DELIMITERS = Regex(
        """^(?:[\s:\-–—|•·/\\]+)|(?:[\s:\-–—|•·/\\]+)$"""
    )

    // NOTE: closing brackets MUST be escaped — Android's ICU regex rejects a bare `]`
    // outside a character class (desktop JVM tolerates it, so JVM-only tests stay green
    // while every on-device notification crash-loops the process with a
    // PatternSyntaxException-in-<clinit>). This exact bug shipped once: keep the `\]`/`\}`.
    private val EMPTY_BRACKETS_REGEX = Regex("""\(\s*\)|\[\s*\]|\{\s*\}""")
    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    /**
     * Strips [rawTitle] of any raw app-package text (both the notification's own
     * [packageName] and any embedded reverse-DNS package identifiers like `com.whatsapp`
     * or `org.telegram.messenger`), cleaning up leftover delimiters/brackets.
     *
     * If the title is blank or consisted solely of a raw package name, falls back to
     * a clean human-readable app name derived from [appName] or [packageName].
     */
    fun stripRawPackageText(
        rawTitle: String?,
        packageName: String,
        appName: String? = null,
    ): String {
        val cleanFallbackApp = sanitizeAppName(appName, packageName)
        if (rawTitle.isNullOrBlank()) return cleanFallbackApp

        var cleaned = rawTitle.trim()

        // 1. Strip the notification's exact packageName (case-insensitive)
        val trimmedPkg = packageName.trim()
        if (trimmedPkg.isNotEmpty()) {
            val escapedPkg = Regex.escape(trimmedPkg)
            cleaned = cleaned.replace(Regex("(?i)\\(\\s*$escapedPkg\\s*\\)"), " ")
            cleaned = cleaned.replace(Regex("(?i)\\[\\s*$escapedPkg\\s*]"), " ")
            cleaned = cleaned.replace(Regex("(?i)\\b$escapedPkg\\b"), " ")
        }

        // 2. Strip any generic reverse-DNS package tokens (e.g. org.telegram.messenger, com.whatsapp)
        cleaned = cleaned.replace(RAW_PACKAGE_TOKEN_REGEX, " ")
        cleaned = cleaned.replace(TWO_SEGMENT_KNOWN_PACKAGE_REGEX, " ")

        // 3. Remove empty parens/brackets and leading/trailing punctuation delimiters
        cleaned = cleaned.replace(EMPTY_BRACKETS_REGEX, " ")
        cleaned = cleaned.replace(MULTI_SPACE_REGEX, " ").trim()
        cleaned = cleaned.replace(LEADING_OR_TRAILING_DELIMITERS, "").trim()

        return if (cleaned.isNotEmpty()) cleaned else cleanFallbackApp
    }

    /**
     * Produces a human-readable application name that never exposes a raw package identifier.
     */
    fun sanitizeAppName(rawAppName: String?, packageName: String): String {
        val candidate = rawAppName?.trim().orEmpty()
        if (candidate.isNotEmpty() &&
            !candidate.equals(packageName.trim(), ignoreCase = true) &&
            !RAW_PACKAGE_TOKEN_REGEX.matches(candidate) &&
            !TWO_SEGMENT_KNOWN_PACKAGE_REGEX.matches(candidate)
        ) {
            return candidate
        }
        return humanizePackageName(packageName)
    }

    /**
     * Converts a raw package name like `"com.whatsapp"` or `"org.telegram.messenger"`
     * into a clean display label (`"Whatsapp"`, `"Telegram"`).
     */
    fun humanizePackageName(packageName: String): String {
        val segments = packageName.trim()
            .split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "App"
        val genericStopWords = setOf(
            "com", "org", "net", "io", "de", "app", "me", "co",
            "android", "mobile", "client", "messenger", "app", "lite", "plus", "free", "pro",
        )
        val meaningful = segments.lastOrNull { it.lowercase() !in genericStopWords }
            ?: segments.last()
        return meaningful.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }

    /**
     * Builds a deterministic, non-blank notification ID from [StatusBarNotification] metadata.
     */
    fun buildNotificationId(
        key: String?,
        packageName: String,
        id: Int,
        tag: String?,
    ): String {
        val trimmedKey = key?.trim().orEmpty()
        if (trimmedKey.isNotEmpty()) return trimmedKey
        val tagPart = tag?.trim()?.takeIf { it.isNotEmpty() } ?: "notag"
        return "${packageName.trim()}:$tagPart:$id"
    }

    /**
     * Maps an [ExtractedStatusBarNotification] into `:core`'s [NotificationPostInfo] relay model.
     * Returns `null` if the notification has no package name or is an empty ongoing/group summary.
     */
    fun mapExtractedToRelayModel(extracted: ExtractedStatusBarNotification): NotificationPostInfo? {
        val pkg = extracted.packageName.trim()
        if (pkg.isEmpty()) return null
        if (extracted.isGroupSummary && extracted.rawTitle.isNullOrBlank() && extracted.rawText.isNullOrBlank()) {
            return null
        }

        val notifId = buildNotificationId(
            key = extracted.key,
            packageName = pkg,
            id = extracted.id,
            tag = extracted.tag,
        )
        val cleanAppName = sanitizeAppName(extracted.appName, pkg)
        val cleanTitle = stripRawPackageText(
            rawTitle = extracted.rawTitle,
            packageName = pkg,
            appName = cleanAppName,
        )
        val bodyText = extracted.rawBigText?.trim()?.takeIf { it.isNotEmpty() }
            ?: extracted.rawText?.trim().orEmpty()

        val replyActionTitles = extracted.replyActions
            .map { it.actionTitle.trim().ifEmpty { "Reply" } }
        val allActions = (replyActionTitles + extracted.actionLabels.map { it.trim() })
            .filter { it.isNotEmpty() }
            .distinct()

        val timestamp = if (extracted.postTimeMs > 0L) extracted.postTimeMs else System.currentTimeMillis()

        return NotificationPostInfo(
            notifId = notifId,
            packageName = pkg,
            appName = cleanAppName,
            title = cleanTitle,
            body = bodyText,
            timestampMs = timestamp,
            canReply = extracted.replyActions.isNotEmpty(),
            actions = allActions,
        )
    }

    /**
     * Extracts fields from an Android [StatusBarNotification] and maps it into
     * [ExtractedStatusBarNotification].
     */
    fun extractFromStatusBarNotification(
        sbn: StatusBarNotification,
        appLabelResolver: (String) -> String? = { null },
        extrasReader: (Bundle?, String) -> CharSequence? = ::defaultExtrasReader,
    ): ExtractedStatusBarNotification? {
        val pkg = runCatching { sbn.packageName }.getOrNull()?.trim().orEmpty()
        if (pkg.isEmpty()) return null

        val notification: Notification? = runCatching { sbn.notification }.getOrNull()
        val extras: Bundle? = runCatching { notification?.extras }.getOrNull()

        val rawTitle = runCatching { extrasReader(extras, Notification.EXTRA_TITLE)?.toString() }.getOrNull()
            ?: runCatching { extrasReader(extras, Notification.EXTRA_TITLE_BIG)?.toString() }.getOrNull()
            ?: runCatching { notification?.tickerText?.toString() }.getOrNull()

        val rawText = runCatching { extrasReader(extras, Notification.EXTRA_TEXT)?.toString() }.getOrNull()
            ?: runCatching { extrasReader(extras, Notification.EXTRA_SUMMARY_TEXT)?.toString() }.getOrNull()

        val rawBigText = runCatching { extrasReader(extras, Notification.EXTRA_BIG_TEXT)?.toString() }.getOrNull()

        val replyActions = mutableListOf<RegisteredReplyAction>()
        val actionLabels = mutableListOf<String>()

        val actionsArray = runCatching { notification?.actions }.getOrNull()
        if (actionsArray != null) {
            for (action in actionsArray) {
                if (action == null) continue
                val label = runCatching { action.title?.toString()?.trim() }.getOrNull().orEmpty()
                if (label.isNotEmpty()) {
                    actionLabels.add(label)
                }
                val remoteInputs = runCatching { action.remoteInputs }.getOrNull()
                if (!remoteInputs.isNullOrEmpty()) {
                    val resultKeys = remoteInputs.mapNotNull { ri ->
                        runCatching { ri.resultKey }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                    replyActions.add(
                        RegisteredReplyAction(
                            actionTitle = label.ifEmpty { "Reply" },
                            resultKeys = if (resultKeys.isNotEmpty()) resultKeys else listOf("android.intent.extra.TEXT"),
                            remoteInputs = remoteInputs,
                            actionIntent = runCatching { action.actionIntent }.getOrNull(),
                        ),
                    )
                }
            }
        }

        val flags = runCatching { notification?.flags ?: 0 }.getOrDefault(0)
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isOngoing = runCatching { sbn.isOngoing }.getOrDefault(false)

        return ExtractedStatusBarNotification(
            key = runCatching { sbn.key }.getOrNull(),
            id = runCatching { sbn.id }.getOrDefault(0),
            tag = runCatching { sbn.tag }.getOrNull(),
            packageName = pkg,
            appName = runCatching { appLabelResolver(pkg) }.getOrNull(),
            rawTitle = rawTitle,
            rawText = rawText,
            rawBigText = rawBigText,
            postTimeMs = runCatching { sbn.postTime }.getOrDefault(0L),
            isOngoing = isOngoing,
            isGroupSummary = isGroupSummary,
            replyActions = replyActions,
            actionLabels = actionLabels,
        )
    }

    private fun defaultExtrasReader(extras: Bundle?, key: String): CharSequence? {
        if (extras == null) return null
        return runCatching { extras.getCharSequence(key) }.getOrNull()
            ?: runCatching { extras.getString(key) }.getOrNull()
    }

    /**
     * Maps an Android [StatusBarNotification] directly to `:core`'s [NotificationPostInfo].
     */
    fun fromStatusBarNotification(
        sbn: StatusBarNotification,
        appLabelResolver: (String) -> String? = { null },
        extrasReader: (Bundle?, String) -> CharSequence? = ::defaultExtrasReader,
    ): NotificationPostInfo? {
        val extracted = extractFromStatusBarNotification(sbn, appLabelResolver, extrasReader) ?: return null
        return mapExtractedToRelayModel(extracted)
    }
}

/**
 * Helper that detects whether [Ec0NotificationListener] has been granted Notification Access
 * by the user and builds/launches the deep-link intent to the Android system grant screen.
 */
object NotificationAccessHelper {
    const val SETTING_ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    const val EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME =
        "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
    private const val EXTRA_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /**
     * Pure check verifying whether [packageName]/[serviceClassName] appears in Android's
     * colon-separated `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` value.
     */
    fun isComponentInEnabledListeners(
        enabledListenersSetting: String?,
        packageName: String,
        serviceClassName: String = Ec0NotificationListener::class.java.name,
    ): Boolean {
        if (enabledListenersSetting.isNullOrBlank() || packageName.isBlank()) return false
        val pkg = packageName.trim()
        val fullClass = if (serviceClassName.startsWith(".")) "$pkg$serviceClassName" else serviceClassName.trim()
        val shortClass = if (fullClass.startsWith("$pkg.")) {
            fullClass.removePrefix(pkg)
        } else {
            ".${fullClass.substringAfterLast('.')}"
        }

        val fullFlat = "$pkg/$fullClass"
        val shortFlat = "$pkg/$shortClass"

        return enabledListenersSetting
            .split(':')
            .map { it.trim() }
            .any { token ->
                token.equals(fullFlat, ignoreCase = false) ||
                    token.equals(shortFlat, ignoreCase = false)
            }
    }

    /**
     * Checks whether [Ec0NotificationListener] currently has system notification listener access.
     */
    fun isNotificationListenerEnabled(
        context: Context,
        serviceClass: Class<*> = Ec0NotificationListener::class.java,
    ): Boolean {
        val component = runCatching { ComponentName(context, serviceClass) }.getOrNull()
        if (component != null) {
            val nmGranted = runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.isNotificationListenerAccessGranted(component)
            }.getOrNull()
            if (nmGranted == true) return true
        }
        val flat = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                SETTING_ENABLED_NOTIFICATION_LISTENERS,
            )
        }.getOrNull()
        return isComponentInEnabledListeners(
            enabledListenersSetting = flat,
            packageName = runCatching { context.packageName }.getOrNull().orEmpty(),
            serviceClassName = serviceClass.name,
        )
    }

    /**
     * Builds an [Intent] deep-linking to Android's Notification Access grant screen
     * with [Ec0NotificationListener] highlighted.
     */
    fun buildNotificationAccessSettingsIntent(
        context: Context,
        serviceClass: Class<*> = Ec0NotificationListener::class.java,
    ): Intent {
        val pkg = runCatching { context.packageName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ec0.android"
        return buildNotificationAccessSettingsIntent(
            packageName = pkg,
            serviceClassName = serviceClass.name,
        )
    }

    fun buildNotificationAccessSettingsIntent(
        packageName: String,
        serviceClassName: String = Ec0NotificationListener::class.java.name,
    ): Intent {
        val componentName = ComponentName(packageName, serviceClassName)
        val flattened = "${packageName.trim()}/${serviceClassName.trim()}"
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, componentName)
            putExtra(EXTRA_FRAGMENT_ARGS_KEY, flattened)
            val bundle = Bundle().apply {
                putString(EXTRA_FRAGMENT_ARGS_KEY, flattened)
            }
            putExtra(EXTRA_SHOW_FRAGMENT_ARGS, bundle)
        }
    }

    /**
     * Launches the system Notification Access grant screen.
     */
    fun openNotificationAccessSettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildNotificationAccessSettingsIntent(context))
            true
        }.getOrElse {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                true
            }.getOrDefault(false)
        }
    }
}

/**
 * Android [NotificationListenerService] that bridges posted phone notifications into
 * [AndroidCompanionRuntime.onAndroidNotificationPosted] and executes [RemoteInput]
 * inline replies via [sendInlineReply].
 */
class Ec0NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeListenerInstance = this
    }

    override fun onListenerDisconnected() {
        if (activeListenerInstance === this) {
            activeListenerInstance = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeListenerInstance === this) {
            activeListenerInstance = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
        val sbnPkg = runCatching { sbn.packageName }.getOrNull()
        if (!ownPkg.isNullOrBlank() && ownPkg == sbnPkg) return

        val extracted = NotificationRelayMapper.extractFromStatusBarNotification(
            sbn = sbn,
            appLabelResolver = ::resolveAppLabel,
        ) ?: return

        handleExtractedNotificationPosted(extracted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = runCatching { sbn.packageName }.getOrNull()?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val notifId = NotificationRelayMapper.buildNotificationId(
            key = runCatching { sbn.key }.getOrNull(),
            packageName = pkg,
            id = runCatching { sbn.id }.getOrDefault(0),
            tag = runCatching { sbn.tag }.getOrNull(),
        )
        onNotificationRemovedById(notifId)
    }

    /**
     * Instance-level entry point to execute a [RemoteInput] inline reply.
     */
    fun sendInlineReply(reply: NotificationReplyCommand): Boolean {
        val ctx = runCatching { applicationContext }.getOrNull()
        return Companion.sendInlineReply(reply = reply, context = ctx)
    }

    private fun resolveAppLabel(packageName: String): String? {
        return runCatching {
            val pm = applicationContext?.packageManager ?: return null
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val MAX_CACHED_REPLY_ACTIONS = 128

        private val registryLock = ReentrantLock()
        // Note: Standard LinkedHashMap with manual eviction (never subclass LinkedHashMap due to K2 backend bug)
        private val replyActionsByNotifId = LinkedHashMap<String, List<RegisteredReplyAction>>()

        /**
         * NotificationListenerService callbacks run on the MAIN thread, and the relay
         * performs a blocking socket write (`sendOrQueueNotification` -> `sendInfo`).
         * Any send on main throws NetworkOnMainThreadException and kills the process —
         * serialize every relay send onto this daemon thread instead (also preserves
         * notification ordering).
         */
        private val relayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ec0-notification-relay").apply { isDaemon = true }
        }

        /**
         * Test seam: when non-null, relay dispatch runs the block inline on the caller's
         * thread so unit tests can assert relay side effects synchronously. Production
         * always leaves this null (real executor, off-main).
         */
        @Volatile
        internal var relayDispatcherOverride: ((block: () -> Unit) -> Unit)? = null

        private fun dispatchRelay(block: () -> Unit) {
            val override = relayDispatcherOverride
            if (override != null) override(block) else relayExecutor.execute(block)
        }

        @Volatile
        private var attachedRuntime: AndroidCompanionRuntime? = null

        @Volatile
        private var activeListenerInstance: Ec0NotificationListener? = null

        fun attachRuntime(runtime: AndroidCompanionRuntime?) {
            attachedRuntime = runtime
        }

        fun currentRuntime(): AndroidCompanionRuntime? = attachedRuntime

        /**
         * Processes an [ExtractedStatusBarNotification]: maps it to [NotificationPostInfo],
         * caches any [RemoteInput] actions for [sendInlineReply], updates the local
         * [ec0.core.engine.services.NotificationRelayService], and relays it through
         * [AndroidCompanionRuntime.onAndroidNotificationPosted].
         */
        fun handleExtractedNotificationPosted(
            extracted: ExtractedStatusBarNotification,
            runtime: AndroidCompanionRuntime? = attachedRuntime,
        ): NotificationPostInfo? {
            val postInfo = NotificationRelayMapper.mapExtractedToRelayModel(extracted) ?: return null

            if (extracted.replyActions.isNotEmpty()) {
                registerReplyActions(postInfo.notifId, extracted.replyActions)
            } else {
                unregisterReplyActions(postInfo.notifId)
            }

            if (runtime != null) {
                dispatchRelay {
                // Record locally in engine's NotificationRelayService feed and relay to paired desktops
                val localEnvelope = EnvelopeSigner.signEnvelope(
                    identity = runtime.phoneIdentity,
                    msgType = Ec0EnvelopeCodec.TYPE_INFO,
                    msgBody = Ec0EnvelopeCodec.buildInfoBody(
                        infoType = NotificationProtocol.INFO_NOTIF_POST,
                        infoBody = postInfo.toJsonObject(),
                    ),
                    category = NotificationProtocol.CATEGORY_NOTIF,
                    timestampMs = postInfo.timestampMs,
                    seq = 1L,
                )
                runtime.node.notificationRelayService.handleControlEnvelope(localEnvelope)
                val targetIds = (
                    runtime.node.pinnedPeers.value.map { it.deviceId.lowercase() } +
                        runtime.pinStore.all().map { it.deviceId.lowercase() } +
                        runtime.node.connectionStates.value
                            .filterValues { it == ec0.core.engine.EngineConnectionState.CONNECTED }
                            .keys
                    ).toSet()
                if (targetIds.isNotEmpty()) {
                    runtime.onAndroidNotificationPosted(postInfo, targetIds)
                } else {
                    runtime.onAndroidNotificationPosted(postInfo)
                }
                }
            }

            return postInfo
        }

        fun onNotificationRemovedById(
            notifId: String,
            runtime: AndroidCompanionRuntime? = attachedRuntime,
        ) {
            unregisterReplyActions(notifId)
            if (runtime != null) {
                val dismissEnvelope = EnvelopeSigner.signEnvelope(
                    identity = runtime.phoneIdentity,
                    msgType = Ec0EnvelopeCodec.TYPE_CMD,
                    msgBody = Ec0EnvelopeCodec.buildCmdBody(
                        cmdType = NotificationProtocol.CMD_NOTIF_DISMISS,
                        cmdBody = NotificationDismissCommand(notifId).toJsonObject(),
                    ),
                    category = NotificationProtocol.CATEGORY_NOTIF,
                    timestampMs = System.currentTimeMillis(),
                    seq = 1L,
                )
                runtime.node.notificationRelayService.handleControlEnvelope(dismissEnvelope)
            }
        }

        fun registerReplyActions(
            notifId: String,
            actions: List<RegisteredReplyAction>,
        ) {
            if (notifId.isBlank() || actions.isEmpty()) return
            registryLock.withLock {
                replyActionsByNotifId.remove(notifId)
                while (replyActionsByNotifId.size >= MAX_CACHED_REPLY_ACTIONS) {
                    val oldestKey = replyActionsByNotifId.keys.firstOrNull() ?: break
                    replyActionsByNotifId.remove(oldestKey)
                }
                replyActionsByNotifId[notifId] = actions
            }
        }

        fun unregisterReplyActions(notifId: String) {
            registryLock.withLock {
                replyActionsByNotifId.remove(notifId)
            }
        }

        fun clearReplyActionsForTest() {
            registryLock.withLock {
                replyActionsByNotifId.clear()
            }
        }

        /**
         * Executes an inline reply for [reply] using the cached [RemoteInput] + [PendingIntent]
         * captured when the notification was posted.
         */
        fun sendInlineReply(
            reply: NotificationReplyCommand,
            context: Context? = null,
        ): Boolean {
            if (reply.notifId.isBlank() || reply.replyText.isBlank()) return false

            val actions = registryLock.withLock {
                replyActionsByNotifId[reply.notifId]
            } ?: return false

            val targetAction = actions.firstOrNull {
                it.actionTitle.equals(reply.action, ignoreCase = true)
            } ?: actions.firstOrNull() ?: return false

            // Allow unit tests or custom hooks to intercept the reply execution deterministically
            targetAction.customReplyExecutor?.let { executor ->
                return runCatching { executor(reply.replyText) }.getOrDefault(false)
            }

            val pendingIntent = targetAction.actionIntent ?: return false
            val targetContext = context
                ?: runCatching { activeListenerInstance?.applicationContext }.getOrNull()
                ?: return false

            return runCatching {
                val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val resultsBundle = Bundle()
                val keys = if (targetAction.resultKeys.isNotEmpty()) {
                    targetAction.resultKeys
                } else {
                    targetAction.remoteInputs.mapNotNull { ri ->
                        runCatching { ri.resultKey }.getOrNull()
                    }
                }
                for (key in keys) {
                    resultsBundle.putCharSequence(key, reply.replyText)
                }
                if (targetAction.remoteInputs.isNotEmpty()) {
                    RemoteInput.addResultsToIntent(
                        targetAction.remoteInputs,
                        fillInIntent,
                        resultsBundle,
                    )
                }
                pendingIntent.send(targetContext, 0, fillInIntent)
                true
            }.getOrDefault(false)
        }

        /**
         * Dismisses an active notification from the Android status bar if the listener is connected.
         */
        fun dismissSystemNotification(notifId: String): Boolean {
            unregisterReplyActions(notifId)
            val listener = activeListenerInstance ?: return false
            return runCatching {
                listener.cancelNotification(notifId)
                true
            }.getOrDefault(false)
        }
    }
}
