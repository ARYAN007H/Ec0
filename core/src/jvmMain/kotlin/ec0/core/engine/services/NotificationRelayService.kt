/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Notification Relay Service (`docs/engine/design.md` §6.4):
 *   - Phone -> Desktop: posts and dismisses Android notifications (`notif_post`, `notif_dismiss`).
 *   - Desktop -> Phone: dispatches inline quick-replies (`notif_reply`) and remote dismissals.
 *   - Bounded feed (`maxActiveNotifications = 128`) sorted newest-first.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.NotificationDismissCommand
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NotificationRelayService(
    private val maxActiveNotifications: Int = 128,
    private val onInlineReplyReceived: (NotificationReplyCommand) -> Unit = {},
    private val onRemoteDismissReceived: (String) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val byId = LinkedHashMap<String, NotificationPostInfo>()

    private val _notifications = MutableStateFlow<List<NotificationPostInfo>>(emptyList())
    val notifications: StateFlow<List<NotificationPostInfo>> = _notifications.asStateFlow()

    fun postNotification(
        notif: NotificationPostInfo,
        session: Ec0Session,
    ) = postNotification(notif) { body ->
        session.sendInfo(
            infoType = NotificationProtocol.INFO_NOTIF_POST,
            infoBody = body,
            category = NotificationProtocol.CATEGORY_NOTIF,
            timestampMs = notif.timestampMs,
        )
    }

    fun postNotification(
        notif: NotificationPostInfo,
        sendInfo: (JsonObject) -> Unit,
    ) {
        sendInfo(notif.toJsonObject())
    }

    fun dismissNotification(
        notifId: String,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) = dismissNotification(notifId) { body ->
        session.sendCmd(
            cmdType = NotificationProtocol.CMD_NOTIF_DISMISS,
            cmdBody = body,
            category = NotificationProtocol.CATEGORY_NOTIF,
            timestampMs = timestampMs,
        )
    }

    fun dismissNotification(
        notifId: String,
        sendCmd: (JsonObject) -> Unit,
    ) {
        removeLocal(notifId)
        val cmd = NotificationDismissCommand(notifId)
        sendCmd(cmd.toJsonObject())
    }

    fun sendInlineReply(
        reply: NotificationReplyCommand,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) = sendInlineReply(reply) { body ->
        session.sendCmd(
            cmdType = NotificationProtocol.CMD_NOTIF_REPLY,
            cmdBody = body,
            category = NotificationProtocol.CATEGORY_NOTIF,
            timestampMs = timestampMs,
        )
    }

    fun sendInlineReply(
        reply: NotificationReplyCommand,
        sendCmd: (JsonObject) -> Unit,
    ) {
        sendCmd(reply.toJsonObject())
    }

    /**
     * Handles an incoming verified `notif_post`, `notif_dismiss`, or `notif_reply` [Ec0Envelope].
     * Returns `true` if handled by this service.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            NotificationProtocol.INFO_NOTIF_POST -> {
                val post = NotificationPostInfo.fromJsonObject(body)
                upsertLocal(post)
                true
            }
            NotificationProtocol.CMD_NOTIF_DISMISS -> {
                val dismiss = NotificationDismissCommand.fromJsonObject(body)
                removeLocal(dismiss.notifId)
                onRemoteDismissReceived(dismiss.notifId)
                true
            }
            NotificationProtocol.CMD_NOTIF_REPLY -> {
                val reply = NotificationReplyCommand.fromJsonObject(body)
                onInlineReplyReceived(reply)
                true
            }
            else -> false
        }
    }

    private fun upsertLocal(post: NotificationPostInfo) {
        lock.withLock {
            byId.remove(post.notifId)
            if (byId.size >= maxActiveNotifications) {
                val oldestKey = byId.keys.firstOrNull()
                if (oldestKey != null) byId.remove(oldestKey)
            }
            byId[post.notifId] = post
            publishLocked()
        }
    }

    private fun removeLocal(notifId: String) {
        lock.withLock {
            if (byId.remove(notifId) != null) {
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        _notifications.value = byId.values.sortedByDescending { it.timestampMs }
    }
}
