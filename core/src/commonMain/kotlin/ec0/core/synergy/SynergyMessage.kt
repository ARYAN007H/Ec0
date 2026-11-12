package ec0.core.synergy

/**
 * Synergy control-plane envelope schema constants — wire truth, verified
 * against com.oplus.linker 17.7.35 `protocol/SynergyMessage.java` (7
 * @SerializedName keys) and PR #7's independent review.
 *
 * IMPORTANT: there are NO top-level `cmd`/`info` envelope keys — "cmd" and
 * "info" are the VALUES of [Fields.MSG_TYPE] (TYPE_CMD / TYPE_INFO), and the
 * per-command payload lives in `msg_body`. Nonce/signature are LONGs.
 *
 * The serializable codec lives in the JVM source set
 * (`core/src/jvmMain/kotlin/ec0/core/synergy/SynergyEnvelope.kt`) because the
 * transport is JVM-target today; common code should reference these constants
 * rather than re-declaring models.
 */
object SynergyMessageSchema {
    /** Envelope field names, exactly as Gson serializes them. */
    object Fields {
        const val MSG_TYPE = "msg_type"
        const val MSG_BODY = "msg_body"
        const val SRC_ROLE = "src_role"
        const val CATEGORY = "category"
        const val NONCE_STR = "noncestr"
        const val SIGNATURE = "signature"
        const val TIMESTAMP = "timestamp"
    }

    /** Envelope message types (SynergyMessage.java:8-9). */
    object MessageType {
        const val CMD = "cmd"
        const val INFO = "info"
    }

    /** Base signing path (WebSocketConnectManager:558): constant signature, seeded nonce. */
    object BaseSigning {
        const val SIGNATURE = 0x0FFFFFFFL // 268435455
        // nonce = java.util.Random(timestamp).nextLong()
    }
}
