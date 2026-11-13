/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.services

import ec0.core.engine.EngineCapability
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.PresenceProtocol
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InputNotificationPresenceScrcpyTest {

    private val desktopKey = Ed25519Identity.generateKeyPair()
    private val phoneKey = Ed25519Identity.generateKeyPair()

    @Test
    fun inputShareRoutesKeyboardCombosUtf8CommitsAndTouchpadDeltas() {
        val receivedKeys = mutableListOf<InputKeyEvent>()
        val receivedPointers = mutableListOf<InputPointerEvent>()

        val sender = InputShareService()
        val receiver = InputShareService(
            sink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    receivedKeys.add(event)
                }

                override fun onPointerEvent(event: InputPointerEvent) {
                    receivedPointers.add(event)
                }
            },
        )

        val proc = SessionEnvelopeProcessor(desktopKey, phoneKey.deviceId, phoneKey.publicKeyRaw)
        val outbound = mutableListOf<JsonObject>()

        val keyEvt = InputKeyEvent(
            action = InputProtocol.KEY_ACTION_COMMIT_TEXT,
            keyCode = 0,
            modifiers = InputProtocol.MOD_CTRL or InputProtocol.MOD_SHIFT,
            text = "नमस्ते Continuity 🚀",
        )
        assertTrue(sender.sendKeyEvent(keyEvt) { outbound.add(it) })
        val keyEnv = proc.nextCmdEnvelope(
            cmdType = InputProtocol.CMD_INPUT_KEY,
            cmdBody = outbound.removeAt(0),
            category = InputProtocol.CATEGORY_INPUT,
        )
        assertTrue(receiver.handleControlEnvelope(keyEnv))
        assertEquals(1, receivedKeys.size)
        assertEquals(keyEvt, receivedKeys[0])

        val ptrEvt = InputPointerEvent(
            action = InputProtocol.POINTER_ACTION_MOVE,
            dx = -18,
            dy = 42,
            scrollX = 0,
            scrollY = -3,
            buttonsMask = 1,
        )
        assertTrue(sender.sendPointerEvent(ptrEvt) { outbound.add(it) })
        val ptrEnv = proc.nextCmdEnvelope(
            cmdType = InputProtocol.CMD_INPUT_POINTER,
            cmdBody = outbound.removeAt(0),
            category = InputProtocol.CATEGORY_INPUT,
        )
        assertTrue(receiver.handleControlEnvelope(ptrEnv))
        assertEquals(1, receivedPointers.size)
        assertEquals(ptrEvt, receivedPointers[0])
    }

    @Test
    fun notificationRelayMirrorsPostRoutesQuickReplyAndPropagatesDismiss() {
        val phoneReplies = mutableListOf<NotificationReplyCommand>()
        val phoneDismissedIds = mutableListOf<String>()

        val phoneNotifService = NotificationRelayService(
            onInlineReplyReceived = { phoneReplies.add(it) },
            onRemoteDismissReceived = { phoneDismissedIds.add(it) },
        )
        val desktopNotifService = NotificationRelayService()

        val phoneProc = SessionEnvelopeProcessor(phoneKey, desktopKey.deviceId, desktopKey.publicKeyRaw)
        val desktopProc = SessionEnvelopeProcessor(desktopKey, phoneKey.deviceId, phoneKey.publicKeyRaw)

        val msg1 = NotificationPostInfo(
            notifId = "whatsapp:101",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Aryan",
            body = "Is Slice 6 ready?",
            timestampMs = 1_762_905_600_000L,
            canReply = true,
            actions = listOf("Reply", "Mark Read"),
        )
        val msg2 = NotificationPostInfo(
            notifId = "signal:202",
            packageName = "org.thoughtcrime.securesms",
            appName = "Signal",
            title = "Lead",
            body = "CI is green!",
            timestampMs = 1_762_905_605_000L,
            canReply = true,
            actions = listOf("Reply"),
        )

        val phoneOut = mutableListOf<JsonObject>()
        phoneNotifService.postNotification(msg1) { phoneOut.add(it) }
        phoneNotifService.postNotification(msg2) { phoneOut.add(it) }

        for (body in phoneOut) {
            val env = phoneProc.nextInfoEnvelope(
                infoType = NotificationProtocol.INFO_NOTIF_POST,
                infoBody = body,
                category = NotificationProtocol.CATEGORY_NOTIF,
            )
            assertTrue(desktopNotifService.handleControlEnvelope(env))
        }

        // Feed is sorted newest-first
        assertEquals(listOf("signal:202", "whatsapp:101"), desktopNotifService.notifications.value.map { it.notifId })

        // Desktop user sends inline quick reply to whatsapp:101
        val desktopOut = mutableListOf<JsonObject>()
        val replyCmd = NotificationReplyCommand(
            notifId = "whatsapp:101",
            action = "Reply",
            replyText = "Yes, tested and verified!",
        )
        desktopNotifService.sendInlineReply(replyCmd) { desktopOut.add(it) }
        val replyEnv = desktopProc.nextCmdEnvelope(
            cmdType = NotificationProtocol.CMD_NOTIF_REPLY,
            cmdBody = desktopOut.removeAt(0),
            category = NotificationProtocol.CATEGORY_NOTIF,
        )
        assertTrue(phoneNotifService.handleControlEnvelope(replyEnv))
        assertEquals(listOf(replyCmd), phoneReplies)

        // Desktop user dismisses signal:202 -> removed locally and propagated to phone
        desktopNotifService.dismissNotification("signal:202") { desktopOut.add(it) }
        assertEquals(listOf("whatsapp:101"), desktopNotifService.notifications.value.map { it.notifId })
        val dismissEnv = desktopProc.nextCmdEnvelope(
            cmdType = NotificationProtocol.CMD_NOTIF_DISMISS,
            cmdBody = desktopOut.removeAt(0),
            category = NotificationProtocol.CATEGORY_NOTIF,
        )
        assertTrue(phoneNotifService.handleControlEnvelope(dismissEnv))
        assertEquals(listOf("signal:202"), phoneDismissedIds)
    }

    @Test
    fun presenceServiceTracksSignedBatteryAndCapabilityBeacons() {
        val phonePresence = PresenceService()
        val desktopPresence = PresenceService()
        val phoneProc = SessionEnvelopeProcessor(phoneKey, desktopKey.deviceId, desktopKey.publicKeyRaw)

        val beacon = PresenceBeaconInfo(
            batteryPct = 88,
            isCharging = true,
            screenOn = true,
            capabilities = setOf(EngineCapability.CLIPBOARD, EngineCapability.FILE, EngineCapability.MIRROR),
            wifiSsidHash = "a1b2c3d4",
        )
        val out = mutableListOf<JsonObject>()
        phonePresence.broadcastBeacon(beacon) { out.add(it) }

        val env = phoneProc.nextInfoEnvelope(
            infoType = PresenceProtocol.INFO_PRESENCE_BEACON,
            infoBody = out.removeAt(0),
            category = PresenceProtocol.CATEGORY_PRESENCE,
        )
        assertTrue(desktopPresence.handleControlEnvelope(phoneKey.deviceId, env))

        val tracked = desktopPresence.peerPresence.value[phoneKey.deviceId]
        assertNotNull(tracked)
        assertEquals(88, tracked.batteryPct)
        assertTrue(tracked.isCharging)
        assertTrue(tracked.screenOn)
        assertEquals("a1b2c3d4", tracked.wifiSsidHash)
    }

    @Test
    fun scrcpyCoordinatorRunsAdbConnectBeforeSpawningScrcpyPerAmendment5() {
        val executedCommands = mutableListOf<List<String>>()
        var adbShouldSucceed = true
        var spawnedAlive = false

        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
                executedCommands.add(command)
                return if (adbShouldSucceed) {
                    CommandExecResult(0, "connected to 192.168.1.42:5555\n")
                } else {
                    CommandExecResult(1, "failed to connect to 192.168.1.42:5555\n")
                }
            }

            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                executedCommands.add(command)
                spawnedAlive = true
                return object : RunningProcessHandle {
                    override val isAlive: Boolean
                        get() = spawnedAlive

                    override fun stop() {
                        spawnedAlive = false
                    }
                }
            }
        }

        val coordinator = ScrcpyMirrorCoordinator(launcher = fakeLauncher)
        val phoneProc = SessionEnvelopeProcessor(phoneKey, desktopKey.deviceId, desktopKey.publicKeyRaw)

        val startCmd = ScrcpyMirrorStartCommand(
            host = "192.168.1.42",
            adbPort = 5555,
            maxFps = 90,
            bitrateKbps = 12_000,
            windowTitle = "OnePlus 12 Continuity",
        )
        val startEnv = phoneProc.nextCmdEnvelope(
            cmdType = PresenceProtocol.CMD_MIRROR_START,
            cmdBody = startCmd.toJsonObject(),
            category = PresenceProtocol.CATEGORY_MIRROR,
        )
        assertTrue(coordinator.handleControlEnvelope(startEnv))
        assertTrue(coordinator.isMirroring)
        assertEquals("192.168.1.42:5555", coordinator.currentSerial)

        // Verify exact Amendment 5 command ordering: 1st = adb connect, 2nd = scrcpy --serial
        assertEquals(2, executedCommands.size)
        assertEquals(listOf("adb", "connect", "192.168.1.42:5555"), executedCommands[0])
        assertEquals(
            listOf(
                "scrcpy",
                "--serial",
                "192.168.1.42:5555",
                "--window-title",
                "OnePlus 12 Continuity",
                "--max-fps",
                "90",
                "--video-bit-rate",
                "12000K",
            ),
            executedCommands[1],
        )

        // Stop mirror session
        coordinator.stopLocalMirror()
        assertFalse(coordinator.isMirroring)

        // If adb connect fails, scrcpy must NOT be spawned
        executedCommands.clear()
        adbShouldSucceed = false
        assertFailsWith<IOException> {
            coordinator.launchMirrorSession(startCmd)
        }
        assertEquals(1, executedCommands.size) // Only adb connect ran; scrcpy was not spawned
        assertFalse(coordinator.isMirroring)
    }
}
