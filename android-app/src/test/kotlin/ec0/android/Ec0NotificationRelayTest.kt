/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.android

import android.app.Notification
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.EnvelopeSigner
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ec0NotificationRelayTest {

    @AfterTest
    fun tearDown() {
        Ec0NotificationListener.attachRuntime(null)
        Ec0NotificationListener.clearReplyActionsForTest()
    }

    @Test
    fun `stripRawPackageText removes raw package names from notification titles`() {
        assertEquals(
            "Alice",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "com.whatsapp: Alice",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
            ),
        )
        assertEquals(
            "New voice note",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "New voice note (com.whatsapp)",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
            ),
        )
        assertEquals(
            "Bob — CI green",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "[org.telegram.messenger] Bob — CI green",
                packageName = "org.telegram.messenger",
                appName = "Telegram",
            ),
        )
        assertEquals(
            "#engineering",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "com.Slack.android - #engineering",
                packageName = "com.Slack.android",
                appName = "Slack",
            ),
        )
        assertEquals(
            "Room invite",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "io.element.android.x: Room invite (net.matrix.client)",
                packageName = "io.element.android.x",
                appName = "Element X",
            ),
        )
        // When title consists solely of the raw package name, fall back to clean appName
        assertEquals(
            "WhatsApp",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "com.whatsapp",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
            ),
        )
        // When both title and appName are raw package strings, humanize packageName
        assertEquals(
            "Telegram",
            NotificationRelayMapper.stripRawPackageText(
                rawTitle = "org.telegram.messenger",
                packageName = "org.telegram.messenger",
                appName = "org.telegram.messenger",
            ),
        )
    }

    @Test
    fun `StatusBarNotification maps to NotificationPostInfo relay model and strips package text`() {
        val fakeNotification = Notification().apply {
            extras = Bundle()
            tickerText = "com.whatsapp: Aryan (com.whatsapp)"
        }

        val fakeSbn = object : StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            42,
            "chat_tag",
            10123,
            0,
            0,
            fakeNotification,
            null as UserHandle?,
            1_731_700_000_000L,
        ) {
            override fun getKey(): String = "0|com.whatsapp|42|chat_tag|10123"
            override fun getPackageName(): String = "com.whatsapp"
            override fun getId(): Int = 42
            override fun getTag(): String = "chat_tag"
            override fun getPostTime(): Long = 1_731_700_000_000L
            override fun getNotification(): Notification = fakeNotification
            override fun isOngoing(): Boolean = false
        }

        val postInfo = NotificationRelayMapper.fromStatusBarNotification(
            sbn = fakeSbn,
            appLabelResolver = { pkg -> if (pkg == "com.whatsapp") "WhatsApp" else null },
            extrasReader = { _, key ->
                when (key) {
                    Notification.EXTRA_TITLE -> "com.whatsapp: Aryan (com.whatsapp)"
                    Notification.EXTRA_TEXT -> "Short preview"
                    Notification.EXTRA_BIG_TEXT -> "Did the Smart Connect QR scan succeed?"
                    else -> null
                }
            },
        )
        assertNotNull(postInfo)
        assertEquals("0|com.whatsapp|42|chat_tag|10123", postInfo.notifId)
        assertEquals("com.whatsapp", postInfo.packageName)
        assertEquals("WhatsApp", postInfo.appName)
        assertEquals("Aryan", postInfo.title)
        assertEquals("Did the Smart Connect QR scan succeed?", postInfo.body)
        assertEquals(1_731_700_000_000L, postInfo.timestampMs)
        assertFalse(postInfo.canReply)

        // Verify empty group-summary notifications are ignored
        val emptySummary = ExtractedStatusBarNotification(
            key = "0|com.whatsapp|0|summary|10123",
            id = 0,
            tag = "summary",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            rawTitle = null,
            rawText = null,
            rawBigText = null,
            postTimeMs = 1_731_700_000_000L,
            isGroupSummary = true,
        )
        assertNull(NotificationRelayMapper.mapExtractedToRelayModel(emptySummary))
    }

    @Test
    fun `onNotificationPosted relays to AndroidCompanionRuntime and supports RemoteInput sendInlineReply`() {
        val tempRoot = Files.createTempDirectory("ec0-notif-relay-test").toFile()
        try {
            val storeDir = File(tempRoot, "files/ec0")
            var capturedReplyText: String? = null

            val runtime = AndroidCompanionRuntime.createWithPersistentStores(
                storeDir = storeDir,
                phoneModelName = "A5x-Phone",
                callbacks = object : AndroidPlatformCallbacks {
                    override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean {
                        return Ec0NotificationListener.sendInlineReply(reply)
                    }
                },
                listenPort = 0,
                clockMs = { 1_731_700_050_000L },
            )

            try {
                // Pin a desktop peer so onAndroidNotificationPosted routes to it
                val desktopIdentity = Ed25519Identity.generate()
                runtime.node.pinStore.verifyOrPin(
                    deviceId = desktopIdentity.deviceId,
                    publicKeyRaw = desktopIdentity.publicKeyRaw,
                    alias = "Arch-Desktop",
                    trustLevel = TrustLevel.TRUSTED_QR,
                    nowMs = 1_700_000_000_000L,
                )

                Ec0NotificationListener.attachRuntime(runtime)

                val extracted = ExtractedStatusBarNotification(
                    key = "0|org.telegram.messenger|77|dm|10200",
                    id = 77,
                    tag = "dm",
                    packageName = "org.telegram.messenger",
                    appName = "Telegram",
                    rawTitle = "[org.telegram.messenger] Agent-0",
                    rawText = "Ping when PR is ready",
                    rawBigText = null,
                    postTimeMs = 1_731_700_050_000L,
                    replyActions = listOf(
                        RegisteredReplyAction(
                            actionTitle = "Reply",
                            resultKeys = listOf("key_text_reply"),
                            customReplyExecutor = { text ->
                                capturedReplyText = text
                                true
                            },
                        ),
                    ),
                    actionLabels = listOf("Reply", "Mark as read"),
                )

                val posted = Ec0NotificationListener.handleExtractedNotificationPosted(extracted)
                assertNotNull(posted)
                assertEquals("Agent-0", posted.title)
                assertEquals("Telegram", posted.appName)
                assertTrue(posted.canReply)
                assertEquals(listOf("Reply", "Mark as read"), posted.actions)

                // Verify local NotificationRelayService recorded the notification
                val activeFeed = runtime.node.notificationRelayService.notifications.value
                assertEquals(1, activeFeed.size)
                assertEquals("0|org.telegram.messenger|77|dm|10200", activeFeed.first().notifId)
                assertEquals("Agent-0", activeFeed.first().title)

                // Verify notification was also queued in outboxQueue for the offline pinned desktop
                val queuedForDesktop = runtime.node.outboxQueue.drainFresh(
                    peerDeviceId = desktopIdentity.deviceId,
                    nowMs = 1_731_700_051_000L,
                )
                assertEquals(1, queuedForDesktop.size)

                // Simulate desktop sending a signed CMD_NOTIF_REPLY envelope back to the phone engine
                val replyCmd = NotificationReplyCommand(
                    notifId = posted.notifId,
                    action = "Reply",
                    replyText = "PR is up and CI is green!",
                )
                val replyEnvelope = EnvelopeSigner.signEnvelope(
                    identity = desktopIdentity,
                    msgType = Ec0EnvelopeCodec.TYPE_CMD,
                    msgBody = Ec0EnvelopeCodec.buildCmdBody(
                        cmdType = NotificationProtocol.CMD_NOTIF_REPLY,
                        cmdBody = replyCmd.toJsonObject(),
                    ),
                    category = NotificationProtocol.CATEGORY_NOTIF,
                    timestampMs = 1_731_700_060_000L,
                    seq = 1L,
                )

                val handled = runtime.node.notificationRelayService.handleControlEnvelope(replyEnvelope)
                assertTrue(handled)
                assertEquals("PR is up and CI is green!", capturedReplyText)
            } finally {
                runtime.close()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `NotificationAccessHelper detects listener access in colon-separated setting string`() {
        val enabledFull =
            "com.google.android.projection.gearhead/com.google.android.gearhead.notifications.CarNotificationListenerService:" +
                "ec0.android/ec0.android.Ec0NotificationListener"
        assertTrue(
            NotificationAccessHelper.isComponentInEnabledListeners(
                enabledListenersSetting = enabledFull,
                packageName = "ec0.android",
                serviceClassName = "ec0.android.Ec0NotificationListener",
            ),
        )

        val enabledShort = "ec0.android/.Ec0NotificationListener:com.other.app/.Listener"
        assertTrue(
            NotificationAccessHelper.isComponentInEnabledListeners(
                enabledListenersSetting = enabledShort,
                packageName = "ec0.android",
                serviceClassName = "ec0.android.Ec0NotificationListener",
            ),
        )

        val disabled = "com.other.app/com.other.app.OtherListener"
        assertFalse(
            NotificationAccessHelper.isComponentInEnabledListeners(
                enabledListenersSetting = disabled,
                packageName = "ec0.android",
                serviceClassName = "ec0.android.Ec0NotificationListener",
            ),
        )
        assertFalse(
            NotificationAccessHelper.isComponentInEnabledListeners(
                enabledListenersSetting = null,
                packageName = "ec0.android",
            ),
        )
    }
}
