/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Tests for VfsPhoneService: virtual root enumeration, subdir listing,
 * chunked file download round-trip with VfsDesktopService, and security
 * enforcement (path traversal, symlink escape, permission gate, rate limit).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VfsPhoneServiceTest {

    private fun makeCmd(proc: SessionEnvelopeProcessor, cmdType: String, path: String): Ec0Envelope =
        proc.nextCmdEnvelope(
            cmdType = cmdType,
            cmdBody = buildJsonObject { put("path", path) },
            category = VfsProtocol.CATEGORY_VFS,
        )

    @Test
    fun listsRootsSubdirsAndStreamsChunkedDownloadIntoDesktopService() {
        val phoneRoot = Files.createTempDirectory("ec0-vfs-phone").toFile()
        val desktopDownloads = Files.createTempDirectory("ec0-vfs-desktop").toFile()
        try {
            val downloads = File(phoneRoot, "Downloads").apply { mkdirs() }
            val dcim = File(phoneRoot, "DCIM").apply { mkdirs() }
            val payload = ByteArray(110 * 1024) { (it % 251).toByte() }
            File(downloads, "sample.bin").writeBytes(payload)
            File(downloads, ".hidden").writeText("ignored")

            val phoneKey = Ed25519Identity.generate()
            val desktopKey = Ed25519Identity.generate()
            val desktopProc = SessionEnvelopeProcessor(desktopKey, phoneKey.deviceId, phoneKey.publicKeyRaw)
            val phoneProc = SessionEnvelopeProcessor(phoneKey, desktopKey.deviceId, desktopKey.publicKeyRaw)

            val phoneVfs = VfsPhoneService(
                rootsProvider = { mapOf("Downloads" to downloads, "DCIM" to dcim) },
                chunkSizeBytes = 32 * 1024,
            )
            val desktopVfs = VfsDesktopService(destinationDir = desktopDownloads.toPath())

            val relay: (String, JsonObject) -> Unit = { infoType, body ->
                val env = phoneProc.nextInfoEnvelope(infoType, body, category = VfsProtocol.CATEGORY_VFS)
                assertTrue(desktopVfs.handleControlEnvelope(env))
            }

            // 1. Virtual root `/` returns only granted root names
            assertTrue(phoneVfs.handleControlEnvelope(desktopKey.deviceId, makeCmd(desktopProc, VfsProtocol.CMD_VFS_LIST, "/"), relay))
            val rootListing = desktopVfs.browseState.value
            assertNotNull(rootListing)
            assertEquals("/", rootListing.path)
            assertEquals(listOf("DCIM", "Downloads"), rootListing.entries.map { it.name })
            assertTrue(rootListing.entries.all { it.isDir })

            // 2. Listing `/Downloads` hides dotfiles and exposes sample.bin
            assertTrue(phoneVfs.handleControlEnvelope(desktopKey.deviceId, makeCmd(desktopProc, VfsProtocol.CMD_VFS_LIST, "/Downloads"), relay))
            val dlListing = desktopVfs.browseState.value
            assertNotNull(dlListing)
            assertEquals("/Downloads", dlListing.path)
            assertEquals(listOf("sample.bin"), dlListing.entries.map { it.name })

            // 3. Download `/Downloads/sample.bin` streams multi-chunk and lands cleanly on disk
            desktopVfs.download(
                sender = { cmdType, body ->
                    val env = desktopProc.nextCmdEnvelope(cmdType, body, category = VfsProtocol.CATEGORY_VFS)
                    assertTrue(phoneVfs.handleControlEnvelope(desktopKey.deviceId, env, relay))
                },
                path = "/Downloads/sample.bin",
            )
            assertNull(desktopVfs.error.value)
            val landed = File(desktopDownloads, "sample.bin")
            assertTrue(landed.isFile)
            assertContentEquals(payload, landed.readBytes())
            assertEquals(landed.canonicalPath, desktopVfs.lastDownloadedPath.value)
        } finally {
            phoneRoot.deleteRecursively()
            desktopDownloads.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalSymlinkEscapeAndUnpermittedAccess() {
        val phoneRoot = Files.createTempDirectory("ec0-vfs-sec").toFile()
        val secretOutside = Files.createTempDirectory("ec0-vfs-secret").toFile()
        try {
            val downloads = File(phoneRoot, "Downloads").apply { mkdirs() }
            val secretFile = File(secretOutside, "private.key").apply { writeText("TOP-SECRET") }
            val linkInside = File(downloads, "escape.txt").toPath()
            Files.createSymbolicLink(linkInside, secretFile.toPath())

            val phoneKey = Ed25519Identity.generate()
            val desktopKey = Ed25519Identity.generate()
            val desktopProc = SessionEnvelopeProcessor(desktopKey, phoneKey.deviceId, phoneKey.publicKeyRaw)

            val errors = mutableListOf<String>()
            val capture: (String, JsonObject) -> Unit = { infoType, body ->
                if (infoType == VfsProtocol.INFO_VFS_ERROR) {
                    errors += body["message"].toString()
                }
            }

            val phoneVfs = VfsPhoneService(rootsProvider = { mapOf("Downloads" to downloads) })

            // Path traversal attempt
            phoneVfs.handleControlEnvelope(
                desktopKey.deviceId,
                makeCmd(desktopProc, VfsProtocol.CMD_VFS_DOWNLOAD, "/Downloads/../../etc/passwd"),
                capture,
            )
            // Symlink escaping granted root
            phoneVfs.handleControlEnvelope(
                desktopKey.deviceId,
                makeCmd(desktopProc, VfsProtocol.CMD_VFS_DOWNLOAD, "/Downloads/escape.txt"),
                capture,
            )
            assertEquals(2, errors.size)
            assertTrue(errors.all { it.contains("outside granted roots") })

            // Denied storage permission
            val deniedVfs = VfsPhoneService(
                rootsProvider = { mapOf("Downloads" to downloads) },
                accessGranted = { false },
            )
            val deniedErrors = mutableListOf<String>()
            deniedVfs.handleControlEnvelope(
                desktopKey.deviceId,
                makeCmd(desktopProc, VfsProtocol.CMD_VFS_LIST, "/"),
                { infoType, body -> if (infoType == VfsProtocol.INFO_VFS_ERROR) deniedErrors += body["message"].toString() },
            )
            assertEquals(1, deniedErrors.size)
            assertTrue(deniedErrors.first().contains("not granted"))

            // Rate limiter caps floods
            val limitedVfs = VfsPhoneService(
                rootsProvider = { mapOf("Downloads" to downloads) },
                requestLimiter = TokenBucketRateLimiter(capacityPerPeer = 1, refillPerSecond = 1, clockMs = { 1_000L }),
            )
            val limitedErrors = mutableListOf<String>()
            repeat(2) {
                limitedVfs.handleControlEnvelope(
                    desktopKey.deviceId,
                    makeCmd(desktopProc, VfsProtocol.CMD_VFS_LIST, "/"),
                    { infoType, body -> if (infoType == VfsProtocol.INFO_VFS_ERROR) limitedErrors += body["message"].toString() },
                )
            }
            assertEquals(1, limitedErrors.size)
            assertTrue(limitedErrors.first().contains("rate limit"))
        } finally {
            phoneRoot.deleteRecursively()
            secretOutside.deleteRecursively()
        }
    }
}
