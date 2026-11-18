/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B4 tests — dbus-monitor block parsing (fixture), relay policy (denylist,
 * quiet hours incl. midnight wrap, title-only privacy), and the monitor
 * loop through an injected runner.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.NotificationPostInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNotificationMonitorTest {

    private val block = """
        method call time=1731800000.123456 sender=:1.42 -> destination=org.freedesktop.Notifications serial=99 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
           string ":1.42"
           uint32 0
           string "Firefox"
           string "dialog-information"
           string "Download complete"
           string "ec0-design.png saved to ~/Downloads"
           array [
              string "default"
           ]
           int32 0

    """.trimIndent() + "\n"

    @Test
    fun parsesNotifyBlockIntoPostInfo() {
        val info = DesktopNotificationParser.parseNotifyBlock(block, nowMs = 1_737_200_000_000L)
        assertEquals("Firefox", info?.packageName)
        assertEquals("Firefox", info?.appName)
        assertEquals("Download complete", info?.title)
        assertEquals("ec0-design.png saved to ~/Downloads", info?.body)
        assertEquals(1_737_200_000_000L, info?.timestampMs)
        assertFalse(info?.canReply ?: true)
    }

    @Test
    fun nonNotifyBlocksAreSkipped() {
        val other = block.replace("member=Notify", "member=GetCapabilities")
        assertNull(DesktopNotificationParser.parseNotifyBlock(other, 0L))
    }

    @Test
    fun streamSplitsIntoBlocksAndFilters() {
        val stream = block + block.replace("member=Notify", "member=GetCapabilities") + block
        val blocks = DesktopNotificationParser.splitBlocks(stream)
        assertEquals(2, blocks.size) // only the two Notify blocks
    }

    // ---- relay policy ------------------------------------------------------

    private fun sample() = NotificationPostInfo(
        notifId = "x", packageName = "org.gnome.Software", appName = "Software",
        title = "Updates", body = "3 updates ready", timestampMs = 0L,
    )

    @Test
    fun denylistBlocksByPackage() {
        val policy = NotificationRelayPolicy(disabledApps = setOf("org.gnome.Software"))
        assertFalse(DesktopNotificationParser.shouldRelay(sample(), policy, minuteOfDay = 12 * 60))
    }

    @Test
    fun quietHoursBlockWithinWindow() {
        val policy = NotificationRelayPolicy(quietHoursEnabled = true, quietStartMinuteOfDay = 22 * 60, quietEndMinuteOfDay = 8 * 60)
        assertFalse(DesktopNotificationParser.shouldRelay(sample(), policy, minuteOfDay = 23 * 60))
        assertTrue(DesktopNotificationParser.shouldRelay(sample(), policy, minuteOfDay = 12 * 60))
        // midnight wrap: 07:59 still quiet, 08:00 not
        assertFalse(DesktopNotificationParser.shouldRelay(sample(), policy, minuteOfDay = 7 * 60 + 59))
        assertTrue(DesktopNotificationParser.shouldRelay(sample(), policy, minuteOfDay = 8 * 60))
    }

    @Test
    fun titleOnlyPrivacyStripsBody() {
        val stripped = DesktopNotificationParser.applyPrivacy(sample(), NotificationRelayPolicy(titleOnly = true))
        assertEquals("", stripped.body)
        assertEquals("Updates", stripped.title)
        assertEquals(sample(), DesktopNotificationParser.applyPrivacy(sample(), NotificationRelayPolicy.DEFAULT))
    }

    // ---- monitor loop ------------------------------------------------------

    @Test
    fun monitorLoopRelaysParsedNotificationsThroughPolicy() {
        val runnerLines = mutableListOf<String>()
        val relayed = mutableListOf<NotificationPostInfo>()
        val monitor = DesktopNotificationMonitor(
            policyProvider = { NotificationRelayPolicy.DEFAULT },
            monitorRunner = { _, onLine ->
                block.lines().forEach(onLine)
                onLine("") // flush terminator
            },
            sender = { relayed.add(it) },
        )
        monitor.start()
        assertEquals(listOf("Firefox"), relayed.map { it.appName })
        monitor.stop()
    }
}
