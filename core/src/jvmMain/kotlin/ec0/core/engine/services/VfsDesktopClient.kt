/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 — desktop-side VFS client: request builders, reply parsing and the
 * download chunk assembler. Pure — fixture-tested. The Ec0EngineNode reply
 * routing (registering the service's handleControlEnvelope) is a documented
 * handoff: Ec0EngineNode is a protected file.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.VfsChunkReply
import ec0.core.engine.protocol.VfsDirReply
import ec0.core.engine.protocol.VfsEntryDto
import ec0.core.engine.protocol.VfsListRequest
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.Json

object VfsDesktopCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeListRequest(path: String): String = json.encodeToString(VfsListRequest.serializer(), VfsListRequest(path))

    fun encodeDownloadRequest(path: String): String =
        json.encodeToString(VfsDownloadRequest.serializer(), ec0.core.engine.protocol.VfsDownloadRequest(path))

    fun decodeDirReply(body: String): VfsDirReply? = runCatching { json.decodeFromString(VfsDirReply.serializer(), body) }.getOrNull()

    fun decodeChunkReply(body: String): VfsChunkReply? = runCatching { json.decodeFromString(VfsChunkReply.serializer(), body) }.getOrNull()
}

/**
 * Assembles an offset-ordered download out of vfs_chunk replies. Duplicates
 * at the same offset are idempotent; assembly completes only when the eof
 * chunk closes every gap.
 */
class DownloadChunkAssembler {
    private val chunks = sortedMapOf<Long, ByteArray>()
    private var eofSeen = false
    private var eofEnd = Long.MIN_VALUE

    val complete: Boolean
        get() = eofSeen && isContiguousThroughEof()

    val totalBytes: Long
        get() = chunks.values.sumOf { it.size.toLong() }

    fun feed(offset: Long, eof: Boolean, bytes: ByteArray) {
        if (offset < 0) return
        chunks[offset] = bytes
        if (eof) {
            eofSeen = true
            eofEnd = offset + bytes.size
        }
    }

    /** Concatenated bytes when complete (contiguous from 0 through the eof chunk), else null. */
    fun assembled(): ByteArray? {
        if (!isContiguousThroughEof()) return null
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        for ((chunkOffset, bytes) in chunks) {
            if (chunkOffset != offset) return null
            out.write(bytes)
            offset += bytes.size
        }
        return out.toByteArray()
    }

    private fun isContiguousThroughEof(): Boolean {
        if (!eofSeen) return false
        var expected = 0L
        for ((offset, bytes) in chunks) {
            if (offset != expected) return false
            expected += bytes.size
        }
        return expected == eofEnd
    }
}
