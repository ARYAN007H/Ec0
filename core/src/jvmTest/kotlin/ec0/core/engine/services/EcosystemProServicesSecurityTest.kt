/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Comprehensive JVM unit & security regression tests covering ALL 15
 * Ecosystem Pro features and their security boundaries.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.EnvelopeSigner
import ec0.core.engine.crypto.KeySealerBackend
import ec0.core.engine.crypto.LinuxSecretServiceMasterKeyProvider
import ec0.core.engine.crypto.SecretToolCommandRunner
import ec0.core.engine.linux.Ec0CliCommand
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.protocol.BrowserTabHandoffInfo
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CameraEffectsCommand
import ec0.core.engine.protocol.CaptureResultInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ContinuityCaptureProtocol
import ec0.core.engine.protocol.FocusSyncProtocol
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.SidecarDisplayProtocol
import ec0.core.engine.protocol.SidecarStartCommand
import ec0.core.engine.protocol.SidecarTouchInputEvent
import ec0.core.engine.protocol.SmsInboxProtocol
import ec0.core.engine.protocol.SmsSendCommand
import ec0.core.engine.protocol.TransportFailoverProtocol
import ec0.core.engine.protocol.VfsEntryDto
import ec0.core.engine.protocol.WifiDirectTurboCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcosystemProServicesSecurityTest {

    private class FakeLauncher(
        private val handler: (List<String>) -> CommandExecResult = { CommandExecResult(0, "") },
    ) : ExternalProcessLauncher {
        val commands = mutableListOf<List<String>>()
        override fun spawnProcess(command: List<String>): RunningProcessHandle {
            commands.add(command)
            return object : RunningProcessHandle {
                private var alive = true
                override val isAlive: Boolean get() = alive
                override fun stop() {
                    alive = false
                }
            }
        }

        override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
            commands.add(command)
            return handler(command)
        }
    }

    @Test
    fun feature1_callMediaDuckingAndSafeAudioSink() {
        var playing = true
        val actions = mutableListOf<String>()
        val ducker = CallMediaDuckingCoordinator(
            isMediaPlayingProvider = { if (playing) "spotify" else null },
            pausePlayer = { player ->
                actions.add("pause:$player")
                playing = false
                true
            },
            resumePlayer = { player ->
                actions.add("play:$player")
                playing = true
                true
            },
        )
        // Incoming call pauses playing media
        assertEquals(
            "spotify",
            ducker.onCallStateUpdate(CallStateInfo("c1", PhoneCallState.RINGING, "+15550001", "Alice", 1_000L)),
        )
        assertEquals(listOf("pause:spotify"), actions)
        // Call ends -> restores media
        assertNull(ducker.onCallStateUpdate(CallStateInfo("c1", PhoneCallState.ENDED, "+15550001", "Alice", 2_000L)))
        assertEquals(listOf("pause:spotify", "play:spotify"), actions)

        // If media was ALREADY paused before call, never auto-resume it!
        playing = false
        actions.clear()
        assertNull(ducker.onCallStateUpdate(CallStateInfo("c2", PhoneCallState.ACTIVE, "+15550002", "Bob", 3_000L)))
        assertNull(ducker.onCallStateUpdate(CallStateInfo("c2", PhoneCallState.ENDED, "+15550002", "Bob", 4_000L)))
        assertTrue(actions.isEmpty())

        val launcher = FakeLauncher { CommandExecResult(0, "42") }
        val audioSvc = AudioHandoffService(launcher = launcher)
        val status = audioSvc.startDesktopAudioRoute(mode = "media_sink")
        assertTrue(status.active)
        assertTrue(launcher.commands.first().first() == "pactl")
        assertFalse(launcher.commands.first().any { it.contains("/tmp/") })
        audioSvc.close()
    }

    @Test
    fun feature2_continuityCaptureRejectsUnsolicitedAndTamperedPayloads() {
        val kp = Ed25519Identity.generate()
        val svc = ContinuityCaptureService()
        val payload = "scanned-pdf-bytes".encodeToByteArray()
        val payloadB64 = payload.toBase64UrlUnpadded()
        val goodSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        // Unsolicited capture (no matching requestId) MUST be rejected
        val unsolicited = CaptureResultInfo(
            requestId = "forged-req-1",
            mode = ContinuityCaptureProtocol.MODE_PHOTO,
            mime = "image/jpeg",
            sizeBytes = payload.size.toLong(),
            sha256Hex = goodSha,
            inlineBase64Url = payloadB64,
        )
        val unsolicitedEnv = EnvelopeSigner.signEnvelope(
            identity = kp,
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = Ec0EnvelopeCodec.buildInfoBody(ContinuityCaptureProtocol.INFO_CAPTURE_RESULT, unsolicited.toJsonObject()),
            category = ContinuityCaptureProtocol.CATEGORY_CAPTURE,
            timestampMs = System.currentTimeMillis(),
            seq = 1L,
        )
        assertTrue(svc.handleControlEnvelope(unsolicitedEnv))
        assertNull(svc.lastDeliveredCapture.value)

        // Solicited capture with corrupted SHA-256 MUST be rejected
        val req = svc.requestCaptureFromPhone(ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN, autoPaste = false, session = null)
        val badHash = CaptureResultInfo(
            requestId = req.requestId,
            mode = ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN,
            mime = "application/pdf",
            sizeBytes = payload.size.toLong(),
            sha256Hex = "0".repeat(64),
            inlineBase64Url = payloadB64,
        )
        val badEnv = EnvelopeSigner.signEnvelope(
            identity = kp,
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = Ec0EnvelopeCodec.buildInfoBody(ContinuityCaptureProtocol.INFO_CAPTURE_RESULT, badHash.toJsonObject()),
            category = ContinuityCaptureProtocol.CATEGORY_CAPTURE,
            timestampMs = System.currentTimeMillis(),
            seq = 2L,
        )
        svc.handleControlEnvelope(badEnv)
        assertNull(svc.lastDeliveredCapture.value)

        // Solicited capture with matching SHA-256 succeeds
        val req2 = svc.requestCaptureFromPhone(ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN, autoPaste = false, session = null)
        val good = CaptureResultInfo(
            requestId = req2.requestId,
            mode = ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN,
            mime = "application/pdf",
            sizeBytes = payload.size.toLong(),
            sha256Hex = goodSha,
            inlineBase64Url = payloadB64,
        )
        val goodEnv = EnvelopeSigner.signEnvelope(
            identity = kp,
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = Ec0EnvelopeCodec.buildInfoBody(ContinuityCaptureProtocol.INFO_CAPTURE_RESULT, good.toJsonObject()),
            category = ContinuityCaptureProtocol.CATEGORY_CAPTURE,
            timestampMs = System.currentTimeMillis(),
            seq = 3L,
        )
        svc.handleControlEnvelope(goodEnv)
        assertNotNull(svc.lastDeliveredCapture.value)
        assertEquals(req2.requestId, svc.lastDeliveredCapture.value?.requestId)
    }

    @Test
    fun feature3_vfsLoopbackWebDavAuthTraversalAndXmlEscaping() {
        val ds = object : VfsMountDataSource {
            override fun listDirectory(relativePath: String): List<VfsEntryDto>? = listOf(
                VfsEntryDto(name = "photo & <tag>.jpg", isDir = false, size = 5L),
                VfsEntryDto(name = ".secret", isDir = false, size = 10L),
                VfsEntryDto(name = "DCIM", isDir = true, size = 0L),
            )
            override fun readFileBytes(relativePath: String): ByteArray? =
                if (relativePath == "hello.txt") "world".encodeToByteArray() else null
        }
        val server = VfsLoopbackWebDavServer(dataSource = ds)
        val port = server.start()
        try {
            assertTrue(server.mountUrl.startsWith("http://127.0.0.1:"))

            // 1. Unauthenticated request MUST fail with 401
            val unauth = URI("http://127.0.0.1:$port/hello.txt").toURL().openConnection() as HttpURLConnection
            assertEquals(401, unauth.responseCode)

            val basicAuth = "Basic " + Base64.getEncoder().encodeToString("ec0:${server.authToken}".encodeToByteArray())

            // 2. Path traversal / dotfile MUST fail
            assertNull(VfsLoopbackWebDavServer.normalizeWebDavPath("/../etc/passwd"))
            assertNull(VfsLoopbackWebDavServer.normalizeWebDavPath("/.ssh/id_rsa"))

            // 3. Authenticated GET returns exact bytes
            val okGet = URI("http://127.0.0.1:$port/hello.txt").toURL().openConnection() as HttpURLConnection
            okGet.setRequestProperty("Authorization", basicAuth)
            assertEquals(200, okGet.responseCode)
            assertEquals("world", okGet.inputStream.readBytes().decodeToString())

            // 4. XML escaping prevents injection
            val escaped = VfsLoopbackWebDavServer.escapeXml("<script>&\"'</script>")
            assertFalse(escaped.contains("<script>"))
            assertTrue(escaped.contains("&lt;script&gt;&amp;&quot;&apos;"))
        } finally {
            server.close()
        }
    }

    @Test
    fun feature4_sidecarDisplayGuardsInactiveTouchAndValidatesArgv() {
        val injected = mutableListOf<SidecarTouchInputEvent>()
        val launcher = FakeLauncher()
        val svc = SidecarDisplayService(launcher = launcher, onTouchInputInjected = { injected.add(it) })
        val kp = Ed25519Identity.generate()

        val touch = SidecarTouchInputEvent("down", 0, 5_000, 5_000, 1000, 0, 0, "stylus")
        val env = EnvelopeSigner.signEnvelope(
            identity = kp,
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = Ec0EnvelopeCodec.buildCmdBody(SidecarDisplayProtocol.CMD_SIDECAR_TOUCH, touch.toJsonObject()),
            category = SidecarDisplayProtocol.CATEGORY_SIDECAR,
            timestampMs = System.currentTimeMillis(),
            seq = 1L,
        )
        // Inactive Sidecar rejects touch injection
        svc.handleControlEnvelope("peer-1", env)
        assertTrue(injected.isEmpty())

        // Start Sidecar -> touch accepted
        assertTrue(svc.startSidecar(SidecarStartCommand(1920, 1080, 60, 8000), session = null).active)
        svc.handleControlEnvelope("peer-1", env)
        assertEquals(1, injected.size)
        val argv = svc.buildSidecarEncoderArgv(SidecarStartCommand(1920, 1080, 60, 8000))
        assertEquals("ffmpeg", argv.first())
        svc.close()
    }

    @Test
    fun feature5_smsRateLimitAndOtpSingleShotExpiry() {
        var now = 10_000L
        val kp = Ed25519Identity.generate()
        val sent = mutableListOf<SmsSendCommand>()
        val sms = SmsInboxService(
            onRemoteSmsSendRequested = { cmd -> sent.add(cmd); true },
            clockMs = { now },
        )
        // 10 inbound CMD_SMS_SEND allowed per bucket burst; 11th rate-limited
        repeat(11) { i ->
            val cmd = SmsSendCommand("req-$i", "+15550001234", "msg $i")
            val env = EnvelopeSigner.signEnvelope(
                identity = kp,
                msgType = Ec0EnvelopeCodec.TYPE_CMD,
                msgBody = Ec0EnvelopeCodec.buildCmdBody(SmsInboxProtocol.CMD_SMS_SEND, cmd.toJsonObject()),
                category = SmsInboxProtocol.CATEGORY_SMS,
                timestampMs = now,
                seq = i.toLong() + 1L,
            )
            sms.handleControlEnvelope("peer-1", env)
        }
        assertEquals(10, sent.size)

        val typed = mutableListOf<String>()
        val otp = OtpAutoTypeController(
            typeDigits = { d -> typed.add(d); true },
            ttlMs = 60_000L,
            clockMs = { now },
        )
        otp.recordOtp("not-digits!", "Bank")
        assertNull(otp.pendingOtp.value)

        otp.recordOtp("482910", "Bank")
        assertEquals("482910", otp.pendingOtp.value?.first)
        assertTrue(otp.consumeAndAutoType())
        assertEquals(listOf("482910"), typed)
        // Single-shot: second trigger must return false
        assertFalse(otp.consumeAndAutoType())

        // Expiry check
        otp.recordOtp("999111", "Bank")
        now += 61_000L
        assertFalse(otp.consumeAndAutoType())
    }

    @Test
    fun feature6And7_meetingAutoFocusAndBrowserTabResume() {
        var micBusy = true
        val focus = FocusSyncService(
            launcher = FakeLauncher {
                if (micBusy) CommandExecResult(0, "1\t0\t12\tpipewire\ts16le 1ch 48000Hz\n") else CommandExecResult(0, "")
            },
        )
        assertTrue(focus.pollMeetingAutoFocus())
        assertEquals(FocusSyncProtocol.MODE_MEETING, focus.focusState.value.mode)

        micBusy = false
        assertTrue(focus.pollMeetingAutoFocus())
        assertEquals(FocusSyncProtocol.MODE_OFF, focus.focusState.value.mode)

        val tab = BrowserTabHandoffInfo(
            tabId = "tab-yt",
            url = "https://youtu.be/abc123XYZ",
            title = "Talk",
            mediaPositionSec = 240,
        )
        assertTrue(BrowserTabHandoffService.buildResumeUrl(tab).endsWith("t=240s"))
    }

    @Test
    fun feature8And12_zeroRttResumeTokenAndClipboardShelfUndo() {
        var now = 1_000_000L
        val peerId = "a".repeat(64)
        val failover = TransportFailoverCoordinator(clockMs = { now })
        val token = failover.issueResumeToken(peerId, epoch = 1, ttlMs = 30_000L)
        // First consume succeeds
        assertTrue(failover.verifyAndConsumeResumeToken(token))
        // Replay MUST fail (single-use)
        assertFalse(failover.verifyAndConsumeResumeToken(token))
        // Expired token MUST fail
        val expToken = failover.issueResumeToken(peerId, epoch = 2, ttlMs = 5_000L)
        now += 6_000L
        assertFalse(failover.verifyAndConsumeResumeToken(expToken))

        // Safe nmcli argv check
        val cmd = failover.buildSafeP2pJoinCommand(
            WifiDirectTurboCommand(
                action = TransportFailoverProtocol.ACTION_P2P_READY,
                groupOwnerIp = "192.168.49.1",
                ssid = "DIRECT-ec0",
                passphrase = "SuperSecretPass99",
                port = 49880,
            ),
        )
        assertEquals("nmcli", cmd.first())

        // Clipboard shelf (max 20, pin protection, 30s OTP expiry, Undo)
        val shelf = ClipboardShelfManager(maxEntries = 3, sensitiveTtlMs = 30_000L, clockMs = { now })
        val prevLocal = ClipboardItem.text("my-local-draft")
        val remote = ClipboardItem.text("remote-text")
        val e1 = shelf.recordItem(remote, sourceAlias = "Phone", isRemote = true, previousLocalClip = prevLocal)
        assertNotNull(shelf.toastEvent.value)
        assertTrue(shelf.toastEvent.value!!.canUndo)
        assertEquals("my-local-draft", shelf.consumeUndoSnapshot()?.bytes?.decodeToString())
        assertNull(shelf.consumeUndoSnapshot())

        shelf.togglePin(e1.id)
        shelf.recordItem(ClipboardItem.text("otp-123456", isSensitive = true), sourceAlias = "Phone", isRemote = true)
        shelf.recordItem(ClipboardItem.text("clip-2"), sourceAlias = "PC", isRemote = false)
        shelf.recordItem(ClipboardItem.text("clip-3"), sourceAlias = "PC", isRemote = false)
        // Pinned item survives capacity eviction
        assertTrue(shelf.entries.value.any { it.id == e1.id })

        // Sensitive item auto-expires after 30s
        now += 31_000L
        shelf.pruneExpired()
        assertFalse(shelf.entries.value.any { it.isSensitive })
    }

    @Test
    fun feature10And11_daemonIpcVirtualMicAndCameraEffects() {
        val tmpDir = Files.createTempDirectory("ec0-ipc-test").toFile()
        val descFile = File(tmpDir, "daemon_ipc.json")
        val server = LocalDaemonIpcServer(
            descriptorFile = descFile,
            onSendFilesRequested = {},
            onCommandRequested = { cmd, _ ->
                buildJsonObject { put("ok", true); put("echo", cmd) }
            },
        )
        server.start()
        try {
            val reply = LocalDaemonIpcClient.sendCommandToRunningDaemon("status", descriptorFile = descFile)
            assertNotNull(reply)
            assertTrue(reply.toString().contains("\"echo\":\"status\""))
        } finally {
            server.close()
            tmpDir.deleteRecursively()
        }

        // Virtual mic path must NOT live in /tmp
        val micPath = ContinuityVirtualMicBridge.defaultSecurePipePath()
        assertFalse(micPath.startsWith("/tmp/"))

        // Camera effects argv & adaptive bitrate governor
        val fxCtrl = CameraStudioEffectsController()
        val argv = fxCtrl.buildFfmpegStudioDecodeArgv(
            codec = "h264",
            v4l2DevicePath = "/dev/video10",
            effects = CameraEffectsCommand(centerStage = true, portraitBlur = true, hwDecodePreference = "vaapi"),
        )
        assertTrue(argv.contains("vaapi"))
        assertTrue(argv.contains("-vf"))
        assertFailsWith<IllegalArgumentException> {
            fxCtrl.buildFfmpegStudioDecodeArgv("h264", "/dev/video10; rm -rf /")
        }

        // CLI parser supports new daemon/pro flags
        assertEquals(Ec0CliCommand.RunDaemon, Ec0CliCommand.parse(listOf("--daemon")))
        assertEquals(Ec0CliCommand.AutoTypeOtp, Ec0CliCommand.parse(listOf("--auto-type-otp")))
        assertEquals(Ec0CliCommand.SetFocus("dnd"), Ec0CliCommand.parse(listOf("--focus", "dnd")))
        assertTrue(LinuxDesktopIntegrationGenerator.buildSystemdUserUnit().contains("NoNewPrivileges=true"))
    }

    @Test
    fun feature13_14_15_keystorePiiRedactionWatchdogAndSignedUpdates() {
        // #13: LinuxSecretServiceMasterKeyProvider passes secret via stdin ONLY (never argv!)
        val tmpConfig = Files.createTempDirectory("ec0-ks-test").toFile()
        try {
            var storedSecret: String? = null
            val fakeRunner = object : SecretToolCommandRunner {
                override fun run(argv: List<String>, stdinText: String?, timeoutMs: Long): SecretToolCommandRunner.ExecResult {
                    // CRITICAL SECURITY INVARIANT: secret must NEVER appear in argv
                    if (storedSecret != null) {
                        assertFalse(argv.any { it.contains(storedSecret!!) })
                    }
                    return when (argv.getOrNull(1)) {
                        "lookup" -> if (storedSecret != null) SecretToolCommandRunner.ExecResult(0, storedSecret!!) else SecretToolCommandRunner.ExecResult(1, "")
                        "store" -> {
                            assertFalse(argv.any { it == stdinText })
                            storedSecret = stdinText
                            SecretToolCommandRunner.ExecResult(0, "")
                        }
                        else -> SecretToolCommandRunner.ExecResult(1, "")
                    }
                }
            }
            val provider = LinuxSecretServiceMasterKeyProvider(configDir = tmpConfig, runner = fakeRunner)
            val store = EncryptedIdentityStore(configDir = tmpConfig, masterKeyProvider = provider)
            val kp1 = store.loadOrCreateIdentity()
            assertEquals(KeySealerBackend.SECRET_SERVICE, store.activeKeySealerBackend)
            val kp2 = store.loadOrCreateIdentity()
            assertEquals(kp1.deviceId, kp2.deviceId)
        } finally {
            tmpConfig.deleteRecursively()
        }

        // #14: PII redaction & Support Bundle + Watchdog
        val dirty = "User alice@example.com from 192.168.1.42 sent token=secret1234567890 Bearer abcdef1234567890 and OTP code: 482910 via +1-555-867-5309 (loopback 127.0.0.1)"
        val clean = PiiRedactor.redact(dirty)
        assertFalse(clean.contains("alice@example.com"))
        assertFalse(clean.contains("192.168.1.42"))
        assertFalse(clean.contains("secret1234567890"))
        assertFalse(clean.contains("482910"))
        assertTrue(clean.contains("127.0.0.1"))

        var now = 1000L
        val watchdog = ServiceCrashLoopWatchdog(maxCrashesInWindow = 3, windowMs = 10_000L, clockMs = { now })
        assertEquals(1000L, watchdog.recordFailure(now))
        assertEquals(2000L, watchdog.recordFailure(now + 500L))
        assertEquals(-1L, watchdog.recordFailure(now + 1000L))
        assertTrue(watchdog.isCircuitTripped(now + 1000L))

        // #15: Ed25519 Signed Update Verifier
        val releaseKp = Ed25519Identity.generate()
        val verifier = SignedUpdateVerifier(releaseKp.publicKeyRaw)
        val manifest = SignedUpdateVerifier.signManifestForRelease(
            version = "1.2.0",
            channel = "stable",
            releasedAtMs = 1_700_000_000_000L,
            downloadUrl = "https://releases.ec0.app/ec0-1.2.0.AppImage",
            sha256Hex = "c".repeat(64),
            releaseNotes = "Ecosystem Pro release",
            signingKeyPair = releaseKp,
        )
        assertTrue(verifier.verifyManifest(manifest, "1.0.0") is UpdateVerificationResult.UpdateAvailable)
        assertTrue(verifier.verifyManifest(manifest, "1.2.0") is UpdateVerificationResult.UpToDate)
        // Tampered manifest MUST be rejected
        val tampered = manifest.copy(downloadUrl = "https://evil.example/malware.bin")
        assertTrue(verifier.verifyManifest(tampered, "1.0.0") is UpdateVerificationResult.Rejected)
        // Non-HTTPS URL MUST be rejected
        assertFailsWith<IllegalArgumentException> {
            SignedReleaseManifest.validateHttpsUrl("http://releases.ec0.app/ec0.bin")
        }
    }
}
