/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Wire models for ec0-engine Notification Relay (`category = "notif"`,
 * `info_type = "notif_post"`, `cmd_type = "notif_dismiss" | "notif_reply"`,
 * docs/engine/design.md §6.4).
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object NotificationProtocol {
    const val CATEGORY_NOTIF: String = "notif"
    const val INFO_NOTIF_POST: String = "notif_post"
    const val CMD_NOTIF_DISMISS: String = "notif_dismiss"
    const val CMD_NOTIF_REPLY: String = "notif_reply"
    const val CMD_NOTIF_ACTION: String = "notif_action"
}

data class NotificationPostInfo(
    val notifId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val timestampMs: Long,
    val canReply: Boolean = false,
    val actions: List<String> = emptyList(),
    /** Optional tiny PNG/WebP app icon encoded in Base64 (capped at 16 KiB text). */
    val appIconB64: String? = null,
) {
    init {
        require(notifId.isNotBlank()) { "notifId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    /**
     * Walled-garden content caps (audit M1): relayed notification content is
     * bounded on BOTH the send and receive paths — a local app or paired peer
     * pushing multi-megabyte strings can neither inflate the peer link nor the
     * receiving feed. `notifId`/`packageName` keep their integrity requirements.
     */
    fun sanitized(): NotificationPostInfo = copy(
        appName = appName.take(MAX_APP_NAME_CHARS),
        title = title.take(MAX_TITLE_CHARS),
        body = body.take(MAX_BODY_CHARS),
        actions = actions.take(MAX_ACTION_COUNT).map { it.take(MAX_ACTION_CHARS) },
        appIconB64 = appIconB64?.takeIf { it.isNotBlank() && it.length <= MAX_ICON_B64_CHARS },
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("actions", buildJsonArray { for (a in actions) add(JsonPrimitive(a)) })
        if (!appIconB64.isNullOrBlank() && appIconB64.length <= MAX_ICON_B64_CHARS) {
            put("app_icon_b64", appIconB64)
        }
        put("app_name", appName)
        put("body", body)
        put("can_reply", canReply)
        put("notif_id", notifId)
        put("pkg", packageName)
        put("title", title)
        put("ts_ms", timestampMs)
    }

    companion object {
        const val MAX_TITLE_CHARS: Int = 512
        const val MAX_BODY_CHARS: Int = 4096
        const val MAX_APP_NAME_CHARS: Int = 128
        const val MAX_ACTION_COUNT: Int = 8
        const val MAX_ACTION_CHARS: Int = 128
        const val MAX_ICON_B64_CHARS: Int = 16_384

        fun fromJsonObject(obj: JsonObject): NotificationPostInfo {
            val notifId = (obj["notif_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'notif_id' in notif_post")
            val pkg = (obj["pkg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'pkg' in notif_post")
            val appName = (obj["app_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: pkg
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val tsMs = (obj["ts_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
            val canReply = (obj["can_reply"] as? JsonPrimitive)?.booleanOrNull ?: false
            val actions = (obj["actions"] as? JsonArray)
                ?.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content }
                ?: emptyList()
            val iconB64 = (obj["app_icon_b64"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() && it.length <= MAX_ICON_B64_CHARS }

            return NotificationPostInfo(
                notifId = notifId,
                packageName = pkg,
                appName = appName,
                title = title,
                body = body,
                timestampMs = tsMs,
                canReply = canReply,
                actions = actions,
                appIconB64 = iconB64,
            )
        }
    }
}

data class NotificationDismissCommand(
    val notifId: String,
) {
    init {
        require(notifId.isNotBlank()) { "notifId must not be blank" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("notif_id", notifId)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): NotificationDismissCommand {
            val notifId = (obj["notif_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'notif_id' in notif_dismiss")
            return NotificationDismissCommand(notifId)
        }
    }
}

data class NotificationReplyCommand(
    val notifId: String,
    val action: String = "reply",
    val replyText: String,
) {
    init {
        require(notifId.isNotBlank()) { "notifId must not be blank" }
        require(replyText.isNotBlank()) { "replyText must not be blank" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("notif_id", notifId)
        put("reply_text", replyText)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): NotificationReplyCommand {
            val notifId = (obj["notif_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'notif_id' in notif_reply")
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "reply"
            val replyText = (obj["reply_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'reply_text' in notif_reply")
            return NotificationReplyCommand(
                notifId = notifId,
                action = action,
                replyText = replyText,
            )
        }
    }
}

/**
 * Triggers a non-reply action button (e.g. "Mark as Read", "Archive") against a
 * cached Android [android.app.PendingIntent] captured when the notification posted.
 */
data class NotificationActionCommand(
    val notifId: String,
    val actionIndex: Int,
    val actionLabel: String = "",
) {
    init {
        require(notifId.isNotBlank()) { "notifId must not be blank" }
        require(actionIndex in 0 until NotificationPostInfo.MAX_ACTION_COUNT) {
            "actionIndex out of range: $actionIndex"
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action_index", actionIndex)
        put("action_label", actionLabel.take(NotificationPostInfo.MAX_ACTION_CHARS))
        put("notif_id", notifId)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): NotificationActionCommand {
            val notifId = (obj["notif_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'notif_id' in notif_action")
            val index = (obj["action_index"] as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("Missing 'action_index' in notif_action")
            val label = (obj["action_label"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return NotificationActionCommand(
                notifId = notifId,
                actionIndex = index,
                actionLabel = label.take(NotificationPostInfo.MAX_ACTION_CHARS),
            )
        }
    }
}
