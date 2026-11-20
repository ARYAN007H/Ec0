/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unit & security validation tests for all Ecosystem Pro protocol models
 * (Features #1, #2, #4, #5, #6, #7, #8, #11, #12).
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.protocol.AudioRouteCommand
import ec0.core.engine.protocol.AudioRouteStatusInfo
import ec0.core.engine.protocol.AudioRoutingProtocol
import ec0.core.engine.protocol.BrowserTabHandoffInfo
import ec0.core.engine.protocol.CameraEffectsCommand
import ec0.core.engine.protocol.CaptureRequestCommand
import ec0.core.engine.protocol.CaptureResultInfo
import ec0.core.engine.protocol.ContinuityCaptureProtocol
import ec0.core.engine.protocol.FocusStateInfo
import ec0.core.engine.protocol.FocusSyncProtocol
import ec0.core.engine.protocol.SessionResumeTokenInfo
import ec0.core.engine.protocol.SidecarStartCommand
import ec0.core.engine.protocol.SidecarTouchInputEvent
import ec0.core.engine.protocol.SmsMessageRecord
import ec0.core.engine.protocol.SmsSendCommand
import ec0.core.engine.protocol.TransportFailoverProtocol
import ec0.core.engine.protocol.WifiDirectTurboCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EcosystemProModelsTest {
    @Test
    fun allModelsRoundTripAndCanonicalJsonSafe() {
        val audioCmd = AudioRouteCommand(
            action = AudioRoutingProtocol.ACTION_START_CALL_AUDIO,
            sampleRateHz = 48_000,
            channels = 1,
            duplex = true,
        )
        val audioJson = audioCmd.toJsonObject()
        assertTrue(CanonicalJson.canonicalizeToString(audioJson).contains("start_call_audio"))
        assertEquals(audioCmd, AudioRouteCommand.fromJsonObject(audioJson))

        val audioStatus = AudioRouteStatusInfo(active = true, mode = "media_sink", channels = 2)
        assertEquals(audioStatus, AudioRouteStatusInfo.fromJsonObject(audioStatus.toJsonObject()))

        val capReq = CaptureRequestCommand(
            requestId = "req-12345678",
            mode = ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN,
            autoPaste = true,
        )
        assertEquals(capReq, CaptureRequestCommand.fromJsonObject(capReq.toJsonObject()))

        val capBytes = "hello-scan".encodeToByteArray()
        val capSha = "a".repeat(64)
        val capRes = CaptureResultInfo(
            requestId = "req-12345678",
            mode = ContinuityCaptureProtocol.MODE_DOCUMENT_SCAN,
            mime = "application/pdf",
            sizeBytes = capBytes.size.toLong(),
            sha256Hex = capSha,
            inlineBase64Url = capBytes.toBase64UrlUnpadded(),
        )
        val parsedCap = CaptureResultInfo.fromJsonObject(capRes.toJsonObject())
        assertEquals(capRes, parsedCap)

        val sidecar = SidecarStartCommand(width = 1920, height = 1080, fps = 60, bitrateKbps = 12_000)
        assertEquals(sidecar, SidecarStartCommand.fromJsonObject(sidecar.toJsonObject()))

        val touch = SidecarTouchInputEvent(
            action = "move",
            pointerId = 1,
            xPermille10k = 5_000,
            yPermille10k = 7_500,
            pressurePermille = 820,
            tiltXDeg = -15,
            tiltYDeg = 20,
            toolType = "stylus",
        )
        assertTrue(CanonicalJson.canonicalizeToString(touch.toJsonObject()).contains("\"pressure\":820"))
        assertEquals(touch, SidecarTouchInputEvent.fromJsonObject(touch.toJsonObject()))

        val sms = SmsMessageRecord(
            messageId = "m-1",
            threadId = "t-1",
            address = "+15551234567",
            contactName = "Alice",
            body = "See you at 5",
            incoming = true,
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(sms, SmsMessageRecord.fromJsonObject(sms.toJsonObject()))

        val smsSend = SmsSendCommand(clientRequestId = "s-1", recipientAddress = "+15551234567", body = "On my way")
        assertEquals(smsSend, SmsSendCommand.fromJsonObject(smsSend.toJsonObject()))

        val focus = FocusStateInfo(mode = FocusSyncProtocol.MODE_MEETING, source = "meeting_auto", updatedAtMs = 100L)
        assertEquals(focus, FocusStateInfo.fromJsonObject(focus.toJsonObject()))

        val tab = BrowserTabHandoffInfo(
            tabId = "tab-1",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Demo Video",
            mediaPositionSec = 95,
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(tab, BrowserTabHandoffInfo.fromJsonObject(tab.toJsonObject()))

        val resume = SessionResumeTokenInfo(
            tokenIdHex = "a".repeat(32),
            peerDeviceId = "b".repeat(64),
            epoch = 2,
            expiresAtMs = 60_000L,
            macHex = "c".repeat(64),
        )
        assertEquals(resume, SessionResumeTokenInfo.fromJsonObject(resume.toJsonObject()))

        val turbo = WifiDirectTurboCommand(
            action = TransportFailoverProtocol.ACTION_P2P_READY,
            groupOwnerIp = "192.168.49.1",
            ssid = "DIRECT-ec0-5g",
            passphrase = "StrongPassword1234",
            port = 49880,
        )
        assertEquals(turbo, WifiDirectTurboCommand.fromJsonObject(turbo.toJsonObject()))

        val fx = CameraEffectsCommand(
            centerStage = true,
            portraitBlur = true,
            deskView = false,
            targetBitrateKbps = 8000,
            hwDecodePreference = "vaapi",
        )
        assertEquals(fx, CameraEffectsCommand.fromJsonObject(fx.toJsonObject()))
    }

    @Test
    fun failClosedValidationRejectsMaliciousInputs() {
        assertFailsWith<IllegalArgumentException> {
            AudioRouteCommand(action = "shell; rm -rf /")
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserTabHandoffInfo(tabId = "t1", url = "javascript:alert(1)", title = "XSS")
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserTabHandoffInfo(tabId = "t1", url = "file:///etc/passwd", title = "LFI")
        }
        assertFailsWith<IllegalArgumentException> {
            SidecarStartCommand(width = 99999, height = 1080, fps = 60)
        }
        assertFailsWith<IllegalArgumentException> {
            SidecarTouchInputEvent(action = "move", pointerId = 0, xPermille10k = 15_000, yPermille10k = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SmsSendCommand(clientRequestId = "r1", recipientAddress = "bad;cmd", body = "hi")
        }
        assertFailsWith<IllegalArgumentException> {
            FocusStateInfo(mode = "root_override")
        }
        assertFailsWith<IllegalArgumentException> {
            WifiDirectTurboCommand(
                action = TransportFailoverProtocol.ACTION_P2P_READY,
                ssid = "bad\nssid",
                passphrase = "short",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CameraEffectsCommand(hwDecodePreference = "cuda;id")
        }
    }
}
