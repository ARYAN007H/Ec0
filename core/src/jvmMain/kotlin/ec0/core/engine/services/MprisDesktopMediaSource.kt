/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B2 — MPris2 desktop media source over the session bus. Enumerates
 * `org.mpris.MediaPlayer2.*` players, exposes PlaybackStatus/Metadata/
 * Position as a StateFlow the phone renders, and scopes control commands to
 * the ACTIVE player (playerctl `--player=` targeting).
 *
 * All D-Bus text is produced by `busctl --user --json=short` invocations
 * (injected launcher — CI parses fixtures, never a live session):
 *   - player enumeration: `busctl --user list` rows containing
 *     `org.mpris.MediaPlayer2.`
 *   - properties: `busctl --user get-property <dest> /org/mpris/MediaPlayer2
 *     org.mpris.MediaPlayer2.Player <Property>` → {"type":"…","data":[…]}
 */
package ec0.core.engine.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.isString
import kotlinx.serialization.json.longOrNull


/** Pure parsing of busctl output — fixture-tested, no live D-Bus. */
object MprisParser {

    const val MPRIS_PREFIX: String = "org.mpris.MediaPlayer2."

    /** Extracts MPris destination names from a `busctl --user list` dump
     *  (first column; instance-suffixed names like firefox.instance1234 are
     *  valid destinations). */
    fun parseListNames(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains(MPRIS_PREFIX) }
            .mapNotNull { line ->
                line.split(Regex("\\s+")).firstOrNull { it.startsWith(MPRIS_PREFIX) }
            }
            .distinct()
            .toList()

    /**
     * `{"type":"s","data":["Playing"]}` → `"Playing"`; returns null for
     * anything but a single string payload.
     */
    fun parseStringProperty(raw: String): String? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "s") return null
        val data = root["data"]?.jsonArray ?: return null
        (data.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }.getOrNull()

    /**
     * `{"type":"x","data":[231000000]}` → 231000000L (Position/Length are
     * signed 64-bit microseconds).
     */
    fun parseInt64Property(raw: String): Long? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "x") return null
        val data = root["data"]?.jsonArray ?: return null
        (data.firstOrNull() as? JsonPrimitive)?.longOrNull
    }.getOrNull()

    /**
     * Parses an `a{sv}` Metadata payload into a flat map of the keys ec0
     * cares about: xesam:title, xesam:artist, xesam:album, mpris:length.
     * Handles s (string), as (string array joined), x (int64) value shapes.
     */
    fun parseMetadata(raw: String): Map<String, String> = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "a{sv}") return emptyMap()
        val entries = (root["data"]?.jsonArray?.firstOrNull() as? JsonObject)
            ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((key, value) in entries) {
            val valueObj = value as? JsonObject ?: continue
            val type = valueObj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val first = (valueObj["data"]?.jsonArray?.firstOrNull()) ?: continue
            when (type) {
                "s" -> out[key] = first.jsonPrimitive.content
                "as" -> out[key] = first.jsonArray.joinToString(", ") { it.jsonPrimitive.content }
                "x" -> out[key] = (first.jsonPrimitive.longOrNull ?: 0L).toString()
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** Builds the ec0 media-session model from parsed MPris properties. */
    fun toMediaSessionInfo(
        playerId: String,
        playbackStatus: String?,
        metadata: Map<String, String>,
        positionUs: Long?,
    ): MediaSessionInfo = MediaSessionInfo(
        playerName = playerId.removePrefix(MPRIS_PREFIX),
        title = metadata["xesam:title"] ?: "Unknown title",
        artist = metadata["xesam:artist"] ?: "",
        album = metadata["xesam:album"] ?: "",
        state = when (playbackStatus) {
            "Playing" -> MediaPlaybackState.PLAYING
            "Paused" -> MediaPlaybackState.PAUSED
            else -> MediaPlaybackState.STOPPED
        },
        positionMs = (positionUs ?: 0L) / 1_000,
        durationMs = metadata["mpris:length"]?.toLongOrNull()?.div(1_000) ?: 0L,
        volumePct = 100,
    )

    /** playerctl targeting a SPECIFIC player (kills firstOrNull ambiguity). */
    fun buildTargetedPlayerctlCommand(command: MediaControlCommand, playerId: String): List<String>? {
        val base = listOf("playerctl", "--player=$playerId")
        return when (command.action) {
            MediaControlCommand.ACTION_PLAY -> base + "play"
            MediaControlCommand.ACTION_PAUSE -> base + "pause"
            MediaControlCommand.ACTION_PLAY_PAUSE -> base + "play-pause"
            MediaControlCommand.ACTION_NEXT -> base + "next"
            MediaControlCommand.ACTION_PREVIOUS -> base + "previous"
            MediaControlCommand.ACTION_SET_VOLUME -> {
                val vol = command.volumePct ?: 100
                base + "volume" + "%.2f".format(java.util.Locale.US, vol / 100.0)
            }
            else -> null
        }
    }

}

/**
 * Polls MPris properties over the session bus and exposes the active
 * player's state. The [launcher] is injected — CI tests parse fixtures.
 */
class MprisDesktopMediaSource(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val timeoutMs: Long = 5_000L,
) {
    private val _session = MutableStateFlow<MediaSessionInfo?>(null)
    val mediaSession: StateFlow<MediaSessionInfo?> = _session.asStateFlow()

    private var activePlayerId: String? = null

    fun buildListNamesCommand(): List<String> =
        listOf("busctl", "--user", "list")

    fun buildGetPropertyCommand(playerId: String, property: String): List<String> =
        listOf(
            "busctl", "--user", "get-property",
            playerId, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", property,
            "--json=short",
        )

    /** One poll tick: enumerate → read PlaybackStatus/Metadata/Position → publish. */
    fun poll(nowMs: Long = System.currentTimeMillis()): MediaSessionInfo? {
        val player = activePlayerId ?: firstPlayerOrNull() ?: run {
            _session.value = null
            return null
        }
        val status = runCatching { launcher.runBlockingCommand(buildGetPropertyCommand(player, "PlaybackStatus"), timeoutMs) }
            .getOrNull()?.stdout?.trim().takeUnless { it.isNullOrBlank() }
        val metadata = runCatching { launcher.runBlockingCommand(buildGetPropertyCommand(player, "Metadata"), timeoutMs) }
            .getOrNull()?.stdout?.trim().takeUnless { it.isNullOrBlank() }
        val position = runCatching { launcher.runBlockingCommand(buildGetPropertyCommand(player, "Position"), timeoutMs) }
            .getOrNull()?.stdout?.trim().takeUnless { it.isNullOrBlank() }

        val info = MprisParser.toMediaSessionInfo(
            playerId = player,
            playbackStatus = status?.let(MprisParser::parseStringProperty),
            metadata = metadata?.let(MprisParser::parseMetadata) ?: emptyMap(),
            positionUs = position?.let(MprisParser::parseInt64Property),
        )
        _session.value = info
        return info
    }

    /** Sets the player subsequent polls + commands target (from enumeration). */
    fun setActivePlayer(playerId: String?) {
        activePlayerId = playerId
    }

    fun firstPlayerOrNull(raw: String? = null): String? {
        val dump = raw ?: runCatching { launcher.runBlockingCommand(buildListNamesCommand(), timeoutMs) }
            .getOrNull()?.stdout ?: return null
        return MprisParser.parseListNames(dump).firstOrNull()
    }

    /** Targeted control command for the active player (argv — see [MprisParser]). */
    fun buildTargetedCommand(command: MediaControlCommand): List<String>? {
        val player = activePlayerId ?: return null
        return MprisParser.buildTargetedPlayerctlCommand(command, player)
    }
}
