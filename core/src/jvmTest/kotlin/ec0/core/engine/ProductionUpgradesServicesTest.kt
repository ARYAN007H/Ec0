/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM service & security regression tests covering:
 *   - Hardened bidirectional VFS uploads + bounded image thumbnails (S9)
 *   - Adaptive chunk sizing, savedLocalPath, and auto-reoffer resume (`FileTransferService`)
 *   - Hardware H.264 / MJPEG ffmpeg argv construction & codec injection defense (`CamDroidStreamReceiver`)
 *   - Linux XDG Login Autostart `0600` & Exec-field injection defense + Wayland SNI probe (`LinuxAutostartManager`)
 *   - Universal Control screen-edge barrier watcher (`LinuxScreenEdgeWatcher`)
 *   - Linux BlueZ BLE proximity scanner MAC allowlist & walk-away lock (`LinuxBleProximityScanner`)
 */
package ec0.core.engine

import ec0.core.engine.linux.LinuxAutostartManager
import ec0.core.engine.linux.LinuxBleProximityScanner
import ec0.core.engine.linux.LinuxScreenEdgeWatcher
import ec0.core.engine.linux.ScreenPointerSample
import ec0.core.engine.protocol.BleProximityStateMachine
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.FileTransferProtocol
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.ProximityEvent
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.protocol.VfsProtocol
import ec0.core.engine.services.CamDroidStreamConfig
import ec0.core.engine.services.CamDroidStreamReceiver
import ec0.core.engine.services.CommandExecResult
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.FileTransferService
import ec0.core.engine.services.RunningProcessHandle
import ec0.core.engine.services.UniversalControlService
import ec0.core.engine.services.VfsPhoneService
import ec0.core.engine.transport.BulkStreamFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionUpgradesServicesTest {

    private val seqCounter = AtomicLong(1L)

    private fun wrapCommand(cmdType: String, body: JsonObject): Ec0Envelope = Ec0Envelope(
        msgType = Ec0EnvelopeCodec.TYPE_CMD,
        msgBody = buildJsonObject {
            put("cmd_type", cmdType)
            put("cmd_body", body)
        },
        timestamp = 1_700_000_000_000L,
        seq = seqCounter.getAndIncrement(),
        nonce = seqCounter.get() * 13L,
        sig = "test-sig",
    )

    private fun wrapInfo(infoType: String, body: JsonObject): Ec0Envelope = Ec0Envelope(
        msgType = Ec0EnvelopeCodec.TYPE_INFO,
        msgBody = buildJsonObject {
            put("info_type", infoType)
            put("info_body", body)
        },
        timestamp = 1_700_000_000_000L,
        seq = seqCounter.getAndIncrement(),
        nonce = seqCounter.get() * 17L,
        sig = "test-sig",
    )

    private fun blockingLauncher(handler: (List<String>) -> CommandExecResult): ExternalProcessLauncher =
        object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult =
                handler(command)

            override fun spawnProcess(command: List<String>): RunningProcessHandle =
                object : RunningProcessHandle {
                    override val isAlive: Boolean = false
                    override fun stop() = Unit
                }
        }

    // -------------------------------------------------------------------------
    // 1. VfsPhoneService: default-off uploads, atomic write, symlink/traversal defense, bounded thumbnails
    // -------------------------------------------------------------------------

    @Test
    fun vfsPhoneServiceDeniesUploadsByDefaultAndAllowsValidChunkedUploadWhenOptedIn() {
        val tempRoot = createTempDirectory("ec0-vfs-upload").toFile()
        try {
            val downloads = File(tempRoot, "Downloads").apply { mkdirs() }
            var uploadsEnabled = false
            val vfs = VfsPhoneService(
                rootsProvider = { mapOf("Downloads" to downloads) },
                allowUploads = { uploadsEnabled },
                maxUploadBytes = 4096L,
            )

            val replies = mutableListOf<Pair<String, JsonObject>>()
            val payload = "hello encrypted continuity upload!".encodeToByteArray()
            val b64 = Base64.getEncoder().encodeToString(payload)

            // Default: uploads disabled
            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(
                    VfsProtocol.CMD_VFS_UPLOAD,
                    buildJsonObject {
                        put("path", "/Downloads/note.txt")
                        put("offset", 0L)
                        put("eof", true)
                        put("buffer_b64", b64)
                    },
                ),
            ) { t, b -> replies += t to b }
            assertEquals(VfsProtocol.INFO_VFS_ERROR, replies.last().first)
            assertFalse(File(downloads, "note.txt").exists())

            // Opt-in enabled: multi-chunk upload succeeds atomically
            uploadsEnabled = true
            replies.clear()
            val part1 = payload.copyOfRange(0, 10)
            val part2 = payload.copyOfRange(10, payload.size)
            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(
                    VfsProtocol.CMD_VFS_UPLOAD,
                    buildJsonObject {
                        put("path", "/Downloads/note.txt")
                        put("offset", 0L)
                        put("eof", false)
                        put("buffer_b64", Base64.getEncoder().encodeToString(part1))
                    },
                ),
            ) { t, b -> replies += t to b }
            assertTrue(replies.isEmpty(), "Intermediate chunk should not emit error or premature ack")

            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(
                    VfsProtocol.CMD_VFS_UPLOAD,
                    buildJsonObject {
                        put("path", "/Downloads/note.txt")
                        put("offset", 10L)
                        put("eof", true)
                        put("buffer_b64", Base64.getEncoder().encodeToString(part2))
                    },
                ),
            ) { t, b -> replies += t to b }

            assertEquals(1, replies.size)
            assertEquals(VfsProtocol.INFO_VFS_UPLOAD_ACK, replies.single().first)
            assertEquals("hello encrypted continuity upload!", File(downloads, "note.txt").readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun vfsPhoneServiceBlocksTraversalDotfilesRootOverwriteSymlinkOversizeAndOutOfOrderUploads() {
        val tempRoot = createTempDirectory("ec0-vfs-sec").toFile()
        try {
            val downloads = File(tempRoot, "Downloads").apply { mkdirs() }
            val outsideFile = File(tempRoot, "secret.txt").apply { writeText("DO NOT OVERWRITE") }
            val symlinkTarget = File(downloads, "escape.txt")
            Files.createSymbolicLink(symlinkTarget.toPath(), outsideFile.toPath())

            val vfs = VfsPhoneService(
                rootsProvider = { mapOf("Downloads" to downloads) },
                allowUploads = { true },
                maxUploadBytes = 128L,
            )

            val smallB64 = Base64.getEncoder().encodeToString("pwn".encodeToByteArray())
            val forbiddenPaths = listOf(
                "/Downloads/../../etc/passwd",
                "/Downloads/.ssh/authorized_keys",
                "/Downloads/.hidden",
                "/Downloads",
                "/",
                "/UnknownRoot/file.txt",
                "/Downloads/escape.txt",
            )
            for (badPath in forbiddenPaths) {
                val replies = mutableListOf<Pair<String, JsonObject>>()
                vfs.handleControlEnvelope(
                    peerDeviceId = "peer-1",
                    envelope = wrapCommand(
                        VfsProtocol.CMD_VFS_UPLOAD,
                        buildJsonObject {
                            put("path", badPath)
                            put("offset", 0L)
                            put("eof", true)
                            put("buffer_b64", smallB64)
                        },
                    ),
                ) { t, b -> replies += t to b }
                assertEquals(
                    VfsProtocol.INFO_VFS_ERROR,
                    replies.lastOrNull()?.first,
                    "Expected VFS error for forbidden upload path '$badPath'",
                )
            }
            assertEquals("DO NOT OVERWRITE", outsideFile.readText())

            // Oversize upload rejected
            val bigB64 = Base64.getEncoder().encodeToString(ByteArray(256) { 0x41 })
            val oversizeReplies = mutableListOf<Pair<String, JsonObject>>()
            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(
                    VfsProtocol.CMD_VFS_UPLOAD,
                    buildJsonObject {
                        put("path", "/Downloads/big.bin")
                        put("offset", 0L)
                        put("eof", true)
                        put("buffer_b64", bigB64)
                    },
                ),
            ) { t, b -> oversizeReplies += t to b }
            assertEquals(VfsProtocol.INFO_VFS_ERROR, oversizeReplies.lastOrNull()?.first)
            assertFalse(File(downloads, "big.bin").exists())

            // Out-of-order offset rejected
            val oooReplies = mutableListOf<Pair<String, JsonObject>>()
            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(
                    VfsProtocol.CMD_VFS_UPLOAD,
                    buildJsonObject {
                        put("path", "/Downloads/ooo.bin")
                        put("offset", 64L)
                        put("eof", true)
                        put("buffer_b64", smallB64)
                    },
                ),
            ) { t, b -> oooReplies += t to b }
            assertEquals(VfsProtocol.INFO_VFS_ERROR, oooReplies.lastOrNull()?.first)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun vfsPhoneServiceEmitsBoundedImageThumbnailsAndCapsCountPerListing() {
        val tempRoot = createTempDirectory("ec0-vfs-thumbs").toFile()
        try {
            val dcim = File(tempRoot, "DCIM").apply { mkdirs() }
            for (i in 1..30) {
                File(dcim, "IMG_$i.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), i.toByte()))
            }
            File(dcim, "oversize.png").writeBytes(byteArrayOf(1, 2, 3))
            File(dcim, "readme.txt").writeText("plain")

            val vfs = VfsPhoneService(
                rootsProvider = { mapOf("DCIM" to dcim) },
                thumbnailProvider = { file ->
                    if (file.name == "oversize.png") ByteArray(VfsPhoneService.MAX_THUMBNAIL_BYTES + 1)
                    else byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
                },
            )

            val replies = mutableListOf<Pair<String, JsonObject>>()
            vfs.handleControlEnvelope(
                peerDeviceId = "peer-1",
                envelope = wrapCommand(VfsProtocol.CMD_VFS_LIST, buildJsonObject { put("path", "/DCIM") }),
            ) { t, b -> replies += t to b }

            assertEquals(VfsProtocol.INFO_VFS_DIR, replies.single().first)
            val entries = replies.single().second["entries"] as JsonArray
            val thumbs = entries.mapNotNull { (it.jsonObject["thumb_b64"] as? JsonPrimitive)?.content }
            assertEquals(VfsPhoneService.MAX_THUMBNAILS_PER_LISTING, thumbs.size)
            assertTrue(thumbs.all { it.length <= VfsProtocol.MAX_THUMB_B64_CHARS })

            val oversizeEntry = entries.first { it.jsonObject["name"]!!.jsonPrimitive.content == "oversize.png" }.jsonObject
            assertEquals("image/png", oversizeEntry["mime_hint"]?.jsonPrimitive?.content)
            assertEquals(null, oversizeEntry["thumb_b64"], "Oversized thumbnail must be dropped")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 2. FileTransferService: adaptive chunk sizing + savedLocalPath + reoffer resume
    // -------------------------------------------------------------------------

    @Test
    fun fileTransferAdaptiveChunkSizingAndSavedLocalPathAndResumeReoffer() {
        assertEquals(FileTransferService.SOFTAP_CHUNK_BYTES, FileTransferService.recommendChunkSize(rttMs = 3L, isSoftApLink = true))
        assertEquals(FileTransferService.SOFTAP_CHUNK_BYTES, FileTransferService.recommendChunkSize(rttMs = 55L, isSoftApLink = false))
        assertEquals(FileTransferService.FAST_LAN_CHUNK_BYTES, FileTransferService.recommendChunkSize(rttMs = 2L, isSoftApLink = false))
        assertEquals(EngineConstants.DEFAULT_FILE_CHUNK_BYTES, FileTransferService.recommendChunkSize(rttMs = 15L, isSoftApLink = false))

        val senderDir = createTempDirectory("ec0-ft-send").toFile()
        val receiverDir = createTempDirectory("ec0-ft-recv").toFile()
        try {
            val source = File(senderDir, "photo.jpg").apply {
                writeBytes(ByteArray(4096) { (it and 0xFF).toByte() })
            }
            val sender = FileTransferService(senderDir, defaultChunkSize = 1024)
            val receiver = FileTransferService(receiverDir, defaultChunkSize = 1024, autoAcceptIncomingOffers = true)

            lateinit var senderToReceiver: FileTransferService.OutboundTransport
            lateinit var receiverToSender: FileTransferService.OutboundTransport

            var dropAfterChunks = 2
            var chunksDelivered = 0
            senderToReceiver = object : FileTransferService.OutboundTransport {
                override fun sendCmd(cmdType: String, cmdBody: JsonObject) {
                    receiver.handleControlEnvelope(wrapCommand(cmdType, cmdBody), receiverToSender)
                }

                override fun sendInfo(infoType: String, infoBody: JsonObject) = Unit

                override fun sendChunk(frame: BulkStreamFrame) {
                    chunksDelivered++
                    if (chunksDelivered <= dropAfterChunks) {
                        receiver.handleBulkChunk(frame, receiverToSender)
                    }
                }
            }
            receiverToSender = object : FileTransferService.OutboundTransport {
                override fun sendCmd(cmdType: String, cmdBody: JsonObject) {
                    sender.handleControlEnvelope(wrapCommand(cmdType, cmdBody), senderToReceiver)
                }

                override fun sendInfo(infoType: String, infoBody: JsonObject) {
                    sender.handleControlEnvelope(wrapInfo(infoType, infoBody), senderToReceiver)
                }

                override fun sendChunk(frame: BulkStreamFrame) = Unit
            }

            // First attempt drops after 2 chunks (partial .ec0part persists on receiver)
            val offer = sender.registerOutgoingOffer(listOf(source to source.name), chunkSize = 1024)
            senderToReceiver.sendCmd(FileTransferProtocol.CMD_FILE_OFFER, offer.toJsonObject())
            assertEquals(FileTransferState.TRANSFERRING, receiver.transfers.value[offer.transferId]?.state)

            // Reconnect: allow all chunks and re-offer interrupted outgoing transfer -> resumes & completes
            dropAfterChunks = 100
            val reoffered = sender.reofferInterruptedOutgoingTransfers(senderToReceiver)
            assertEquals(1, reoffered)

            val finalSnap = receiver.transfers.value[offer.transferId]
            assertNotNull(finalSnap)
            assertEquals(FileTransferState.COMPLETED, finalSnap.state)
            assertNotNull(finalSnap.savedLocalPath)
            assertTrue(File(finalSnap.savedLocalPath!!).isFile)
            assertEquals(source.readBytes().toList(), File(finalSnap.savedLocalPath!!).readBytes().toList())
        } finally {
            senderDir.deleteRecursively()
            receiverDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 3. CamDroidStreamReceiver: H.264 / MJPEG argv + codec injection defense
    // -------------------------------------------------------------------------

    @Test
    fun camDroidFfmpegArgvSupportsH264MjpegAndSanitizesInjectedCodec() {
        val receiver = CamDroidStreamReceiver(v4l2DevicePath = "/dev/video10")

        val h264Argv = receiver.buildFfmpegArgv(
            CamDroidStreamConfig(width = 1920, height = 1080, fps = 30, codec = "h264"),
        )
        assertTrue(h264Argv.contains("-fflags"))
        assertTrue(h264Argv.contains("nobuffer"))
        assertTrue(h264Argv.contains("low_delay"))
        val fIndex = h264Argv.indexOf("-f")
        assertEquals("h264", h264Argv[fIndex + 1])

        val mjpegArgv = receiver.buildFfmpegArgv(
            CamDroidStreamConfig(width = 1280, height = 720, fps = 30, codec = "mjpeg"),
        )
        assertEquals("mjpeg", mjpegArgv[mjpegArgv.indexOf("-f") + 1])

        // Codec injection falls back cleanly to mjpeg
        val injectedArgv = receiver.buildFfmpegArgv(
            CamDroidStreamConfig(width = 1280, height = 720, fps = 30, codec = "h264;id"),
        )
        assertEquals("mjpeg", injectedArgv[injectedArgv.indexOf("-f") + 1])
        assertFalse(injectedArgv.any { it.contains(";") })
    }

    // -------------------------------------------------------------------------
    // 4. LinuxAutostartManager: 0600 permissions, Exec injection defense, Wayland SNI probe
    // -------------------------------------------------------------------------

    @Test
    fun linuxAutostartManagerWritesRestrictedDesktopFileRejectsInjectionAndProbesWaylandSni() {
        val dir = createTempDirectory("ec0-autostart").toFile()
        try {
            val fakeLauncher = blockingLauncher { argv ->
                if (argv.firstOrNull() == "busctl") {
                    CommandExecResult(0, "org.kde.StatusNotifierWatcher  1234  plasmashell", "")
                } else {
                    CommandExecResult(1, "", "unknown")
                }
            }
            val mgr = LinuxAutostartManager(autostartDir = dir, launcher = fakeLauncher)

            assertFalse(mgr.isAutostartEnabled())
            assertTrue(mgr.isStatusNotifierWatcherAvailable())

            assertTrue(mgr.setAutostartEnabled(true, execPath = "/usr/bin/ec0"))
            assertTrue(mgr.isAutostartEnabled())
            assertTrue(mgr.desktopFile.readText().contains("Exec=/usr/bin/ec0 --tray"))

            // POSIX permissions must be 0600
            val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(mgr.desktopFile.toPath()))
            assertEquals("rw-------", perms)

            // Disable removes file
            assertTrue(mgr.setAutostartEnabled(false))
            assertFalse(mgr.isAutostartEnabled())

            // Rejects freedesktop Exec-field / shell metacharacter injection
            val badExecs = listOf(
                "/usr/bin/ec0\nExec=sh -c id",
                "/usr/bin/ec0;id",
                "/usr/bin/ec0`whoami`",
                "/usr/bin/ec0\$(id)",
                "/usr/bin/ec0%f",
                "/usr/bin/ec0|cat",
                "/usr/bin/ec0&",
                "\"/usr/bin/ec0\"",
            )
            for (bad in badExecs) {
                assertFailsWith<IllegalArgumentException>("Expected rejection for execPath='$bad'") {
                    LinuxAutostartManager.validateExecPath(bad)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 5. LinuxScreenEdgeWatcher: Universal Control edge crossing, clamped deltas, pull-back, escape
    // -------------------------------------------------------------------------

    @Test
    fun linuxScreenEdgeWatcherEntersRemoteEmitsClampedDeltasAndReturnsLocal() {
        val ucService = UniversalControlService()
        var currentSample = ScreenPointerSample(x = 500, y = 500, screenWidth = 1920, screenHeight = 1080)
        val deltas = mutableListOf<Pair<Int, Int>>()
        val handoffs = mutableListOf<CursorHandoffCommand>()
        val now = AtomicLong(10_000L)

        val watcher = LinuxScreenEdgeWatcher(
            universalControlService = ucService,
            activePeerProvider = { "phone-peer-1" to null },
            onRemotePointerDelta = { dx, dy -> deltas += dx to dy },
            onHandoffChanged = { handoffs += it },
            pointerSampler = { currentSample },
            clockMs = { now.get() },
            pollIntervalMs = 60_000L, // drive deterministically via pollOnce()
        )
        try {
            watcher.configureEdge(ScreenEdge.RIGHT)
            // Center of screen -> stays local
            watcher.pollOnce()
            assertFalse(watcher.isRemoteActive)
            assertTrue(handoffs.isEmpty())

            // Cross right edge -> enters remote
            currentSample = ScreenPointerSample(x = 1919, y = 540, screenWidth = 1920, screenHeight = 1080)
            now.addAndGet(500L)
            watcher.pollOnce()
            assertTrue(watcher.isRemoteActive)
            assertEquals(CursorHandoffCommand.ACTION_ENTER, handoffs.last().action)

            // Vertical movement near edge emits clamped relative delta
            currentSample = ScreenPointerSample(x = 1919, y = 940, screenWidth = 1920, screenHeight = 1080)
            now.addAndGet(20L)
            watcher.pollOnce()
            assertEquals(listOf(0 to 256), deltas, "Large delta should be clamped to [-256, 256]")

            // Pull cursor back away from right edge (> 24px) -> returns local
            currentSample = ScreenPointerSample(x = 1800, y = 540, screenWidth = 1920, screenHeight = 1080)
            now.addAndGet(500L)
            watcher.pollOnce()
            assertFalse(watcher.isRemoteActive)
            assertEquals(CursorHandoffCommand.ACTION_LEAVE, handoffs.last().action)

            // Re-enter and verify forceReturnToLocal() escape hatch
            currentSample = ScreenPointerSample(x = 1919, y = 300, screenWidth = 1920, screenHeight = 1080)
            now.addAndGet(1_000L)
            watcher.pollOnce()
            assertTrue(watcher.isRemoteActive)
            watcher.forceReturnToLocal()
            assertFalse(watcher.isRemoteActive)
        } finally {
            watcher.close()
        }
    }

    // -------------------------------------------------------------------------
    // 6. LinuxBleProximityScanner: bluetoothctl parsing, MAC validation, walk-away lock
    // -------------------------------------------------------------------------

    @Test
    fun linuxBleProximityScannerValidatesMacsAndTriggersWalkAwayLock() {
        val parsed = LinuxBleProximityScanner.parseBluetoothctlDevices(
            """
            Device AA:BB:CC:DD:EE:FF Pixel 9 Pro
            Device not-a-mac;id Evil Device
            Device 11:22:33:44:55:66 Galaxy S24
            """.trimIndent(),
        )
        assertEquals(2, parsed.size)
        assertEquals("AA:BB:CC:DD:EE:FF" to "Pixel 9 Pro", parsed[0])
        assertEquals(-64, LinuxBleProximityScanner.extractRssiDbm("  RSSI: 0xffffffc0 (-64)"))
        assertEquals(null, LinuxBleProximityScanner.extractRssiDbm("  RSSI: -999"))

        var simulatedRssi = -55
        val clock = AtomicLong(1_000L)
        val locks = AtomicInteger(0)
        val wakes = AtomicInteger(0)

        val fakeLauncher = blockingLauncher { argv ->
            when (argv.getOrNull(1)) {
                "devices" -> CommandExecResult(0, "Device AA:BB:CC:DD:EE:FF Pixel 9 Pro\n", "")
                "info" -> {
                    val mac = argv[2]
                    assertTrue(LinuxBleProximityScanner.MAC_REGEX.matches(mac))
                    CommandExecResult(0, "  RSSI: $simulatedRssi\n", "")
                }
                else -> CommandExecResult(1, "", "")
            }
        }

        val scanner = LinuxBleProximityScanner(
            launcher = fakeLauncher,
            stateMachine = BleProximityStateMachine(lockGraceMs = 15_000L, emaAlpha = 1.0),
            onWalkAwayLock = { locks.incrementAndGet() },
            onApproachWake = { wakes.incrementAndGet() },
            targetDeviceNameProvider = { "Pixel 9" },
            clockMs = { clock.get() },
            pollIntervalMs = 60_000L,
        )
        try {
            scanner.setEnabled(true)
            assertEquals(ProximityEvent.APPROACH_WAKE, scanner.pollOnce())
            assertEquals(1, wakes.get())

            // Walk away: drop to -92 dBm
            simulatedRssi = -92
            clock.addAndGet(5_000L)
            assertEquals(null, scanner.pollOnce()) // grace timer started
            assertEquals(0, locks.get())

            clock.addAndGet(16_000L)
            assertEquals(ProximityEvent.WALK_AWAY_LOCK, scanner.pollOnce())
            assertEquals(1, locks.get())
        } finally {
            scanner.close()
        }
    }
}
