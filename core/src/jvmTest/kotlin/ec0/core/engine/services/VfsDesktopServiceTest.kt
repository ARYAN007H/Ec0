/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 tests — vfs_dir parsing, chunk assembly (out-of-order, duplicate, gap,
 * EOF completion), envelope dispatch, and download landing on disk.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VfsDesktopServiceTest {

    private fun freshService() = VfsDesktopService(destinationDir = Files.createTempDirectory("ec0-vfs"))

    private fun envelope(bodyType: String, body: kotlinx.serialization.json.JsonObject) =
        ec0.core.engine.Ec0Envelope(
            msgType = "info",
            msgBody = buildJsonObject {
                put("info_type", bodyType)
                put("info_body", body)
            },
            srcRole = "phone",
            category = ec0.core.engine.protocol.VfsProtocol.CATEGORY_VFS,
            timestamp = 0L,
            seq = 0L,
            nonce = 0L,
            sig = "test",
        )

    @Test
    fun dirReplyPopulatesBrowseState() {
        val service = freshService()
        val body = buildJsonObject {
            put("path", "/storage/emulated/0/DCIM")
            put("entries", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("name", "Camera")
                    put("is_dir", true)
                    put("size", 0)
                })
                add(buildJsonObject {
                    put("name", "a.jpg")
                    put("is_dir", false)
                    put("size", 12345)
                })
            })
        }
        assertTrue(service.handleControlEnvelope(envelope(VfsProtocol.INFO_VFS_DIR, body)))
        val reply = service.browseState.value!!
        assertEquals("/storage/emulated/0/DCIM", reply.path)
        assertEquals(2, reply.entries.size)
        assertEquals("Camera", reply.entries[0].name)
        assertTrue(reply.entries[0].isDir)
        assertEquals(12345L, reply.entries[1].size)
    }

    @Test
    fun nonVfsEnvelopeReturnsFalse() {
        val service = freshService()
        val body = buildJsonObject { put("whatever", 1) }
        assertTrue(!service.handleControlEnvelope(envelope("some_other_type", body)))
    }

    @Test
    fun downloadAssemblesOutOfOrderChunksAndLandsOnDisk() {
        val service = freshService()
        val chunkA = "hello ".toByteArray()
        val chunkB = "world".toByteArray()
        val sent = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        val sender = { cmd: String, body: kotlinx.serialization.json.JsonObject -> sent.add(cmd to body) }

        // out-of-order + a duplicate of the second chunk
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_CHUNK, buildJsonObject {
                put("path", "/pc/readme.txt"); put("offset", 6L); put("eof", true)
                put("buffer_b64", java.util.Base64.getEncoder().encodeToString(chunkB))
            }),
        )
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_CHUNK, buildJsonObject {
                put("path", "/pc/readme.txt"); put("offset", 6L); put("eof", true)
                put("buffer_b64", java.util.Base64.getEncoder().encodeToString(chunkB))
            }),
        )
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_CHUNK, buildJsonObject {
                put("path", "/pc/readme.txt"); put("offset", 0L); put("eof", false)
                put("buffer_b64", java.util.Base64.getEncoder().encodeToString(chunkA))
            }),
        )

        // gap at offset 0 — no completion, no error, no landed bytes (honest in-progress state)
        assertNull(service.error.value)
        val landed = service.downloadBytes.value
        assertEquals("hello world", String(landed!!.second), "assembled bytes")
        assertTrue(landed.first.endsWith("readme.txt"), "landed path = ${landed.first}")
        assertEquals("hello world", Files.readString(java.nio.file.Path.of(landed.first)), "file on disk")
        assertEquals("vfs_download", sent.single().first)
    }

    @Test
    fun gapKeepsDownloadIncomplete() {
        val service = freshService()
        service.download({ _, _ -> }, path = "/pc/big.bin")
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_CHUNK, buildJsonObject {
                put("path", "/pc/big.bin"); put("offset", 10L); put("eof", false)
                put("buffer_b64", java.util.Base64.getEncoder().encodeToString("late".toByteArray()))
            }),
        )
        assertNull(service.downloadBytes.value) // gap at offset 0 — never complete
    }

    @Test
    fun vfsErrorSurfaces() {
        val service = freshService()
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_ERROR, buildJsonObject {
                put("path", "/root/secret")
                put("message", "path outside granted roots")
            }),
        )
        assertEquals("path outside granted roots", service.error.value)
    }
}
