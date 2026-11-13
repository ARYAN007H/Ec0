/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unit tests for Continuity Camera wire models and byte-exact CamDroid v1/v2
 * binary & JSON codecs (`github.com/ARYAN007H/CamDroid`).
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidServerCapabilities
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import ec0.core.engine.protocol.CamDroidV2Codec
import ec0.core.engine.protocol.CamDroidV2Flags
import ec0.core.engine.protocol.CamDroidV2Packet
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CameraModelsTest {

    @Test
    fun camDroidV1HeartbeatAndVideoFrameRoundtrip() {
        val hb = CamDroidV1Codec.heartbeatPacket()
        val hbEncoded = CamDroidV1Codec.encode(hb)
        // [0x05] [0x00, 0x00, 0x00, 0x00]
        assertEquals("0500000000", hbEncoded.toHex())
        val hbDecoded = CamDroidV1Codec.decode(hbEncoded)
        assertEquals(CamDroidMediaKind.HEARTBEAT, hbDecoded.kind)
        assertEquals(0, hbDecoded.payload.size)

        val h264Nal = "0000000165abcd".decodeHex()
        val videoPkt = CamDroidV1Packet(CamDroidMediaKind.VIDEO, h264Nal)
        val videoEncoded = CamDroidV1Codec.encode(videoPkt)
        // [0x01] [0x07, 0x00, 0x00, 0x00] [00 00 00 01 65 ab cd]
        assertEquals("01070000000000000165abcd", videoEncoded.toHex())
        val videoDecoded = CamDroidV1Codec.decode(videoEncoded)
        assertEquals(videoPkt, videoDecoded)
    }

    @Test
    fun camDroidV2Cmd2HeaderMatchesExactLittleEndianLayout() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        val packet = CamDroidV2Packet(
            kind = CamDroidMediaKind.VIDEO_CONFIG,
            flags = CamDroidV2Flags.KEY_FRAME or CamDroidV2Flags.CONFIGURATION,
            sessionId = 0x0102030405060708L,
            sequence = 42L,
            captureTimestampNs = 1_000_000_000L,
            payload = payload,
        )
        val encoded = CamDroidV2Codec.encode(packet)
        assertEquals(CamDroidConstants.V2_HEADER_BYTES + payload.size, encoded.size)

        // Verify magic "CMD2" + version 2 + kind 0x06 + flags 0x0003 LE
        assertEquals("434d443202060300", encoded.copyOfRange(0, 8).toHex())
        // Verify sessionId 0x0102030405060708 in Little-Endian
        assertEquals("0807060504030201", encoded.copyOfRange(8, 16).toHex())

        val decoded = CamDroidV2Codec.decode(encoded)
        assertEquals(packet, decoded)
        assertContentEquals(payload, decoded.payload)

        // Corrupt magic must fail closed
        val badMagic = encoded.copyOf().also { it[0] = 0x00 }
        assertFailsWith<IllegalArgumentException> {
            CamDroidV2Codec.decode(badMagic)
        }

        // Unknown flags bit must fail closed
        assertFailsWith<IllegalArgumentException> {
            CamDroidV2Packet(
                kind = CamDroidMediaKind.VIDEO,
                flags = 0x80,
                sessionId = 1L,
                sequence = 1L,
                captureTimestampNs = 1L,
                payload = ByteArray(0),
            )
        }
    }

    @Test
    fun camDroidControlCommandsFormatExactWireJsonAndCanonicalEnvelopeJson() {
        val start = CamDroidControlCommand.Start(codec = "h264", resolution = "1080p", fps = 60, audio = true)
        assertEquals(
            """{"cmd":"start","codec":"h264","resolution":"1080p","fps":60,"audio":true}""",
            start.toCamDroidJsonString(),
        )

        val zoom = CamDroidControlCommand.SetZoom(250L)
        assertEquals(
            """{"cmd":"set_zoom","value":2.5}""",
            zoom.toCamDroidJsonString(),
        )

        val focusManual = CamDroidControlCommand.SetFocus(mode = "manual", distanceX100 = 175L)
        assertEquals(
            """{"cmd":"set_focus","mode":"manual","distance":1.75}""",
            focusManual.toCamDroidJsonString(),
        )

        val allCommands = listOf(
            start,
            CamDroidControlCommand.Stop,
            CamDroidControlCommand.SwitchCamera,
            zoom,
            focusManual,
            CamDroidControlCommand.SetExposure(-2),
            CamDroidControlCommand.SetWhiteBalance("daylight"),
            CamDroidControlCommand.SetFlash(true),
            CamDroidControlCommand.SetMirror(false),
            CamDroidControlCommand.SetResolution("1440p"),
            CamDroidControlCommand.SetFps(30),
            CamDroidControlCommand.SetCodec("h265"),
            CamDroidControlCommand.SetBitrate(8_000_000L),
            CamDroidControlCommand.RequestKeyframe,
        )

        for (cmd in allCommands) {
            val jsonObj = cmd.toJsonObject()
            // Must pass strict integer-only CanonicalJson serialization without throwing
            val canonical = CanonicalJson.canonicalize(jsonObj)
            assertTrue(canonical.contains("\"cmd\":\"${cmd.cmdName}\""))
            val roundtripped = CamDroidControlCommand.fromJsonObject(jsonObj)
            assertEquals(cmd, roundtripped)
        }
    }

    @Test
    fun cameraStatusAndServerCapabilitiesRoundtrip() {
        val status = CameraStatusInfo(
            state = CameraStreamState.STREAMING,
            backend = CameraBackend.CAMDROID_NATIVE,
            host = "192.168.1.42",
            port = 4747,
            v4l2DevicePath = "/dev/video10",
            codec = "h264",
            resolution = "1440p",
            fps = 60,
            audioEnabled = true,
            frontCamera = true,
            flashEnabled = false,
            mirrorEnabled = true,
            zoomX100 = 200L,
            whiteBalance = "tungsten",
            exposureEv = 1,
        )
        val statusJson = status.toJsonObject()
        val canonical = CanonicalJson.canonicalize(statusJson)
        assertTrue(canonical.contains("\"zoom_x100\":200"))
        assertEquals(status, CameraStatusInfo.fromJsonObject(statusJson))

        val caps = CamDroidServerCapabilities(
            version = "1.0",
            device = "OPPO A5x 5G",
            codecs = listOf("h264", "h265", "mjpeg"),
            resolutions = listOf("1080p", "1440p", "4k"),
            fps = listOf(30, 60),
            audio = true,
            battery = 88,
        )
        val capsJson = caps.toJsonObject()
        assertEquals(caps, CamDroidServerCapabilities.fromJsonObject(capsJson))
    }
}
