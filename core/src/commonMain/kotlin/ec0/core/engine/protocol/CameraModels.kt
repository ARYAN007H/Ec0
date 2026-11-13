/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Continuity Camera wire models and byte-exact CamDroid protocol codecs
 * (interoperable with github.com/ARYAN007H/CamDroid ProtocolHandler v1,
 * ProtocolV2 "CMD2" 36-byte LE framing, NsdHelper `_camdroid._tcp.`, and
 * ec0-engine's signed `cam.control.cmd` / `cam.status.info` envelopes).
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineEncoding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object CamDroidConstants {
    /** mDNS service type registered by CamDroid's `NsdHelper.kt`. */
    const val MDNS_SERVICE_TYPE: String = "_camdroid._tcp.local."

    /** Default TCP streaming & control port used by CamDroid's `StreamServer.kt`. */
    const val DEFAULT_TCP_PORT: Int = 4747

    /** Default Linux v4l2loopback virtual camera device node (`v4l2_output.rs`). */
    const val DEFAULT_V4L2_DEVICE: String = "/dev/video10"

    /** Default v4l2loopback card label (`v4l2_output.rs`). */
    const val DEFAULT_V4L2_CARD_LABEL: String = "CamDroid"

    /** Maximum frame payload size enforced by CamDroid v1 and v2 (16 MiB). */
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024

    /** CamDroid ProtocolV2 4-byte ASCII magic: `"CMD2"` (`0x43, 0x4D, 0x44, 0x32`). */
    val V2_MAGIC: ByteArray = byteArrayOf(0x43, 0x4d, 0x44, 0x32)
    const val V2_VERSION: Byte = 2
    const val V2_HEADER_BYTES: Int = 36

    // ec0-engine envelope message types for Continuity Camera signaling
    const val MSG_CAM_CONTROL_CMD: String = "cam.control.cmd"
    const val MSG_CAM_STATUS_INFO: String = "cam.status.info"

    val SUPPORTED_CODECS: Set<String> = setOf("h264", "h265", "hevc", "mjpeg")
    val SUPPORTED_RESOLUTIONS: Set<String> = setOf("1080p", "1440p", "2k", "4k")
    val SUPPORTED_FPS: Set<Int> = setOf(30, 60)
    val SUPPORTED_WHITE_BALANCE: Set<String> = setOf("auto", "daylight", "tungsten", "fluorescent", "cloudy")
}

/**
 * Packet type discriminator shared by CamDroid `ProtocolHandler` (v1) and
 * `ProtocolV2.MediaKind` (v2).
 */
enum class CamDroidMediaKind(val wireValue: Byte) {
    VIDEO(0x01),
    AUDIO(0x02),
    CONTROL(0x03),
    METADATA(0x04),
    HEARTBEAT(0x05),
    VIDEO_CONFIG(0x06),
    AUDIO_CONFIG(0x07);

    companion object {
        fun fromWireValue(value: Byte): CamDroidMediaKind =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException(
                    "Unsupported CamDroid media kind: 0x${(value.toInt() and 0xff).toString(16)}",
                )
    }
}

/** Bitmask flags carried in CamDroid `ProtocolV2` headers (`ProtocolV2.Flags`). */
object CamDroidV2Flags {
    const val NONE: Int = 0
    const val KEY_FRAME: Int = 1 shl 0
    const val CONFIGURATION: Int = 1 shl 1
    const val DISCONTINUITY: Int = 1 shl 2

    private const val KNOWN_MASK: Int = KEY_FRAME or CONFIGURATION or DISCONTINUITY

    fun validate(flags: Int) {
        require((flags and KNOWN_MASK) == flags && (flags and 0xffff.inv()) == 0) {
            "Unsupported CamDroid v2 media flags: 0x${flags.toString(16)}"
        }
    }
}

/**
 * CamDroid Protocol v1 packet (`ProtocolHandler.Packet` / `desktop/src/protocol/wire.rs`):
 * `[type: 1B] [length: 4B Little-Endian] [payload: N bytes]`.
 */
data class CamDroidV1Packet(
    val kind: CamDroidMediaKind,
    val payload: ByteArray,
) {
    init {
        require(payload.size <= CamDroidConstants.MAX_PAYLOAD_BYTES) {
            "CamDroid v1 payload exceeds 16 MiB limit: ${payload.size} bytes"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CamDroidV1Packet &&
            kind == other.kind &&
            EngineEncoding.constantTimeEquals(payload, other.payload)

    override fun hashCode(): Int = 31 * kind.hashCode() + payload.contentHashCode()
}

/** Pure-Kotlin Little-Endian codec for CamDroid Protocol v1 (`ProtocolHandler`). */
object CamDroidV1Codec {
    const val HEADER_BYTES: Int = 5

    fun encode(packet: CamDroidV1Packet): ByteArray {
        val out = ByteArray(HEADER_BYTES + packet.payload.size)
        out[0] = packet.kind.wireValue
        writeInt32Le(out, 1, packet.payload.size)
        packet.payload.copyInto(out, destinationOffset = HEADER_BYTES)
        return out
    }

    fun decode(bytes: ByteArray): CamDroidV1Packet {
        require(bytes.size >= HEADER_BYTES) {
            "Truncated CamDroid v1 header: expected >= $HEADER_BYTES bytes, got ${bytes.size}"
        }
        val kind = CamDroidMediaKind.fromWireValue(bytes[0])
        val length = readInt32Le(bytes, 1)
        require(length in 0..CamDroidConstants.MAX_PAYLOAD_BYTES) {
            "Invalid CamDroid v1 payload length: $length"
        }
        val expectedTotal = HEADER_BYTES + length
        require(bytes.size == expectedTotal) {
            "CamDroid v1 frame size mismatch: expected $expectedTotal bytes, got ${bytes.size}"
        }
        val payload = bytes.copyOfRange(HEADER_BYTES, expectedTotal)
        return CamDroidV1Packet(kind = kind, payload = payload)
    }

    fun heartbeatPacket(): CamDroidV1Packet =
        CamDroidV1Packet(CamDroidMediaKind.HEARTBEAT, ByteArray(0))

    fun controlCommandPacket(command: CamDroidControlCommand): CamDroidV1Packet =
        CamDroidV1Packet(
            kind = CamDroidMediaKind.CONTROL,
            payload = command.toCamDroidJsonString().encodeToByteArray(),
        )

    fun clientHandshakePacket(clientName: String = "ec0-desktop", version: String = "1.0"): CamDroidV1Packet {
        val json = """{"version":"${escapeJsonString(version)}","client":"${escapeJsonString(clientName)}"}"""
        return CamDroidV1Packet(
            kind = CamDroidMediaKind.METADATA,
            payload = json.encodeToByteArray(),
        )
    }
}

/**
 * CamDroid Protocol v2 timestamped packet (`com.camdroid.network.ProtocolV2.Packet`):
 * 36-byte Little-Endian header:
 * - `[0..3]`: Magic `"CMD2"` (`0x43, 0x4D, 0x44, 0x32`)
 * - `[4]`: Version `2`
 * - `[5]`: `kind` (`CamDroidMediaKind.wireValue`)
 * - `[6..7]`: `flags` (`u16` LE)
 * - `[8..15]`: `sessionId` (`i64` LE)
 * - `[16..23]`: `sequence` (`i64` LE)
 * - `[24..31]`: `captureTimestampNs` (`i64` LE)
 * - `[32..35]`: `payloadLength` (`i32` LE)
 * - `[36..36+N]`: `payload`
 */
data class CamDroidV2Packet(
    val kind: CamDroidMediaKind,
    val flags: Int = CamDroidV2Flags.NONE,
    val sessionId: Long,
    val sequence: Long,
    val captureTimestampNs: Long,
    val payload: ByteArray,
) {
    init {
        CamDroidV2Flags.validate(flags)
        require(payload.size <= CamDroidConstants.MAX_PAYLOAD_BYTES) {
            "CamDroid v2 payload too large: ${payload.size} bytes"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CamDroidV2Packet &&
            kind == other.kind &&
            flags == other.flags &&
            sessionId == other.sessionId &&
            sequence == other.sequence &&
            captureTimestampNs == other.captureTimestampNs &&
            EngineEncoding.constantTimeEquals(payload, other.payload)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + flags
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + captureTimestampNs.hashCode()
        return 31 * result + payload.contentHashCode()
    }
}

/** Pure-Kotlin Little-Endian codec for CamDroid `ProtocolV2` (`36`-byte `"CMD2"` header). */
object CamDroidV2Codec {
    fun encode(packet: CamDroidV2Packet): ByteArray {
        val out = ByteArray(CamDroidConstants.V2_HEADER_BYTES + packet.payload.size)
        CamDroidConstants.V2_MAGIC.copyInto(out, destinationOffset = 0)
        out[4] = CamDroidConstants.V2_VERSION
        out[5] = packet.kind.wireValue
        writeInt16Le(out, 6, packet.flags)
        writeInt64Le(out, 8, packet.sessionId)
        writeInt64Le(out, 16, packet.sequence)
        writeInt64Le(out, 24, packet.captureTimestampNs)
        writeInt32Le(out, 32, packet.payload.size)
        packet.payload.copyInto(out, destinationOffset = CamDroidConstants.V2_HEADER_BYTES)
        return out
    }

    fun decode(bytes: ByteArray): CamDroidV2Packet {
        require(bytes.size >= CamDroidConstants.V2_HEADER_BYTES) {
            "Truncated CamDroid v2 header: expected ${CamDroidConstants.V2_HEADER_BYTES} bytes, got ${bytes.size}"
        }
        for (i in 0 until 4) {
            require(bytes[i] == CamDroidConstants.V2_MAGIC[i]) {
                "Invalid CamDroid v2 protocol magic"
            }
        }
        val version = bytes[4]
        require(version == CamDroidConstants.V2_VERSION) {
            "Unsupported CamDroid v2 version: ${version.toInt() and 0xff}"
        }
        val kind = CamDroidMediaKind.fromWireValue(bytes[5])
        val flags = readUint16Le(bytes, 6)
        CamDroidV2Flags.validate(flags)
        val sessionId = readInt64Le(bytes, 8)
        val sequence = readInt64Le(bytes, 16)
        val captureTimestampNs = readInt64Le(bytes, 24)
        val payloadLength = readInt32Le(bytes, 32)
        require(payloadLength in 0..CamDroidConstants.MAX_PAYLOAD_BYTES) {
            "CamDroid v2 payload too large: $payloadLength bytes"
        }
        val expectedTotal = CamDroidConstants.V2_HEADER_BYTES + payloadLength
        require(bytes.size >= expectedTotal) {
            "Truncated CamDroid v2 payload: declared $payloadLength bytes, received ${bytes.size - CamDroidConstants.V2_HEADER_BYTES}"
        }
        require(bytes.size == expectedTotal) {
            "CamDroid v2 frame has ${bytes.size - expectedTotal} trailing bytes"
        }
        val payload = bytes.copyOfRange(CamDroidConstants.V2_HEADER_BYTES, expectedTotal)
        return CamDroidV2Packet(
            kind = kind,
            flags = flags,
            sessionId = sessionId,
            sequence = sequence,
            captureTimestampNs = captureTimestampNs,
            payload = payload,
        )
    }
}

/**
 * Continuity Camera control commands matching CamDroid's `ControlCommand` enum
 * (`desktop/src/protocol/wire.rs` and `desktop/src/control/remote.rs`).
 *
 * All scaled numeric parameters (`zoomX100`, `distanceX100`) are stored as
 * integers (`100` = `1.0`, `250` = `2.5`) so `ec0-engine` envelopes remain 100%
 * integer-only for `CanonicalJson` deterministic signing, while `toCamDroidJsonString()`
 * formats the exact float/integer JSON fields expected by CamDroid's TCP `:4747` server.
 */
sealed class CamDroidControlCommand {
    abstract val cmdName: String

    data class Start(
        val codec: String = "h264",
        val resolution: String = "1080p",
        val fps: Int = 30,
        val audio: Boolean = true,
    ) : CamDroidControlCommand() {
        override val cmdName: String = "start"
        init {
            require(codec.lowercase() in CamDroidConstants.SUPPORTED_CODECS) { "Unsupported codec: $codec" }
            require(resolution.lowercase() in CamDroidConstants.SUPPORTED_RESOLUTIONS) { "Unsupported resolution: $resolution" }
            require(fps in CamDroidConstants.SUPPORTED_FPS) { "FPS must be 30 or 60 (got $fps)" }
        }
    }

    data object Stop : CamDroidControlCommand() {
        override val cmdName: String = "stop"
    }

    data object SwitchCamera : CamDroidControlCommand() {
        override val cmdName: String = "switch_camera"
    }

    /** `zoomX100`: `100` = `1.0x`, `250` = `2.5x`, range `100..1000`. */
    data class SetZoom(val zoomX100: Long) : CamDroidControlCommand() {
        override val cmdName: String = "set_zoom"
        init {
            require(zoomX100 in 100L..1_000L) { "zoomX100 must be in 100..1000 (got $zoomX100)" }
        }
    }

    /** `mode`: `"auto"` or `"manual"`; `distanceX100`: `0..1000` (`0.0..10.0` diopters). */
    data class SetFocus(
        val mode: String,
        val distanceX100: Long? = null,
    ) : CamDroidControlCommand() {
        override val cmdName: String = "set_focus"
        init {
            require(mode == "auto" || mode == "manual") { "Focus mode must be 'auto' or 'manual'" }
            if (distanceX100 != null) {
                require(distanceX100 in 0L..1_000L) { "Focus distanceX100 must be in 0..1000" }
            }
        }
    }

    /** `compensation`: `-4..+4` EV steps. */
    data class SetExposure(val compensation: Int) : CamDroidControlCommand() {
        override val cmdName: String = "set_exposure"
        init {
            require(compensation in -4..4) { "Exposure compensation must be in -4..4 EV (got $compensation)" }
        }
    }

    /** `mode`: `"auto"`, `"daylight"`, `"tungsten"`, `"fluorescent"`, `"cloudy"`. */
    data class SetWhiteBalance(val mode: String) : CamDroidControlCommand() {
        override val cmdName: String = "set_white_balance"
        init {
            require(mode.lowercase() in CamDroidConstants.SUPPORTED_WHITE_BALANCE) {
                "Unsupported white balance mode: $mode"
            }
        }
    }

    data class SetFlash(val enabled: Boolean) : CamDroidControlCommand() {
        override val cmdName: String = "set_flash"
    }

    data class SetMirror(val enabled: Boolean) : CamDroidControlCommand() {
        override val cmdName: String = "set_mirror"
    }

    data class SetResolution(val value: String) : CamDroidControlCommand() {
        override val cmdName: String = "set_resolution"
        init {
            require(value.lowercase() in CamDroidConstants.SUPPORTED_RESOLUTIONS) {
                "Unsupported resolution: $value"
            }
        }
    }

    data class SetFps(val value: Int) : CamDroidControlCommand() {
        override val cmdName: String = "set_fps"
        init {
            require(value in CamDroidConstants.SUPPORTED_FPS) { "FPS must be 30 or 60 (got $value)" }
        }
    }

    data class SetCodec(val value: String) : CamDroidControlCommand() {
        override val cmdName: String = "set_codec"
        init {
            require(value.lowercase() in CamDroidConstants.SUPPORTED_CODECS) { "Unsupported codec: $value" }
        }
    }

    /** `value`: bitrate in bps (`0` = auto adaptive). */
    data class SetBitrate(val value: Long) : CamDroidControlCommand() {
        override val cmdName: String = "set_bitrate"
        init {
            require(value in 0L..100_000_000L) { "Bitrate must be in 0..100_000_000 bps" }
        }
    }

    data object RequestKeyframe : CamDroidControlCommand() {
        override val cmdName: String = "request_keyframe"
    }

    /**
     * Formats the exact `serde_json` snake_case tagged JSON payload expected by
     * CamDroid's `StreamServer.kt` (`ProtocolHandler.CONTROL_CMD` `0x03`).
     */
    fun toCamDroidJsonString(): String = when (this) {
        is Start ->
            """{"cmd":"start","codec":"${escapeJsonString(codec.lowercase())}","resolution":"${escapeJsonString(resolution.lowercase())}","fps":$fps,"audio":$audio}"""
        is Stop -> """{"cmd":"stop"}"""
        is SwitchCamera -> """{"cmd":"switch_camera"}"""
        is SetZoom -> """{"cmd":"set_zoom","value":${formatHundredths(zoomX100)}}"""
        is SetFocus -> if (distanceX100 != null) {
            """{"cmd":"set_focus","mode":"${escapeJsonString(mode)}","distance":${formatHundredths(distanceX100)}}"""
        } else {
            """{"cmd":"set_focus","mode":"${escapeJsonString(mode)}"}"""
        }
        is SetExposure -> """{"cmd":"set_exposure","compensation":$compensation}"""
        is SetWhiteBalance -> """{"cmd":"set_white_balance","mode":"${escapeJsonString(mode.lowercase())}"}"""
        is SetFlash -> """{"cmd":"set_flash","enabled":$enabled}"""
        is SetMirror -> """{"cmd":"set_mirror","enabled":$enabled}"""
        is SetResolution -> """{"cmd":"set_resolution","value":"${escapeJsonString(value.lowercase())}"}"""
        is SetFps -> """{"cmd":"set_fps","value":$value}"""
        is SetCodec -> """{"cmd":"set_codec","value":"${escapeJsonString(value.lowercase())}"}"""
        is SetBitrate -> """{"cmd":"set_bitrate","value":$value}"""
        is RequestKeyframe -> """{"cmd":"request_keyframe"}"""
    }

    /**
     * Encodes this command as an integer-only `JsonObject` for `ec0-engine`'s
     * signed `cam.control.cmd` envelope (`CanonicalJson` deterministic signing).
     */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("cmd", cmdName)
        when (val c = this@CamDroidControlCommand) {
            is Start -> {
                put("audio", c.audio)
                put("codec", c.codec.lowercase())
                put("fps", c.fps.toLong())
                put("resolution", c.resolution.lowercase())
            }
            is Stop -> Unit
            is SwitchCamera -> Unit
            is SetZoom -> put("zoom_x100", c.zoomX100)
            is SetFocus -> {
                put("mode", c.mode)
                if (c.distanceX100 != null) put("distance_x100", c.distanceX100)
            }
            is SetExposure -> put("compensation", c.compensation.toLong())
            is SetWhiteBalance -> put("mode", c.mode.lowercase())
            is SetFlash -> put("enabled", c.enabled)
            is SetMirror -> put("enabled", c.enabled)
            is SetResolution -> put("value", c.value.lowercase())
            is SetFps -> put("value", c.value.toLong())
            is SetCodec -> put("value", c.value.lowercase())
            is SetBitrate -> put("value", c.value)
            is RequestKeyframe -> Unit
        }
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CamDroidControlCommand {
            val cmd = obj["cmd"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Missing 'cmd' in cam.control.cmd")
            return when (cmd) {
                "start" -> Start(
                    codec = obj["codec"]?.jsonPrimitive?.contentOrNull ?: "h264",
                    resolution = obj["resolution"]?.jsonPrimitive?.contentOrNull ?: "1080p",
                    fps = obj["fps"]?.jsonPrimitive?.intOrNull ?: 30,
                    audio = obj["audio"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
                "stop" -> Stop
                "switch_camera" -> SwitchCamera
                "set_zoom" -> SetZoom(
                    zoomX100 = obj["zoom_x100"]?.jsonPrimitive?.longOrNull
                        ?: throw IllegalArgumentException("Missing 'zoom_x100' in set_zoom"),
                )
                "set_focus" -> SetFocus(
                    mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "auto",
                    distanceX100 = obj["distance_x100"]?.jsonPrimitive?.longOrNull,
                )
                "set_exposure" -> SetExposure(
                    compensation = obj["compensation"]?.jsonPrimitive?.intOrNull
                        ?: throw IllegalArgumentException("Missing 'compensation' in set_exposure"),
                )
                "set_white_balance" -> SetWhiteBalance(
                    mode = obj["mode"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Missing 'mode' in set_white_balance"),
                )
                "set_flash" -> SetFlash(
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: throw IllegalArgumentException("Missing 'enabled' in set_flash"),
                )
                "set_mirror" -> SetMirror(
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: throw IllegalArgumentException("Missing 'enabled' in set_mirror"),
                )
                "set_resolution" -> SetResolution(
                    value = obj["value"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Missing 'value' in set_resolution"),
                )
                "set_fps" -> SetFps(
                    value = obj["value"]?.jsonPrimitive?.intOrNull
                        ?: throw IllegalArgumentException("Missing 'value' in set_fps"),
                )
                "set_codec" -> SetCodec(
                    value = obj["value"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Missing 'value' in set_codec"),
                )
                "set_bitrate" -> SetBitrate(
                    value = obj["value"]?.jsonPrimitive?.longOrNull
                        ?: throw IllegalArgumentException("Missing 'value' in set_bitrate"),
                )
                "request_keyframe" -> RequestKeyframe
                else -> throw IllegalArgumentException("Unknown cam.control.cmd: '$cmd'")
            }
        }
    }
}

/** Server capabilities reported by CamDroid during initial metadata exchange. */
data class CamDroidServerCapabilities(
    val version: String = "1.0",
    val device: String = "Android",
    val codecs: List<String> = listOf("h264", "h265", "mjpeg"),
    val resolutions: List<String> = listOf("1080p", "1440p", "4k"),
    val fps: List<Int> = listOf(30, 60),
    val audio: Boolean = true,
    val battery: Int = 100,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("audio", audio)
        put("battery", battery.toLong())
        put("codecs", JsonArray(codecs.map { JsonPrimitive(it) }))
        put("device", device)
        put("fps", JsonArray(fps.map { JsonPrimitive(it.toLong()) }))
        put("resolutions", JsonArray(resolutions.map { JsonPrimitive(it) }))
        put("version", version)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CamDroidServerCapabilities = CamDroidServerCapabilities(
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "1.0",
            device = obj["device"]?.jsonPrimitive?.contentOrNull ?: "Android",
            codecs = obj["codecs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOf("h264"),
            resolutions = obj["resolutions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOf("1080p"),
            fps = obj["fps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?: listOf(30, 60),
            audio = obj["audio"]?.jsonPrimitive?.booleanOrNull ?: true,
            battery = obj["battery"]?.jsonPrimitive?.intOrNull ?: 100,
        )
    }
}

/** Which Continuity Camera transport backend is driving the Linux V4L2 sink. */
enum class CameraBackend(val wireName: String) {
    /** Native `ARYAN007H/CamDroid` TCP `:4747` (`CMD2` v2 / v1) -> `/dev/video10`. */
    CAMDROID_NATIVE("camdroid_native"),

    /** `scrcpy --video-source=camera --v4l2-sink=/dev/video10` over wireless ADB. */
    SCRCPY_V4L2_FALLBACK("scrcpy_v4l2");

    companion object {
        fun fromWireName(wire: String): CameraBackend =
            entries.firstOrNull { it.wireName == wire } ?: CAMDROID_NATIVE
    }
}

enum class CameraStreamState(val wireName: String) {
    IDLE("idle"),
    STARTING("starting"),
    STREAMING("streaming"),
    STOPPED("stopped"),
    ERROR("error");

    companion object {
        fun fromWireName(wire: String): CameraStreamState =
            entries.firstOrNull { it.wireName == wire } ?: ERROR
    }
}

/**
 * Signed `cam.status.info` payload reporting Continuity Camera state and studio
 * telemetry between Phone and Linux Desktop.
 */
data class CameraStatusInfo(
    val state: CameraStreamState,
    val backend: CameraBackend = CameraBackend.CAMDROID_NATIVE,
    val host: String? = null,
    val port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
    val v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
    val codec: String = "h264",
    val resolution: String = "1080p",
    val fps: Int = 30,
    val audioEnabled: Boolean = true,
    val frontCamera: Boolean = false,
    val flashEnabled: Boolean = false,
    val mirrorEnabled: Boolean = false,
    val zoomX100: Long = 100L,
    val whiteBalance: String = "auto",
    val exposureEv: Int = 0,
    val reason: String? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("audio_enabled", audioEnabled)
        put("backend", backend.wireName)
        put("codec", codec)
        put("exposure_ev", exposureEv.toLong())
        put("flash_enabled", flashEnabled)
        put("fps", fps.toLong())
        put("front_camera", frontCamera)
        if (host != null) put("host", host)
        put("mirror_enabled", mirrorEnabled)
        put("port", port.toLong())
        if (reason != null) put("reason", reason)
        put("resolution", resolution)
        put("state", state.wireName)
        put("v4l2_device", v4l2DevicePath)
        put("white_balance", whiteBalance)
        put("zoom_x100", zoomX100)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CameraStatusInfo = CameraStatusInfo(
            state = CameraStreamState.fromWireName(obj["state"]?.jsonPrimitive?.contentOrNull ?: "error"),
            backend = CameraBackend.fromWireName(obj["backend"]?.jsonPrimitive?.contentOrNull ?: "camdroid_native"),
            host = obj["host"]?.jsonPrimitive?.contentOrNull,
            port = obj["port"]?.jsonPrimitive?.intOrNull ?: CamDroidConstants.DEFAULT_TCP_PORT,
            v4l2DevicePath = obj["v4l2_device"]?.jsonPrimitive?.contentOrNull ?: CamDroidConstants.DEFAULT_V4L2_DEVICE,
            codec = obj["codec"]?.jsonPrimitive?.contentOrNull ?: "h264",
            resolution = obj["resolution"]?.jsonPrimitive?.contentOrNull ?: "1080p",
            fps = obj["fps"]?.jsonPrimitive?.intOrNull ?: 30,
            audioEnabled = obj["audio_enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            frontCamera = obj["front_camera"]?.jsonPrimitive?.booleanOrNull ?: false,
            flashEnabled = obj["flash_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            mirrorEnabled = obj["mirror_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            zoomX100 = obj["zoom_x100"]?.jsonPrimitive?.longOrNull ?: 100L,
            whiteBalance = obj["white_balance"]?.jsonPrimitive?.contentOrNull ?: "auto",
            exposureEv = obj["exposure_ev"]?.jsonPrimitive?.intOrNull ?: 0,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

private fun formatHundredths(x100: Long): String {
    val whole = x100 / 100L
    val frac = kotlin.math.abs(x100 % 100L)
    return when {
        frac == 0L -> "$whole.0"
        frac % 10L == 0L -> "$whole.${frac / 10L}"
        else -> "$whole.${frac.toString().padStart(2, '0')}"
    }
}

private fun escapeJsonString(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private fun writeInt16Le(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xff).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

private fun readUint16Le(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

private fun writeInt32Le(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xff).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xff).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

private fun readInt32Le(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xff) or
        ((buf[offset + 1].toInt() and 0xff) shl 8) or
        ((buf[offset + 2].toInt() and 0xff) shl 16) or
        ((buf[offset + 3].toInt() and 0xff) shl 24)

private fun writeInt64Le(buf: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        buf[offset + i] = ((value ushr (i * 8)) and 0xffL).toByte()
    }
}

private fun readInt64Le(buf: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((buf[offset + i].toLong() and 0xffL) shl (i * 8))
    }
    return result
}
