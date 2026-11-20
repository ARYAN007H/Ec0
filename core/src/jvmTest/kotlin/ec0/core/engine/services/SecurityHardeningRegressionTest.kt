/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Defensive security regression tests for the production hardening pass:
 *   - S1: Receiver-side local per-peer input capability revocation in Ec0EngineNode
 *   - S2: VfsPhoneService dotfile direct-access block & escaping symlink exclusion in listings
 *   - S3: MprisParser playerId option-injection block + volume clamping; nmcli SSID/passphrase validation
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConstants
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.PresenceProtocol
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityHardeningRegressionTest {

    @Test
    fun localInputGrantGateBlocksRemoteInputEvenWhenPeerBeaconAdvertisesInput() {
        val desktopKey = Ed25519Identity.generate()
        val phoneKey = Ed25519Identity.generate()
        var granted = true
        val receivedKeys = mutableListOf<Int>()

        val node = Ec0EngineNode(
            localIdentity = desktopKey,
            displayName = "Desktop",
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            inputEventSink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    receivedKeys += event.keyCode
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
            localInputGrantGate = { peerId -> granted && peerId.equals(phoneKey.deviceId, ignoreCase = true) },
        )
        try {
            val phoneProc = SessionEnvelopeProcessor(phoneKey, desktopKey.deviceId, desktopKey.publicKeyRaw)
            // Peer sends a valid presence beacon advertising INPUT + REMOTE_INPUT
            val beaconEnv = phoneProc.nextInfoEnvelope(
                infoType = PresenceProtocol.INFO_PRESENCE_BEACON,
                infoBody = PresenceBeaconInfo(
                    batteryPct = 85,
                    isCharging = false,
                    screenOn = true,
                    capabilities = setOf(EngineCapability.INPUT, EngineCapability.REMOTE_INPUT),
                ).toJsonObject(),
                category = EngineConstants.CATEGORY_SYSTEM,
            )
            node.presenceService.handleControlEnvelope(phoneKey.deviceId, beaconEnv)

            // 1. Allowed while local grant is active
            val keyAllowed = phoneProc.nextCmdEnvelope(
                cmdType = InputProtocol.CMD_INPUT_KEY,
                cmdBody = InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = 30).toJsonObject(),
                category = InputProtocol.CATEGORY_INPUT,
            )
            assertTrue(node.inputShareService.handleControlEnvelope(phoneKey.deviceId, keyAllowed))
            assertEquals(listOf(30), receivedKeys)

            // 2. Local user revokes remote_input -> subsequent keystrokes MUST be dropped
            granted = false
            val keyRevoked = phoneProc.nextCmdEnvelope(
                cmdType = InputProtocol.CMD_INPUT_KEY,
                cmdBody = InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = 31).toJsonObject(),
                category = InputProtocol.CATEGORY_INPUT,
            )
            assertTrue(node.inputShareService.handleControlEnvelope(phoneKey.deviceId, keyRevoked))
            assertEquals(listOf(30), receivedKeys, "revoked peer must not inject keystrokes")
            assertEquals(1L, node.inputShareService.capabilityDenied)
        } finally {
            node.close()
        }
    }

    @Test
    fun vfsPhoneServiceBlocksDirectDotfileAccessAndHidesEscapingSymlinksInListings() {
        val root = Files.createTempDirectory("ec0-vfs-sec").toFile()
        val outside = Files.createTempDirectory("ec0-vfs-out").toFile()
        try {
            val downloads = File(root, "Downloads").apply { mkdirs() }
            File(downloads, "public.txt").writeText("ok")
            File(downloads, ".secret_token").writeText("top-secret")
            val hiddenDir = File(downloads, ".hidden").apply { mkdirs() }
            File(hiddenDir, "keys.txt").writeText("private-key")
            val secretOut = File(outside, "shadow.txt").apply { writeText("outside") }
            Files.createSymbolicLink(File(downloads, "escape_link.txt").toPath(), secretOut.toPath())

            val vfs = VfsPhoneService(rootsProvider = { mapOf("Downloads" to downloads) })
            val phoneKey = Ed25519Identity.generate()
            val desktopKey = Ed25519Identity.generate()
            val proc = SessionEnvelopeProcessor(desktopKey, phoneKey.deviceId, phoneKey.publicKeyRaw)

            // 1. Listing /Downloads must exclude dotfiles AND the escaping symlink
            var listedNames: List<String> = emptyList()
            val listEnv = proc.nextCmdEnvelope(
                cmdType = VfsProtocol.CMD_VFS_LIST,
                cmdBody = buildJsonObject { put("path", "/Downloads") },
                category = VfsProtocol.CATEGORY_VFS,
            )
            vfs.handleControlEnvelope(desktopKey.deviceId, listEnv) { infoType, body ->
                if (infoType == VfsProtocol.INFO_VFS_DIR) {
                    listedNames = (body["entries"] as JsonArray).map {
                        it.jsonObject["name"]!!.jsonPrimitive.content
                    }
                }
            }
            assertEquals(listOf("public.txt"), listedNames)

            // 2. Direct dotfile download and hidden directory listing must both fail
            val errors = mutableListOf<String>()
            val collectErr: (String, JsonObject) -> Unit = { infoType, body ->
                if (infoType == VfsProtocol.INFO_VFS_ERROR) {
                    errors += body["message"]?.jsonPrimitive?.content.orEmpty()
                }
            }
            vfs.handleControlEnvelope(
                desktopKey.deviceId,
                proc.nextCmdEnvelope(
                    cmdType = VfsProtocol.CMD_VFS_DOWNLOAD,
                    cmdBody = buildJsonObject { put("path", "/Downloads/.secret_token") },
                    category = VfsProtocol.CATEGORY_VFS,
                ),
                collectErr,
            )
            vfs.handleControlEnvelope(
                desktopKey.deviceId,
                proc.nextCmdEnvelope(
                    cmdType = VfsProtocol.CMD_VFS_DOWNLOAD,
                    cmdBody = buildJsonObject { put("path", "/Downloads/.hidden/keys.txt") },
                    category = VfsProtocol.CATEGORY_VFS,
                ),
                collectErr,
            )
            assertEquals(2, errors.size)
            assertTrue(errors.all { it.contains("outside granted roots") })
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun mprisParserRejectsInjectedPlayerIdsAndClampsVolume() {
        val play = MediaControlCommand(action = MediaControlCommand.ACTION_PLAY)
        assertNotNull(MprisParser.buildTargetedPlayerctlCommand(play, "org.mpris.MediaPlayer2.spotify"))
        assertNotNull(MprisParser.buildTargetedPlayerctlCommand(play, "firefox.instance_1234"))

        // Option injection, shell metacharacters, whitespace, control chars, blank
        assertNull(MprisParser.buildTargetedPlayerctlCommand(play, "--all-players"))
        assertNull(MprisParser.buildTargetedPlayerctlCommand(play, "-x"))
        assertNull(MprisParser.buildTargetedPlayerctlCommand(play, "spotify;id"))
        assertNull(MprisParser.buildTargetedPlayerctlCommand(play, "spotify --format={{title}}"))
        assertNull(MprisParser.buildTargetedPlayerctlCommand(play, ""))

        // Model itself rejects out-of-range volumePct; valid boundaries format cleanly
        assertFailsWith<IllegalArgumentException> {
            MediaControlCommand(action = MediaControlCommand.ACTION_SET_VOLUME, volumePct = 9999)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaControlCommand(action = MediaControlCommand.ACTION_SET_VOLUME, volumePct = -250)
        }
        val maxVol = MprisParser.buildTargetedPlayerctlCommand(
            MediaControlCommand(action = MediaControlCommand.ACTION_SET_VOLUME, volumePct = 100),
            "spotify",
        )
        assertEquals(listOf("playerctl", "--player=spotify", "volume", "1.00"), maxVol)
        val minVol = MprisParser.buildTargetedPlayerctlCommand(
            MediaControlCommand(action = MediaControlCommand.ACTION_SET_VOLUME, volumePct = 0),
            "spotify",
        )
        assertEquals(listOf("playerctl", "--player=spotify", "volume", "0.00"), minVol)
    }

    @Test
    fun nmcliConnectCommandRejectsFlagInjectionAndControlCharacters() {
        val svc = HotspotAndTelephonyService()
        assertEquals(
            listOf("nmcli", "device", "wifi", "connect", "PixelHotspot", "password", "correcthorse123"),
            svc.buildNmcliConnectCommand("PixelHotspot", "correcthorse123"),
        )
        assertFailsWith<IllegalArgumentException> { svc.buildNmcliConnectCommand("--help", "pass") }
        assertFailsWith<IllegalArgumentException> { svc.buildNmcliConnectCommand("-bad", "pass") }
        assertFailsWith<IllegalArgumentException> { svc.buildNmcliConnectCommand("Net\nName", "pass") }
        assertFailsWith<IllegalArgumentException> { svc.buildNmcliConnectCommand("Net", "pass\u0000word") }
        assertFailsWith<IllegalArgumentException> { svc.buildNmcliConnectCommand("a".repeat(65), "pass") }
    }
}
