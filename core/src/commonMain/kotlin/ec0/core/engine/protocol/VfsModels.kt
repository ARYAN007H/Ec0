/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 — VFS wire models (PROPOSED — coordination entry in agentchat.md,
 * agent-1 implements the phone-side receiver against these exact shapes;
 * field names follow the established snake_case envelope style from
 * InputModels.kt / NotificationModels.kt).
 *
 * Flow: desktop browses the PHONE's storage scoped to the phone's granted
 * roots. Desktop sends cmd vfs_list / cmd vfs_download; the phone answers
 * with info vfs_dir / streamed info vfs_chunk; failures are info vfs_error.
 */
package ec0.core.engine.protocol

import kotlinx.serialization.Serializable

object VfsProtocol {
    const val CATEGORY_VFS: String = "vfs"
    const val CMD_VFS_LIST: String = "vfs_list"
    const val INFO_VFS_DIR: String = "vfs_dir"
    const val CMD_VFS_DOWNLOAD: String = "vfs_download"
    const val INFO_VFS_CHUNK: String = "vfs_chunk"
    const val INFO_VFS_ERROR: String = "vfs_error"
    const val INFO_VFS_ROOTS: String = "vfs_roots"
}

@Serializable
data class VfsListRequest(val path: String)

@Serializable
data class VfsEntryDto(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
)

@Serializable
data class VfsDirReply(
    val path: String,
    val entries: List<VfsEntryDto>,
)

@Serializable
data class VfsDownloadRequest(val path: String)

@Serializable
data class VfsChunkReply(
    val path: String,
    val offset: Long,
    val eof: Boolean,
    /** Base64 of the chunk bytes (offset-ordered, duplicates tolerated). */
    val bufferB64: String,
)

@Serializable
data class VfsErrorReply(
    val path: String? = null,
    val message: String,
)

