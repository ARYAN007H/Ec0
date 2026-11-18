/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B4 — desktop→phone notification relay: eavesdrops the session bus for
 * `org.freedesktop.Notifications.Notify` method calls (every desktop app's
 * notification lands there) via `dbus-monitor --pcap`-less text mode, parses
 * them into NotificationPostInfo envelopes, and applies the relay policy:
 * per-app denylist, quiet hours, title-only privacy tier.
 *
 * The parser is pure (fixture-tested); the service shells out to
 * `dbus-monitor` via the injected process runner and forwards through a
 * caller-supplied sender (the hub's notification envelope path).
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.NotificationPostInfo

/**
 * Relay policy (SettingsState mirrors these): per-app denylist, quiet hours
 * window (minutes-of-day, may wrap midnight), title-only privacy tier.
 */
data class NotificationRelayPolicy(
    val disabledApps: Set<String> = emptySet(),
    val quietHoursEnabled: Boolean = false,
    val quietStartMinuteOfDay: Int = 22 * 60,
    val quietEndMinuteOfDay: Int = 8 * 60,
    val titleOnly: Boolean = false,
) {
    companion object {
        val DEFAULT = NotificationRelayPolicy()
    }
}

object DesktopNotificationParser {

    /**
     * Parses one `dbus-monitor` Notify method-call block into a
     * [NotificationPostInfo]. Reference Notify signature:
     * `Notify(app_name UINT32 replaces_id icon summary body actions…)` —
     * dbus-monitor prints the body strings in order.
     */
    fun parseNotifyBlock(block: String, nowMs: Long): NotificationPostInfo? {
        if (!block.contains("member=Notify")) return null
        val stringArgs = Regex("string\\s+\"([^\"]*)\"").findAll(block).map { it.groupValues[1] }.toList()
        // string args: [sender, app_name, icon, summary(title), body, …]
        if (stringArgs.size < 5) return null
        val appName = stringArgs[1]
        val title = stringArgs[3]
        val body = stringArgs[4]
        if (appName.isBlank() && title.isBlank()) return null
        return NotificationPostInfo(
            notifId = "$appName:${title.hashCode()}:$nowMs",
            packageName = appName,
            appName = appName,
            title = title,
            body = body,
            timestampMs = nowMs,
            canReply = false,
        )
    }

    /** Splits a raw dbus-monitor stream into per-call blocks (method-call … next). */
    fun splitBlocks(stream: String): List<String> =
        stream.split(Regex("(?=method call time=)")).filter { it.contains("member=Notify") }

    /** Applies the relay policy. Pure — unit-tested. */
    fun shouldRelay(
        info: NotificationPostInfo,
        policy: NotificationRelayPolicy,
        minuteOfDay: Int,
    ): Boolean {
        if (info.packageName in policy.disabledApps) return false
        if (policy.quietHoursEnabled && inQuietHours(minuteOfDay, policy)) return false
        return true
    }

    /** Applies the privacy tier: title-only strips the body. */
    fun applyPrivacy(info: NotificationPostInfo, policy: NotificationRelayPolicy): NotificationPostInfo =
        if (policy.titleOnly) info.copy(body = "") else info

    /** Quiet window supports wrapping past midnight (22:00 → 08:00). */
    fun inQuietHours(minuteOfDay: Int, policy: NotificationRelayPolicy): Boolean {
        val start = policy.quietStartMinuteOfDay
        val end = policy.quietEndMinuteOfDay
        return if (start <= end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
    }
}

class DesktopNotificationMonitor(
    private val policyProvider: () -> NotificationRelayPolicy = { NotificationRelayPolicy.DEFAULT },
    private val monitorRunner: (List<String>, onLine: (String) -> Unit) -> Unit = { _, _ -> },
    private val sender: (NotificationPostInfo) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val buffer = StringBuilder()
        monitorRunner(listOf("dbus-monitor", "--session")) { line ->
            buffer.appendLine(line)
            if (line.isBlank()) {
                val block = buffer.toString()
                buffer.clear()
                val info = DesktopNotificationParser.parseNotifyBlock(block, clock()) ?: return@monitorRunner
                val policy = policyProvider()
                val minuteOfDay = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                if (DesktopNotificationParser.shouldRelay(info, policy, minuteOfDay)) {
                    sender(DesktopNotificationParser.applyPrivacy(info, policy))
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }
}


}
