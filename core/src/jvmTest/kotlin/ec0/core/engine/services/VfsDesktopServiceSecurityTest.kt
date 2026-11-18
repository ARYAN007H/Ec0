/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Walled-garden VFS tests (audit H2): the download sink enforces canonical
 * containment, the chunk assembler refuses unbounded growth, and reply
 * flooding is rate-limited.
 */
package ec0.core.engine.services

import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VfsDesktopServiceSecurityTest {

    private fun freshService(
        replyLimiter: TokenBucketRateLimiter? = null,
        destinationDir: java.nio.file.Path = Files.createTempDirectory("ec0-vfs-sec"),
    ): Pair<VfsDesktopService, java.nio.file.Path> {
        val service = if (replyLimiter != null) {
            VfsDesktopService(destinationDir = destinationDir, replyLimiter = replyLimiter)
        } else {
            VfsDesktopService(destinationDir = destinationDir)
        }
        return service to destinationDir
    }

    private fun chunkEnvelope(offset: Long, eof: Boolean, bytes: ByteArray) =
        ec0.core.engine.Ec0Envelope(
            msgType = "info",
            msgBody = buildJsonObject {
                put("info_type", VfsProtocol.INFO_VFS_CHUNK)
                put("info_body", buildJsonObject {
                    put("path", "/phone/file.bin")
                    put("offset", offset)
                    put("eof", eof)
                    put("buffer_b64", java.util.Base64.getEncoder().encodeToString(bytes))
                })
            },
            srcRole = "phone",
            category = VfsProtocol.CATEGORY_VFS,
            timestamp = 0L,
            seq = 1L,
            nonce = 0L,
            sig = "test",
        )

    @Test
    fun dotDotDownloadPathIsContainedInsideDestination() {
        val (service, dir) = freshService()
        service.download({ _, _ -> }, path = "..") // hostile local path with no basename
        service.handleControlEnvelope(chunkEnvelope(0L, true, "payload".toByteArray()))
        assertNull(service.downloadBytes.value, "traversal target must never land")
        assertTrue(
            service.error.value?.contains("containment") == true,
            "honest containment error, got: ${service.error.value}",
        )
        // Nothing written outside the destination dir.
        val parent = dir.parent
        assertTrue(
            Files.list(parent).use { stream -> stream.noneMatch { it.fileName.toString().startsWith("payload") } },
        )
    }

    @Test
    fun normalDownloadStillLandsInsideDestination() {
        val (service, dir) = freshService()
        service.download({ _, _ -> }, path = "/phone/reports/report 2024.pdf")
        service.handleControlEnvelope(chunkEnvelope(0L, true, "hello-vfs".toByteArray()))
        val landed = service.downloadBytes.value
        assertEquals("report 2024.pdf", landed!!.first.substringAfterLast('/'))
        assertTrue(landed.first.startsWith(dir.toString()), "landed inside destinationDir")
        assertEquals("hello-vfs", String(landed.second))
    }

    @Test
    fun assemblerOverflowFailsDownloadHonestly() {
        val tiny = VfsDesktopService(
            destinationDir = Files.createTempDirectory("ec0-vfs-tiny"),
            maxDownloadBytes = 64L,
        )
        // 100-byte chunk against a 64-byte cap → overflow flag, honest error.
        tiny.handleControlEnvelope(chunkEnvelope(0L, true, ByteArray(100)))
        assertNull(tiny.downloadBytes.value)
        assertTrue(
            tiny.error.value?.contains("safety cap") == true,
            "overflow surfaces an error, got: ${tiny.error.value}",
        )
    }

    @Test
    fun chunkFloodCannotGrowMemoryBeyondCap() {
        val assembler = DownloadChunkAssembler(maxBytes = 1024L)
        val chunk = ByteArray(128)
        // 64 unique offsets x 128 B = 8 KiB — way past the 1 KiB cap.
        repeat(64) { i ->
            assembler.feed(offset = i.toLong() * 128L, eof = false, bytes = chunk)
        }
        assertTrue(assembler.overflowed)
        assertNull(assembler.assembled())
        assertTrue(assembler.totalBytes <= 1024L, "retained heap stays under the cap")
    }

    @Test
    fun duplicateOffsetsAreIdempotentUnderTheCap() {
        val assembler = DownloadChunkAssembler(maxBytes = 1024L)
        val chunk = ByteArray(128)
        repeat(100) {
            assembler.feed(offset = 0L, eof = false, bytes = chunk)
        }
        assertTrue(!assembler.overflowed, "resends of the same chunk do not trip the cap")
        assertEquals(128L, assembler.totalBytes)
    }

    @Test
    fun replyFloodIsRateLimited() {
        val (service, _) = freshService(
            replyLimiter = TokenBucketRateLimiter(
                capacityPerPeer = 5,
                refillPerSecond = 1,
                clockMs = { 0L },
            ),
        )
        var handled = 0
        repeat(50) {
            if (service.handleControlEnvelope(chunkEnvelope(0L, false, "x".toByteArray()))) handled++
        }
        assertEquals(50, handled, "VFS replies still count as handled (service owns the category)")
        assertEquals(45L, service.droppedReplyCount, "flood tail dropped by the limiter")
        assertNull(service.downloadBytes.value)
    }
}
