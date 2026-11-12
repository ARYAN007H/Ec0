package ec0.core.localsend.dto

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import kotlinx.serialization.Serializable

/**
 * Device registration payload — POST /api/localsend/v2/register request and
 * the `info` sub-object of prepare-upload.
 *
 * Reference: `packages/core/src/http/dto_v2.rs` (`RegisterDtoV2`).
 * `download` is `#[serde(default)]` without skip → always serialized.
 */
@Serializable
data class RegisterDtoV2(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    /** u16 in the reference. */
    val port: Int,
    val protocol: ProtocolType,
    val download: Boolean = false,
)

/**
 * Response of POST /api/localsend/v2/register (no port/protocol echoed).
 * `fingerprint` defaults to "" but is always serialized (reference:
 * `RegisterResponseDtoV2`).
 */
@Serializable
data class RegisterResponseDtoV2(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String = "",
    val download: Boolean = false,
)

/**
 * Response of GET /api/localsend/v2/info; also the `info` field of
 * PrepareDownloadResponseDtoV2 (reference: `InfoResponseDtoV2`).
 */
@Serializable
data class InfoResponseDtoV2(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    val download: Boolean = false,
)
