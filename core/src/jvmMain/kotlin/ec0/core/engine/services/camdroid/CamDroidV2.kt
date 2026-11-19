/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * CamDroid wire protocol v2 ("CMD2") — the exact framing of
 * ARYAN007H/CamDroid's `desktop/src/protocol/v2.rs`, ported so ec0's phone
 * engine and desktop receiver speak the owner's proven camera wire natively
 * (hardware H.264 up to the device's real encoder limits).
 *
 * Envelope: 36-byte header + payload.
 *   [0..4)   magic "CMD2"
 *   [4]      version (2)
 *   [5]      kind (Video=1, Audio=2, Control=3, Metadata=4, Heartbeat=5,
 *                 VideoConfig=6, AudioConfig=7)
 *   [6..8)   flags u16 LE (KEY_FRAME=1, CONFIGURATION=2, DISCONTINUITY=4)
 *   [8..16)  session id u64 LE
 *   [16..24) sequence u64 LE
 *   [24..32) capture timestamp ns u64 LE
 *   [32..36) payload length u32 LE
 *
 * Control-plane messages ride METADATA packets as tagged JSON:
 * `{"type": "<snake_case>", "data": {...}}` — byte-compatible with the Rust
 * serde `#[serde(tag = "type", content = "data", rename_all = "snake_case")]`
 * SessionMessage enum and the Kotlin/Gson phone implementation.
 */
package ec0.core.engine.services.camdroid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.io.OutputStream

object CamDroidV2 {
    const val MAGIC: String = "CMD2"
    const val VERSION: Int = 2
    const val HEADER_LEN: Int = 36
    const val MAX_PAYLOAD: Int = 16 * 1024 * 1024

    const val KIND_VIDEO: Int = 0x01
    const val KIND_AUDIO: Int = 0x02
    const val KIND_CONTROL: Int = 0x03
    const val KIND_METADATA: Int = 0x04
    const val KIND_HEARTBEAT: Int = 0x05
    const val KIND_VIDEO_CONFIG: Int = 0x06
    const val KIND_AUDIO_CONFIG: Int = 0x07

    const val FLAG_KEY_FRAME: Int = 1
    const val FLAG_CONFIGURATION: Int = 2
    const val FLAG_DISCONTINUITY: Int = 4

    data class Packet(
        val kind: Int,
        val flags: Int,
        val sessionId: Long,
        val sequence: Long,
        val captureTimestampNs: Long,
        val payload: ByteArray,
    ) {
        fun encode(): ByteArray {
            require(payload.size <= MAX_PAYLOAD) { "v2 payload too large: ${payload.size}" }
            val bytes = ByteArray(HEADER_LEN + payload.size)
            MAGIC.toByteArray().copyInto(bytes, 0)
            bytes[4] = VERSION.toByte()
            bytes[5] = kind.toByte()
            writeLeU16(bytes, 6, flags)
            writeLeU64(bytes, 8, sessionId)
            writeLeU64(bytes, 16, sequence)
            writeLeU64(bytes, 24, captureTimestampNs)
            writeLeU32(bytes, 32, payload.size)
            payload.copyInto(bytes, HEADER_LEN)
            return bytes
        }

        fun writeTo(out: OutputStream) {
            out.write(encode())
            out.flush()
        }

        companion object {
            fun heartbeat(sessionId: Long, sequence: Long): Packet = Packet(
                kind = KIND_HEARTBEAT,
                flags = 0,
                sessionId = sessionId,
                sequence = sequence,
                captureTimestampNs = 0,
                payload = ByteArray(0),
            )
        }
    }

    fun readPacket(input: java.io.DataInputStream): Packet {
        val header = ByteArray(HEADER_LEN)
        input.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == MAGIC) { "invalid v2 protocol magic" }
        require(header[4].toInt() and 0xFF == VERSION) {
            "unsupported v2 protocol version: ${header[4].toInt() and 0xFF}"
        }
        val kind = header[5].toInt() and 0xFF
        val flags = readLeU16(header, 6)
        val sessionId = readLeU64(header, 8)
        val sequence = readLeU64(header, 16)
        val timestamp = readLeU64(header, 24)
        val payloadLen = readLeU32(header, 32)
        require(payloadLen <= MAX_PAYLOAD) { "v2 payload too large: $payloadLen" }
        val payload = ByteArray(payloadLen)
        input.readFully(payload)
        return Packet(kind, flags, sessionId, sequence, timestamp, payload)
    }

    // ---- little-endian helpers (java.nio.ByteBuffer order changes with
    // platform endianness assumptions — explicit here, matching the LE wire) --

    fun writeLeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun writeLeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun writeLeU64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    fun readLeU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun readLeU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun readLeU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}

/** One advertised encoder mode — the phone is authoritative on its own limits. */
data class CamDroidVideoMode(
    val width: Int,
    val height: Int,
    val fps: Int,
    val minBitrateBps: Long,
    val maxBitrateBps: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("min_bitrate_bps", minBitrateBps)
        put("max_bitrate_bps", maxBitrateBps)
    }

    companion object {
        fun fromJson(obj: JsonObject): CamDroidVideoMode = CamDroidVideoMode(
            width = obj["width"]!!.jsonPrimitive.longOrNull?.toInt() ?: 0,
            height = obj["height"]!!.jsonPrimitive.longOrNull?.toInt() ?: 0,
            fps = obj["fps"]!!.jsonPrimitive.longOrNull?.toInt() ?: 0,
            minBitrateBps = obj["min_bitrate_bps"]?.jsonPrimitive?.longOrNull ?: 0L,
            maxBitrateBps = obj["max_bitrate_bps"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}

/** The capability set the phone advertises in its Capabilities message. */
data class CamDroidCapabilities(
    val codecs: List<String>,
    val videoModes: List<CamDroidVideoMode>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("protocol_versions") { add(JsonPrimitive(2)) }
        putJsonArray("codecs") { codecs.forEach { add(JsonPrimitive(it)) } }
        put("video_modes", buildJsonArray { videoModes.forEach { add(it.toJson()) } })
        putJsonArray("audio_codecs") { /* none yet — ec0 streams video-only */ }
    }

    companion object {
        fun fromJson(data: JsonObject): CamDroidCapabilities = CamDroidCapabilities(
            codecs = data["codecs"]?.jsonArrayOrEmpty()?.map { it.jsonPrimitive.content } ?: emptyList(),
            videoModes = data["video_modes"]?.jsonArrayOrEmpty()?.map {
                CamDroidVideoMode.fromJson(it.jsonObject)
            } ?: emptyList(),
        )

        private fun JsonElement.jsonArrayOrEmpty(): JsonArray =
            this as? JsonArray ?: JsonArray(emptyList())
    }
}

/**
 * Chooses the closest advertised mode for a desired capture shape — ported
 * from CamDroid's `CapabilitySet::resolve` (the phone is authoritative: an
 * exact match wins, otherwise the closest pixel count wins and the bitrate is
 * re-derived). THE A03 LESSON: requesting a mode the encoder doesn't offer
 * crashes MediaCodec.configure — never send an un-advertised mode.
 */
object CamDroidModeResolver {
    fun recommendedBitrate(width: Int, height: Int, fps: Int, codec: String): Long {
        val bitsPerPixel = when (codec.lowercase()) {
            "h265", "hevc" -> 0.06
            "mjpeg" -> 0.35
            else -> 0.10
        }
        return (width.toLong() * height * fps * bitsPerPixel).toLong()
    }

    fun resolve(
        capabilities: CamDroidCapabilities,
        desiredCodec: String,
        desiredWidth: Int,
        desiredHeight: Int,
        desiredFps: Int,
    ): CamDroidVideoMode? {
        val codec = capabilities.codecs.firstOrNull { it.equals(desiredCodec, ignoreCase = true) }
            ?: capabilities.codecs.firstOrNull { it.equals("h264", ignoreCase = true) }
            ?: capabilities.codecs.firstOrNull()
            ?: return null
        val wantedPixels = desiredWidth.toLong() * desiredHeight
        val mode = capabilities.videoModes.minWithOrNull(
            compareBy(
                { kotlin.math.abs(it.width.toLong() * it.height - wantedPixels) },
                { kotlin.math.abs(it.fps - desiredFps) },
            ),
        ) ?: return null
        // The codec choice is reflected by the server's encoder config; the
        // returned mode carries the honest geometry/fps/bitrate bounds.
        return mode
    }
}

/** Tagged-JSON session messages (`{"type": ..., "data": ...}`) as METADATA. */
object CamDroidSessionMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun hello(client: String): JsonObject = buildJsonObject {
        put("type", "hello")
        putJsonObject("data") {
            putJsonArray("protocol_versions") { add(kotlinx.serialization.json.JsonPrimitive(2)) }
            put("client", client)
        }
    }

    fun capabilities(capabilities: CamDroidCapabilities): JsonObject = buildJsonObject {
        put("type", "capabilities")
        put("data", capabilities.toJson())
    }

    fun start(
        codec: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Long,
        audioEnabled: Boolean,
    ): JsonObject = buildJsonObject {
        put("type", "start")
        putJsonObject("data") {
            putJsonObject("config") {
                put("codec", codec)
                put("width", width)
                put("height", height)
                put("fps", fps)
                put("bitrate_bps", bitrateBps)
            }
            put("audio_enabled", audioEnabled)
        }
    }

    fun streamAccepted(sessionId: Long, codec: String, mode: CamDroidVideoMode, bitrateBps: Long): JsonObject =
        buildJsonObject {
            put("type", "stream_accepted")
            putJsonObject("data") {
                put("session_id", sessionId)
                putJsonObject("config") {
                    put("codec", codec)
                    put("width", mode.width)
                    put("height", mode.height)
                    put("fps", mode.fps)
                    put("bitrate_bps", bitrateBps)
                }
                put("audio_enabled", false)
            }
        }

    fun streamRejected(code: String, message: String): JsonObject = buildJsonObject {
        put("type", "stream_rejected")
        putJsonObject("data") {
            put("code", code)
            put("message", message)
        }
    }

    /** `{"type": ..., "data": {...}}` — null when the payload isn't a session message. */
    fun parseType(payload: ByteArray): Pair<String, JsonObject>? = runCatching {
        val obj = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val data = obj["data"]?.jsonObject ?: JsonObject(emptyMap())
        type to data
    }.getOrNull()
}
