package ec0.core.localsend.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * File metadata exchanged in prepare-upload / prepare-download.
 *
 * Reference: `packages/core/src/model/transfer.rs` (`FileDto`,
 * `FileMetadata`) — `#[serde(rename_all = "camelCase")]`, options skipped
 * when absent.
 */
@Serializable
data class FileDto(
    val id: String,
    @SerialName("fileName") val fileName: String,
    /** u64 in the reference; Long covers realistic file sizes. */
    val size: Long,
    @SerialName("fileType") val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileMetadata? = null,
)

/** Optional timestamp metadata (ISO-8601 strings, shapes only). */
@Serializable
data class FileMetadata(
    val modified: String? = null,
    val accessed: String? = null,
)
