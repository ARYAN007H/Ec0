/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Wire models and path-traversal sanitizer for ec0-engine Chunked Resumable
 * File & Folder Transfer (`category = "file"`, docs/engine/design.md §6.2).
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineConstants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class FileTransferState {
    OFFERED,
    TRANSFERRING,
    COMPLETED,
    CANCELLED,
    FAILED;

    val isTerminal: Boolean
        get() = this in FINISHED_STATES

    companion object {
        val FINISHED_STATES: Set<FileTransferState> = setOf(COMPLETED, CANCELLED, FAILED)
    }
}

data class FileEntryManifest(
    val fileId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val lastModifiedMs: Long,
    val streamId: Int,
) {
    init {
        val sanitized = FileTransferProtocol.sanitizeRelativePath(relativePath)
        require(sanitized == relativePath) {
            "FileEntryManifest relativePath must be canonical and sanitized (got '$relativePath')"
        }
        require(sizeBytes >= 0L) { "sizeBytes must be non-negative" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid file sha256 hex: $sha256"
        }
    }

    fun totalChunks(chunkSize: Int): Int {
        if (sizeBytes == 0L) return 1
        return ((sizeBytes + chunkSize.toLong() - 1L) / chunkSize.toLong()).toInt()
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("file_id", fileId)
        put("rel_path", relativePath)
        put("size_bytes", sizeBytes)
        put("sha256", sha256.lowercase())
        put("mtime_ms", lastModifiedMs)
        put("stream_id", streamId)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FileEntryManifest {
            val fileId = (obj["file_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'file_id'")
            val rawPath = (obj["rel_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'rel_path'")
            val sanitizedPath = FileTransferProtocol.sanitizeRelativePath(rawPath)
            val sizeBytes = (obj["size_bytes"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Missing 'size_bytes'")
            val sha256 = (obj["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'sha256'")
            val mtimeMs = (obj["mtime_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
            val streamId = (obj["stream_id"] as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("Missing 'stream_id'")

            return FileEntryManifest(
                fileId = fileId,
                relativePath = sanitizedPath,
                sizeBytes = sizeBytes,
                sha256 = sha256.lowercase(),
                lastModifiedMs = mtimeMs,
                streamId = streamId,
            )
        }
    }
}

data class FileOfferCommand(
    val transferId: String,
    val chunkSize: Int = EngineConstants.DEFAULT_FILE_CHUNK_BYTES,
    val files: List<FileEntryManifest>,
) {
    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(chunkSize in 1024..(4 * 1024 * 1024)) { "chunkSize out of range: $chunkSize" }
        require(files.isNotEmpty()) { "FileOfferCommand must contain at least one file" }
    }

    val totalBytes: Long
        get() = files.sumOf { it.sizeBytes }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("transfer_id", transferId)
        put("chunk_size", chunkSize)
        put(
            "files",
            buildJsonArray {
                for (f in files) add(f.toJsonObject())
            },
        )
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FileOfferCommand {
            val transferId = (obj["transfer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'transfer_id' in file_offer")
            val chunkSize = (obj["chunk_size"] as? JsonPrimitive)?.intOrNull
                ?: EngineConstants.DEFAULT_FILE_CHUNK_BYTES
            val filesArr = obj["files"] as? JsonArray
                ?: throw IllegalArgumentException("Missing 'files' array in file_offer")
            val files = filesArr.map { el -> FileEntryManifest.fromJsonObject(el.jsonObject) }
            return FileOfferCommand(transferId = transferId, chunkSize = chunkSize, files = files)
        }
    }
}

data class FileAcceptCommand(
    val transferId: String,
    val accepted: Boolean,
    val resumeFromChunk: Map<String, Int> = emptyMap(),
    val reason: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("transfer_id", transferId)
        put("accepted", accepted)
        put(
            "resume_from_chunk",
            buildJsonObject {
                for ((fileId, chunkIdx) in resumeFromChunk) {
                    put(fileId, chunkIdx)
                }
            },
        )
        if (reason.isNotEmpty()) {
            put("reason", reason)
        }
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FileAcceptCommand {
            val transferId = (obj["transfer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'transfer_id' in file_accept")
            val accepted = (obj["accepted"] as? JsonPrimitive)?.booleanOrNull
                ?: throw IllegalArgumentException("Missing 'accepted' in file_accept")
            val resumeObj = obj["resume_from_chunk"] as? JsonObject
            val resumeMap = linkedMapOf<String, Int>()
            if (resumeObj != null) {
                for ((k, v) in resumeObj) {
                    val idx = (v as? JsonPrimitive)?.intOrNull ?: 0
                    if (idx >= 0) resumeMap[k] = idx
                }
            }
            val reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return FileAcceptCommand(
                transferId = transferId,
                accepted = accepted,
                resumeFromChunk = resumeMap,
                reason = reason,
            )
        }
    }
}

data class FileCompleteInfo(
    val transferId: String,
    val fileId: String,
    val sha256: String,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("transfer_id", transferId)
        put("file_id", fileId)
        put("sha256", sha256.lowercase())
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FileCompleteInfo {
            val transferId = (obj["transfer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'transfer_id' in file_complete")
            val fileId = (obj["file_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'file_id' in file_complete")
            val sha256 = (obj["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'sha256' in file_complete")
            return FileCompleteInfo(transferId, fileId, sha256.lowercase())
        }
    }
}

data class FileCancelCommand(
    val transferId: String,
    val reason: String = "cancelled_by_user",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("transfer_id", transferId)
        put("reason", reason)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FileCancelCommand {
            val transferId = (obj["transfer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'transfer_id' in file_cancel")
            val reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return FileCancelCommand(transferId, reason)
        }
    }
}

object FileTransferProtocol {
    const val CATEGORY_FILE: String = "file"
    const val CMD_FILE_OFFER: String = "file_offer"
    const val CMD_FILE_ACCEPT: String = "file_accept"
    const val CMD_FILE_CANCEL: String = "file_cancel"
    const val INFO_FILE_COMPLETE: String = "file_complete"

    /**
     * Strictly validates and normalizes a relative file path for cross-device folder transfer.
     * Rejects empty paths, leading/trailing slashes, backslashes, Windows drive prefixes (`C:`),
     * control characters, `.` current-dir segments, and `..` parent traversal segments.
     */
    fun sanitizeRelativePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        require(trimmed.isNotEmpty()) { "File relativePath must not be empty" }
        require(!trimmed.startsWith('/') && !trimmed.startsWith('\\')) {
            "Absolute path prohibited in relativePath: '$rawPath'"
        }
        require(!trimmed.endsWith('/') && !trimmed.endsWith('\\')) {
            "Trailing slash prohibited in relativePath: '$rawPath'"
        }
        require(!trimmed.contains('\\')) {
            "Backslash prohibited in relativePath (use POSIX '/'): '$rawPath'"
        }
        require(!trimmed.contains(':')) {
            "Drive letter or colon prohibited in relativePath: '$rawPath'"
        }
        require(trimmed.none { it.code < 32 || it.code == 127 }) {
            "Control characters prohibited in relativePath"
        }

        val segments = trimmed.split('/')
        require(segments.isNotEmpty()) { "Empty path segments in '$rawPath'" }
        for (seg in segments) {
            require(seg.isNotEmpty() && seg != "." && seg != "..") {
                "Illegal path traversal segment '$seg' in '$rawPath'"
            }
        }
        return segments.joinToString("/")
    }
}
