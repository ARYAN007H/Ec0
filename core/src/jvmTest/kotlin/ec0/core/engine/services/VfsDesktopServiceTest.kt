/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 tests — vfs_dir parsing, chunk assembly (out-of-order, duplicate, gap,
 * EOF completion), envelope dispatch, and download landing on disk.
 */
package ec0.core.engine.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class VfsDesktopServiceTest {

    private val service = VfsDesktopService(destinationDir = Files.createTempDirectory("ec0-vfs"))

    private fun envelope(bodyType: String, body: kotlinx.serialization.json.JsonObject) =
        Json.decodeFromString(
            ec0.core.engine.Ec0Envelope.serializer(),
            buildJsonObject {
                put("msg_type", "info")
                put("info_type", bodyType)
                put("info_body", body)
                put("src_role", "phone")
                put("timestamp", 0L)
            }.toString(),
        )

    @Test
    fun dirReplyPopulatesBrowseState() {
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
        val body = buildJsonObject { put("whatever", 1) }
        assertTrue(!service.handleControlEnvelope(envelope("some_other_type", body)))
    }

    @Test
    fun downloadAssemblesOutOfOrderChunksAndLandsOnDisk() {
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

        assertNull(service.browseState.value.error)
        val landed = service.downloadBytes.value
        assertEquals("hello world", String(landed!!.second))
        assertTrue(landed.first.endsWith("readme.txt"))
        assertEquals("hello world", Files.readString(java.nio.file.Path.of(landed.first)))
        assertEquals("vfs_download", sent.single().first)
    }

    @Test
    fun gapKeepsDownloadIncomplete() {
        val service2 = VfsDesktopService(destinationDir = Files.createTempDirectory("ec0-vfs"))
        service2.download({ _, _ -> }, path = "/pc/big.bin")
        service2.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_CHUNK, buildJsonObject {
                put("path", "/pc/big.bin"); put("offset", 10L); put("eof", false)
                put("buffer_b64", java.util.Base64.getEncoder().encodeToString("late".toByteArray()))
            }),
        )
        assertNull(service2.downloadBytes.value) // gap at offset 0 — never complete
    }

    @Test
    fun vfsErrorSurfaces() {
        service.handleControlEnvelope(
            envelope(VfsProtocol.INFO_VFS_ERROR, buildJsonObject {
                put("path", "/root/secret")
                put("message", "path outside granted roots")
            }),
        )
        assertEquals("path outside granted roots", service.error.value)
    }
}
