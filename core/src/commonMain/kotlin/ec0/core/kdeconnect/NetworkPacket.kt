/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from kdeconnect-android (org.kde.kdeconnect.NetworkPacket.kt,
 * upstream SPDX: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL;
 * GPL-3.0-only option taken). Wire format is 1:1 with upstream 25.xx:
 *   {"id":<epoch ms>,"type":"...","body":{...}[, "payloadSize":N,
 *    "payloadTransferInfo":{...}]}\n
 * Deviations are listed in docs/re/kdeconnect-port.md §Deviations.
 */
package ec0.core.kdeconnect

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A KDE Connect network packet. Body keys keep insertion order (LinkedHashMap).
 */
class NetworkPacket private constructor(
    val type: String,
    private val mBody: MutableMap<String, JsonElement>,
    var payload: Payload?,
    var payloadTransferInfo: JsonObject,
    /** Envelope id of an unserialized packet (upstream keeps this field internal). */
    val envelopeId: Long?,
) {
    constructor(type: String) : this(
        type = type,
        mBody = LinkedHashMap(),
        payload = null,
        payloadTransferInfo = JsonObject(emptyMap()),
        envelopeId = null,
    )

    private fun primitive(key: String): JsonPrimitive? {
        val value = mBody[key]
        return if (value is JsonPrimitive && value !is JsonNull) value else null
    }

    fun getString(key: String): String = getString(key, "")

    fun getStringOrNull(key: String): String? = when (val value = mBody[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    operator fun set(key: String, value: String) {
        mBody[key] = JsonPrimitive(value)
    }

    fun getInt(key: String): Int = getInt(key, -1)

    fun getIntOrNull(key: String): Int? {
        val content = primitive(key)?.content ?: return null
        return content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
    }

    fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue

    operator fun set(key: String, value: Int) {
        mBody[key] = JsonPrimitive(value)
    }

    fun getLong(key: String): Long = getLong(key, -1)

    fun getLong(key: String, defaultValue: Long): Long {
        val content = primitive(key)?.content ?: return defaultValue
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong() ?: defaultValue
    }

    operator fun set(key: String, value: Long) {
        mBody[key] = JsonPrimitive(value)
    }

    fun getBoolean(key: String): Boolean = getBoolean(key, false)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val content = primitive(key)?.content ?: return defaultValue
        return when {
            content.equals("true", ignoreCase = true) -> true
            content.equals("false", ignoreCase = true) -> false
            else -> defaultValue
        }
    }

    operator fun set(key: String, value: Boolean) {
        mBody[key] = JsonPrimitive(value)
    }

    fun getDouble(key: String): Double = getDouble(key, Double.NaN)

    fun getDouble(key: String, defaultValue: Double): Double =
        primitive(key)?.content?.toDoubleOrNull() ?: defaultValue

    operator fun set(key: String, value: Double) {
        // NaN and infinity are not representable in JSON
        if (value.isFinite()) {
            mBody[key] = JsonPrimitive(value)
        }
    }

    fun getJsonArray(key: String): JsonArray? = mBody[key] as? JsonArray

    operator fun set(key: String, value: JsonArray?) {
        if (value == null) mBody.remove(key) else mBody[key] = value
    }

    fun getJsonObject(key: String): JsonObject? = mBody[key] as? JsonObject

    operator fun set(key: String, value: JsonObject?) {
        if (value == null) mBody.remove(key) else mBody[key] = value
    }

    private fun JsonArray.toStringList(): List<String> = mapNotNull { element ->
        when (element) {
            JsonNull -> null
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    fun getStringSet(key: String): Set<String>? = getJsonArray(key)?.toStringList()?.toHashSet()

    fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        getStringSet(key) ?: defaultValue

    operator fun set(key: String, value: Set<String>) {
        mBody[key] = JsonArray(value.map(::JsonPrimitive))
    }

    fun getStringList(key: String): List<String>? = getJsonArray(key)?.toStringList()

    fun getStringList(key: String, defaultValue: List<String>): List<String> =
        getStringList(key) ?: defaultValue

    operator fun set(key: String, value: List<String>) {
        mBody[key] = JsonArray(value.map(::JsonPrimitive))
    }

    fun has(key: String): Boolean = mBody.containsKey(key)

    operator fun contains(key: String): Boolean = has(key)

    /**
     * Serializes with a trailing newline, the on-the-wire frame terminator.
     * id comes from [Companion.idProvider] at serialize time (upstream uses
     * System.currentTimeMillis(); set it from the platform layer).
     */
    @Throws(SerializationException::class)
    fun serialize(): String {
        val serialized = WirePacket(
            id = idProvider(),
            type = type,
            body = JsonObject(mBody),
            payloadSize = if (hasPayload()) payload!!.payloadSize else null,
            payloadTransferInfo = if (hasPayload()) payloadTransferInfo else null,
        )
        // Explicit serializer (not the reified overload): serializer resolution
        // for a nested @Serializable class is compiler-version sensitive, and
        // this call is unambiguous.
        return json.encodeToString(WirePacket.serializer(), serialized) + "\n"
    }

    val payloadSize: Long
        get() = payload?.payloadSize ?: 0L

    fun hasPayload(): Boolean = payload != null && payload!!.payloadSize != 0L

    fun hasPayloadTransferInfo(): Boolean = payloadTransferInfo.isNotEmpty()

    /**
     * Data-only payload handle (size + transfer info travel in the packet);
     * stream sockets are platform-layer concerns and live outside commonMain.
     */
    class Payload(val payloadSize: Long)

    /** The on-the-wire representation. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    internal class WirePacket(
        val id: Long = 0,
        val type: String,
        val body: JsonObject,
        @EncodeDefault(EncodeDefault.Mode.NEVER) // omit instead of writing null — peers check key presence
        val payloadSize: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val payloadTransferInfo: JsonObject? = null,
    )

    companion object {
        const val PACKET_TYPE_IDENTITY: String = "kdeconnect.identity"
        const val PACKET_TYPE_PAIR: String = "kdeconnect.pair"

        val PROTOCOL_PACKET_TYPES: List<String> = listOf(PACKET_TYPE_IDENTITY, PACKET_TYPE_PAIR)

        /**
         * Envelope id source. Upstream uses System.currentTimeMillis(); set
         * this from the platform layer at startup (desktop/android modules).
         * Default: process-uptime-based monotonic value so ids at least move.
         */
        var idProvider: () -> Long = { TimeSourceDefault.nextId() }

        internal val json: Json = Json {
            ignoreUnknownKeys = true // tolerate extra fields — mirrors upstream JsonHelper
            encodeDefaults = true
            // NOTE: upstream also sets maxNestingDepth = 64 as a decode-depth
            // guard; kotlinx-serialization on our classpath (1.7.3) does not
            // expose that knob yet (upstream ships a newer version). Re-add
            // when the version catalog is bumped.
        }

        @Throws(SerializationException::class)
        fun unserialize(s: String): NetworkPacket {
            val serialized = json.decodeFromString(WirePacket.serializer(), s)
            val payloadSize = serialized.payloadSize
            val payload = if (payloadSize != null) Payload(payloadSize) else null
            val payloadTransferInfo = if (payloadSize != null) {
                serialized.payloadTransferInfo
                    ?: throw SerializationException("Packet has payloadSize but no payloadTransferInfo")
            } else {
                JsonObject(emptyMap())
            }
            return NetworkPacket(
                serialized.type,
                LinkedHashMap(serialized.body),
                payload,
                payloadTransferInfo,
                serialized.id,
            )
        }
    }
}

/** Monotonic, process-lifetime id fallback (see [NetworkPacket.idProvider]). */
private object TimeSourceDefault {
    private val start = kotlin.time.TimeSource.Monotonic.markNow()

    fun nextId(): Long = start.elapsedNow().inWholeMilliseconds
}
