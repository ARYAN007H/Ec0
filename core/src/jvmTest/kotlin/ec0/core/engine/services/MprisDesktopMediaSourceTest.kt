/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * MPris fixture tests — the exact `busctl --user --json=short` reply shapes,
 * parsed without a live session bus. Also: player enumeration, targeted
 * playerctl argv, and the poll loop through an injected fake launcher.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.MediaControlCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MprisDesktopMediaSourceTest {

    private val statusFixture = """{"type":"s","data":["Playing"]}"""
    private val metadataFixture = """{"type":"a{sv}","data":[
        {"mpris:length":{"type":"x","data":[231000000]},
         "xesam:title":{"type":"s","data":["Blue Monday"]},
         "xesam:artist":{"type":"as","data":["New Order"]},
         "xesam:album":{"type":"s","data":["Substance"]}}]}"""
    private val positionFixture = """{"type":"x","data":[42000000]}"""

    @Test
    fun listNamesExtractsMprisDestinationsOnly() {
        val dump = """
            NAME         PID  PROCESS   USER
            org.freedesktop.DBus 1 dbus 0
            org.mpris.MediaPlayer2.vlc 4021 vlc 1000
            org.mpris.MediaPlayer2.firefox.instance1234 2911 firefox 1000
            com.example.Other 100 x 1000
        """.trimIndent()
        val players = MprisParser.parseListNames(dump)
        // instance-suffixed names are valid MPris destinations — keep them whole
        assertEquals(
            listOf("org.mpris.MediaPlayer2.vlc", "org.mpris.MediaPlayer2.firefox.instance1234"),
            players,
        )
    }

    @Test
    fun parsesStringProperty() {
        assertEquals("Playing", MprisParser.parseStringProperty(statusFixture))
        assertNull(MprisParser.parseStringProperty("""{"type":"x","data":[1]}"""))
        assertNull(MprisParser.parseStringProperty("not json"))
    }

    @Test
    fun parsesInt64Property() {
        assertEquals(42_000_000L, MprisParser.parseInt64Property(positionFixture))
        assertNull(MprisParser.parseInt64Property(statusFixture))
    }

    @Test
    fun parsesMetadataFixtureIntoSessionFields() {
        val metadata = MprisParser.parseMetadata(metadataFixture)
        assertEquals("Blue Monday", metadata["xesam:title"])
        assertEquals("New Order", metadata["xesam:artist"])
        assertEquals("Substance", metadata["xesam:album"])
        assertEquals("231000000", metadata["mpris:length"])

        val info = MprisParser.toMediaSessionInfo(
            playerId = "org.mpris.MediaPlayer2.vlc",
            playbackStatus = "Playing",
            metadata = metadata,
            positionUs = 42_000_000L,
        )
        assertEquals("vlc", info.playerName)
        assertEquals("Blue Monday", info.title)
        assertEquals("New Order", info.artist)
        assertEquals("Substance", info.album)
        assertEquals(ec0.core.engine.protocol.MediaPlaybackState.PLAYING, info.state)
        assertEquals(42_000L, info.positionMs)
        assertEquals(231_000L, info.durationMs)
    }


    @Test
    fun targetedPlayerctlCommandCarriesPlayerScope() {
        val cmd = MediaControlCommand(action = MediaControlCommand.ACTION_NEXT)
        assertEquals(
            listOf("playerctl", "--player=org.mpris.MediaPlayer2.vlc", "next"),
            MprisParser.buildTargetedPlayerctlCommand(cmd, "org.mpris.MediaPlayer2.vlc"),
        )
        val volume = MediaControlCommand(action = MediaControlCommand.ACTION_SET_VOLUME, volumePct = 35)
        assertEquals(
            listOf("playerctl", "--player=org.mpris.MediaPlayer2.vlc", "volume", "0.35"),
            MprisParser.buildTargetedPlayerctlCommand(volume, "org.mpris.MediaPlayer2.vlc"),
        )
    }

    @Test
    fun pollThroughFakeLauncherPublishesSessionState() {
        val replies = mapOf(
            "PlaybackStatus" to statusFixture,
            "Metadata" to metadataFixture,
            "Position" to positionFixture,
        )
        val launcher = FakeBusctlLauncher(
            listDump = "org.mpris.MediaPlayer2.vlc 4021 vlc 1000",
            propertyReplies = replies,
        )
        val source = MprisDesktopMediaSource(launcher = launcher)
        source.setActivePlayer("org.mpris.MediaPlayer2.vlc")

        val info = source.poll()
        assertEquals("vlc", info?.playerName)
        assertEquals("Blue Monday", info?.title)
        assertEquals(42_000L, info?.positionMs)
        // the wire commands the source actually issued
        assertTrue(launcher.commands.any { it.take(3) == listOf("busctl", "--user", "list") })
        assertTrue(launcher.commands.any { it.contains("PlaybackStatus") })
    }

    @Test
    fun pollWithoutPlayerPublishesNull() {
        val source = MprisDesktopMediaSource(launcher = FakeBusctlLauncher("", emptyMap()))
        assertNull(source.poll())
        assertNull(source.mediaSession.value)
    }

    @Test
    fun targetedCommandIsNullWithoutActivePlayer() {
        val source = MprisDesktopMediaSource(launcher = FakeBusctlLauncher("", emptyMap()))
        assertNull(source.buildTargetedCommand(MediaControlCommand(action = MediaControlCommand.ACTION_PLAY)))
    }
}

/** Fake launcher returning fixture payloads per property — no processes. */
private class FakeBusctlLauncher(
    private val listDump: String,
    private val propertyReplies: Map<String, String>,
) : ExternalProcessLauncher {
    val commands = mutableListOf<List<String>>()

    override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
        commands.add(command)
        return when {
            command.contains("list") -> CommandExecResult(0, listDump, "")
            command.contains("PlaybackStatus") -> CommandExecResult(0, propertyReplies.getValue("PlaybackStatus"), "")
            command.contains("Metadata") -> CommandExecResult(0, propertyReplies.getValue("Metadata"), "")
            command.contains("Position") -> CommandExecResult(0, propertyReplies.getValue("Position"), "")
            else -> CommandExecResult(0, "", "")
        }
    }

    override fun spawnProcess(command: List<String>): RunningProcessHandle =
        throw UnsupportedOperationException("no spawns in fixture tests")
}
