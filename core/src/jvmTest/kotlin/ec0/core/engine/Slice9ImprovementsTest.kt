/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM integration tests (`jvmTest`) for Slices 9, 10, and 11 production-grade improvements:
 *   - EncryptedIdentityStore & PersistentPinStore (ChaCha20-Poly1305 at-rest encryption,
 *     atomic disk persistence, reload across restarts, TOFU key mismatch rejection)
 *   - Ec0MdnsDiscovery automatic `_camdroid._tcp.local.` service extraction
 *   - ContinuityCameraCoordinator 1-click `pkexec modprobe v4l2loopback` & persistent
 *     `/etc/modules-load.d/` config generation
 *   - UniversalControlService screen-edge cursor barrier & cooldown hysteresis
 *   - HotspotAndTelephonyService `nmcli` command builder & auto `playerctl pause` on call
 *   - MediaSessionBridgeService `playerctl` MPRIS2 command dispatch
 *   - LinuxClipboardBridge `wl-paste --watch`, RFC 2483 `text/uri-list`, & 30s OTP auto-scrub
 *   - LinuxNotificationBridge interactive D-Bus `gdbus` notification & ActionInvoked parser
 *   - FileTransferService adaptive chunk sizing & TransferThroughputTracker EMA + ETA
 *   - Slice 11: Tier-0 USB-C ADB Tunnel, PipeWire Virtual Mic + A/V drift, Handoff URL
 *     security validation, 2FA OTP extraction, Proximity Lock / Find My Phone,
 *     Authenticated Loopback CLI IPC (`127.0.0.1`), Session Re-Keying (`0x04`),
 *     and Continuity Sketch normalized overlay serialization.
 */
package ec0.core.engine

import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.AeadSecurityException
import ec0.core.engine.crypto.AeadSessionChannel
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.KeyMismatchException
import ec0.core.engine.crypto.PersistentPinStore
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.discovery.Ec0MdnsDiscovery
import ec0.core.engine.discovery.EndpointCandidate
import ec0.core.engine.discovery.EndpointResolver
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDisplayServer
import ec0.core.engine.linux.LinuxInputInjectionBridge
import ec0.core.engine.linux.LinuxNotificationBridge
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ContinuitySketchOverlay
import ec0.core.engine.protocol.ContinuitySketchPoint
import ec0.core.engine.protocol.ContinuitySketchStroke
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.FindMyRingCommand
import ec0.core.engine.protocol.HandoffOpenCommand
import ec0.core.engine.protocol.HandoffProtocol
import ec0.core.engine.protocol.HotspotAndTelephonyProtocol
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaControlProtocol
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.ProximitySecurityProtocol
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.protocol.UniversalControlProtocol
import ec0.core.engine.protocol.WorkstationLockCommand
import ec0.core.engine.services.CommandExecutionResult
import ec0.core.engine.services.ContinuityCameraCoordinator
import ec0.core.engine.services.ContinuityVirtualMicBridge
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.FileTransferService
import ec0.core.engine.services.HandoffAndProximityService
import ec0.core.engine.services.HotspotAndTelephonyService
import ec0.core.engine.services.LocalDaemonIpcClient
import ec0.core.engine.services.LocalDaemonIpcServer
import ec0.core.engine.services.MediaSessionBridgeService
import ec0.core.engine.services.RunningProcessHandle
import ec0.core.engine.services.TransferThroughputTracker
import ec0.core.engine.services.UniversalControlService
import ec0.core.engine.services.UsbAdbTunnelCoordinator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Slice9ImprovementsTest {

    @Test
    fun encryptedIdentityStorePersistsAndReloadsEd25519IdentityWithChaCha20Poly1305() {
        val tempDir = Files.createTempDirectory("ec0-keystore-test").toFile()
        try {
            val store1 = EncryptedIdentityStore(configDir = tempDir)
            val created = store1.loadOrCreateIdentity()

            val store2 = EncryptedIdentityStore(configDir = tempDir)
            val reloaded = store2.loadOrCreateIdentity()

            assertEquals(created.deviceId, reloaded.deviceId)
            assertContentEquals(created.privateSeedRaw, reloaded.privateSeedRaw)
            assertContentEquals(created.publicKeyRaw, reloaded.publicKeyRaw)

            // Verify wrong master secret fails closed with AeadSecurityException
            val wrongSecretStore = EncryptedIdentityStore(
                configDir = tempDir,
                secretOverride = ByteArray(32) { 0x7F.toByte() },
            )
            assertFailsWith<AeadSecurityException> {
                wrongSecretStore.loadOrCreateIdentity()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun persistentPinStorePersistsPinsAndEndpointsAcrossRestarts() {
        val tempDir = Files.createTempDirectory("ec0-pinstore-test").toFile()
        val storeFile = File(tempDir, "pinned_peers.json")
        try {
            val peerIdentity = Ed25519Identity.generate()
            val store1 = PersistentPinStore(storeFile = storeFile, allowAutoTofuOnUnknownPeer = false)
            store1.verifyOrPin(
                deviceId = peerIdentity.deviceId,
                publicKeyRaw = peerIdentity.publicKeyRaw,
                alias = "OPPO-A5x",
                trustLevel = TrustLevel.TRUSTED_QR,
                nowMs = 1_700_000_000_000L,
            )
            store1.recordKnownEndpoint(
                deviceId = peerIdentity.deviceId,
                host = "192.168.1.42",
                port = 49880,
                nowMs = 1_700_000_005_000L,
            )

            // Reload in a brand new instance
            val store2 = PersistentPinStore(storeFile = storeFile, allowAutoTofuOnUnknownPeer = false)
            val pinned = store2.get(peerIdentity.deviceId)
            assertNotNull(pinned)
            assertEquals("OPPO-A5x", pinned.alias)
            assertEquals(TrustLevel.TRUSTED_QR, pinned.trustLevel)
            assertContentEquals(peerIdentity.publicKeyRaw, pinned.publicKeyRaw)

            val endpoint = store2.getKnownEndpoint(peerIdentity.deviceId)
            assertNotNull(endpoint)
            assertEquals("192.168.1.42", endpoint.host)
            assertEquals(49880, endpoint.port)

            // Presenting a different key for the pinned deviceId must throw KeyMismatchException
            val impostor = Ed25519Identity.generate()
            assertFailsWith<KeyMismatchException> {
                store2.verifyOrPin(
                    deviceId = peerIdentity.deviceId,
                    publicKeyRaw = impostor.publicKeyRaw,
                    alias = "OPPO-A5x",
                    trustLevel = TrustLevel.TRUSTED_QR,
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun mdnsDiscoveryAutoDiscoversCamDroidServicesAlongsideEc0Peers() {
        val sentPackets = mutableListOf<ByteArray>()
        val fakeTransport = object : MdnsDatagramTransport {
            override fun send(payload: ByteArray) {
                sentPackets.add(payload)
            }
            override fun receive(): MdnsDatagram? = null
            override fun close() = Unit
        }
        val discovery = Ec0MdnsDiscovery(
            localPeer = PeerInfo(
                deviceId = "a".repeat(64),
                displayName = "linux-box",
                role = "desktop",
                port = 49880,
                addresses = listOf("192.168.1.10"),
            ),
            transportFactory = { fakeTransport },
        )
        val camDroidPacket = MdnsCodec.encodeCamDroidAnnouncement(
            deviceName = "Pixel 9 Pro",
            port = 4747,
            addresses = listOf("192.168.1.77"),
            codecs = listOf("h264", "h265"),
            resolution = "4k",
        )
        discovery.ingestDatagram(camDroidPacket, senderIp = "192.168.1.77", nowMs = 1_000L)
        val services = discovery.camDroidServices.value
        assertEquals(1, services.size)
        assertEquals("Pixel 9 Pro", services.first().deviceName)
        assertEquals(listOf("192.168.1.77"), services.first().addresses)
        assertEquals(4747, services.first().port)
    }

    @Test
    fun continuityCameraCoordinatorPkexecAndPersistentModprobeConfigs() {
        val executedCommands = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecutionResult {
                executedCommands.add(command)
                return CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
            }
            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                throw UnsupportedOperationException()
            }
        }
        val coordinator = ContinuityCameraCoordinator(launcher = fakeLauncher)
        val ok = coordinator.setupV4l2WithPkexec("/dev/video10", "CamDroid")
        assertTrue(ok)
        assertEquals(
            listOf("pkexec", "modprobe", "v4l2loopback", "exclusive_caps=1", "video_nr=10", "card_label=CamDroid"),
            executedCommands.single(),
        )

        val (modulesLoad, modprobeConf) = coordinator.generatePersistentModprobeConfigs("/dev/video10", "CamDroid")
        assertTrue(modulesLoad.contains("v4l2loopback"))
        assertTrue(modprobeConf.contains("options v4l2loopback exclusive_caps=1 video_nr=10 card_label=\"CamDroid\""))
    }

    @Test
    fun universalControlScreenEdgeBarrierTransitionsAndReturns() {
        val service = UniversalControlService(initialTargetEdge = ScreenEdge.RIGHT, handoffCooldownMs = 200L)

        // Pointer in middle of screen does not trigger handoff
        val mid = service.evaluateLocalPointerAtEdge(
            x = 960,
            y = 540,
            screenWidth = 1920,
            screenHeight = 1080,
            peerDeviceId = "phone-1",
            nowMs = 1_000L,
        )
        assertNull(mid)

        // Pointer hitting right edge (x = 1919, y = 540 -> 500 permille) triggers enter handoff
        val enter = service.evaluateLocalPointerAtEdge(
            x = 1919,
            y = 540,
            screenWidth = 1920,
            screenHeight = 1080,
            peerDeviceId = "phone-1",
            nowMs = 1_500L,
        )
        assertNotNull(enter)
        assertEquals("enter", enter.action)
        assertEquals(ScreenEdge.RIGHT, enter.edge)
        assertEquals(500, enter.positionPermille)
        assertEquals("phone-1", service.activeRemotePeerId.value)

        // Return cursor to local desktop
        val leave = service.returnCursorToLocal(positionPermille = 500, nowMs = 2_000L)
        assertEquals("leave", leave.action)
        assertEquals(ScreenEdge.LEFT, leave.edge)
        assertNull(service.activeRemotePeerId.value)
    }

    @Test
    fun hotspotAndTelephonyServiceAutoPausesLinuxMediaOnIncomingCallAndJoinsNmcli() {
        val executedCommands = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecutionResult {
                executedCommands.add(command)
                return CommandExecutionResult(exitCode = 0, stdout = "Connected", stderr = "")
            }
            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                throw UnsupportedOperationException()
            }
        }
        val service = HotspotAndTelephonyService(launcher = fakeLauncher)

        // Verify nmcli command builder
        val nmcliCmd = service.buildNmcliConnectCommand("OPPO-5G", "secret123")
        assertEquals(
            listOf("nmcli", "device", "wifi", "connect", "OPPO-5G", "password", "secret123"),
            nmcliCmd,
        )
        assertTrue(
            service.joinHotspotOnLinux(
                HotspotStatusInfo(enabled = true, ssid = "OPPO-5G", passphrase = "secret123"),
            ),
        )

        // Simulate incoming RINGING phone call envelope -> should auto-invoke `playerctl pause`
        executedCommands.clear()
        val callInfo = CallStateInfo(
            callId = "call-99",
            state = PhoneCallState.RINGING,
            callerNumber = "+15551234",
            callerName = "Bob",
            timestampMs = 10_000L,
        )
        val env = Ec0Envelope(
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = Ec0EnvelopeCodec.buildInfoBody(
                infoType = HotspotAndTelephonyProtocol.INFO_CALL_STATE,
                infoBody = callInfo.toJsonObject(),
            ),
            category = HotspotAndTelephonyProtocol.CATEGORY_TELEPHONY,
            timestamp = 10_000L,
            seq = 1L,
            nonce = 1L,
            sig = "00",
        )
        assertTrue(service.handleControlEnvelope(env))
        assertEquals(1, service.activeCalls.value.size)
        assertEquals(listOf("playerctl", "pause"), executedCommands.single())
    }

    @Test
    fun mediaSessionBridgeDispatchesPlayerctlCommands() {
        val executed = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecutionResult {
                executed.add(command)
                return CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
            }
            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                throw UnsupportedOperationException()
            }
        }
        val bridge = MediaSessionBridgeService(launcher = fakeLauncher)
        val cmd = MediaControlCommand(action = MediaControlCommand.ACTION_NEXT)
        val env = Ec0Envelope(
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = Ec0EnvelopeCodec.buildCmdBody(
                cmdType = MediaControlProtocol.CMD_MEDIA_CONTROL,
                cmdBody = cmd.toJsonObject(),
            ),
            category = MediaControlProtocol.CATEGORY_MEDIA,
            timestamp = 20_000L,
            seq = 2L,
            nonce = 2L,
            sig = "00",
        )
        assertTrue(bridge.handleControlEnvelope(env))
        assertEquals(listOf("playerctl", "next"), executed.single())
    }

    @Test
    fun linuxClipboardUriListAndDbusNotificationHelpers() {
        val tempFile = Files.createTempFile("ec0-clip-uri-", ".txt").toFile()
        try {
            val bridge = LinuxClipboardBridge()
            val watchCmd = bridge.buildWaylandWatchCommand("text/plain", listOf("ec0-clip-helper"))
            assertEquals(listOf("wl-paste", "--type", "text/plain", "--watch", "ec0-clip-helper"), watchCmd)

            val gnomePayload = "copy\n${tempFile.toURI()}\n# comment line\n"
            val extracted = bridge.extractCopiedFilesFromUriList(gnomePayload)
            assertEquals(1, extracted.size)
            assertEquals(tempFile.canonicalPath, extracted.first().canonicalPath)

            val notifBridge = LinuxNotificationBridge()
            val notif = NotificationPostInfo(
                notifId = "n-1",
                packageName = "org.telegram.messenger",
                appName = "Telegram",
                title = "Alice",
                body = "Hey from phone",
                canReply = true,
                actions = listOf("Mark Read"),
                timestampMs = 50_000L,
            )
            val gdbusCmd = notifBridge.buildGdbusNotifyCommand(notif, replacesId = 7)
            assertEquals("gdbus", gdbusCmd[0])
            assertTrue(gdbusCmd.contains("org.freedesktop.Notifications.Notify"))
            assertTrue(gdbusCmd.any { it.contains("'reply'") && it.contains("'Mark Read'") })

            val parsedSignal = notifBridge.parseDbusActionInvokedSignal(
                "/org/freedesktop/Notifications: org.freedesktop.Notifications.ActionInvoked (uint32 7, 'reply')",
            )
            assertEquals(7 to "reply", parsedSignal)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun adaptiveFileChunkSizingRecommendations() {
        assertEquals(
            FileTransferService.FAST_LAN_CHUNK_BYTES,
            FileTransferService.recommendChunkSize(rttMs = 2L, isSoftApLink = false),
        )
        assertEquals(
            EngineConstants.DEFAULT_FILE_CHUNK_BYTES,
            FileTransferService.recommendChunkSize(rttMs = 15L, isSoftApLink = false),
        )
        assertEquals(
            FileTransferService.SOFTAP_CHUNK_BYTES,
            FileTransferService.recommendChunkSize(rttMs = 3L, isSoftApLink = true),
        )
        assertEquals(
            FileTransferService.SOFTAP_CHUNK_BYTES,
            FileTransferService.recommendChunkSize(rttMs = 65L, isSoftApLink = false),
        )
    }

    @Test
    fun linuxInputInjectionBridgeGeneratesWaylandYdotoolAndX11XdotoolCommands() {
        val executed = mutableListOf<List<String>>()
        val fakeExecutor = object : ec0.core.engine.linux.BinaryCommandExecutor {
            override fun execute(
                command: List<String>,
                stdin: ByteArray,
                timeoutMs: Long,
            ): ec0.core.engine.linux.BinaryCommandResult {
                executed.add(command)
                return ec0.core.engine.linux.BinaryCommandResult(
                    exitCode = 0,
                    stdout = ByteArray(0),
                    stderr = "",
                )
            }
        }

        // Wayland (ydotool)
        val waylandBridge = LinuxInputInjectionBridge(
            executor = fakeExecutor,
            backendProvider = { ec0.core.engine.linux.LinuxInputBackend.YDOTOOL_WAYLAND },
        )
        waylandBridge.onPointerEvent(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = -15, dy = 24),
        )
        assertEquals(
            listOf("ydotool", "mousemove", "--", "-15", "24"),
            executed.last(),
        )
        waylandBridge.onPointerEvent(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_CLICK, buttonsMask = 1),
        )
        assertEquals(
            listOf("ydotool", "click", "0xC0"),
            executed.last(),
        )
        waylandBridge.onKeyEvent(
            InputKeyEvent(action = InputProtocol.KEY_ACTION_COMMIT_TEXT, text = "Hello Linux"),
        )
        assertEquals(
            listOf("ydotool", "type", "--", "Hello Linux"),
            executed.last(),
        )

        // X11 (xdotool)
        executed.clear()
        val x11Bridge = LinuxInputInjectionBridge(
            executor = fakeExecutor,
            backendProvider = { ec0.core.engine.linux.LinuxInputBackend.XDOTOOL_X11 },
        )
        x11Bridge.onPointerEvent(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = 10, dy = -5),
        )
        assertEquals(
            listOf("xdotool", "mousemove_relative", "--", "10", "-5"),
            executed.last(),
        )
        x11Bridge.onPointerEvent(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_SCROLL, scrollY = -2),
        )
        assertEquals(
            listOf("xdotool", "click", "5"),
            executed.last(),
        )
        x11Bridge.onKeyEvent(
            InputKeyEvent(action = InputProtocol.KEY_ACTION_COMMIT_TEXT, text = "X11 text"),
        )
        assertEquals(
            listOf("xdotool", "type", "--clearmodifiers", "--", "X11 text"),
            executed.last(),
        )
    }

    @Test
    fun androidCompanionRuntimeRoutesHotspotTelephonyMediaAndUniversalControlCallbacks() {
        val tempDir = Files.createTempDirectory("ec0-android-companion-test").toFile()
        try {
            var handoffReceived: CursorHandoffCommand? = null
            var hotspotCmdReceived: HotspotControlCommand? = null
            var callCmdReceived: CallActionCommand? = null
            var mediaCmdReceived: MediaControlCommand? = null

            val callbacks = object : AndroidPlatformCallbacks {
                override fun onApplyRemoteClipboard(item: ClipboardItem): Boolean = true
                override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean = true
                override fun onDismissSystemNotification(notifId: String): Boolean = true
                override fun onInjectPointerEvent(event: InputPointerEvent): Boolean = true
                override fun onInjectKeyEvent(event: InputKeyEvent): Boolean = true
                override fun onCursorHandoff(command: CursorHandoffCommand): Boolean {
                    handoffReceived = command
                    return true
                }
                override fun onHotspotControlCommand(command: HotspotControlCommand): HotspotStatusInfo? {
                    hotspotCmdReceived = command
                    return null
                }
                override fun onCallActionCommand(command: CallActionCommand): Boolean {
                    callCmdReceived = command
                    return true
                }
                override fun onMediaControlCommand(command: MediaControlCommand): MediaSessionInfo? {
                    mediaCmdReceived = command
                    return null
                }
            }

            val runtime = AndroidCompanionRuntime(
                phoneIdentity = Ed25519KeyPair.generate(),
                phoneModelName = "A5x-Test",
                downloadDir = tempDir,
                callbacks = callbacks,
            )
            try {
                val handoff = CursorHandoffCommand(
                    action = "enter",
                    edge = ScreenEdge.RIGHT,
                    positionPermille = 420,
                )
                runtime.node.universalControlService.handleControlEnvelope(
                    Ec0Envelope(
                        msgType = Ec0EnvelopeCodec.TYPE_CMD,
                        msgBody = Ec0EnvelopeCodec.buildCmdBody(
                            cmdType = UniversalControlProtocol.CMD_CURSOR_HANDOFF,
                            cmdBody = handoff.toJsonObject(),
                        ),
                        category = UniversalControlProtocol.CATEGORY_INPUT,
                        timestamp = 1_000L,
                        seq = 1L,
                        nonce = 1L,
                        sig = "00",
                    ),
                )
                assertNotNull(handoffReceived)
                assertEquals(420, handoffReceived?.positionPermille)

                val hotspotCmd = HotspotControlCommand(
                    action = HotspotControlCommand.ACTION_ENABLE,
                    preferredBandMhz = 5180,
                )
                runtime.node.hotspotAndTelephonyService.handleControlEnvelope(
                    Ec0Envelope(
                        msgType = Ec0EnvelopeCodec.TYPE_CMD,
                        msgBody = Ec0EnvelopeCodec.buildCmdBody(
                            cmdType = HotspotAndTelephonyProtocol.CMD_HOTSPOT_CONTROL,
                            cmdBody = hotspotCmd.toJsonObject(),
                        ),
                        category = HotspotAndTelephonyProtocol.CATEGORY_NET,
                        timestamp = 2_000L,
                        seq = 2L,
                        nonce = 2L,
                        sig = "00",
                    ),
                )
                assertNotNull(hotspotCmdReceived)
                assertEquals(HotspotControlCommand.ACTION_ENABLE, hotspotCmdReceived?.action)

                val callCmd = CallActionCommand(
                    callId = "call-7",
                    action = CallActionCommand.ACTION_ANSWER_SPEAKER,
                )
                runtime.node.hotspotAndTelephonyService.handleControlEnvelope(
                    Ec0Envelope(
                        msgType = Ec0EnvelopeCodec.TYPE_CMD,
                        msgBody = Ec0EnvelopeCodec.buildCmdBody(
                            cmdType = HotspotAndTelephonyProtocol.CMD_CALL_ACTION,
                            cmdBody = callCmd.toJsonObject(),
                        ),
                        category = HotspotAndTelephonyProtocol.CATEGORY_TELEPHONY,
                        timestamp = 3_000L,
                        seq = 3L,
                        nonce = 3L,
                        sig = "00",
                    ),
                )
                assertNotNull(callCmdReceived)
                assertEquals("call-7", callCmdReceived?.callId)

                val mediaCmd = MediaControlCommand(action = MediaControlCommand.ACTION_PLAY_PAUSE)
                runtime.node.mediaSessionBridgeService.handleControlEnvelope(
                    Ec0Envelope(
                        msgType = Ec0EnvelopeCodec.TYPE_CMD,
                        msgBody = Ec0EnvelopeCodec.buildCmdBody(
                            cmdType = MediaControlProtocol.CMD_MEDIA_CONTROL,
                            cmdBody = mediaCmd.toJsonObject(),
                        ),
                        category = MediaControlProtocol.CATEGORY_MEDIA,
                        timestamp = 4_000L,
                        seq = 4L,
                        nonce = 4L,
                        sig = "00",
                    ),
                )
                assertNotNull(mediaCmdReceived)
                assertEquals(MediaControlCommand.ACTION_PLAY_PAUSE, mediaCmdReceived?.action)
            } finally {
                runtime.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun slice11UsbTunnelEndpointResolutionAndAdbForwarding() {
        val candidates = EndpointResolver().resolveCandidates(
            deviceId = "peer-1",
            usbForwardedPort = 49880,
            explicitHostOverride = "192.168.1.42",
        )
        assertEquals(EndpointCandidate.Tier.USB_ADB_TUNNEL, candidates.first().tier)
        assertEquals("127.0.0.1", candidates.first().host)
        assertEquals(49880, candidates.first().port)

        val adbOutput = """
            List of devices attached
            R58M42ABCDE          device usb:1-2 product:a5x model:CPH2725 device:OP5E93L1 transport_id:1
            192.168.1.42:5555    device product:a5x model:CPH2725 transport_id:2
        """.trimIndent()
        val executed = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecutionResult {
                executed.add(command)
                return CommandExecutionResult(exitCode = 0, stdout = adbOutput, stderr = "")
            }
            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                throw UnsupportedOperationException()
            }
        }
        val coordinator = UsbAdbTunnelCoordinator(launcher = fakeLauncher)
        val parsedDevices = coordinator.parseUsbDevicesOutput(adbOutput)
        assertEquals(1, parsedDevices.size)
        assertEquals("R58M42ABCDE", parsedDevices.single().serial)

        val status = coordinator.probeAndEstablishUsbTunnel()
        assertNotNull(status)
        assertEquals("R58M42ABCDE", status.serial)
        assertTrue(executed.any { it.contains("tcp:49880") })
        assertTrue(executed.any { it.contains("tcp:4747") })
    }

    @Test
    fun slice11HandoffUrlValidatorOtpExtractorAndEphemeralClipboardScrub() {
        // Strict URL validation: allow http/https, reject javascript:/file:/data:
        assertEquals(
            "https://github.com/ARYAN007H/CamDroid",
            HandoffProtocol.validateSafeHandoffUrl("  https://github.com/ARYAN007H/CamDroid  "),
        )
        assertFailsWith<IllegalArgumentException> {
            HandoffProtocol.validateSafeHandoffUrl("javascript:alert(1)")
        }
        assertFailsWith<IllegalArgumentException> {
            HandoffProtocol.validateSafeHandoffUrl("file:///etc/passwd")
        }
        assertFailsWith<IllegalArgumentException> {
            HandoffProtocol.validateSafeHandoffUrl("data:text/html,<script>")
        }

        // 2FA OTP extraction
        assertEquals(
            "482910",
            OtpCodeExtractor.extractOtpCode("GitHub", "Your GitHub verification code is 482910. Valid for 10 minutes."),
        )
        assertEquals(
            "739201",
            OtpCodeExtractor.extractOtpCode("Steam Guard", "Use OTP 739201 to sign in to your account."),
        )
        assertNull(
            OtpCodeExtractor.extractOtpCode("Calendar", "Meeting at 14:30 in room 204"),
        )

        // Ephemeral 30s clipboard auto-scrub when SHA-256 still matches
        var currentClipboard = "482910"
        val executed = mutableListOf<List<String>>()
        val fakeExec = object : ec0.core.engine.linux.BinaryCommandExecutor {
            override fun execute(
                command: List<String>,
                stdin: ByteArray,
                timeoutMs: Long,
            ): ec0.core.engine.linux.BinaryCommandResult {
                executed.add(command)
                if (command.first() == "wl-paste") {
                    return ec0.core.engine.linux.BinaryCommandResult(
                        exitCode = 0,
                        stdout = currentClipboard.encodeToByteArray(),
                        stderr = "",
                    )
                }
                if (command.contains("--clear")) {
                    currentClipboard = ""
                }
                return ec0.core.engine.linux.BinaryCommandResult(exitCode = 0, stdout = ByteArray(0), stderr = "")
            }
        }
        val clipBridge = LinuxClipboardBridge(
            executor = fakeExec,
            displayServerProvider = { LinuxDisplayServer.WAYLAND },
        )
        val otpHash = ClipboardItem.text("482910", isSensitive = true).sha256Hex
        assertTrue(clipBridge.scrubIfUnchanged(otpHash))
        assertEquals("", currentClipboard)

        // If user already copied something else, scrubIfUnchanged must NOT overwrite it
        currentClipboard = "User new text"
        assertFalse(clipBridge.scrubIfUnchanged(otpHash))
        assertEquals("User new text", currentClipboard)
    }

    @Test
    fun slice11SessionRekeyingAndAuthenticatedLoopbackCliIpc() {
        // 1. Session Re-Keying (`FRAME_KEY_UPDATE = 0x04`)
        val sharedSecret = ByteArray(32) { (it + 1).toByte() }
        val transcript = ByteArray(32) { (it + 65).toByte() }
        val initialKeys = HkdfSha256.deriveSessionKeys(
            sharedSecret,
            transcript.copyOfRange(0, 16),
            transcript.copyOfRange(16, 32),
            ByteArray(32) { (it + 33).toByte() },
            ByteArray(32) { (it + 65).toByte() },
            ByteArray(32) { (it + 97).toByte() },
            ByteArray(32) { (it + 129).toByte() },
        )

        val initiator = AeadSessionChannel(isInitiator = true, keys = initialKeys)
        val responder = AeadSessionChannel(isInitiator = false, keys = initialKeys)

        val keyUpdateFrame = initiator.sealApplicationFrame(EngineConstants.FRAME_KEY_UPDATE, ByteArray(0))
        val openedUpdate = responder.openApplicationFrame(keyUpdateFrame)
        assertEquals(EngineConstants.FRAME_KEY_UPDATE, openedUpdate.frameType)

        val epoch1Initiator = initiator.rotateKeys()
        val epoch1Responder = responder.rotateKeys()
        assertEquals(1, epoch1Initiator)
        assertEquals(1, epoch1Responder)

        val postRekeyPayload = "encrypted-under-epoch-1".encodeToByteArray()
        val sealedEpoch1 = initiator.sealApplicationFrame(EngineConstants.FRAME_ENVELOPE_JSON, postRekeyPayload)
        val openedEpoch1 = responder.openApplicationFrame(sealedEpoch1)
        assertContentEquals(postRekeyPayload, openedEpoch1.plaintext)

        // 2. Authenticated Loopback CLI IPC (`127.0.0.1`)
        val tempDir = Files.createTempDirectory("ec0-ipc-test").toFile()
        val sampleFile = File(tempDir, "photo1.jpg").apply { writeText("img") }
        val tokenFile = File(tempDir, "daemon_ipc.json")
        val receivedFiles = mutableListOf<File>()
        val server = LocalDaemonIpcServer(
            descriptorFile = tokenFile,
            onSendFilesRequested = { files ->
                receivedFiles.addAll(files)
            },
        )
        try {
            val port = server.start()
            assertTrue(port > 0)
            assertTrue(tokenFile.isFile)
            val accepted = LocalDaemonIpcClient.trySendFilesToRunningDaemon(
                filePaths = listOf(sampleFile.absolutePath),
                descriptorFile = tokenFile,
            )
            assertTrue(accepted)
            assertEquals(listOf(sampleFile.canonicalPath), receivedFiles.map { it.canonicalPath })
        } finally {
            server.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun slice11VirtualMicProximityHandoffThroughputAndSketchOverlay() {
        // 1. Virtual Mic A/V Drift & Pactl commands
        val executed = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecutionResult {
                executed.add(command)
                return CommandExecutionResult(exitCode = 0, stdout = "42\n", stderr = "")
            }
            override fun spawnProcess(command: List<String>): RunningProcessHandle {
                throw UnsupportedOperationException()
            }
        }
        val micBridge = ContinuityVirtualMicBridge(launcher = fakeLauncher)
        assertEquals(
            18L,
            micBridge.recordCaptureTimestamps(videoCaptureNs = 118_000_000L, audioCaptureNs = 100_000_000L),
        )

        // 2. Handoff & Proximity Service
        val handoffService = HandoffAndProximityService(launcher = fakeLauncher)
        val openCmd = HandoffOpenCommand(url = "https://kotlinlang.org", title = "Kotlin")
        val openEnv = Ec0Envelope(
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = Ec0EnvelopeCodec.buildCmdBody(
                cmdType = HandoffProtocol.CMD_HANDOFF_OPEN,
                cmdBody = openCmd.toJsonObject(),
            ),
            category = HandoffProtocol.CATEGORY_HANDOFF,
            timestamp = 1_000L,
            seq = 1L,
            nonce = 1L,
            sig = "00",
        )
        executed.clear()
        assertTrue(handoffService.handleControlEnvelope(openEnv))
        assertEquals(listOf("xdg-open", "https://kotlinlang.org"), executed.single())

        val lockCmd = WorkstationLockCommand(reason = "rssi")
        val lockEnv = Ec0Envelope(
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = Ec0EnvelopeCodec.buildCmdBody(
                cmdType = ProximitySecurityProtocol.CMD_WORKSTATION_LOCK,
                cmdBody = lockCmd.toJsonObject(),
            ),
            category = ProximitySecurityProtocol.CATEGORY_SECURITY,
            timestamp = 2_000L,
            seq = 2L,
            nonce = 2L,
            sig = "00",
        )
        executed.clear()
        assertTrue(handoffService.handleControlEnvelope(lockEnv))
        assertEquals(listOf("loginctl", "lock-session"), executed.single())

        val ringCmd = FindMyRingCommand(action = FindMyRingCommand.ACTION_START_RING)
        val ringEnv = Ec0Envelope(
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = Ec0EnvelopeCodec.buildCmdBody(
                cmdType = ProximitySecurityProtocol.CMD_FIND_MY_RING,
                cmdBody = ringCmd.toJsonObject(),
            ),
            category = ProximitySecurityProtocol.CATEGORY_SECURITY,
            timestamp = 3_000L,
            seq = 3L,
            nonce = 3L,
            sig = "00",
        )
        assertTrue(handoffService.handleControlEnvelope(ringEnv))
        assertTrue(handoffService.findMyRinging.value)

        // 3. TransferThroughputTracker EMA + ETA
        val tracker = TransferThroughputTracker()
        tracker.recordProgress(
            transferId = "t-1",
            transferredBytes = 0L,
            totalBytes = 100L * 1024L * 1024L,
            nowMs = 1_000L,
        )
        val sample = tracker.recordProgress(
            transferId = "t-1",
            transferredBytes = 25L * 1024L * 1024L,
            totalBytes = 100L * 1024L * 1024L,
            nowMs = 2_000L,
        )
        assertTrue(sample.bytesPerSecond > 20_000_000L)
        assertEquals(3L, sample.etaSeconds)
        assertTrue(sample.formatTabular().contains("MB/s"))

        // 4. Continuity Sketch Overlay round-trip
        val overlay = ContinuitySketchOverlay(
            baseClipSha256 = "a".repeat(64),
            strokes = listOf(
                ContinuitySketchStroke(
                    colorHex = "#EF4444",
                    strokeWidthPx = 8,
                    points = listOf(
                        ContinuitySketchPoint(100, 200),
                        ContinuitySketchPoint(850, 750),
                    ),
                ),
            ),
        )
        val parsedOverlay = ContinuitySketchOverlay.fromJsonObject(overlay.toJsonObject())
        assertEquals("a".repeat(64), parsedOverlay.baseClipSha256)
        assertEquals(1, parsedOverlay.strokes.size)
        assertEquals(2, parsedOverlay.strokes.first().points.size)
        assertEquals(850, parsedOverlay.strokes.first().points.last().xPermille)
    }
}
