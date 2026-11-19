/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 — desktop VFS service: sends browse/download requests to the phone's
 * VFS receiver and consumes the replies (vfs_dir → listing state,
 * vfs_chunk → download assembler, vfs_error → honest error state).
 *
 * HANDOFF (protected file): the reply routing one-liner
 * `if (vfsDesktopService.handleControlEnvelope(envelope)) return` belongs in
 * Ec0EngineNode's dispatch chain (Ec0EngineNode.kt:696-703) — agent-0 applies
 * it when merging (same pattern as the Slice-9 service registrations).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.VfsDirReply
import ec0.core.engine.protocol.VfsPathGuard
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

class VfsDesktopService(
    private val destinationDir: java.nio.file.Path = java.nio.file.Path.of(
        System.getProperty("user.home"), "Downloads",
    ),
    /** Bounded in-memory assembly per download (walled-garden cap). */
    private val maxDownloadBytes: Long = DownloadChunkAssembler.DEFAULT_MAX_BYTES,
    /** vfs reply limiter — a compromised peer cannot flood the assembler/disk. */
    private val replyLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(
        capacityPerPeer = DEFAULT_REPLY_BURST,
        refillPerSecond = DEFAULT_REPLY_REFILL_PER_SECOND,
    ),
) {
    companion object {
        const val DEFAULT_REPLY_BURST: Long = 512
        const val DEFAULT_REPLY_REFILL_PER_SECOND: Long = 256

        private val HANDLED_REPLY_TYPES = setOf(
            VfsProtocol.INFO_VFS_DIR,
            VfsProtocol.INFO_VFS_CHUNK,
            VfsProtocol.INFO_VFS_ERROR,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val assemblerField = java.util.concurrent.atomic.AtomicReference(DownloadChunkAssembler(maxDownloadBytes))
    private val _browseState = MutableStateFlow<VfsDirReply?>(null)
    val browseState: StateFlow<VfsDirReply?> = _browseState.asStateFlow()

    private val _downloadBytes = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val downloadBytes: StateFlow<Pair<String, ByteArray>?> = _downloadBytes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastDownloadedPath = MutableStateFlow<String?>(null)
    val lastDownloadedPath: StateFlow<String?> = _lastDownloadedPath.asStateFlow()

    /** Dropped reply counter (rate-limit) — honest telemetry for the UI. */
    val droppedReplyCount: Long
        get() = droppedReplies.get()

    private val droppedReplies = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var downloadPath: String? = null

    /** Builds the vfs_list request body (cmdBody JSON element). */
    fun buildListBody(path: String): JsonObject = buildJsonObject { put("path", path) }

    /** Builds the vfs_download request body. */
    fun buildDownloadBody(path: String): JsonObject = buildJsonObject { put("path", path) }

    /** Sends a browse request over the injected sender; writes the pending path. */
    fun browse(sender: (cmdType: String, body: JsonObject) -> Unit, path: String) {
        _error.value = null
        sender(VfsProtocol.CMD_VFS_LIST, buildListBody(path))
    }

    /** Sends a download request; chunks accumulate into the assembler. */
    fun download(sender: (cmdType: String, body: JsonObject) -> Unit, path: String) {
        _error.value = null
        resetAssembler()
        downloadPath = path
        sender(VfsProtocol.CMD_VFS_DOWNLOAD, buildDownloadBody(path))
    }

    private fun resetAssembler() {
        assemblerField.getAndSet(DownloadChunkAssembler(maxDownloadBytes))
    }

    /**
     * Envelope dispatch hook — returns true when the envelope was a VFS reply
     * (registered in Ec0EngineNode's chain via the control-envelope interceptor).
     *
     * Walled-garden hardening: every reply is rate-limited; the landed file is
     * name-sanitized via [VfsPathGuard.safeDownloadFileName] AND verified with a
     * canonical-path containment check against [destinationDir] before any write;
     * assembler overflow (a peer streaming an unbounded "download") fails the
     * download honestly instead of growing the heap.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val bodyType = envelope.bodyType ?: envelope.msgType
        if (bodyType !in HANDLED_REPLY_TYPES) {
            return false
        }
        if (!replyLimiter.tryAcquire("vfs-replies", units = 1)) {
            droppedReplies.incrementAndGet()
            return true
        }
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return true
        return when (bodyType) {
            VfsProtocol.INFO_VFS_DIR -> {
                val path = (body["path"] as? JsonPrimitive)?.content ?: return true
                val entries = (body["entries"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { entry ->
                    val obj = entry as? JsonObject ?: return@mapNotNull null
                    ec0.core.engine.protocol.VfsEntryDto(
                        name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                        isDir = (obj["is_dir"] as? JsonPrimitive)?.content == "true" ||
                            (obj["is_dir"] as? JsonPrimitive)?.content == "1",
                        size = (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                    )
                } ?: emptyList()
                _browseState.value = ec0.core.engine.protocol.VfsDirReply(path = path, entries = entries)
                true
            }
            VfsProtocol.INFO_VFS_CHUNK -> {
                val offset = (body["offset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val eof = (body["eof"] as? JsonPrimitive)?.content == "true"
                val bufferB64 = (body["buffer_b64"] as? JsonPrimitive)?.content ?: ""
                val current = assemblerField.get()
                current.feed(offset, eof, Base64.getMimeDecoder().decode(bufferB64))
                if (current.overflowed) {
                    _error.value = "vfs download exceeded the ${maxDownloadBytes}B safety cap — aborted"
                    resetAssembler()
                    return true
                }
                if (current.complete) {
                    val bytes = current.assembled() ?: ByteArray(0)
                    val target = resolveContainedTarget(downloadPath ?: "vfs_download")
                    if (target == null) {
                        _error.value = "download filename rejected by containment guard"
                        resetAssembler()
                        return true
                    }
                    target.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
                    java.nio.file.Files.write(
                        target,
                        bytes,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    )
                    _downloadBytes.value = target.toString() to bytes
                    _lastDownloadedPath.value = target.toString()
                    resetAssembler()
                }
                true
            }
            VfsProtocol.INFO_VFS_ERROR -> {
                _error.value = (body["message"] as? JsonPrimitive)?.content ?: "VFS error"
                true
            }
            else -> false
        }
    }

    /**
     * Canonical containment check (H2): the sanitized basename is resolved
     * against [destinationDir] with symlink-following canonicalization and must
     * land inside it — a `..`/absolute/hidden-path trick can never escape.
     * Returns null when the name is rejected.
     */
    private fun resolveContainedTarget(rawDownloadPath: String): java.nio.file.Path? {
        val safe = VfsPathGuard.safeDownloadFileName(rawDownloadPath) ?: return null
        val rootCanonical = destinationDir.toFile().canonicalFile
        val candidate = rootCanonical.resolve(safe).canonicalFile
        if (!ec0.core.engine.protocol.VfsPathGuard.isInsideRoot(
                canonicalRoot = rootCanonical.toString(),
                canonicalCandidate = candidate.toString(),
            )
        ) {
            return null
        }
        return candidate.toPath()
    }
}
