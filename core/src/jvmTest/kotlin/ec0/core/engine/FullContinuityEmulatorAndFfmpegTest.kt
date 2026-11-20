/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Full-stack loopback continuity emulator & real FFmpeg pixel verification suite:
 *   1. Real `/usr/bin/ffmpeg` decode test proving Continuity Camera MJPEG streams
 *      (plain AND with live Studio Effects hot-reload) decode into bright, valid
 *      `yuyv422` pixels (NOT dark greenish-black corrupted frames!) with zero
 *      stderr-pipe deadlock.
 *   2. H.264 Annex-B GOP-safe buffering verification (SPS/PPS/IDR never dropped).
 *   3. End-to-end Desktop Ec0EngineNode ↔ Simulated Android Phone
 *      (AndroidCompanionRuntime + VfsPhoneService + CamDroidStreamServer) over
 *      real encrypted ChaCha20-Poly1305 sessions exercising ALL Continuity &
 *      Ecosystem Pro features plus idempotency under rapid toggle stress.
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.linux.BinaryCommandExecutor
import ec0.core.engine.linux.BinaryCommandResult
import ec0.core.engine.linux.LinuxClipboardBridge
import ec0.core.engine.linux.LinuxDisplayServer
import ec0.core.engine.protocol.AudioRouteCommand
import ec0.core.engine.protocol.AudioRouteStatusInfo
import ec0.core.engine.protocol.AudioRoutingProtocol
import ec0.core.engine.protocol.BrowserTabHandoffInfo
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraEffectsCommand
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.CaptureRequestCommand
import ec0.core.engine.protocol.CaptureResultInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ContinuityCaptureProtocol
import ec0.core.engine.protocol.FindMyRingCommand
import ec0.core.engine.protocol.FocusStateInfo
import ec0.core.engine.protocol.FocusSyncProtocol
import ec0.core.engine.protocol.NotificationActionCommand
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.SidecarStartCommand
import ec0.core.engine.protocol.SidecarTouchInputEvent
import ec0.core.engine.protocol.SmsMessageRecord
import ec0.core.engine.protocol.SmsSendCommand
import ec0.core.engine.protocol.VfsEntryDto
import ec0.core.engine.protocol.VfsProtocol
import ec0.core.engine.services.CallMediaDuckingCoordinator
import ec0.core.engine.services.CamDroidArmResult
import ec0.core.engine.services.CamDroidFrameSource
import ec0.core.engine.services.CamDroidReceiverState
import ec0.core.engine.services.CamDroidSinkHandle
import ec0.core.engine.services.CamDroidSinkLauncher
import ec0.core.engine.services.CamDroidStreamConfig
import ec0.core.engine.services.CamDroidStreamReceiver
import ec0.core.engine.services.CamDroidStreamServer
import ec0.core.engine.services.CommandExecResult
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.FfmpegV4l2SinkLauncher
import ec0.core.engine.services.OtpAutoTypeController
import ec0.core.engine.services.RunningProcessHandle
import ec0.core.engine.services.VfsDesktopService
import ec0.core.engine.services.VfsLoopbackWebDavServer
import ec0.core.engine.services.VfsMountDataSource
import ec0.core.engine.services.VfsPhoneService
import ec0.core.kdeconnect.Sha256
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FullContinuityEmulatorAndFfmpegTest {

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25L)
        }
        return predicate()
    }

    /** Generates a real vivid bright JPEG frame (high luma Y > 170) for FFmpeg OBS pipeline tests. */
    private fun generateBrightJpeg(width: Int = 640, height: Int = 360, frameIdx: Int = 0): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            // Bright vivid warm-white + sky-blue stripes (Y ~ 195-235, never black/dark green)
            g.color = Color(240, 220, 180)
            g.fillRect(0, 0, width, height)
            g.color = Color(120, 210, 255)
            g.fillRect((frameIdx * 16) % (width / 2), 40, width / 2, height - 80)
            g.color = Color(255, 255, 255)
            g.fillOval(width / 3, height / 4, width / 3, height / 2)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream(32 * 1024)
        assertTrue(ImageIO.write(img, "jpg", out), "ImageIO must encode JPEG")
        return out.toByteArray()
    }

    /** Computes average Y (luma) across a raw YUYV 4:2:2 byte buffer (Y0, U, Y1, V). */
    private fun averageYuyvLuma(yuyvBytes: ByteArray): Double {
        if (yuyvBytes.size < 4) return 0.0
        var sum = 0L
        var count = 0L
        var i = 0
        while (i + 3 < yuyvBytes.size) {
            val y0 = yuyvBytes[i].toInt() and 0xFF
            val y1 = yuyvBytes[i + 2].toInt() and 0xFF
            sum += y0 + y1
            count += 2
            i += 4
        }
        return if (count == 0L) 0.0 else sum.toDouble() / count.toDouble()
    }

    @Test
    fun realFfmpegPipelineProducesBrightYuyvPixelsAndHotReloadsStudioEffects() {
        val ffmpegExists = runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!ffmpegExists) return // Gracefully skip only on hosts without ffmpeg binary

        val tempDir = Files.createTempDirectory("ec0-ffmpeg-obs-test").toFile()
        val rawYuyvPlain = File(tempDir, "obs_plain.yuyv")
        val rawYuyvStudio = File(tempDir, "obs_studio.yuyv")
        val launchCount = AtomicInteger(0)
        val launchedArgvs = CopyOnWriteArrayList<List<String>>()
        val currentEffects = AtomicReference(CameraEffectsCommand())

        // Redirect `-f v4l2 /dev/video10` -> `-y -f rawvideo <tmpFile>` so real /usr/bin/ffmpeg
        // executes our EXACT decode + studio `-vf` filter graph + `yuyv422` pixel format pipeline!
        val testRealFfmpegLauncher = CamDroidSinkLauncher { argv ->
            launchedArgvs.add(argv)
            val idx = launchCount.incrementAndGet()
            val outFile = if (idx == 1) rawYuyvPlain else rawYuyvStudio
            val rewritten = argv.dropLast(2) + listOf("-y", "-f", "rawvideo", outFile.absolutePath)
            FfmpegV4l2SinkLauncher.launch(rewritten)
        }

        val token = "real-ffmpeg-obs-token-42"
        val jpegFrames = (0 until 4).map { generateBrightJpeg(640, 360, it) }
        val feederRunning = AtomicBoolean(false)
        var feederThread: Thread? = null

        val server = CamDroidStreamServer(
            frameSourceFactory = {
                object : CamDroidFrameSource {
                    override fun start(sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
                        feederRunning.set(true)
                        feederThread = Thread({
                            var i = 0
                            while (feederRunning.get()) {
                                sink(jpegFrames[i % jpegFrames.size])
                                i++
                                try {
                                    Thread.sleep(25L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }, "test-jpeg-feeder").apply {
                            isDaemon = true
                            start()
                        }
                    }

                    override fun close() {
                        feederRunning.set(false)
                        feederThread?.interrupt()
                    }
                }
            },
            port = 0,
            width = 640,
            height = 360,
            targetFps = 30,
            codec = "mjpeg",
        )
        val receiver = CamDroidStreamReceiver(
            sinkLauncher = testRealFfmpegLauncher,
            v4l2DevicePath = "/dev/video10",
            effectsProvider = { currentEffects.get() },
            bindRetryAttempts = 3,
            bindRetryIntervalMs = 50L,
        )

        try {
            val armed = server.arm(token)
            assertTrue(armed is CamDroidArmResult.Armed, "Server must arm cleanly: $armed")
            val boundPort = (armed as CamDroidArmResult.Armed).port

            val started = receiver.start(
                host = "127.0.0.1",
                port = boundPort,
                streamToken = token,
                requestedFps = 30,
                requestedCodec = "mjpeg",
            )
            assertEquals(CamDroidReceiverState.STREAMING, started.state, "Receiver should reach STREAMING: ${started.reason}")

            // Wait for at least 15 frames to flow through real ffmpeg into rawYuyvPlain
            assertTrue(
                awaitCondition(4_000L) { receiver.snapshot.value.framesReceived >= 15L && rawYuyvPlain.length() >= 640L * 360L * 2L },
                "Real ffmpeg must write decoded YUYV frames (frames=${receiver.snapshot.value.framesReceived}, bytes=${rawYuyvPlain.length()})",
            )

            // Verify Studio Effects live hot-reload WITHOUT dropping the TCP stream!
            currentEffects.set(CameraEffectsCommand(centerStage = true, portraitBlur = true))
            assertTrue(receiver.reconfigureSinkForEffects(), "Live Studio Effects hot-reload must succeed")
            val framesBeforeStudio = receiver.snapshot.value.framesReceived
            assertTrue(
                awaitCondition(4_000L) {
                    receiver.snapshot.value.framesReceived >= framesBeforeStudio + 10L &&
                        rawYuyvStudio.length() >= 1280L * 720L * 2L
                },
                "Real ffmpeg with Studio Effects must produce 1280x720 YUYV frames (bytes=${rawYuyvStudio.length()})",
            )

            // Cleanly stop receiver so ffmpeg flushes all buffered output bytes
            feederRunning.set(false)
            receiver.stop()
            assertEquals(CamDroidReceiverState.STOPPED, receiver.snapshot.value.state)

            // Inspect REAL decoded pixels from OBS output #1 (plain MJPEG 640x360 YUYV)
            val plainBytes = rawYuyvPlain.readBytes()
            val plainFrameBytes = 640 * 360 * 2
            assertTrue(plainBytes.size >= plainFrameBytes, "Must have >= 1 full 640x360 YUYV frame")
            val plainLuma = averageYuyvLuma(plainBytes.copyOf(plainFrameBytes))
            assertTrue(
                plainLuma > 160.0,
                "Plain OBS YUYV frame must be bright and vivid (got luma=$plainLuma — black/corrupt would be < 40!)",
            )

            // Inspect REAL decoded pixels from OBS output #2 (Studio Effects Center Stage + Portrait Blur 1280x720 YUYV)
            val studioBytes = rawYuyvStudio.readBytes()
            val studioFrameBytes = 1280 * 720 * 2
            assertTrue(studioBytes.size >= studioFrameBytes, "Must have >= 1 full 1280x720 studio YUYV frame")
            val studioLuma = averageYuyvLuma(studioBytes.copyOf(studioFrameBytes))
            assertTrue(
                studioLuma > 120.0,
                "Studio Effects OBS YUYV frame must be bright and valid (got luma=$studioLuma)",
            )

            // Verify ffmpeg argv included low-latency flags, even-dimension scale, yuyv422, and Studio filter chain
            assertEquals(2, launchedArgvs.size)
            assertTrue(launchedArgvs[0].contains("yuyv422"))
            assertTrue(launchedArgvs[0].any { it.contains("scale=trunc(iw/2)*2:trunc(ih/2)*2") })
            assertTrue(launchedArgvs[1].any { it.contains("vignette=PI/5") && it.contains("crop=") })
        } finally {
            feederRunning.set(false)
            feederThread?.interrupt()
            receiver.close()
            server.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun h264ServerBufferPreservesAnnexBKeyframesUnderHeavyBackpressure() {
        val token = "h264-gop-safe-token"
        // Annex-B SPS (NAL type 7) + IDR (NAL type 5)
        val spsIdr = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F, 0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x11)
        val pFrame = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x22, 0x33)

        assertTrue(CamDroidStreamServer.isAnnexBKeyOrConfig(spsIdr))
        assertFalse(CamDroidStreamServer.isAnnexBKeyOrConfig(pFrame))

        val receivedChunks = CopyOnWriteArrayList<ByteArray>()
        val fakeSinkLauncher = CamDroidSinkLauncher {
            val out = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    receivedChunks.add(byteArrayOf(b.toByte()))
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    receivedChunks.add(b.copyOfRange(off, off + len))
                }
            }
            object : CamDroidSinkHandle {
                override val stdin: java.io.OutputStream = out
                override val isAlive: Boolean = true
                override fun exitCode(): Int? = null
                override fun close() = Unit
            }
        }

        val server = CamDroidStreamServer(
            frameSourceFactory = {
                object : CamDroidFrameSource {
                    override fun start(sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
                        // Burst SPS+IDR followed immediately by 20 P-frames to saturate the 8-frame H.264 queue.
                        // The GOP-safe queue MUST keep the initial SPS+IDR and drop only excess P-frames.
                        sink(spsIdr)
                        repeat(20) { sink(pFrame) }
                    }
                    override fun close() = Unit
                }
            },
            port = 0,
            width = 1280,
            height = 720,
            targetFps = 30,
            codec = "h264",
        )
        val receiver = CamDroidStreamReceiver(
            sinkLauncher = fakeSinkLauncher,
            v4l2DevicePath = "/dev/video10",
            bindRetryAttempts = 3,
            bindRetryIntervalMs = 50L,
        )
        try {
            val armed = server.arm(token) as CamDroidArmResult.Armed
            val snap = receiver.start("127.0.0.1", armed.port, token, requestedFps = 30, requestedCodec = "h264")
            assertEquals(CamDroidReceiverState.STREAMING, snap.state)
            assertTrue(
                awaitCondition(3_000L) { receivedChunks.isNotEmpty() },
                "H.264 receiver must receive frames from server",
            )
            // First video access unit delivered to sink MUST be the SPS+IDR keyframe!
            assertTrue(
                receivedChunks.first().contentEquals(spsIdr),
                "First delivered H.264 frame must be the preserved SPS+IDR keyframe, never a dropped-keyframe P-slice",
            )
            assertTrue(server.droppedFrameCount() > 0L, "Excess P-frames above queue cap must be cleanly dropped")
        } finally {
            receiver.close()
            server.close()
        }
    }

    @Test
    fun fullDesktopToSimulatedPhoneContinuityEndToEndAndIdempotency() {
        val tempDir = Files.createTempDirectory("ec0-full-emulator").toFile()
        val desktopDownloads = File(tempDir, "desktop-downloads").apply { mkdirs() }
        val phoneDownloads = File(tempDir, "phone-downloads").apply { mkdirs() }
        val phoneVfsRoot = File(tempDir, "phone-vfs-dcim").apply { mkdirs() }
        val samplePhonePhoto = File(phoneVfsRoot, "sunset.jpg").apply {
            writeBytes(generateBrightJpeg(320, 180, 1))
        }
        val samplePhoneNote = File(phoneVfsRoot, "notes.txt").apply {
            writeText("Hello from simulated Android phone VFS!")
        }

        // Track simulated Android phone callbacks
        val phoneAppliedClipboard = CopyOnWriteArrayList<ClipboardItem>()
        val phoneReplies = CopyOnWriteArrayList<NotificationReplyCommand>()
        val phoneActions = CopyOnWriteArrayList<NotificationActionCommand>()
        val phoneDismissed = CopyOnWriteArrayList<String>()
        val phoneFindMy = CopyOnWriteArrayList<FindMyRingCommand>()
        val phoneSidecarEvents = CopyOnWriteArrayList<String>()
        val phoneSentSms = CopyOnWriteArrayList<SmsSendCommand>()
        val phoneFocusModes = CopyOnWriteArrayList<String>()
        val phoneReceivedTabs = CopyOnWriteArrayList<BrowserTabHandoffInfo>()
        val phoneReceivedCameraFx = CopyOnWriteArrayList<CameraEffectsCommand>()
        val phoneCameraCmds = CopyOnWriteArrayList<CamDroidControlCommand>()
        val captureJpeg = generateBrightJpeg(320, 180, 2)
        val captureSha = Sha256.digest(captureJpeg).toHex()

        val fakeLauncher = object : ExternalProcessLauncher {
            override fun spawnProcess(command: List<String>): RunningProcessHandle = object : RunningProcessHandle {
                private var alive = true
                override val isAlive: Boolean get() = alive
                override fun stop() { alive = false }
            }
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult =
                CommandExecResult(0, "77")
        }

        val desktopId = Ed25519Identity.generate()
        val phoneId = Ed25519Identity.generate()

        val desktopReceivedClipboard = CopyOnWriteArrayList<ClipboardItem>()
        val desktopNode = Ec0EngineNode(
            localIdentity = desktopId,
            displayName = "Linux-Workstation",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = desktopDownloads,
            processLauncher = fakeLauncher,
            onRemoteClipboardApplied = { desktopReceivedClipboard.add(it) },
        )

        val vfsDesktop = VfsDesktopService(destinationDir = desktopDownloads.toPath())
        desktopNode.controlEnvelopeInterceptor = { _, env ->
            vfsDesktop.handleControlEnvelope(env)
        }

        val phoneCallbacks = object : AndroidPlatformCallbacks {
            override fun onApplyRemoteClipboard(item: ClipboardItem): Boolean {
                phoneAppliedClipboard.add(item)
                return true
            }
            override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean {
                phoneReplies.add(reply)
                return true
            }
            override fun onNotificationActionButton(command: NotificationActionCommand): Boolean {
                phoneActions.add(command)
                return true
            }
            override fun onDismissSystemNotification(notifId: String): Boolean {
                phoneDismissed.add(notifId)
                return true
            }
            override fun onFindMyRingCommand(command: FindMyRingCommand) {
                phoneFindMy.add(command)
            }
            override fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo {
                phoneCameraCmds.add(command)
                return CameraStatusInfo(
                    state = if (command is CamDroidControlCommand.Stop) CameraStreamState.IDLE else CameraStreamState.STREAMING,
                    codec = "mjpeg",
                    resolution = "1080p",
                    fps = 30,
                )
            }
            override fun onAudioRouteCommand(command: AudioRouteCommand): AudioRouteStatusInfo {
                return when (command.action) {
                    AudioRoutingProtocol.ACTION_START_MEDIA_SINK ->
                        AudioRouteStatusInfo(active = true, mode = "media_sink", sampleRateHz = command.sampleRateHz, channels = command.channels)
                    AudioRoutingProtocol.ACTION_START_CALL_AUDIO ->
                        AudioRouteStatusInfo(active = true, mode = "call_duplex", sampleRateHz = command.sampleRateHz, channels = command.channels)
                    else -> AudioRouteStatusInfo(active = false, mode = "idle")
                }
            }
            override fun onCaptureRequest(command: CaptureRequestCommand): CaptureResultInfo =
                CaptureResultInfo(
                    requestId = command.requestId,
                    mode = command.mode,
                    mime = "image/jpeg",
                    sizeBytes = captureJpeg.size.toLong(),
                    sha256Hex = captureSha,
                    inlineBase64Url = captureJpeg.toBase64UrlUnpadded(),
                )
            override fun onSidecarStart(command: SidecarStartCommand) {
                phoneSidecarEvents.add("start:${command.width}x${command.height}@${command.fps}")
            }
            override fun onSidecarStop() {
                phoneSidecarEvents.add("stop")
            }
            override fun onSmsSyncRequested(): List<SmsMessageRecord> = listOf(
                SmsMessageRecord(
                    messageId = "sms-1",
                    threadId = "t-bank",
                    address = "+15551234567",
                    contactName = "MyBank",
                    body = "Your verification code is 739204. Expires in 5 minutes.",
                    timestampMs = System.currentTimeMillis(),
                    incoming = true,
                    read = false,
                ),
            )
            override fun onSmsSendRequested(command: SmsSendCommand): Boolean {
                phoneSentSms.add(command)
                return true
            }
            override fun onFocusStateChanged(state: FocusStateInfo) {
                phoneFocusModes.add(state.mode)
            }
            override fun onBrowserTabReceived(info: BrowserTabHandoffInfo) {
                phoneReceivedTabs.add(info)
            }
            override fun onCameraEffectsUpdated(command: CameraEffectsCommand) {
                phoneReceivedCameraFx.add(command)
            }
            override fun queryPhonePresence(): PresenceBeaconInfo = PresenceBeaconInfo(
                batteryPct = 88,
                isCharging = true,
                screenOn = true,
                capabilities = setOf(
                    EngineCapability.CLIPBOARD,
                    EngineCapability.FILE,
                    EngineCapability.NOTIFICATION,
                    EngineCapability.CAMERA,
                    EngineCapability.CAPTURE,
                    EngineCapability.AUDIO_HANDOFF,
                    EngineCapability.SIDECAR,
                    EngineCapability.SMS,
                    EngineCapability.FOCUS,
                    EngineCapability.VFS_READ,
                    EngineCapability.FIND_MY,
                ),
            )
        }

        val phoneRuntime = AndroidCompanionRuntime(
            phoneIdentity = phoneId,
            phoneModelName = "Simulated-Pixel-9-Pro",
            callbacks = phoneCallbacks,
            listenPort = 0,
            downloadDir = phoneDownloads,
        )
        val vfsPhone = VfsPhoneService(
            rootsProvider = { mapOf("DCIM" to phoneVfsRoot) },
            accessGranted = { true },
        )
        phoneRuntime.node.controlEnvelopeInterceptor = { peerId, env ->
            val s = phoneRuntime.node.activeSessionFor(peerId)
            if (s != null) {
                vfsPhone.handleControlEnvelope(peerId, env) { infoType, body ->
                    s.sendInfo(infoType, body, category = VfsProtocol.CATEGORY_VFS)
                }
            } else {
                false
            }
        }

        var webDavServer: VfsLoopbackWebDavServer? = null
        try {
            desktopNode.startServer(startMdns = false)
            phoneRuntime.start(startMdns = false)

            // 1. QR Pairing + Authenticated Encrypted Session
            val qr = desktopNode.generatePairingQr(ips = listOf("127.0.0.1"))
            assertTrue(phoneRuntime.pairWithDesktopQr(qr.toUri()), "Simulated phone must pair with desktop via QR")
            assertTrue(awaitCondition { desktopNode.activeSessionFor(phoneId.deviceId) != null })
            assertTrue(awaitCondition { phoneRuntime.node.activeSessionFor(desktopId.deviceId) != null })

            val desktopSession = desktopNode.activeSessionFor(phoneId.deviceId)!!
            val phoneSession = phoneRuntime.node.activeSessionFor(desktopId.deviceId)!!

            // 2. Presence Beacon sync
            assertEquals(1, phoneRuntime.onDeviceWakeOrNetworkValidated())
            assertTrue(awaitCondition { desktopNode.peerPresence.value[phoneId.deviceId]?.batteryPct == 88 })
            val caps = desktopNode.peerPresence.value[phoneId.deviceId]!!.capabilities
            assertTrue(EngineCapability.AUDIO_HANDOFF in caps && EngineCapability.CAPTURE in caps && EngineCapability.SMS in caps)

            // 3. Feature #1: Audio Handoff Route (idempotent start + phone reply + stop) + MPRIS Call Ducking
            val audio1 = desktopNode.audioHandoffService.startDesktopAudioRoute("media_sink", session = desktopSession)
            val audio2 = desktopNode.audioHandoffService.startDesktopAudioRoute("media_sink", session = desktopSession) // idempotent!
            assertTrue(audio1.active && audio2.active)
            assertTrue(awaitCondition { phoneRuntime.node.audioHandoffService.status.value.mode == "media_sink" })
            desktopNode.audioHandoffService.stopDesktopAudioRoute(desktopSession)
            desktopNode.audioHandoffService.stopDesktopAudioRoute(desktopSession) // idempotent double stop!
            assertTrue(awaitCondition { !phoneRuntime.node.audioHandoffService.status.value.active })

            var mediaPlaying = true
            val ducker = CallMediaDuckingCoordinator(
                isMediaPlayingProvider = { if (mediaPlaying) "spotify" else null },
                pausePlayer = { mediaPlaying = false; true },
                resumePlayer = { mediaPlaying = true; true },
            )
            ducker.onCallStateUpdate(CallStateInfo("call-1", PhoneCallState.RINGING, "+15559999", "Mom", 1_000L))
            assertFalse(mediaPlaying)
            ducker.onCallStateUpdate(CallStateInfo("call-1", PhoneCallState.ENDED, "+15559999", "Mom", 2_000L))
            assertTrue(mediaPlaying)

            // 4. Feature #2: "Insert from Phone" Photo Capture roundtrip with SHA-256 verification
            val deliveredCapture = CountDownLatch(1)
            val deliveredBytes = AtomicReference<ByteArray?>(null)
            desktopNode.continuityCaptureService.onCaptureDeliveredHandler = { payload ->
                deliveredBytes.set(payload.bytes)
                deliveredCapture.countDown()
            }
            val capReq = desktopNode.continuityCaptureService.requestCaptureFromPhone(
                mode = ContinuityCaptureProtocol.MODE_PHOTO,
                autoPaste = true,
                session = desktopSession,
            )
            assertTrue(deliveredCapture.await(5, TimeUnit.SECONDS), "Phone capture must arrive & verify SHA-256")
            assertContentEquals(captureJpeg, deliveredBytes.get())
            assertEquals(capReq.requestId, desktopNode.continuityCaptureService.lastDeliveredCapture.value?.requestId)

            // 5. Feature #3: OS-Level WebDAV Phone Mount awaiting REAL VfsPhoneService over encrypted session!
            val webDavDs = object : VfsMountDataSource {
                override fun listDirectory(relativePath: String): List<VfsEntryDto>? {
                    val norm = if (relativePath.isBlank()) "/" else "/${relativePath.trimStart('/')}"
                    return vfsDesktop.browseAndAwait(
                        sender = { cmdType, body -> desktopSession.sendCmd(cmdType, body, category = VfsProtocol.CATEGORY_VFS) },
                        path = norm,
                    )?.entries
                }
                override fun readFileBytes(relativePath: String): ByteArray? {
                    val norm = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
                    return vfsDesktop.downloadAndAwait(
                        sender = { cmdType, body -> desktopSession.sendCmd(cmdType, body, category = VfsProtocol.CATEGORY_VFS) },
                        path = norm,
                    )
                }
            }
            webDavServer = VfsLoopbackWebDavServer(dataSource = webDavDs)
            val davPort = webDavServer.start()
            val basicAuth = "Basic " + Base64.getEncoder().encodeToString("ec0:${webDavServer.authToken}".encodeToByteArray())

            // GET /DCIM/notes.txt via HTTP WebDAV -> encrypted VFS download from simulated phone!
            val getConn = URI("http://127.0.0.1:$davPort/DCIM/notes.txt").toURL().openConnection() as HttpURLConnection
            getConn.setRequestProperty("Authorization", basicAuth)
            assertEquals(200, getConn.responseCode)
            assertEquals(samplePhoneNote.readText(), getConn.inputStream.readBytes().decodeToString())

            // GET /DCIM/sunset.jpg via HTTP WebDAV -> exact binary match!
            val imgConn = URI("http://127.0.0.1:$davPort/DCIM/sunset.jpg").toURL().openConnection() as HttpURLConnection
            imgConn.setRequestProperty("Authorization", basicAuth)
            assertEquals(200, imgConn.responseCode)
            assertContentEquals(samplePhonePhoto.readBytes(), imgConn.inputStream.readBytes())

            // 6. Feature #4: Sidecar Display start/touch/idempotent double-start/stop
            val receivedTouches = CopyOnWriteArrayList<SidecarTouchInputEvent>()
            desktopNode.sidecarDisplayService.onTouchInputInjectedHandler = { receivedTouches.add(it) }
            val sidecarCmd = SidecarStartCommand(width = 1920, height = 1080, fps = 60, bitrateKbps = 8_000)
            desktopNode.sidecarDisplayService.startSidecar(sidecarCmd, session = desktopSession)
            desktopNode.sidecarDisplayService.startSidecar(sidecarCmd, session = desktopSession) // idempotent!
            assertTrue(awaitCondition { phoneSidecarEvents.contains("start:1920x1080@60") })
            assertEquals(1, phoneSidecarEvents.count { it.startsWith("start:") })

            phoneRuntime.node.sidecarDisplayService.sendTouchInput(
                SidecarTouchInputEvent("down", 0, 4200, 5100, 900, 5, -3, "stylus"),
                phoneSession,
            )
            assertTrue(awaitCondition { receivedTouches.size == 1 && receivedTouches[0].toolType == "stylus" })
            desktopNode.sidecarDisplayService.stopSidecar(desktopSession)
            desktopNode.sidecarDisplayService.stopSidecar(desktopSession) // idempotent double stop!
            assertTrue(awaitCondition { phoneSidecarEvents.contains("stop") })

            // 7. Feature #5: SMS Inbox sync + send + 2FA OTP copy on Linux (allowSensitive=true vs false!)
            desktopNode.smsInboxService.requestSync(desktopSession)
            assertTrue(awaitCondition { desktopNode.smsInboxService.threads.value.isNotEmpty() })
            val bankMsg = desktopNode.smsInboxService.threads.value.first().latestBody
            val otp = OtpCodeExtractor.extractOtpCode("MyBank", bankMsg)
            assertEquals("739204", otp)

            // Prove LinuxClipboardBridge blocks unsolicited remote sensitive clips by default,
            // AND succeeds when allowSensitive=true (Copy 2FA Code / Auto-Type)!
            val writtenClipBytes = AtomicReference<ByteArray?>(null)
            val fakeClipboardExec = object : BinaryCommandExecutor {
                override fun execute(command: List<String>, stdin: ByteArray, timeoutMs: Long): BinaryCommandResult {
                    if (command.first() == "wl-copy") writtenClipBytes.set(stdin)
                    return BinaryCommandResult(0, writtenClipBytes.get() ?: ByteArray(0))
                }
            }
            val clipBridge = LinuxClipboardBridge(fakeClipboardExec) { LinuxDisplayServer.WAYLAND }
            val otpClip = ClipboardItem.text(otp!!, isSensitive = true)
            assertFalse(clipBridge.applyRemoteClipboardToSystem(otpClip), "Unsolicited sensitive clip must stay blocked")
            assertTrue(clipBridge.applyRemoteClipboardToSystem(otpClip, allowSensitive = true), "Explicit 2FA OTP copy MUST succeed!")
            assertEquals("739204", writtenClipBytes.get()?.decodeToString())

            val otpCtrl = OtpAutoTypeController(typeDigits = { d -> d == "739204" })
            otpCtrl.recordOtp(otp, "MyBank")
            assertTrue(otpCtrl.consumeAndAutoType())
            assertFalse(otpCtrl.consumeAndAutoType(), "OTP auto-type must be strictly one-shot")

            desktopNode.smsInboxService.sendSms("+15551234567", "On my way!", session = desktopSession)
            assertTrue(awaitCondition { phoneSentSms.any { it.body == "On my way!" } })

            // 8. Feature #6: Bidirectional Focus / DND Sync
            desktopNode.focusSyncService.setLocalFocusMode(FocusSyncProtocol.MODE_MEETING, "manual", desktopSession)
            assertTrue(awaitCondition { phoneFocusModes.contains(FocusSyncProtocol.MODE_MEETING) })
            phoneRuntime.node.focusSyncService.setLocalFocusMode(FocusSyncProtocol.MODE_OFF, "manual", phoneSession)
            assertTrue(awaitCondition { desktopNode.focusSyncService.focusState.value.mode == FocusSyncProtocol.MODE_OFF })

            // 9. Feature #7: Browser Tab & YouTube Timestamp Handoff (Desktop -> Phone)
            val ytTab = BrowserTabHandoffInfo(
                tabId = "tab-yt-1",
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                title = "Tech Talk",
                mediaPositionSec = 125,
            )
            desktopNode.browserTabHandoffService.publishLocalActiveTab(ytTab, desktopSession)
            assertTrue(awaitCondition { phoneReceivedTabs.any { it.tabId == "tab-yt-1" && it.mediaPositionSec == 125 } })

            // 10. Feature #11: Studio Effects sync Desktop -> Phone
            val fx = CameraEffectsCommand(centerStage = true, portraitBlur = true, hwDecodePreference = "auto")
            desktopNode.cameraStudioEffectsController.sendEffects(fx, desktopSession)
            assertTrue(awaitCondition { phoneReceivedCameraFx.any { it.centerStage && it.portraitBlur } })

            // 11. Bidirectional Clipboard + Notifications (Post / Reply / Action / Dismiss) + Find My Phone + Encrypted File Transfer
            desktopNode.sendOrQueueClipboard(phoneId.deviceId, ClipboardItem.text("From Linux PC"))
            assertTrue(awaitCondition { phoneAppliedClipboard.any { it.asUtf8Text() == "From Linux PC" } })
            phoneRuntime.onAndroidClipboardChanged(ClipboardItem.text("From Android Phone"))
            assertTrue(awaitCondition { desktopReceivedClipboard.any { it.asUtf8Text() == "From Android Phone" } })

            val notif = NotificationPostInfo(
                notifId = "n-msg-1",
                packageName = "org.thoughtcrime.securesms",
                appName = "Signal",
                title = "Alex",
                body = "Dinner at 7?",
                timestampMs = System.currentTimeMillis(),
                canReply = true,
                actions = listOf("Mark as read"),
            )
            phoneRuntime.onAndroidNotificationPosted(notif)
            assertTrue(awaitCondition { desktopNode.notifications.value.any { it.notifId == "n-msg-1" } })

            desktopNode.notificationRelayService.sendInlineReply(
                NotificationReplyCommand(notifId = "n-msg-1", action = "reply", replyText = "Sounds great!"),
                desktopSession,
            )
            desktopNode.sendNotificationAction(phoneId.deviceId, "n-msg-1", 0, "Mark as read")
            desktopNode.notificationRelayService.dismissNotification("n-msg-1", desktopSession)
            assertTrue(awaitCondition { phoneReplies.any { it.replyText == "Sounds great!" } })
            assertTrue(awaitCondition { phoneActions.any { it.notifId == "n-msg-1" && it.actionIndex == 0 } })
            assertTrue(awaitCondition { phoneDismissed.contains("n-msg-1") })

            desktopNode.handoffAndProximityService.triggerFindMyPhoneRing(start = true, durationSeconds = 10, session = desktopSession)
            assertTrue(awaitCondition { phoneFindMy.any { it.action == FindMyRingCommand.ACTION_START_RING } })

            val fileToSend = File(tempDir, "report.txt").apply { writeText("Encrypted ec0 AirDrop payload 123456") }
            val offer = desktopNode.sendFileOrDirectory(phoneId.deviceId, fileToSend)
            assertTrue(
                awaitCondition(5_000L) {
                    val landed = File(phoneDownloads, "report.txt")
                    landed.isFile && landed.readText() == "Encrypted ec0 AirDrop payload 123456"
                },
                "Encrypted file transfer ${offer.transferId} must land intact on phone",
            )
        } finally {
            runCatching { webDavServer?.close() }
            runCatching { phoneRuntime.close() }
            runCatching { desktopNode.close() }
            tempDir.deleteRecursively()
        }
    }
}
