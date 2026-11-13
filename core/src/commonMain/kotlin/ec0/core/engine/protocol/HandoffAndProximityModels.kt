/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Slice 11 Continuity Wire Models & Pure-Kotlin Helpers (`commonMain`):
 *   1. Seamless App & Browser Handoff (`category = "handoff"`, `handoff_activity`,
 *      `handoff_open`) with strict `http`/`https` URI validation (`validateSafeHandoffUrl`).
 *   2. Smart 2FA / OTP Verification Code Extractor (`OtpCodeExtractor`).
 *   3. Proximity Workstation Lock & "Find My Phone" Siren (`category = "sec"`,
 *      `workstation_lock`, `find_my_ring`).
 *   4. Continuity Sketch / Instant Screenshot Vector Markup (`ContinuitySketchStroke`,
 *      `ContinuitySketchOverlay`) with integer-only `0..1000` permille coordinates
 *      for deterministic `CanonicalJson` signing.
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

object HandoffProtocol {
    const val CATEGORY_HANDOFF: String = "handoff"
    const val INFO_HANDOFF_ACTIVITY: String = "handoff_activity"
    const val CMD_HANDOFF_OPEN: String = "handoff_open"

    private val SAFE_URL_REGEX = Regex(
        pattern = "^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[^\\s\"'<>\\\\]*)?$",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Validates that [rawUrl] is a safe `http://` or `https://` URL with a valid
     * host and zero whitespace, quotes, or shell metacharacters before passing it
     * to `xdg-open` or Android `Intent.ACTION_VIEW`.
     */
    fun validateSafeHandoffUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.length in 10..2048) {
            "Handoff URL length out of bounds (${trimmed.length})"
        }
        require(SAFE_URL_REGEX.matches(trimmed)) {
            "Rejected unsafe handoff URL (only http:// and https:// without control characters are allowed)"
        }
        return trimmed
    }
}

/**
 * Broadcast by Android or Linux when the user has an active browser tab or document
 * ready for 1-click Continuity Handoff (`info_type = "handoff_activity"`).
 */
data class HandoffActivityInfo(
    val activityId: String,
    val title: String,
    val url: String,
    val sourceApp: String = "Browser",
    val scrollPermille: Int = 0,
    val timestampMs: Long,
) {
    init {
        require(activityId.isNotBlank()) { "activityId must not be blank" }
        HandoffProtocol.validateSafeHandoffUrl(url)
        require(scrollPermille in 0..1_000) { "scrollPermille must be in 0..1000 (got $scrollPermille)" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("activity_id", activityId)
        put("scroll_permille", scrollPermille)
        put("source_app", sourceApp)
        put("title", title)
        put("ts_ms", timestampMs)
        put("url", url)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): HandoffActivityInfo {
            val activityId = (obj["activity_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'activity_id' in handoff_activity")
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val rawUrl = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'url' in handoff_activity")
            val sourceApp = (obj["source_app"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Browser"
            val scroll = (obj["scroll_permille"] as? JsonPrimitive)?.intOrNull ?: 0
            val ts = (obj["ts_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
            return HandoffActivityInfo(
                activityId = activityId,
                title = title,
                url = HandoffProtocol.validateSafeHandoffUrl(rawUrl),
                sourceApp = sourceApp,
                scrollPermille = scroll,
                timestampMs = ts,
            )
        }
    }
}

/**
 * Direct command to open a URL on the target peer (`cmd_type = "handoff_open"`).
 */
data class HandoffOpenCommand(
    val url: String,
    val title: String = "",
    val scrollPermille: Int = 0,
) {
    init {
        HandoffProtocol.validateSafeHandoffUrl(url)
        require(scrollPermille in 0..1_000) { "scrollPermille must be in 0..1000" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("scroll_permille", scrollPermille)
        put("title", title)
        put("url", url)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): HandoffOpenCommand {
            val rawUrl = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'url' in handoff_open")
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val scroll = (obj["scroll_permille"] as? JsonPrimitive)?.intOrNull ?: 0
            return HandoffOpenCommand(
                url = HandoffProtocol.validateSafeHandoffUrl(rawUrl),
                title = title,
                scrollPermille = scroll,
            )
        }
    }
}

/**
 * Pure-Kotlin 2FA / OTP verification code extractor for incoming Android notifications.
 */
object OtpCodeExtractor {
    private val CONTEXT_KEYWORDS = listOf(
        "code",
        "otp",
        "verification",
        "verify",
        "passcode",
        "2fa",
        "security",
        "login",
        "authenticate",
        "pin",
    )

    private val DIGIT_CANDIDATE_REGEX = Regex("(?<![0-9A-Za-z$-])([0-9]{4,8})(?![0-9A-Za-z%])")

    /**
     * Extracts a 4–8 digit OTP / 2FA code from [title] and [body] when the message
     * contains authentication context keywords.
     */
    fun extractOtpCode(title: String, body: String): String? {
        val combined = "$title $body"
        val lower = combined.lowercase()
        if (CONTEXT_KEYWORDS.none { lower.contains(it) }) {
            return null
        }
        val matches = DIGIT_CANDIDATE_REGEX.findAll(combined).map { it.groupValues[1] }.toList()
        // Prefer 6-digit codes first, then 4..8 digit codes that don't look like a 202x year
        val sixDigit = matches.firstOrNull { it.length == 6 }
        if (sixDigit != null) return sixDigit
        return matches.firstOrNull { candidate ->
            !(candidate.length == 4 && candidate.startsWith("202"))
        }
    }
}

object ProximitySecurityProtocol {
    const val CATEGORY_SECURITY: String = "sec"
    const val CMD_WORKSTATION_LOCK: String = "workstation_lock"
    const val CMD_FIND_MY_RING: String = "find_my_ring"
}

data class WorkstationLockCommand(
    val action: String = ACTION_LOCK,
    val reason: String = "remote_request",
) {
    init {
        require(action == ACTION_LOCK || action == ACTION_UNLOCK_HINT) {
            "Unsupported workstation_lock action: '$action'"
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("reason", reason)
    }

    companion object {
        const val ACTION_LOCK: String = "lock"
        const val ACTION_UNLOCK_HINT: String = "unlock_hint"

        fun fromJsonObject(obj: JsonObject): WorkstationLockCommand {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ACTION_LOCK
            val reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "remote_request"
            return WorkstationLockCommand(action = action, reason = reason)
        }
    }
}

data class FindMyRingCommand(
    val action: String = ACTION_START_RING,
    val durationSeconds: Int = 15,
    val overrideDnd: Boolean = true,
) {
    init {
        require(action == ACTION_START_RING || action == ACTION_STOP_RING) {
            "Unsupported find_my_ring action: '$action'"
        }
        require(durationSeconds in 1..120) {
            "durationSeconds must be in 1..120 (got $durationSeconds)"
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("duration_sec", durationSeconds)
        put("override_dnd", overrideDnd)
    }

    companion object {
        const val ACTION_START_RING: String = "start_ring"
        const val ACTION_STOP_RING: String = "stop_ring"

        fun fromJsonObject(obj: JsonObject): FindMyRingCommand {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ACTION_START_RING
            val duration = (obj["duration_sec"] as? JsonPrimitive)?.intOrNull ?: 15
            val overrideDnd = (obj["override_dnd"] as? JsonPrimitive)?.booleanOrNull ?: true
            return FindMyRingCommand(
                action = action,
                durationSeconds = duration,
                overrideDnd = overrideDnd,
            )
        }
    }
}

/**
 * Normalized (`0..1000` permille) point in a Continuity Sketch annotation stroke.
 */
data class ContinuitySketchPoint(
    val xPermille: Int,
    val yPermille: Int,
) {
    init {
        require(xPermille in 0..1_000) { "xPermille out of range: $xPermille" }
        require(yPermille in 0..1_000) { "yPermille out of range: $yPermille" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("x", xPermille)
        put("y", yPermille)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): ContinuitySketchPoint = ContinuitySketchPoint(
            xPermille = (obj["x"] as? JsonPrimitive)?.intOrNull ?: 0,
            yPermille = (obj["y"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
}

data class ContinuitySketchStroke(
    val colorHex: String = "#3B82F6",
    val strokeWidthPx: Int = 4,
    val points: List<ContinuitySketchPoint>,
) {
    init {
        require(points.isNotEmpty()) { "ContinuitySketchStroke must contain at least 1 point" }
        require(strokeWidthPx in 1..64) { "strokeWidthPx must be in 1..64" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("color", colorHex)
        put(
            "points",
            buildJsonArray {
                for (pt in points) add(pt.toJsonObject())
            },
        )
        put("width_px", strokeWidthPx)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): ContinuitySketchStroke {
            val color = (obj["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "#3B82F6"
            val width = (obj["width_px"] as? JsonPrimitive)?.intOrNull ?: 4
            val ptsArr = (obj["points"] as? JsonArray) ?: emptyList()
            val pts = ptsArr.mapNotNull { el ->
                (el as? JsonObject)?.let { ContinuitySketchPoint.fromJsonObject(it) }
            }
            return ContinuitySketchStroke(
                colorHex = color,
                strokeWidthPx = width,
                points = pts,
            )
        }
    }
}

data class ContinuitySketchOverlay(
    val baseClipSha256: String,
    val strokes: List<ContinuitySketchStroke>,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("base_clip_sha256", baseClipSha256)
        put(
            "strokes",
            buildJsonArray {
                for (s in strokes) add(s.toJsonObject())
            },
        )
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): ContinuitySketchOverlay {
            val sha = (obj["base_clip_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val arr = (obj["strokes"] as? JsonArray) ?: emptyList()
            val strokes = arr.mapNotNull { el ->
                (el as? JsonObject)?.let { ContinuitySketchStroke.fromJsonObject(it) }
            }
            return ContinuitySketchOverlay(baseClipSha256 = sha, strokes = strokes)
        }
    }
}
