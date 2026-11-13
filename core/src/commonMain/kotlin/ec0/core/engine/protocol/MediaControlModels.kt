/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Bidirectional Media & MPRIS2 Control wire models (`category = "media"`,
 * `info_type = "media_session"`, `cmd_type = "media_control"`).
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object MediaControlProtocol {
    const val CATEGORY_MEDIA: String = "media"
    const val INFO_MEDIA_SESSION: String = "media_session"
    const val CMD_MEDIA_CONTROL: String = "media_control"
}

enum class MediaPlaybackState(val wireName: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped");

    companion object {
        fun fromWireName(wire: String): MediaPlaybackState =
            entries.firstOrNull { it.wireName == wire.lowercase() } ?: STOPPED
    }
}

data class MediaSessionInfo(
    val playerName: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val state: MediaPlaybackState = MediaPlaybackState.STOPPED,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volumePct: Int = 100,
) {
    init {
        require(volumePct in 0..100) { "volumePct must be in 0..100 (got $volumePct)" }
        require(positionMs >= 0L && durationMs >= 0L) { "positionMs/durationMs must be non-negative" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("album", album)
        put("artist", artist)
        put("duration_ms", durationMs)
        put("player", playerName)
        put("position_ms", positionMs)
        put("state", state.wireName)
        put("title", title)
        put("volume_pct", volumePct)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): MediaSessionInfo = MediaSessionInfo(
            playerName = (obj["player"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "default",
            title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            artist = (obj["artist"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            album = (obj["album"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            state = MediaPlaybackState.fromWireName(
                (obj["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "stopped",
            ),
            positionMs = (obj["position_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            durationMs = (obj["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            volumePct = (obj["volume_pct"] as? JsonPrimitive)?.intOrNull ?: 100,
        )
    }
}

data class MediaControlCommand(
    val action: String,
    val seekPositionMs: Long? = null,
    val volumePct: Int? = null,
) {
    init {
        require(action in VALID_ACTIONS) { "Unsupported media control action: '$action'" }
        if (volumePct != null) {
            require(volumePct in 0..100) { "volumePct must be in 0..100 (got $volumePct)" }
        }
        if (seekPositionMs != null) {
            require(seekPositionMs >= 0L) { "seekPositionMs must be >= 0" }
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        if (seekPositionMs != null) put("seek_ms", seekPositionMs)
        if (volumePct != null) put("volume_pct", volumePct)
    }

    companion object {
        const val ACTION_PLAY: String = "play"
        const val ACTION_PAUSE: String = "pause"
        const val ACTION_PLAY_PAUSE: String = "play_pause"
        const val ACTION_NEXT: String = "next"
        const val ACTION_PREVIOUS: String = "previous"
        const val ACTION_SEEK: String = "seek"
        const val ACTION_SET_VOLUME: String = "set_volume"

        val VALID_ACTIONS: Set<String> = setOf(
            ACTION_PLAY,
            ACTION_PAUSE,
            ACTION_PLAY_PAUSE,
            ACTION_NEXT,
            ACTION_PREVIOUS,
            ACTION_SEEK,
            ACTION_SET_VOLUME,
        )

        fun fromJsonObject(obj: JsonObject): MediaControlCommand = MediaControlCommand(
            action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in media_control"),
            seekPositionMs = (obj["seek_ms"] as? JsonPrimitive)?.longOrNull,
            volumePct = (obj["volume_pct"] as? JsonPrimitive)?.intOrNull,
        )
    }
}
