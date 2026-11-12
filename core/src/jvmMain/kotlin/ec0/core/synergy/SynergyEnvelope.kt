/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Synergy control-plane envelope — wire-accurate codec reverse-engineered
 * from com.oplus.linker 17.7.35 (protocol/SynergyMessage.java + the base
 * send path at base/socket/WebSocketConnectManager.java:558).
 *
 * NOTE: this is the wire truth. The commonMain `SynergyMessage` data class
 * predates the T2 catalog review and diverges from the verified schema
 * (msg_type is a STRING "cmd"/"info", noncestr/signature are LONGs, and the
 * envelope carries `category`) — flagged for agent-0 in the hub log. The
 * transport (this module) speaks ONLY the format below.
 *
 * Base-path signing (WSCM:558, verbatim semantics):
 *   src_role  = "synergy"                       (Config.SYNERGY_NOTIFICATION_CHANNEL_NAME)
 *   timestamp = System.currentTimeMillis()
 *   noncestr  = new Random(timestamp).nextLong()  — java.util.Random, SEEDED
 *   signature = 0x0FFFFFFF (268435455)            — constant on this path
 * The real signature scheme for non-notification message classes is a T4
 * unknown; the codec allows overriding both fields.
 */
package ec0.core.synergy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Current protocol schema — see docs/re/synergy-catalog.yaml (T2, 76/76 verified). */
object SynergyEnvelopeCodec {

    /** Base-path signature constant (WSCM:558 — setSignature(268435455L)). */
    const val SIGNATURE_BASE_PATH: Long = 0x0FFFFFFF

    /** Base-path src_role (WSCM:558 — setSrcRole(Config.SYNERGY_NOTIFICATION_CHANNEL_NAME)). */
    const val BASE_SRC_ROLE: String = "synergy"

    /** Envelope message types (SynergyMessage.java: TYPE_CMD / TYPE_INFO). */
    const val TYPE_CMD: String = "cmd"
    const val TYPE_INFO: String = "info"

    internal val json: Json = Json {
        ignoreUnknownKeys = true // upstream Gson is lenient on unknown keys
        encodeDefaults = false   // Gson omits nulls — do the same on the wire
        explicitNulls = false
    }

    /**
     * Base-path signing material for a given wall-clock timestamp. The nonce
     * is DETERMINISTIC (java.util.Random seeded with the timestamp — upstream
     * `new Random(jCurrentTimeMillis).nextLong()`), which makes it testable.
     */
    fun baseSignedMeta(timestampMs: Long): SignedMeta =
        SignedMeta(
            timestamp = timestampMs,
            nonceStr = java.util.Random(timestampMs).nextLong(),
            signature = SIGNATURE_BASE_PATH,
        )

    /** Builds a `cmd` envelope per the SynergyCmd schema (cmd_type/cmd_body/cmd_value/reason). */
    fun cmdEnvelope(
        cmdType: String,
        cmdBody: JsonElement? = null,
        cmdValue: Int = -1,
        reason: String = "",
        category: String? = null,
        signed: SignedMeta = baseSignedMeta(System.currentTimeMillis()),
    ): SynergyEnvelope = SynergyEnvelope(
        msgType = TYPE_CMD,
        msgBody = buildJsonObject {
            put("cmd_type", cmdType)
            if (cmdBody != null) put("cmd_body", cmdBody)
            put("cmd_value", cmdValue)
            put("reason", reason)
        },
        category = category,
        timestamp = signed.timestamp,
        nonceStr = signed.nonceStr,
        signature = signed.signature,
    )

    /** Builds an `info` envelope per the SynergyInfo schema (info_type/info_body/info_value). */
    fun infoEnvelope(
        infoType: String,
        infoBody: JsonElement? = null,
        infoValue: Int = 0,
        category: String? = null,
        signed: SignedMeta = baseSignedMeta(System.currentTimeMillis()),
    ): SynergyEnvelope = SynergyEnvelope(
        msgType = TYPE_INFO,
        msgBody = buildJsonObject {
            put("info_type", infoType)
            if (infoBody != null) put("info_body", infoBody)
            put("info_value", infoValue)
        },
        category = category,
        timestamp = signed.timestamp,
        nonceStr = signed.nonceStr,
        signature = signed.signature,
    )

    fun encode(envelope: SynergyEnvelope): String = json.encodeToString(SynergyEnvelope.serializer(), envelope)

    /** Lenient parse — unknown keys tolerated (upstream Gson behavior). */
    fun decode(frame: String): SynergyEnvelope = json.decodeFromString(SynergyEnvelope.serializer(), frame)
}

/** Envelope — keys are the wire keys (protocol/SynergyMessage.java @SerializedName). */
@Serializable
data class SynergyEnvelope(
    @SerialName("msg_type") val msgType: String,
    @SerialName("msg_body") val msgBody: JsonElement? = null,
    @SerialName("src_role") val srcRole: String = SynergyEnvelopeCodec.BASE_SRC_ROLE,
    @SerialName("category") val category: String? = null,
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("noncestr") val nonceStr: Long = 0,
    @SerialName("signature") val signature: Long = 0,
) {
    /** Exposed msg_body convenience when the body is an object. */
    val bodyObject: JsonObject? get() = msgBody as? JsonObject

    /** cmd_type / info_type of the body, per schema. */
    val bodyType: String?
        get() = bodyObject?.get(if (msgType == SynergyEnvelopeCodec.TYPE_CMD) "cmd_type" else "info_type")
            ?.toString()?.trim('"')
}

/** Signing material carried by every envelope. */
data class SignedMeta(
    val timestamp: Long,
    val nonceStr: Long,
    val signature: Long,
)
