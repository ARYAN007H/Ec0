package ec0.core.localsend.dto

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import kotlinx.serialization.Serializable

/**
 * UDP multicast announcement message (v2.2) — sent to :53317, never a
 * response (devices answer over HTTP).
 *
 * Reference: `packages/core/src/model/discovery.rs` (`MulticastMessageV2`).
 * Fingerprint semantics: SHA-256 of the certificate in HTTPS mode, a random
 * string in HTTP mode.
 */
@Serializable
data class MulticastMessageV2(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    val port: Int,
    val protocol: ProtocolType,
    val download: Boolean = false,
)
