/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Multiplatform unit tests (`commonTest`) for Slice 9 protocol models,
 * ISO/IEC 18004 QR Code matrix generation, CamDroid mDNS codec, and HKDF
 * bulk-lane key derivation.
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.ClipboardMime
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaPlaybackState
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.ScreenEdge
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QrAndExpansionModelsTest {

    @Test
    fun qrCodeMatrixEncodesPairingUriWithValidFinderPatterns() {
        val uri = "ec0://pair?v=1&id=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "&pk=d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a" +
            "&alias=arch-linux&nonce=000102030405060708090a0b0c0d0e0f&port=49880&ips=192.168.1.10"
        val matrix = QrCodeMatrix.encodeText(uri)
        assertTrue(matrix.version in 1..10, "Expected QR version in 1..10, got ${matrix.version}")
        assertEquals(17 + 4 * matrix.version, matrix.size)

        // Verify top-left 7x7 finder pattern outer border and center 3x3 block are dark
        for (i in 0..6) {
            assertTrue(matrix[i, 0], "Top-left finder top edge ($i, 0)")
            assertTrue(matrix[i, 6], "Top-left finder bottom edge ($i, 6)")
            assertTrue(matrix[0, i], "Top-left finder left edge (0, $i)")
            assertTrue(matrix[6, i], "Top-left finder right edge (6, $i)")
        }
        for (dy in 2..4) {
            for (dx in 2..4) {
                assertTrue(matrix[dx, dy], "Top-left finder 3x3 center ($dx, $dy)")
            }
        }
        // Verify white separator ring inside finder pattern
        assertFalse(matrix[1, 1], "Top-left finder inner white ring (1, 1)")
        assertFalse(matrix[5, 5], "Top-left finder inner white ring (5, 5)")

        val ascii = matrix.toAsciiArt(quietZone = 1)
        assertTrue(ascii.isNotBlank())
        assertTrue(ascii.contains("█") || ascii.contains("▀") || ascii.contains("▄"))
    }

    @Test
    fun hkdfBulkLaneKeyIsCryptographicallyDistinctFromControlKeys() {
        val sharedSecretZ = ByteArray(32) { (it + 1).toByte() }
        val nonceI = ByteArray(16) { (it + 1).toByte() }
        val nonceR = ByteArray(16) { (it + 17).toByte() }
        val ikPubRaw = ByteArray(32) { (it + 33).toByte() }
        val rkPubRaw = ByteArray(32) { (it + 65).toByte() }
        val epkIRaw = ByteArray(32) { (it + 97).toByte() }
        val epkRRaw = ByteArray(32) { (it + 129).toByte() }
        val sessionKeys = HkdfSha256.deriveSessionKeys(sharedSecretZ, nonceI, nonceR, ikPubRaw, rkPubRaw, epkIRaw, epkRRaw)
        val bulkKey = HkdfSha256.deriveBulkLaneKey(sharedSecretZ, sessionKeys.sessionId)
        assertEquals(32, bulkKey.size)
        assertNotEquals(sessionKeys.kInitToResp.toHex(), bulkKey.toHex())
        assertNotEquals(sessionKeys.kRespToInit.toHex(), bulkKey.toHex())
        assertNotEquals(sessionKeys.sessionId.toHex(), bulkKey.toHex())

        // Deterministic across invocations
        val bulkKey2 = HkdfSha256.deriveBulkLaneKey(sharedSecretZ, sessionKeys.sessionId)
        assertContentEquals(bulkKey, bulkKey2)
    }

    @Test
    fun mdnsCodecEncodesAndExtractsCamDroidServiceRecords() {
        val announceBytes = MdnsCodec.encodeCamDroidAnnouncement(
            deviceName = "OPPO A5x 5G",
            port = 4747,
            addresses = listOf("192.168.1.42"),
            codecs = listOf("h264", "h265", "mjpeg"),
            resolution = "1080p",
            ttlSeconds = 120L,
        )
        val packet = MdnsCodec.decodePacket(announceBytes)
        val extracted = MdnsCodec.extractCamDroidServices(packet, nowMs = 5_000L)
        assertEquals(1, extracted.size)
        val svc = extracted.first()
        assertEquals("OPPO A5x 5G", svc.deviceName)
        assertEquals(4747, svc.port)
        assertEquals(listOf("192.168.1.42"), svc.addresses)
        assertEquals(listOf("h264", "h265", "mjpeg"), svc.codecs)
        assertEquals("1080p", svc.resolution)
        assertFalse(svc.isGoodbye)
    }

    @Test
    fun universalControlCursorHandoffRoundTrip() {
        val cmd = CursorHandoffCommand(
            action = CursorHandoffCommand.ACTION_ENTER,
            edge = ScreenEdge.RIGHT,
            positionPermille = 625,
        )
        val decoded = CursorHandoffCommand.fromJsonObject(cmd.toJsonObject())
        assertEquals(cmd, decoded)
        assertEquals(ScreenEdge.LEFT, cmd.edge.opposite())
    }

    @Test
    fun hotspotAndTelephonyModelsRoundTrip() {
        val hotspotCmd = HotspotControlCommand(
            action = HotspotControlCommand.ACTION_ENABLE,
            preferredBandMhz = 5180,
        )
        assertEquals(hotspotCmd, HotspotControlCommand.fromJsonObject(hotspotCmd.toJsonObject()))

        val status = HotspotStatusInfo(
            enabled = true,
            ssid = "OPPO-A5x-Hotspot",
            passphrase = "correct-horse-battery",
            gatewayIp = "192.168.43.1",
            bandMhz = 5180,
            connectedClients = 1,
        )
        assertEquals(status, HotspotStatusInfo.fromJsonObject(status.toJsonObject()))

        val callInfo = CallStateInfo(
            callId = "call-42",
            state = PhoneCallState.RINGING,
            callerNumber = "+15550199",
            callerName = "Alice",
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(callInfo, CallStateInfo.fromJsonObject(callInfo.toJsonObject()))

        val callAct = CallActionCommand(
            callId = "call-42",
            action = CallActionCommand.ACTION_REJECT_WITH_SMS,
            smsReplyText = "On a call, text me.",
        )
        assertEquals(callAct, CallActionCommand.fromJsonObject(callAct.toJsonObject()))
    }

    @Test
    fun mediaControlModelsRoundTrip() {
        assertEquals("text/uri-list", ClipboardMime.URI_LIST)
        val session = MediaSessionInfo(
            playerName = "Spotify",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            state = MediaPlaybackState.PLAYING,
            positionMs = 42_000L,
            durationMs = 243_000L,
            volumePct = 80,
        )
        assertEquals(session, MediaSessionInfo.fromJsonObject(session.toJsonObject()))

        val cmd = MediaControlCommand(
            action = MediaControlCommand.ACTION_SET_VOLUME,
            volumePct = 65,
        )
        assertEquals(cmd, MediaControlCommand.fromJsonObject(cmd.toJsonObject()))
    }
}
