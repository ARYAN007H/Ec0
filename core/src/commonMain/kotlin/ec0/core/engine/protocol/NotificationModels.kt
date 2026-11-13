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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object NotificationProtocol {
    const val CATEGORY_NOTIF: String = "notif"
    const val INFO_NOTIF_POST: String = "notif_post"
    const val CMD_NOTIF_DISMISS: String = "notif_dismiss"
    const val CMD_NOTIF_REPLY: String = "notif_reply"
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
) {
    init {
        require(notifId.isNotBlank()) { "notifId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("actions", buildJsonArray { for (a in actions) add(JsonPrimitive(a)) })
        put("app_name", appName)
        put("body", body)
        put("can_reply", canReply)
        put("notif_id", notifId)
        put("pkg", packageName)
        put("title", title)
        put("ts_ms", timestampMs)
    }

    companion object {
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

            return NotificationPostInfo(
                notifId = notifId,
                packageName = pkg,
                appName = appName,
                title = title,
                body = body,
                timestampMs = tsMs,
                canReply = canReply,
                actions = actions,
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
