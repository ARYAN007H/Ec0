/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Integration & unit tests for Steps 1–4:
 *   1. ContinuityCameraCoordinator (CamDroid TCP :4747 control + scrcpy V4L2 fallback)
 *   2. LinuxSystemBridge (Wayland/X11 clipboard + echo suppression, notify-send argv safety, OS probe)
 *   3. LinuxDesktopIntegrationGenerator & Ec0CliCommand (AirDrop CLI + Dolphin/Nautilus/Freedesktop files)
 *   4. AndroidCompanionRuntime <-> Linux Desktop Ec0EngineNode live localhost Continuity flow
 */
package ec0.core.engine

import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.linux.BinaryCommandExecutor
import ec0.core.engine.linux.BinaryCommandResult
import ec0.core.engine.linux.Ec0CliCommand
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.linux.LinuxDisplayServer
import ec0.core.engine.linux.LinuxEnvironmentProbe
import ec0.core.engine.linux.LinuxNotificationBridge
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidV1Packet
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardMime
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.services.CamDroidControlTransport
import ec0.core.engine.services.CommandExecResult
import ec0.core.engine.services.ContinuityCameraCoordinator
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.RunningProcessHandle
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuitySystemIntegrationTest {

    private class FakeCamDroidTransport(var shouldConnect: Boolean = true) : CamDroidControlTransport {
        override var isConnected: Boolean = false
        var connectedHost: String? = null
        var connectedPort: Int = 0
        val sentPackets = mutableListOf<CamDroidV1Packet>()

        override fun connect(host: String, port: Int, timeoutMs: Int): Boolean {
            if (!shouldConnect) return false
            connectedHost = host
            connectedPort = port
            isConnected = true
            return true
        }

        override fun sendPacket(packet: CamDroidV1Packet): Boolean {
            if (!isConnected) return false
            sentPackets.add(packet)
            return true
        }

        override fun close() {
            isConnected = false
        }
    }

    private class FakeProcessLauncher(
        var adbExitCode: Int = 0,
        var adbStdout: String = "connected to 192.168.1.42:5555",
    ) : ExternalProcessLauncher {
        val blockingCommands = mutableListOf<List<String>>()
        val spawnedCommands = mutableListOf<List<String>>()
        var lastAlive: Boolean = true

        override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
            blockingCommands.add(command)
            return CommandExecResult(exitCode = adbExitCode, stdout = adbStdout)
        }

        override fun spawnProcess(command: List<String>): RunningProcessHandle {
            spawnedCommands.add(command)
            return object : RunningProcessHandle {
                override var isAlive: Boolean = lastAlive
                override fun stop() {
                    isAlive = false
                }
            }
        }
    }

    @Test
    fun continuityCameraCamDroidNativeAndScrcpyFallbackWorkCorrectly() {
        val fakeTransport = FakeCamDroidTransport(shouldConnect = true)
        val fakeLauncher = FakeProcessLauncher()
        var v4l2Writable = true

        val coordinator = ContinuityCameraCoordinator(
            launcher = fakeLauncher,
            camDroidTransportFactory = { fakeTransport },
            v4l2DeviceCheck = { v4l2Writable },
        )

        // 1. Start CamDroid Native stream (front camera = true)
        val status = coordinator.startCameraStream(
            host = "192.168.1.42",
            port = 4747,
            backend = CameraBackend.CAMDROID_NATIVE,
            v4l2DevicePath = "/dev/video10",
            codec = "h264",
            resolution = "1440p",
            fps = 60,
            audio = true,
            frontCamera = true,
            requireV4l2Device = true,
        )
        assertEquals(CameraStreamState.STREAMING, status.state)
        assertEquals("192.168.1.42", fakeTransport.connectedHost)
        assertEquals(4747, fakeTransport.connectedPort)
        // Sent start + switch_camera packets
        assertEquals(2, fakeTransport.sentPackets.size)
        assertEquals(CamDroidMediaKind.CONTROL, fakeTransport.sentPackets[0].kind)
        assertTrue(fakeTransport.sentPackets[0].payload.decodeToString().contains("\"cmd\":\"start\""))
        assertTrue(fakeTransport.sentPackets[1].payload.decodeToString().contains("\"cmd\":\"switch_camera\""))

        // Live zoom control
        coordinator.sendControlCommand(CamDroidControlCommand.SetZoom(250L))
        assertEquals(250L, coordinator.status.value.zoomX100)
        assertTrue(fakeTransport.sentPackets.last().payload.decodeToString().contains("\"value\":2.5"))

        coordinator.stopCameraStream()
        assertEquals(CameraStreamState.STOPPED, coordinator.status.value.state)

        // 2. Test scrcpy V4L2 fallback enforcing Amendment 5 (adb connect first)
        fakeLauncher.blockingCommands.clear()
        fakeLauncher.spawnedCommands.clear()
        val fallbackStatus = coordinator.startCameraStream(
            host = "192.168.1.42",
            port = 5555,
            backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
            v4l2DevicePath = "/dev/video10",
            resolution = "1080p",
            fps = 30,
        )
        assertEquals(CameraStreamState.STREAMING, fallbackStatus.state)
        assertEquals(listOf("adb", "connect", "192.168.1.42:5555"), fakeLauncher.blockingCommands.single())
        val scrcpyCmd = fakeLauncher.spawnedCommands.single()
        assertTrue(scrcpyCmd.contains("--video-source=camera"))
        assertTrue(scrcpyCmd.contains("--v4l2-sink=/dev/video10"))

        // 3. Missing V4L2 device with requireV4l2Device=true must fail closed with modprobe instructions
        v4l2Writable = false
        val errStatus = coordinator.startCameraStream(
            host = "192.168.1.42",
            requireV4l2Device = true,
        )
        assertEquals(CameraStreamState.ERROR, errStatus.state)
        assertTrue(errStatus.reason.orEmpty().contains("modprobe v4l2loopback"))
    }

    @Test
    fun linuxClipboardEchoSuppressionAndNotifySendArgvSafety() {
        var currentClipboardBytes = "initial-linux-clip".encodeToByteArray()
        val executedCommands = mutableListOf<List<String>>()

        val fakeExec = object : BinaryCommandExecutor {
            override fun execute(command: List<String>, stdin: ByteArray, timeoutMs: Long): BinaryCommandResult {
                executedCommands.add(command)
                if (command.first() == "wl-copy") {
                    currentClipboardBytes = stdin.copyOf()
                }
                return BinaryCommandResult(exitCode = 0, stdout = currentClipboardBytes)
            }
        }

        val clipBridge = LinuxClipboardBridge(
            executor = fakeExec,
            displayServerProvider = { LinuxDisplayServer.WAYLAND },
        )

        // First local poll detects "initial-linux-clip"
        val firstPoll = clipBridge.pollLocalClipboardForChange()
        assertNotNull(firstPoll)
        assertEquals("initial-linux-clip", firstPoll.asUtf8Text())

        // Second local poll with unchanged clipboard returns null
        assertNull(clipBridge.pollLocalClipboardForChange())

        // Applying a remote clip from Android writes via wl-copy and records its SHA-256
        val remoteClip = ClipboardItem.text("from-android-phone")
        assertTrue(clipBridge.applyRemoteClipboardToSystem(remoteClip))
        assertEquals("from-android-phone", currentClipboardBytes.decodeToString())

        // Polling local clipboard immediately after remote paste MUST return null (no echo loop!)
        assertNull(clipBridge.pollLocalClipboardForChange())

        // Sensitive clip must NEVER be written to the OS clipboard
        val sensitiveClip = ClipboardItem.text("hunter2-password", isSensitive = true)
        assertFalse(clipBridge.applyRemoteClipboardToSystem(sensitiveClip))

        // Notification bridge uses `--` separator before title/body
        executedCommands.clear()
        val notifBridge = LinuxNotificationBridge(executor = fakeExec)
        val notif = NotificationPostInfo(
            notifId = "n-1",
            packageName = "org.thoughtcrime.securesms",
            appName = "Signal",
            title = "--help", // adversarial title starting with dashes
            body = "Hey from Android",
            timestampMs = 1762905600000L,
            canReply = true,
        )
        assertTrue(notifBridge.showDesktopNotification(notif))
        val notifyCmd = executedCommands.single()
        val dashDashIdx = notifyCmd.indexOf("--")
        assertTrue(dashDashIdx > 0)
        assertEquals("--help", notifyCmd[dashDashIdx + 1])
        assertEquals("Hey from Android", notifyCmd[dashDashIdx + 2])
    }

    @Test
    fun linuxProbeCliParserAndDesktopAirDropInstallerWork() {
        val probe = LinuxEnvironmentProbe(
            envProvider = { k -> if (k == "WAYLAND_DISPLAY") "wayland-1" else null },
            binaryLocator = { bin -> bin in setOf("wl-copy", "wl-paste", "notify-send", "adb", "scrcpy") },
            v4l2Inspector = { false },
        )
        val caps = probe.probe("/dev/video10")
        assertEquals(LinuxDisplayServer.WAYLAND, caps.displayServer)
        assertTrue(caps.clipboardReady)
        assertTrue(caps.notificationsReady)
        assertTrue(caps.screenMirrorReady)
        assertFalse(caps.continuityCameraReady)
        assertEquals(1, caps.remediationHints().size)
        assertTrue(caps.remediationHints()[0].contains("v4l2loopback"))

        // CLI parser
        val sendCmd = Ec0CliCommand.parse(listOf("--send", "/tmp/a.png", "/tmp/b.pdf", "--peer", "phone123"))
        assertEquals(Ec0CliCommand.SendFiles(listOf("/tmp/a.png", "/tmp/b.pdf"), "phone123"), sendCmd)

        val camCmd = Ec0CliCommand.parse(listOf("--camera", "--host", "192.168.1.42", "--res", "1440p", "--fps", "60", "--front"))
        assertEquals(
            Ec0CliCommand.StartCamera(
                host = "192.168.1.42",
                resolution = "1440p",
                fps = 60,
                frontCamera = true,
            ),
            camCmd,
        )

        // Desktop integration generator
        val tempXdg = Files.createTempDirectory("ec0-xdg-test").toFile()
        try {
            val installed = LinuxDesktopIntegrationGenerator.install(tempXdg, "/usr/bin/ec0")
            assertTrue(installed.freedesktopEntry.exists())
            assertTrue(installed.dolphinServiceMenu.exists())
            assertTrue(installed.nautilusScript.exists())
            assertTrue(installed.nautilusScript.canExecute())
            assertTrue(installed.dolphinServiceMenu.readText().contains("Send to Android via ec0 AirDrop"))
        } finally {
            tempXdg.deleteRecursively()
        }
    }

    @Test
    fun androidCompanionRuntimeAndLinuxDesktopNodeEndToEndContinuity() {
        val desktopDownloads = Files.createTempDirectory("ec0-desktop-dl").toFile()
        val phoneDownloads = Files.createTempDirectory("ec0-phone-dl").toFile()

        val replyLatch = CountDownLatch(1)
        val receivedReply = AtomicReference<NotificationReplyCommand>()
        val cameraCmdLatch = CountDownLatch(1)
        val receivedCameraCmd = AtomicReference<CamDroidControlCommand>()

        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generate(),
            displayName = "Arch-Linux-Workstation",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = desktopDownloads,
        )

        val phoneCallbacks = object : AndroidPlatformCallbacks {
            override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean {
                receivedReply.set(reply)
                replyLatch.countDown()
                return true
            }

            override fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo {
                receivedCameraCmd.set(command)
                cameraCmdLatch.countDown()
                return CameraStatusInfo(
                    state = CameraStreamState.STREAMING,
                    zoomX100 = (command as? CamDroidControlCommand.SetZoom)?.zoomX100 ?: 100L,
                )
            }

            override fun queryPhonePresence(): PresenceBeaconInfo = PresenceBeaconInfo(
                batteryPct = 92,
                isCharging = true,
                screenOn = true,
            )
        }

        val phoneRuntime = AndroidCompanionRuntime(
            phoneIdentity = Ed25519Identity.generate(),
            phoneModelName = "OPPO A5x 5G",
            callbacks = phoneCallbacks,
            listenPort = 0,
            downloadDir = phoneDownloads,
        )

        try {
            desktopNode.startServer(startMdns = false)
            phoneRuntime.start(startMdns = false)

            // 1. Desktop generates QR pairing URI; Phone scans and pairs
            val qrUri = desktopNode.generatePairingQr(ips = listOf("127.0.0.1")).toUri()
            assertTrue(phoneRuntime.pairWithDesktopQr(qrUri))

            val desktopId = desktopNode.localIdentity.deviceId
            val phoneId = phoneRuntime.phoneIdentity.deviceId

            // Wait for server-side session registration on desktop
            val deadline = System.currentTimeMillis() + 3_000L
            while (desktopNode.activeSessionFor(phoneId) == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            val desktopSessionToPhone = desktopNode.activeSessionFor(phoneId)
            assertNotNull(desktopSessionToPhone)

            // 2. Phone wakes from ColorOS freeze and sends presence beacon (92% charging)
            assertEquals(1, phoneRuntime.onDeviceWakeOrNetworkValidated())
            val presenceDeadline = System.currentTimeMillis() + 3_000L
            while (desktopNode.peerPresence.value[phoneId] == null && System.currentTimeMillis() < presenceDeadline) {
                Thread.sleep(20L)
            }
            assertEquals(92, desktopNode.peerPresence.value[phoneId]?.batteryPct)
            assertEquals(true, desktopNode.peerPresence.value[phoneId]?.isCharging)

            // 3. Phone posts Android notification -> Desktop sends inline reply -> Phone executes RemoteInput callback
            val notif = NotificationPostInfo(
                notifId = "whatsapp-42",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Aryan",
                body = "Is CamDroid Continuity Camera working?",
                timestampMs = System.currentTimeMillis(),
                canReply = true,
                actions = listOf("reply"),
            )
            assertEquals(1, phoneRuntime.onAndroidNotificationPosted(notif, setOf(desktopId)))

            val notifDeadline = System.currentTimeMillis() + 3_000L
            while (desktopNode.notifications.value.isEmpty() && System.currentTimeMillis() < notifDeadline) {
                Thread.sleep(20L)
            }
            assertEquals("whatsapp-42", desktopNode.notifications.value.first().notifId)

            desktopNode.notificationRelayService.sendInlineReply(
                NotificationReplyCommand(
                    notifId = "whatsapp-42",
                    action = "reply",
                    replyText = "Yes, 1080p60 + V4L2 + inline replies all green!",
                ),
                desktopSessionToPhone,
            )
            assertTrue(replyLatch.await(3, TimeUnit.SECONDS))
            assertEquals("Yes, 1080p60 + V4L2 + inline replies all green!", receivedReply.get()?.replyText)

            // 4. Desktop sends Continuity Camera zoom command over Ec0Session -> Phone receives it
            desktopNode.cameraCoordinator.sendControlCommand(
                CamDroidControlCommand.SetZoom(250L),
                desktopSessionToPhone,
            )
            assertTrue(cameraCmdLatch.await(3, TimeUnit.SECONDS))
            assertEquals(CamDroidControlCommand.SetZoom(250L), receivedCameraCmd.get())
        } finally {
            phoneRuntime.close()
            desktopNode.close()
            desktopDownloads.deleteRecursively()
            phoneDownloads.deleteRecursively()
        }
    }
}
