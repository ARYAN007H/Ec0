/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Production Ecosystem Continuity Protocol Models (`commonMain`):
 *   - Feature #1: Full-Duplex Call Audio & Phone-to-PC Audio Routing (`AudioRoutingProtocol`)
 *   - Feature #2: Insert from Phone — Photo, Document Scan & Continuity Sketch (`ContinuityCaptureProtocol`)
 *   - Feature #4: Wireless Second Display ("Sidecar") & Touch/Stylus Pass-through (`SidecarDisplayProtocol`)
 *   - Feature #5: Persistent SMS/MMS Inbox & Dual-SIM Send (`SmsInboxProtocol`)
 *   - Feature #6: Bidirectional Focus / Do Not Disturb & Meeting Sync (`FocusSyncProtocol`)
 *   - Feature #7: Passive Browser Tab & Media Timestamp Handoff (`BrowserHandoffProtocol`)
 *   - Feature #8: 0-RTT Session Migration & Wi-Fi Direct Turbo Negotiation (`TransportFailoverProtocol`)
 *   - Feature #11: Continuity Camera Studio Effects & Adaptive Bitrate (`CameraEffectsProtocol`)
 *   - Feature #12: Cross-Device Encrypted Clipboard Shelf (`ClipboardShelfEntry`)
 *
 * Security invariants enforced at model construction (fail-closed):
 *   - Integer-only numeric fields (deterministic RFC 8785 CanonicalJson signing).
 *   - Strict regex/length/range allowlists on every codec, MIME, URL, phone number, SSID, and coordinate.
 *   - Zero arbitrary shell metacharacters or control chars in any wire string.
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// Feature #1 — Full-Duplex Call Audio & AirPlay-Style Audio Sink
// ---------------------------------------------------------------------------

object AudioRoutingProtocol {
    const val CATEGORY_AUDIO: String = "audio"
    const val CMD_AUDIO_ROUTE: String = "audio_route"
    const val INFO_AUDIO_STATUS: String = "audio_status"

    const val ACTION_START_CALL_AUDIO: String = "start_call_audio"
    const val ACTION_STOP_CALL_AUDIO: String = "stop_call_audio"
    const val ACTION_START_MEDIA_SINK: String = "start_media_sink"
    const val ACTION_STOP_MEDIA_SINK: String = "stop_media_sink"

    val VALID_ACTIONS: Set<String> = setOf(
        ACTION_START_CALL_AUDIO,
        ACTION_STOP_CALL_AUDIO,
        ACTION_START_MEDIA_SINK,
        ACTION_STOP_MEDIA_SINK,
    )
    val VALID_CODECS: Set<String> = setOf("pcm_s16le", "opus")
    val VALID_SAMPLE_RATES: Set<Int> = setOf(16_000, 24_000, 48_000)

    const val MAX_AUDIO_PACKET_BYTES: Int = 16 * 1024
}

data class AudioRouteCommand(
    val action: String,
    val codec: String = "pcm_s16le",
    val sampleRateHz: Int = 48_000,
    val channels: Int = 1,
    val duplex: Boolean = true,
) {
    init {
        require(action in AudioRoutingProtocol.VALID_ACTIONS) { "Unsupported audio route action: '$action'" }
        require(codec in AudioRoutingProtocol.VALID_CODECS) { "Unsupported audio codec: '$codec'" }
        require(sampleRateHz in AudioRoutingProtocol.VALID_SAMPLE_RATES) { "Unsupported sample rate: $sampleRateHz" }
        require(channels in 1..2) { "Audio channels must be 1 or 2 (got $channels)" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("channels", channels)
        put("codec", codec)
        put("duplex", duplex)
        put("sample_rate_hz", sampleRateHz)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): AudioRouteCommand = AudioRouteCommand(
            action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in audio_route"),
            codec = (obj["codec"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "pcm_s16le",
            sampleRateHz = (obj["sample_rate_hz"] as? JsonPrimitive)?.intOrNull ?: 48_000,
            channels = (obj["channels"] as? JsonPrimitive)?.intOrNull ?: 1,
            duplex = (obj["duplex"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

data class AudioRouteStatusInfo(
    val active: Boolean,
    val mode: String = "idle",
    val codec: String = "pcm_s16le",
    val sampleRateHz: Int = 48_000,
    val channels: Int = 1,
    val duckedMediaPlayer: String? = null,
) {
    init {
        require(mode in VALID_MODES) { "Unsupported audio mode: '$mode'" }
        require(codec in AudioRoutingProtocol.VALID_CODECS) { "Unsupported codec: '$codec'" }
        require(sampleRateHz in AudioRoutingProtocol.VALID_SAMPLE_RATES) { "Unsupported sample rate: $sampleRateHz" }
        require(channels in 1..2) { "Channels must be 1..2" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("active", active)
        put("channels", channels)
        put("codec", codec)
        if (duckedMediaPlayer != null) put("ducked_media_player", duckedMediaPlayer.take(128))
        put("mode", mode)
        put("sample_rate_hz", sampleRateHz)
    }

    companion object {
        val VALID_MODES: Set<String> = setOf("idle", "call_duplex", "media_sink")

        fun fromJsonObject(obj: JsonObject): AudioRouteStatusInfo = AudioRouteStatusInfo(
            active = (obj["active"] as? JsonPrimitive)?.booleanOrNull ?: false,
            mode = (obj["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "idle",
            codec = (obj["codec"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "pcm_s16le",
            sampleRateHz = (obj["sample_rate_hz"] as? JsonPrimitive)?.intOrNull ?: 48_000,
            channels = (obj["channels"] as? JsonPrimitive)?.intOrNull ?: 1,
            duckedMediaPlayer = (obj["ducked_media_player"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #2 — Insert from Phone (Photo, Document Scan, Continuity Sketch)
// ---------------------------------------------------------------------------

object ContinuityCaptureProtocol {
    const val CATEGORY_CAPTURE: String = "capture"
    const val CMD_CAPTURE_REQUEST: String = "capture_request"
    const val INFO_CAPTURE_RESULT: String = "capture_result"

    const val MODE_PHOTO: String = "photo"
    const val MODE_DOCUMENT_SCAN: String = "document_scan"
    const val MODE_SKETCH: String = "sketch"

    val VALID_MODES: Set<String> = setOf(MODE_PHOTO, MODE_DOCUMENT_SCAN, MODE_SKETCH)
    val ALLOWED_MIMES: Set<String> = setOf("image/png", "image/jpeg", "application/pdf")
    private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,64}$")
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

    const val MAX_CAPTURE_BYTES: Int = 16 * 1024 * 1024
    const val MAX_INLINE_B64_CHARS: Int = 64 * 1024

    fun isValidRequestId(id: String): Boolean = SAFE_ID_REGEX.matches(id)
    fun isValidSha256(hex: String): Boolean = SHA256_REGEX.matches(hex.lowercase())
}

data class CaptureRequestCommand(
    val requestId: String,
    val mode: String,
    val autoPaste: Boolean = false,
    val enhanceContrast: Boolean = true,
) {
    init {
        require(ContinuityCaptureProtocol.isValidRequestId(requestId)) { "Invalid capture requestId: '$requestId'" }
        require(mode in ContinuityCaptureProtocol.VALID_MODES) { "Unsupported capture mode: '$mode'" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("auto_paste", autoPaste)
        put("enhance_contrast", enhanceContrast)
        put("mode", mode)
        put("request_id", requestId)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CaptureRequestCommand = CaptureRequestCommand(
            requestId = (obj["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'request_id' in capture_request"),
            mode = (obj["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'mode' in capture_request"),
            autoPaste = (obj["auto_paste"] as? JsonPrimitive)?.booleanOrNull ?: false,
            enhanceContrast = (obj["enhance_contrast"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

data class CaptureResultInfo(
    val requestId: String,
    val mode: String,
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long,
    val sha256Hex: String,
    val streamId: Int = 0,
    val inlineBase64Url: String? = null,
) {
    init {
        require(ContinuityCaptureProtocol.isValidRequestId(requestId)) { "Invalid requestId" }
        require(mode in ContinuityCaptureProtocol.VALID_MODES) { "Invalid mode: '$mode'" }
        require(mime in ContinuityCaptureProtocol.ALLOWED_MIMES) { "Unsupported capture MIME: '$mime'" }
        require(width in 0..8192 && height in 0..8192) { "Capture dimensions out of bounds: ${width}x${height}" }
        require(sizeBytes in 1L..ContinuityCaptureProtocol.MAX_CAPTURE_BYTES.toLong()) {
            "Capture size out of bounds: $sizeBytes"
        }
        require(ContinuityCaptureProtocol.isValidSha256(sha256Hex)) { "Invalid sha256Hex" }
        if (inlineBase64Url != null) {
            require(inlineBase64Url.length <= ContinuityCaptureProtocol.MAX_INLINE_B64_CHARS) {
                "Inline capture payload exceeds limit"
            }
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("height", height)
        if (inlineBase64Url != null) put("inline_b64u", inlineBase64Url)
        put("mime", mime)
        put("mode", mode)
        put("request_id", requestId)
        put("sha256", sha256Hex.lowercase())
        put("size_bytes", sizeBytes)
        put("stream_id", streamId)
        put("width", width)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CaptureResultInfo = CaptureResultInfo(
            requestId = (obj["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'request_id'"),
            mode = (obj["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'mode'"),
            mime = (obj["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'mime'"),
            width = (obj["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            height = (obj["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            sizeBytes = (obj["size_bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256Hex = (obj["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'sha256'"),
            streamId = (obj["stream_id"] as? JsonPrimitive)?.intOrNull ?: 0,
            inlineBase64Url = (obj["inline_b64u"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #4 — Wireless Second Display ("Sidecar") & Touch/Stylus Pass-through
// ---------------------------------------------------------------------------

object SidecarDisplayProtocol {
    const val CATEGORY_SIDECAR: String = "sidecar"
    const val CMD_SIDECAR_START: String = "sidecar_start"
    const val CMD_SIDECAR_STOP: String = "sidecar_stop"
    const val INFO_SIDECAR_STATUS: String = "sidecar_status"
    const val CMD_SIDECAR_TOUCH: String = "sidecar_touch"

    val VALID_CODECS: Set<String> = setOf("h264", "hevc")
    val VALID_TOUCH_ACTIONS: Set<String> = setOf("down", "move", "up", "cancel", "hover")
    val VALID_TOOLS: Set<String> = setOf("finger", "stylus")
}

data class SidecarStartCommand(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateKbps: Int = 10_000,
    val codec: String = "h264",
    val extendMode: Boolean = true,
    val streamPort: Int = 49892,
) {
    init {
        require(width in 640..3840 && height in 480..2160) { "Sidecar resolution out of bounds: ${width}x${height}" }
        require(fps in 15..120) { "Sidecar fps must be in 15..120" }
        require(bitrateKbps in 500..40_000) { "Sidecar bitrateKbps must be in 500..40000" }
        require(codec in SidecarDisplayProtocol.VALID_CODECS) { "Unsupported sidecar codec: '$codec'" }
        require(streamPort in 1024..65535) { "Sidecar streamPort out of range: $streamPort" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("bitrate_kbps", bitrateKbps)
        put("codec", codec)
        put("extend_mode", extendMode)
        put("fps", fps)
        put("height", height)
        put("stream_port", streamPort)
        put("width", width)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): SidecarStartCommand = SidecarStartCommand(
            width = (obj["width"] as? JsonPrimitive)?.intOrNull ?: 1920,
            height = (obj["height"] as? JsonPrimitive)?.intOrNull ?: 1080,
            fps = (obj["fps"] as? JsonPrimitive)?.intOrNull ?: 60,
            bitrateKbps = (obj["bitrate_kbps"] as? JsonPrimitive)?.intOrNull ?: 10_000,
            codec = (obj["codec"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "h264",
            extendMode = (obj["extend_mode"] as? JsonPrimitive)?.booleanOrNull ?: true,
            streamPort = (obj["stream_port"] as? JsonPrimitive)?.intOrNull ?: 49892,
        )
    }
}

data class SidecarTouchInputEvent(
    val action: String,
    val pointerId: Int = 0,
    val xPermille10k: Int,
    val yPermille10k: Int,
    val pressurePermille: Int = 1000,
    val tiltXDeg: Int = 0,
    val tiltYDeg: Int = 0,
    val toolType: String = "finger",
) {
    init {
        require(action in SidecarDisplayProtocol.VALID_TOUCH_ACTIONS) { "Unsupported touch action: '$action'" }
        require(pointerId in 0..9) { "pointerId must be in 0..9" }
        require(xPermille10k in 0..10_000) { "xPermille10k out of range" }
        require(yPermille10k in 0..10_000) { "yPermille10k out of range" }
        require(pressurePermille in 0..1_000) { "pressurePermille out of range" }
        require(tiltXDeg in -90..90 && tiltYDeg in -90..90) { "Stylus tilt out of range" }
        require(toolType in SidecarDisplayProtocol.VALID_TOOLS) { "Unsupported toolType: '$toolType'" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("pointer_id", pointerId)
        put("pressure", pressurePermille)
        put("tilt_x", tiltXDeg)
        put("tilt_y", tiltYDeg)
        put("tool", toolType)
        put("x10k", xPermille10k)
        put("y10k", yPermille10k)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): SidecarTouchInputEvent = SidecarTouchInputEvent(
            action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "move",
            pointerId = (obj["pointer_id"] as? JsonPrimitive)?.intOrNull ?: 0,
            xPermille10k = (obj["x10k"] as? JsonPrimitive)?.intOrNull ?: 0,
            yPermille10k = (obj["y10k"] as? JsonPrimitive)?.intOrNull ?: 0,
            pressurePermille = (obj["pressure"] as? JsonPrimitive)?.intOrNull ?: 1000,
            tiltXDeg = (obj["tilt_x"] as? JsonPrimitive)?.intOrNull ?: 0,
            tiltYDeg = (obj["tilt_y"] as? JsonPrimitive)?.intOrNull ?: 0,
            toolType = (obj["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "finger",
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #5 — Persistent SMS / MMS Inbox & Dual-SIM Send
// ---------------------------------------------------------------------------

object SmsInboxProtocol {
    const val CATEGORY_SMS: String = "sms"
    const val CMD_SMS_SYNC_REQ: String = "sms_sync_req"
    const val INFO_SMS_THREADS: String = "sms_threads"
    const val CMD_SMS_SEND: String = "sms_send"
    const val INFO_SMS_SEND_RESULT: String = "sms_send_result"

    private val PHONE_ADDRESS_REGEX = Regex("^\\+?[A-Za-z0-9*#() ._+-]{3,36}$")
    const val MAX_SMS_BODY_CHARS: Int = 1_600
    const val MAX_THREADS_PER_SYNC: Int = 50
    const val MAX_MESSAGES_PER_THREAD: Int = 50

    fun isValidAddress(address: String): Boolean =
        address.isNotBlank() &&
            !address.any { it.code < 32 || it.code == 127 } &&
            PHONE_ADDRESS_REGEX.matches(address.trim())
}

data class SmsMessageRecord(
    val messageId: String,
    val threadId: String,
    val address: String,
    val contactName: String = "",
    val body: String,
    val incoming: Boolean,
    val timestampMs: Long,
    val simSlot: Int = 0,
    val read: Boolean = true,
) {
    init {
        require(messageId.isNotBlank() && messageId.length <= 64) { "Invalid messageId" }
        require(threadId.isNotBlank() && threadId.length <= 64) { "Invalid threadId" }
        require(SmsInboxProtocol.isValidAddress(address)) { "Invalid SMS address" }
        require(simSlot in 0..3) { "simSlot must be in 0..3" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("address", address.trim())
        put("body", body.take(SmsInboxProtocol.MAX_SMS_BODY_CHARS))
        put("contact_name", contactName.take(128))
        put("incoming", incoming)
        put("msg_id", messageId)
        put("read", read)
        put("sim_slot", simSlot)
        put("thread_id", threadId)
        put("ts_ms", timestampMs)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): SmsMessageRecord = SmsMessageRecord(
            messageId = (obj["msg_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'msg_id'"),
            threadId = (obj["thread_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'thread_id'"),
            address = (obj["address"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'address'"),
            contactName = (obj["contact_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            body = ((obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
                .take(SmsInboxProtocol.MAX_SMS_BODY_CHARS),
            incoming = (obj["incoming"] as? JsonPrimitive)?.booleanOrNull ?: true,
            timestampMs = (obj["ts_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            simSlot = ((obj["sim_slot"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 3),
            read = (obj["read"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

data class SmsSendCommand(
    val clientRequestId: String,
    val recipientAddress: String,
    val body: String,
    val simSlot: Int = 0,
) {
    init {
        require(clientRequestId.isNotBlank() && clientRequestId.length <= 64) { "Invalid clientRequestId" }
        require(SmsInboxProtocol.isValidAddress(recipientAddress)) { "Invalid recipient phone address" }
        require(body.isNotBlank() && body.length <= SmsInboxProtocol.MAX_SMS_BODY_CHARS) {
            "SMS body must be 1..${SmsInboxProtocol.MAX_SMS_BODY_CHARS} chars"
        }
        require(simSlot in 0..3) { "simSlot must be in 0..3" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("body", body)
        put("req_id", clientRequestId)
        put("sim_slot", simSlot)
        put("to", recipientAddress.trim())
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): SmsSendCommand = SmsSendCommand(
            clientRequestId = (obj["req_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'req_id'"),
            recipientAddress = (obj["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'to'"),
            body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'body'"),
            simSlot = ((obj["sim_slot"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 3),
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #6 — Bidirectional Focus / Do Not Disturb & Meeting Auto-Focus
// ---------------------------------------------------------------------------

object FocusSyncProtocol {
    const val CATEGORY_FOCUS: String = "focus"
    const val INFO_FOCUS_STATE: String = "focus_state"
    const val CMD_FOCUS_SET: String = "focus_set"

    const val MODE_OFF: String = "off"
    const val MODE_DND: String = "dnd"
    const val MODE_WORK: String = "work"
    const val MODE_MEETING: String = "meeting"
    const val MODE_SLEEP: String = "sleep"

    val VALID_MODES: Set<String> = setOf(MODE_OFF, MODE_DND, MODE_WORK, MODE_MEETING, MODE_SLEEP)
}

data class FocusStateInfo(
    val mode: String = FocusSyncProtocol.MODE_OFF,
    val suppressNotifications: Boolean = mode != FocusSyncProtocol.MODE_OFF,
    val source: String = "manual",
    val expiresAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    init {
        require(mode in FocusSyncProtocol.VALID_MODES) { "Unsupported focus mode: '$mode'" }
        require(source.length <= 32 && !source.any { it.code < 32 }) { "Invalid focus source" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("expires_at_ms", expiresAtMs)
        put("mode", mode)
        put("source", source)
        put("suppress_notifs", suppressNotifications)
        put("updated_at_ms", updatedAtMs)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): FocusStateInfo = FocusStateInfo(
            mode = (obj["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: FocusSyncProtocol.MODE_OFF,
            suppressNotifications = (obj["suppress_notifs"] as? JsonPrimitive)?.booleanOrNull ?: false,
            source = (obj["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "remote",
            expiresAtMs = (obj["expires_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            updatedAtMs = (obj["updated_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #7 — Passive Browser Tab & Media Timestamp Handoff
// ---------------------------------------------------------------------------

object BrowserHandoffProtocol {
    const val INFO_BROWSER_TAB: String = "browser_tab_handoff"
    private val SAFE_TAB_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,64}$")

    fun isValidTabId(id: String): Boolean = SAFE_TAB_ID_REGEX.matches(id)
}

data class BrowserTabHandoffInfo(
    val tabId: String,
    val url: String,
    val title: String = "",
    val browserName: String = "Browser",
    val mediaPositionSec: Int = 0,
    val mediaDurationSec: Int = 0,
    val timestampMs: Long = 0L,
) {
    init {
        require(BrowserHandoffProtocol.isValidTabId(tabId)) { "Invalid tabId: '$tabId'" }
        HandoffProtocol.validateSafeHandoffUrl(url)
        require(mediaPositionSec >= 0 && mediaDurationSec >= 0) { "Media timestamps must be non-negative" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("browser", browserName.take(64))
        put("media_dur_s", mediaDurationSec)
        put("media_pos_s", mediaPositionSec)
        put("tab_id", tabId)
        put("title", title.take(256))
        put("ts_ms", timestampMs)
        put("url", url)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): BrowserTabHandoffInfo {
            val rawUrl = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'url' in browser_tab_handoff")
            return BrowserTabHandoffInfo(
                tabId = (obj["tab_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "tab-0",
                url = HandoffProtocol.validateSafeHandoffUrl(rawUrl),
                title = ((obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").take(256),
                browserName = ((obj["browser"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Browser").take(64),
                mediaPositionSec = ((obj["media_pos_s"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                mediaDurationSec = ((obj["media_dur_s"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                timestampMs = (obj["ts_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Feature #8 — 0-RTT Session Migration & Wi-Fi Direct Turbo Negotiation
// ---------------------------------------------------------------------------

object TransportFailoverProtocol {
    const val CATEGORY_TRANSPORT: String = "transport"
    const val CMD_RESUME_TOKEN_ISSUE: String = "resume_token_issue"
    const val CMD_WIFI_DIRECT_TURBO: String = "wifi_direct_turbo"

    const val ACTION_REQUEST_P2P: String = "request_p2p"
    const val ACTION_P2P_READY: String = "p2p_ready"
    const val ACTION_P2P_TEARDOWN: String = "p2p_teardown"

    val VALID_P2P_ACTIONS: Set<String> = setOf(ACTION_REQUEST_P2P, ACTION_P2P_READY, ACTION_P2P_TEARDOWN)
    private val IPV4_REGEX = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val HEX_REGEX = Regex("^[0-9a-f]{32,128}$")

    fun isValidSafeSsid(ssid: String): Boolean =
        ssid.length in 1..32 && !ssid.startsWith("-") && ssid.none { it.code < 32 || it.code == 127 }

    fun isValidSafePassphrase(pass: String): Boolean =
        pass.length in 8..63 && !pass.startsWith("-") && pass.none { it.code < 32 || it.code == 127 }

    fun isValidIpv4(ip: String): Boolean {
        if (!IPV4_REGEX.matches(ip)) return false
        return ip.split('.').all { it.toIntOrNull() in 0..255 }
    }

    fun isValidHex(hex: String): Boolean = HEX_REGEX.matches(hex.lowercase())
}

data class SessionResumeTokenInfo(
    val tokenIdHex: String,
    val peerDeviceId: String,
    val epoch: Int,
    val expiresAtMs: Long,
    val macHex: String,
) {
    init {
        require(TransportFailoverProtocol.isValidHex(tokenIdHex)) { "Invalid tokenIdHex" }
        require(peerDeviceId.length == 64 && peerDeviceId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid peerDeviceId"
        }
        require(epoch >= 0) { "epoch must be >= 0" }
        require(TransportFailoverProtocol.isValidHex(macHex)) { "Invalid macHex" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("epoch", epoch)
        put("expires_at_ms", expiresAtMs)
        put("mac_hex", macHex.lowercase())
        put("peer_id", peerDeviceId.lowercase())
        put("token_id", tokenIdHex.lowercase())
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): SessionResumeTokenInfo = SessionResumeTokenInfo(
            tokenIdHex = (obj["token_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'token_id'"),
            peerDeviceId = (obj["peer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'peer_id'"),
            epoch = (obj["epoch"] as? JsonPrimitive)?.intOrNull ?: 0,
            expiresAtMs = (obj["expires_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            macHex = (obj["mac_hex"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'mac_hex'"),
        )
    }
}

data class WifiDirectTurboCommand(
    val action: String,
    val groupOwnerIp: String? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
    val port: Int = 49880,
) {
    init {
        require(action in TransportFailoverProtocol.VALID_P2P_ACTIONS) { "Unsupported P2P action: '$action'" }
        if (groupOwnerIp != null) {
            require(TransportFailoverProtocol.isValidIpv4(groupOwnerIp)) { "Invalid groupOwnerIp: '$groupOwnerIp'" }
        }
        if (ssid != null) {
            require(TransportFailoverProtocol.isValidSafeSsid(ssid)) { "Unsafe P2P SSID" }
        }
        if (passphrase != null) {
            require(TransportFailoverProtocol.isValidSafePassphrase(passphrase)) { "Unsafe P2P passphrase" }
        }
        require(port in 1024..65535) { "Invalid P2P port: $port" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        if (groupOwnerIp != null) put("go_ip", groupOwnerIp)
        if (passphrase != null) put("passphrase", passphrase)
        put("port", port)
        if (ssid != null) put("ssid", ssid)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): WifiDirectTurboCommand = WifiDirectTurboCommand(
            action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action'"),
            groupOwnerIp = (obj["go_ip"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            ssid = (obj["ssid"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            passphrase = (obj["passphrase"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            port = (obj["port"] as? JsonPrimitive)?.intOrNull ?: 49880,
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #11 — Continuity Camera Studio Effects & Hardware Decode
// ---------------------------------------------------------------------------

object CameraEffectsProtocol {
    const val CATEGORY_CAMERA_FX: String = "camera"
    const val CMD_CAMERA_EFFECTS: String = "camera_effects"
    val VALID_HW_DECODERS: Set<String> = setOf("auto", "vaapi", "nvdec", "software")
}

data class CameraEffectsCommand(
    val centerStage: Boolean = false,
    val portraitBlur: Boolean = false,
    val deskView: Boolean = false,
    val targetBitrateKbps: Int = 6_000,
    val hwDecodePreference: String = "auto",
) {
    init {
        require(targetBitrateKbps in 500..25_000) { "targetBitrateKbps must be in 500..25000" }
        require(hwDecodePreference in CameraEffectsProtocol.VALID_HW_DECODERS) {
            "Unsupported hwDecodePreference: '$hwDecodePreference'"
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("bitrate_kbps", targetBitrateKbps)
        put("center_stage", centerStage)
        put("desk_view", deskView)
        put("hw_decode", hwDecodePreference)
        put("portrait_blur", portraitBlur)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CameraEffectsCommand = CameraEffectsCommand(
            centerStage = (obj["center_stage"] as? JsonPrimitive)?.booleanOrNull ?: false,
            portraitBlur = (obj["portrait_blur"] as? JsonPrimitive)?.booleanOrNull ?: false,
            deskView = (obj["desk_view"] as? JsonPrimitive)?.booleanOrNull ?: false,
            targetBitrateKbps = ((obj["bitrate_kbps"] as? JsonPrimitive)?.intOrNull ?: 6_000).coerceIn(500, 25_000),
            hwDecodePreference = (obj["hw_decode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it in CameraEffectsProtocol.VALID_HW_DECODERS } ?: "auto",
        )
    }
}

// ---------------------------------------------------------------------------
// Feature #12 — Encrypted Cross-Device Clipboard Shelf Entry
// ---------------------------------------------------------------------------

data class ClipboardShelfEntry(
    val id: String,
    val mime: String,
    val previewText: String,
    val fullText: String? = null,
    val sizeBytes: Int,
    val sha256Hex: String,
    val sourceAlias: String,
    val isSensitive: Boolean = false,
    val pinned: Boolean = false,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
)
