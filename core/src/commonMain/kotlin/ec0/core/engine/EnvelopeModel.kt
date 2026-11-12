/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Control-plane Ec0Envelope wire model, deterministic ec0-sig-v1 preimage builder,
 * 64-packet sliding ReplayWindow, and normative OfflineFlushDeduplicator
 * (docs/engine/design.md §4.1–§4.4).
 */
package ec0.core.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Ec0Envelope(
    @SerialName("msg_type") val msgType: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("msg_body") val msgBody: JsonElement? = null,
    @SerialName("src_role") val srcRole: String = EngineConstants.ENGINE_SRC_ROLE,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("category") val category: String? = null,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("seq") val seq: Long,
    @SerialName("nonce") val nonce: Long,
    @SerialName("sig") val sig: String,
) {
    val bodyObject: JsonObject? get() = msgBody as? JsonObject

    val bodyType: String?
        get() {
            val key = if (msgType == Ec0EnvelopeCodec.TYPE_CMD) "cmd_type" else "info_type"
            return (bodyObject?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    val innerBodyObject: JsonObject?
        get() {
            val key = if (msgType == Ec0EnvelopeCodec.TYPE_CMD) "cmd_body" else "info_body"
            return bodyObject?.get(key) as? JsonObject
        }
}

object Ec0EnvelopeCodec {
    const val TYPE_CMD: String = "cmd"
    const val TYPE_INFO: String = "info"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Constructs the normative 8-line UTF-8 signing preimage (`docs/engine/design.md` §4.3):
     * ```
     * ec0-sig-v1\n
     * <msg_type>\n
     * <src_role>\n
     * <category_or_empty>\n
     * <timestamp>\n
     * <seq>\n
     * <nonce>\n
     * <CanonicalJson.encode(msg_body)>
     * ```
     */
    fun signingPreimage(
        msgType: String,
        srcRole: String = EngineConstants.ENGINE_SRC_ROLE,
        category: String? = null,
        timestamp: Long,
        seq: Long,
        nonce: Long,
        msgBody: JsonElement?,
    ): ByteArray {
        require(msgType == TYPE_CMD || msgType == TYPE_INFO) {
            "Invalid Ec0Envelope msg_type '$msgType' (expected 'cmd' or 'info')"
        }
        requireNoLineBreaks("msg_type", msgType)
        requireNoLineBreaks("src_role", srcRole)
        require(srcRole.isNotEmpty()) { "src_role must not be empty" }
        if (category != null) {
            requireNoLineBreaks("category", category)
        }
        require(seq >= 1L) { "Envelope seq must be >= 1 (got $seq)" }

        val canonicalBody = CanonicalJson.encode(msgBody)
        val text = buildString(canonicalBody.length + 80) {
            append(EngineConstants.ENVELOPE_SIG_TAG).append('\n')
            append(msgType).append('\n')
            append(srcRole).append('\n')
            append(category ?: "").append('\n')
            append(timestamp.toString()).append('\n')
            append(seq.toString()).append('\n')
            append(nonce.toString()).append('\n')
            append(canonicalBody)
        }
        return text.encodeToByteArray()
    }

    fun signingPreimage(envelope: Ec0Envelope): ByteArray = signingPreimage(
        msgType = envelope.msgType,
        srcRole = envelope.srcRole,
        category = envelope.category,
        timestamp = envelope.timestamp,
        seq = envelope.seq,
        nonce = envelope.nonce,
        msgBody = envelope.msgBody,
    )

    fun buildCmdBody(
        cmdType: String,
        cmdBody: JsonElement? = null,
        cmdValue: Int = 0,
        reason: String = "",
    ): JsonObject = buildJsonObject {
        put("cmd_type", cmdType)
        if (cmdBody != null) put("cmd_body", cmdBody)
        put("cmd_value", cmdValue)
        put("reason", reason)
    }

    fun buildInfoBody(
        infoType: String,
        infoBody: JsonElement? = null,
        infoValue: Int = 0,
    ): JsonObject = buildJsonObject {
        put("info_type", infoType)
        if (infoBody != null) put("info_body", infoBody)
        put("info_value", infoValue)
    }

    fun encode(envelope: Ec0Envelope): String =
        json.encodeToString(Ec0Envelope.serializer(), envelope)

    fun decode(rawJson: String): Ec0Envelope {
        require(rawJson.encodeToByteArray().size <= EngineConstants.MAX_ENVELOPE_JSON_BYTES) {
            "Ec0Envelope JSON exceeds max size (${EngineConstants.MAX_ENVELOPE_JSON_BYTES} bytes)"
        }
        return json.decodeFromString(Ec0Envelope.serializer(), rawJson)
    }

    private fun requireNoLineBreaks(fieldName: String, value: String) {
        require(!value.contains('\n') && !value.contains('\r')) {
            "Header field '$fieldName' must not contain CR/LF characters"
        }
    }
}

enum class ReplayCheckResult {
    ACCEPTED,
    INVALID_SEQ,
    CLOCK_SKEW_EXCEEDED,
    DUPLICATE_SEQ,
    SEQ_TOO_OLD,
}

/**
 * 64-packet sliding sequence bitmask + live clock-skew gate (`docs/engine/design.md` §4.4).
 */
class ReplayWindow(
    private val maxClockSkewMs: Long = EngineConstants.LIVE_CLOCK_SKEW_MS,
) {
    var highestSeq: Long = 0L
        private set

    var windowMask: ULong = 0uL
        private set

    /**
     * Validates and records `(seq, timestampMs)` at [nowMs].
     * State is mutated ONLY when [ReplayCheckResult.ACCEPTED] is returned.
     */
    fun checkAndRecord(seq: Long, timestampMs: Long, nowMs: Long): ReplayCheckResult {
        if (seq <= 0L) return ReplayCheckResult.INVALID_SEQ
        val skew = if (nowMs >= timestampMs) nowMs - timestampMs else timestampMs - nowMs
        if (skew > maxClockSkewMs) return ReplayCheckResult.CLOCK_SKEW_EXCEEDED

        if (seq > highestSeq) {
            val shift = seq - highestSeq
            windowMask = if (highestSeq == 0L || shift >= 64L) {
                1uL
            } else {
                (windowMask shl shift.toInt()) or 1uL
            }
            highestSeq = seq
            return ReplayCheckResult.ACCEPTED
        }

        val offset = highestSeq - seq
        if (offset >= 64L) {
            return ReplayCheckResult.SEQ_TOO_OLD
        }
        val bit = 1uL shl offset.toInt()
        if ((windowMask and bit) != 0uL) {
            return ReplayCheckResult.DUPLICATE_SEQ
        }
        windowMask = windowMask or bit
        return ReplayCheckResult.ACCEPTED
    }

    fun reset() {
        highestSeq = 0L
        windowMask = 0uL
    }
}

/**
 * Cross-session deduplicator for `"offline_flush"` batches (`docs/engine/design.md` §4.4, Amendment 2).
 *
 * Because `ReplayWindow` resets on every new WebSocket session, offline-queued items
 * flushed upon reconnect are guarded by:
 *  1. Strictly increasing per-session `batch_seq` on the outer `"offline_flush"` command.
 *  2. 1-hour maximum creation TTL (`abs(nowMs - inner.timestamp) <= offlineTtlMs`).
 *  3. Strictly increasing `inner.seq` within a single batch.
 *  4. Cross-session bounded LRU deduplication keyed by BOTH `(peerDeviceId, inner.timestamp, inner.nonce)`
 *     and the extracted domain content ID (`clip_sha256`, `notif_id:ts_ms`, `transfer_id`).
 */
class OfflineFlushDeduplicator(
    private val maxEntries: Int = EngineConstants.OFFLINE_DEDUPE_CAPACITY,
    private val offlineTtlMs: Long = EngineConstants.OFFLINE_TTL_MS,
) {
    private var lastBatchSeq: Long = 0L
    private val seenOriginKeys = LinkedHashSet<String>()
    private val seenContentKeys = LinkedHashSet<String>()

    fun onNewSession() {
        lastBatchSeq = 0L
    }

    /**
     * Validates an `"offline_flush"` batch from [peerDeviceId] at [nowMs], verifies each inner
     * envelope via [signatureVerifier], and returns the ordered list of fresh, non-duplicate
     * inner envelopes to apply.
     *
     * Throws [IllegalArgumentException] if the outer batch marker (`batch_seq`) is non-monotonic
     * or if any inner envelope has an invalid signature or non-increasing intra-batch `seq`.
     */
    fun filterBatch(
        peerDeviceId: String,
        batchSeq: Long,
        innerEnvelopes: List<Ec0Envelope>,
        nowMs: Long,
        signatureVerifier: (Ec0Envelope) -> Boolean,
    ): List<Ec0Envelope> {
        require(batchSeq > lastBatchSeq) {
            "Non-monotonic offline_flush batch_seq: got $batchSeq, last was $lastBatchSeq"
        }
        lastBatchSeq = batchSeq

        val accepted = mutableListOf<Ec0Envelope>()
        var prevInnerSeq = 0L
        for (inner in innerEnvelopes) {
            require(inner.seq > prevInnerSeq) {
                "Non-increasing inner seq in offline_flush batch: ${inner.seq} after $prevInnerSeq"
            }
            prevInnerSeq = inner.seq

            require(signatureVerifier(inner)) {
                "Invalid Ed25519 signature on inner offline_flush envelope (seq=${inner.seq})"
            }

            val age = if (nowMs >= inner.timestamp) nowMs - inner.timestamp else inner.timestamp - nowMs
            if (age > offlineTtlMs) {
                // Expired while peer was offline (> 1 hour) — drop cleanly
                continue
            }

            val originKey = "$peerDeviceId:${inner.timestamp}:${inner.nonce}"
            val contentKey = extractContentDedupeKey(peerDeviceId, inner)
            if (originKey in seenOriginKeys) continue
            if (contentKey != null && contentKey in seenContentKeys) continue

            recordSeen(seenOriginKeys, originKey)
            if (contentKey != null) {
                recordSeen(seenContentKeys, contentKey)
            }
            accepted.add(inner)
        }
        return accepted
    }

    /** Extracts the normative domain content-id key (`clip_sha256`, `notif_id:ts_ms`, `transfer_id`). */
    fun extractContentDedupeKey(peerDeviceId: String, envelope: Ec0Envelope): String? {
        val inner = envelope.innerBodyObject ?: return null
        return when (envelope.bodyType) {
            "clipboard_sync" -> {
                val sha = (inner["clip_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                sha?.let { "$peerDeviceId:clip:$it" }
            }
            "notif_post" -> {
                val id = (inner["notif_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val ts = (inner["ts_ms"] as? JsonPrimitive)?.longOrNull ?: envelope.timestamp
                id?.let { "$peerDeviceId:notif:$it:$ts" }
            }
            "file_offer" -> {
                val txId = (inner["transfer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                txId?.let { "$peerDeviceId:file:$it" }
            }
            else -> null
        }
    }

    private fun recordSeen(set: LinkedHashSet<String>, key: String) {
        if (set.size >= maxEntries) {
            val oldest = set.iterator().next()
            set.remove(oldest)
        }
        set.add(key)
    }
}
