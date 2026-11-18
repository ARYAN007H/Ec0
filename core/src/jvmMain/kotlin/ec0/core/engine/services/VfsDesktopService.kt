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
import ec0.core.engine.protocol.VfsDirReply
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
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val assemblerField = java.util.concurrent.atomic.AtomicReference(DownloadChunkAssembler())
    private val _browseState = MutableStateFlow<VfsDirReply?>(null)
    val browseState: StateFlow<VfsDirReply?> = _browseState.asStateFlow()

    private val _downloadBytes = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val downloadBytes: StateFlow<Pair<String, ByteArray>?> = _downloadBytes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var downloadPath: String? = null

    /** Builds the vfs_list request body (cmdBody JSON element). */
    fun buildListBody(path: String): JsonObject = buildJsonObject { put("path", path) }

    /** Builds the vfs_download request body. */
    fun buildDownloadBody(path: String): JsonObject = buildJsonObject { put("path", path) }

    /** Sends a browse request over the injected sender; writes the pending path. */
    fun browse(sender: (cmdType: String, body: JsonObject) -> Unit, path: String) {
        _browseState.value = null
        sender(VfsProtocol.CMD_VFS_LIST, buildListBody(path))
    }

    /** Sends a download request; chunks accumulate into the assembler. */
    fun download(sender: (cmdType: String, body: JsonObject) -> Unit, path: String) {
        resetAssembler()
        downloadPath = path
        sender(VfsProtocol.CMD_VFS_DOWNLOAD, buildDownloadBody(path))
    }

    private fun resetAssembler() {
        assemblerField.getAndSet(DownloadChunkAssembler())
    }

    /**
     * Envelope dispatch hook — returns true when the envelope was a VFS reply
     * (agent-0 registers this in Ec0EngineNode's chain).
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val bodyType = envelope.bodyType ?: envelope.msgType
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
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
                val path = (body["path"] as? JsonPrimitive)?.content ?: return true
                val offset = (body["offset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val eof = (body["eof"] as? JsonPrimitive)?.content == "true"
                val bufferB64 = (body["buffer_b64"] as? JsonPrimitive)?.content ?: ""
                val current = assemblerField.get()
                current.feed(offset, eof, Base64.getMimeDecoder().decode(bufferB64))
                if (current.complete) {
                    val bytes = current.assembled() ?: ByteArray(0)
                    val safe = downloadPath?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "vfs_download"
                    val target = destinationDir.resolve(safe)
                    target.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
                    java.nio.file.Files.write(
                        target,
                        bytes,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    )
                    _downloadBytes.value = target.toString() to bytes
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
}
