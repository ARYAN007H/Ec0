package ec0.core.engine.smartconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Smart Connect ("Ready For") outer WebSocket frame envelope (`ver = "3.0.0"`), nested JSON
 * payload unwrapper, and log redactor (`SmartConnectRedactor`).
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §1.1, §3-S-A, §5.3.
 */
@Serializable
data class SmartConnectEnvelope(
    val deviceId: String,
    val srcServiceId: Int,
    val serviceId: Int,
    val msgId: Int = 0,
    val data: JsonElement,
    val ver: String = SmartConnectConstants.ENVELOPE_VERSION
)

object SmartConnectEnvelopeCodec {

    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Encodes an outer [SmartConnectEnvelope] into a single-line JSON text frame.
     */
    fun encodeEnvelope(envelope: SmartConnectEnvelope): String {
        return json.encodeToString(envelope)
    }

    /**
     * Wraps a typed `@Serializable` [innerData] payload into an outer [SmartConnectEnvelope]
     * and encodes it as a JSON text frame.
     */
    inline fun <reified T> encodeTypedFrame(
        deviceId: String,
        srcServiceId: Int,
        serviceId: Int,
        msgId: Int,
        innerData: T
    ): String {
        val dataElement = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), innerData)
        return encodeEnvelope(
            SmartConnectEnvelope(
                deviceId = deviceId,
                srcServiceId = srcServiceId,
                serviceId = serviceId,
                msgId = msgId,
                data = dataElement,
                ver = SmartConnectConstants.ENVELOPE_VERSION
            )
        )
    }

    /**
     * Parses a raw JSON text frame into [SmartConnectEnvelope].
     */
    fun decodeEnvelope(rawFrame: String): SmartConnectEnvelope {
        return json.decodeFromString<SmartConnectEnvelope>(rawFrame.trim())
    }

    /**
     * Unwraps the `data` field of [SmartConnectEnvelope] into a [JsonObject].
     *
     * Stock Smart Connect Android builds sometimes serialize `data` as a direct [JsonObject]
     * and sometimes as a nested JSON-encoded string (`"{\"cmd\":0,...}"`). This helper
     * transparently handles both representations.
     */
    fun unwrapDataObject(dataElement: JsonElement): JsonObject {
        return when (dataElement) {
            is JsonObject -> dataElement
            is JsonPrimitive -> {
                require(dataElement.isString) {
                    "Expected JsonObject or JSON string in envelope.data, got primitive: $dataElement"
                }
                val parsed = json.parseToJsonElement(dataElement.content.trim())
                parsed.jsonObject
            }
            else -> throw IllegalArgumentException(
                "Unsupported envelope.data JSON type: ${dataElement::class.simpleName}"
            )
        }
    }

    /**
     * Extracts the integer `cmd` field from an envelope's `data` object, or `null` if absent.
     */
    fun extractCmd(envelope: SmartConnectEnvelope): Int? {
        val obj = runCatching { unwrapDataObject(envelope.data) }.getOrNull() ?: return null
        val cmdElement = obj["cmd"] as? JsonPrimitive ?: return null
        return cmdElement.content.toIntOrNull()
    }
}

/**
 * Redacts sensitive cryptographic keys, tokens, and multi-megabyte inline wallpapers from
 * Smart Connect JSON frames before diagnostic logging (§5.3).
 */
object SmartConnectRedactor {
    private val redactedKeys = setOf(
        "key",
        "perPairKey",
        "phoneKey",
        "invitation",
        "token",
        "password",
        "wallpaper"
    )

    fun redactJsonString(rawJson: String): String {
        val parsed = runCatching {
            SmartConnectEnvelopeCodec.json.parseToJsonElement(rawJson)
        }.getOrNull() ?: return "[unparseable-frame]"
        val redacted = redactElement(parsed)
        return SmartConnectEnvelopeCodec.json.encodeToString(JsonElement.serializer(), redacted)
    }

    fun redactElement(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val mapped = buildMap<String, JsonElement> {
                    for ((k, v) in element) {
                        if (k in redactedKeys) {
                            put(k, JsonPrimitive("[REDACTED]"))
                        } else if (k == "data" && v is JsonPrimitive && v.isString) {
                            val nested = runCatching {
                                SmartConnectEnvelopeCodec.json.parseToJsonElement(v.content)
                            }.getOrNull()
                            if (nested != null) {
                                put(k, redactElement(nested))
                            } else {
                                put(k, v)
                            }
                        } else {
                            put(k, redactElement(v))
                        }
                    }
                }
                JsonObject(mapped)
            }
            else -> element
        }
    }
}
