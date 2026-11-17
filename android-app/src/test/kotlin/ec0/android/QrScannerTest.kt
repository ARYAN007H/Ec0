/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature 2 (in-app QR scanner) — pure-layer tests:
 *  - PairUriExtractor: scheme detection across paste/scan noise variants
 *  - QrScannerViewModel: Idle → RequestingCamera → Scanning → Decoded → onConsumed
 *    state machine (incl. denial, background pause, stale-frame races)
 *  - QrFrameDecoder: zxing encode→decode roundtrip over synthetic YUV luminance,
 *    including the 90° rotation CameraX delivers in portrait
 */
package ec0.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PairUriExtractorTest {

    @Test
    fun extractsBareEc0pairUri() {
        val uri = "ec0pair://v=1&id=abc&pk=def&name=Arch&nonce=1234&port=49880&ips=10.0.0.5"
        assertEquals(uri, PairUriExtractor.extractPairUri(uri))
    }

    @Test
    fun extractsEc0PairQueryVariant() {
        val uri = "ec0://pair?v=1&id=abc&pk=def&nonce=1234&port=49880&ips=10.0.0.5"
        assertEquals(uri, PairUriExtractor.extractPairUri(uri))
    }

    @Test
    fun stripsWhitespaceInjectedMidUri() {
        // The desktop QR is a single line, but text framing / Gboard line-wraps can
        // inject newlines anywhere — the URI must survive them.
        val uri = "ec0pair://v=1&id=abc&pk=def&nonce=1234&port=49880"
        val wrapped = "ec0pair://v=1\n&id=abc&pk=def\n&nonce=1234&port=49880"
        assertEquals(uri, PairUriExtractor.extractPairUri(wrapped))
    }

    @Test
    fun findsUriEmbeddedInSurroundingText() {
        val payload = "Point your phone at this code\necopair junk\ne0pair://wrong\n" +
            "ec0pair://v=1&id=xyz&port=1\nfooter"
        val extracted = PairUriExtractor.extractPairUri(payload)
        assertTrue(extracted != null && extracted.startsWith("ec0pair://v=1&id=xyz"), extracted ?: "null")
    }

    @Test
    fun rejectsNonPairPayloads() {
        assertNull(PairUriExtractor.extractPairUri("https://example.com/pair"))
        assertNull(PairUriExtractor.extractPairUri(""))
        assertNull(PairUriExtractor.extractPairUri("ec0://other?v=1"))
    }
}

class QrScannerViewModelTest {

    private fun vm(): QrScannerViewModel {
        // Unconfined: VM launches execute inline, assertions stay synchronous.
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Inject a decoder that never matches — the state machine is under test,
        // not the decoder.
        return QrScannerViewModel(decoder = { _, _, _, _ -> null }, scope = scope)
    }

    @Test
    fun idleToRequestingCameraToScanningOnGrant() {
        val scanner = vm()
        assertEquals(QrScannerState.Idle, scanner.state.value)
        scanner.onScanRequested()
        assertEquals(QrScannerState.RequestingCamera, scanner.state.value)
        scanner.onCameraPermissionResult(true)
        assertEquals(QrScannerState.Scanning, scanner.state.value)
    }

    @Test
    fun denialReturnsToIdle() {
        val scanner = vm()
        scanner.onScanRequested()
        scanner.onCameraPermissionResult(false)
        assertEquals(QrScannerState.Idle, scanner.state.value)
    }

    @Test
    fun permissionResultOutsideRequestingIsIgnored() {
        val scanner = vm()
        scanner.onCameraPermissionResult(true)
        assertEquals(QrScannerState.Idle, scanner.state.value)
    }

    @Test
    fun firstDecodedUriWinsAndConsumedReturnsToIdle() {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uri = "ec0pair://v=1&id=abc&port=49880"
        var decodeCount = 0
        val scanner = QrScannerViewModel(
            decoder = { _, _, _, _ ->
                decodeCount++
                uri
            },
            scope = scope,
        )
        scanner.onScanRequested()
        scanner.onCameraPermissionResult(true)
        scanner.onFrame(ByteArray(64), 8, 8, 0)
        scanner.onFrame(ByteArray(64), 8, 8, 0) // stale frame after Decoded must not restart
        val state = scanner.state.value
        assertIs<QrScannerState.Decoded>(state)
        assertEquals(uri, state.uri)
        // Unconfined execution: the second frame's guard sees Decoded and never
        // reaches the decoder.
        assertEquals(1, decodeCount)

        scanner.onConsumed()
        assertEquals(QrScannerState.Idle, scanner.state.value)
    }

    @Test
    fun framesIgnoredWhenNotScanning() {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scanner = QrScannerViewModel(
            decoder = { _, _, _, _ -> "ec0pair://v=1&id=abc" },
            scope = scope,
        )
        scanner.onFrame(ByteArray(64), 8, 8, 0) // Idle — no scan requested
        assertEquals(QrScannerState.Idle, scanner.state.value)
    }

    @Test
    fun onPauseReturnsIdleFromScanning() {
        val scanner = vm()
        scanner.onScanRequested()
        scanner.onCameraPermissionResult(true)
        assertEquals(QrScannerState.Scanning, scanner.state.value)
        scanner.onPause()
        assertEquals(QrScannerState.Idle, scanner.state.value)
    }
}

class QrFrameDecoderTest {

    /** zxing-encode a payload into a BitMatrix, render it as a synthetic YUV
     *  luminance buffer (dark modules → low luma), return (buffer, width, height). */
    private fun luminanceFor(payload: String): Triple<ByteArray, Int, Int> {
        val matrix: BitMatrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            84,
            84,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val w = matrix.width
        val h = matrix.height
        val buf = ByteArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (matrix[x, y]) 0x10 else 0xE8
        }
        return Triple(buf, w, h)
    }

    @Test
    fun decodesUprightSyntheticQr() {
        val uri = "ec0pair://v=1&id=roundtrip&nonce=12345678&port=49880"
        val (buf, w, h) = luminanceFor(uri)
        assertEquals(uri, QrFrameDecoder.decode(buf, w, h, 0))
    }

    @Test
    fun decodesNinetyDegreeRotatedSyntheticQr() {
        val uri = "ec0pair://v=1&id=rotated&nonce=87654321&port=49880"
        val (buf, w, h) = luminanceFor(uri)
        val rotated = QrFrameDecoder.rotate90(buf, w, h)
        assertEquals(uri, QrFrameDecoder.decode(rotated.first, rotated.second, rotated.third, 90))
    }

    @Test
    fun nonQrBufferDecodesToNull() {
        val buf = ByteArray(64 * 64) { 0xE8.toByte() } // uniform white — nothing to read
        assertNull(QrFrameDecoder.decode(buf, 64, 64, 0))
    }

    @Test
    fun extractorBridgesDecoderOutputToThePairingUri() {
        val uri = "ec0pair://v=1&id=bridge&nonce=1&port=49880"
        val (buf, w, h) = luminanceFor(uri)
        val decoded = QrFrameDecoder.decode(buf, w, h, 0)
        assertTrue(decoded != null)
        assertEquals(uri, PairUriExtractor.extractPairUri(decoded))
    }
}
