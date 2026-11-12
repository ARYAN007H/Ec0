package ec0.core.synergy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Synergy control-plane envelope, reverse-engineered from
 * com.oplus.linker 17.7.35 (protocol/SynergyMessage.java).
 *
 * All fields are the JSON keys the wire format uses. Messages are signed:
 * [signature] covers the body using key material exchanged during pairing,
 * with [noncestr] + [timestamp] guarding replay.
 */
@Serializable
data class SynergyMessage(
    @SerialName("cmd") val cmd: String,
    @SerialName("info") val info: SynergyInfo? = null,
    @SerialName("msg_body") val msgBody: String? = null,
    @SerialName("msg_type") val msgType: Int,
    @SerialName("noncestr") val nonceStr: String,
    @SerialName("signature") val signature: String,
    @SerialName("src_role") val srcRole: String,
    @SerialName("timestamp") val timestamp: Long,
)

/** Synergy info block (protocol/SynergyInfo.java): info_body/info_type/info_value. */
@Serializable
data class SynergyInfo(
    @SerialName("info_body") val infoBody: String? = null,
    @SerialName("info_type") val infoType: String,
    @SerialName("info_value") val infoValue: String? = null,
)
