package ec0.core.localsend.dto

import kotlinx.serialization.Serializable

/**
 * POST /api/localsend/v2/prepare-upload request body.
 * Reference: `packages/core/src/http/dto_v2.rs` (`PrepareUploadRequestDtoV2`).
 */
@Serializable
data class PrepareUploadRequestDtoV2(
    val info: RegisterDtoV2,
    /** file ID → file metadata. */
    val files: Map<String, FileDto>,
)

/**
 * POST /api/localsend/v2/prepare-upload response body — only accepted files
 * appear in [files] (file ID → file token).
 * Reference: `PrepareUploadResponseDtoV2`.
 */
@Serializable
data class PrepareUploadResponseDtoV2(
    val sessionId: String,
    val files: Map<String, String>,
)

/**
 * POST /api/localsend/v2/prepare-download response body (download API):
 * the sender's info + session + offered files.
 * Reference: `PrepareDownloadResponseDtoV2`.
 */
@Serializable
data class PrepareDownloadResponseDtoV2(
    val info: InfoResponseDtoV2,
    val sessionId: String,
    val files: Map<String, FileDto>,
)
