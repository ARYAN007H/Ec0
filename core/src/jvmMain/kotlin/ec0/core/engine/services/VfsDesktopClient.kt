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
import ec0.core.engine.protocol.VfsDownloadRequest
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
 *
 * Walled-garden hardening: [maxBytes] bounds the retained heap for one
 * download — a compromised peer streaming unique-offset chunks with no EOF
 * used to grow this map without limit (OOM). Chunks past the cap flip
 * [overflowed] and are dropped; callers must surface an honest error state.
 */
class DownloadChunkAssembler(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        /** 2 GiB ceiling for a single vfs download assembled in memory. */
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

        /** Largest single chunk accepted (bounds one envelope's decoded payload). */
        const val MAX_CHUNK_BYTES: Int = 8 * 1024 * 1024
    }

    private val chunks = sortedMapOf<Long, ByteArray>()
    private var eofSeen = false
    private var eofEnd = Long.MIN_VALUE
    private var retainedBytes = 0L

    val complete: Boolean
        get() = !overflowed && eofSeen && isContiguousThroughEof()

    val totalBytes: Long
        get() = retainedBytes

    /** Set when a feed was rejected for exceeding [maxBytes] or [MAX_CHUNK_BYTES]. */
    var overflowed: Boolean = false
        private set

    fun feed(offset: Long, eof: Boolean, bytes: ByteArray) {
        if (offset < 0) return
        if (bytes.size > MAX_CHUNK_BYTES) {
            overflowed = true
            return
        }
        // A duplicate offset REPLACES the retained chunk (idempotent resends),
        // so its old bytes come off the counter before the cap check.
        val replaced = chunks[offset]?.size?.toLong() ?: 0L
        if (retainedBytes - replaced + bytes.size.toLong() > maxBytes) {
            overflowed = true
            return
        }
        chunks[offset] = bytes
        retainedBytes += bytes.size.toLong() - replaced
        if (eof) {
            eofSeen = true
            eofEnd = offset + bytes.size
        }
    }

    /** Concatenated bytes when complete (contiguous from 0 through the eof chunk), else null. */
    fun assembled(): ByteArray? {
        if (overflowed || !isContiguousThroughEof()) return null
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
