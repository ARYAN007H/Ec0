/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Android unit & security regression tests covering:
 *   - Hardware H.264 encoder config, codec negotiation fallback, and Annex-B SPS/PPS prepending
 *   - Image/screenshot clipboard sync allowlist, URI guard, and bounded stream reading (S9)
 *   - Cached notification action button execution (PendingIntent-only, bounds/eviction safe, S7)
 *   - Telephony CallStateMapper transitions & control-character sanitization
 *   - Universal Control VirtualCursorState bounds clamping, handoff, and scroll strokes
 */
package ec0.android

import android.telephony.TelephonyManager
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.NotificationActionCommand
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.ScreenEdge
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidProductionUpgradesTest {

    @AfterTest
    fun tearDown() {
        Ec0NotificationListener.clearReplyActionsForTest()
    }

    // -------------------------------------------------------------------------
    // 1. Hardware H.264 Config & Annex-B SPS/PPS Prepending
    // -------------------------------------------------------------------------

    @Test
    fun hardwareH264ConfigAlignsEvenDimensionsScalesBitrateAndPrependsSpsPpsOnlyOnKeyframes() {
        val cfgOdd = HardwareH264Config.forProfile(width = 1921, height = 1081, fps = 60)
        assertEquals(0, cfgOdd.width % 2)
        assertEquals(0, cfgOdd.height % 2)
        assertEquals(60, cfgOdd.fps)
        assertTrue(cfgOdd.bitrateBps > 5_500_000)

        val cfg4k = HardwareH264Config.forProfile(width = 3840, height = 2160, fps = 30)
        assertEquals(14_000_000, cfg4k.bitrateBps)

        assertEquals("h264", HardwareH264Config.selectEffectiveCodec("h264", hardwareEncoderAvailable = true))
        assertEquals("mjpeg", HardwareH264Config.selectEffectiveCodec("h264", hardwareEncoderAvailable = false))
        assertEquals("mjpeg", HardwareH264Config.selectEffectiveCodec("mjpeg", hardwareEncoderAvailable = true))
        assertEquals("mjpeg", HardwareH264Config.selectEffectiveCodec("invalid", hardwareEncoderAvailable = true))

        val spsPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
        val idrFrame = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
        val pFrame = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte())

        val combined = HardwareH264Config.assembleAnnexBAccessUnit(idrFrame, isKeyFrame = true, cachedSpsPps = spsPps)
        assertContentEquals(spsPps + idrFrame, combined)

        val nonKey = HardwareH264Config.assembleAnnexBAccessUnit(pFrame, isKeyFrame = false, cachedSpsPps = spsPps)
        assertContentEquals(pFrame, nonKey)
    }

    // -------------------------------------------------------------------------
    // 2. Android Image/Screenshot Clipboard Sync Policy (S9)
    // -------------------------------------------------------------------------

    @Test
    fun clipboardImagePolicyEnforcesMimeAllowlistUriGuardAndBoundedByteCap() {
        assertEquals("image/png", ClipboardImagePolicy.normalizeSupportedImageMime("image/png"))
        assertEquals("image/jpeg", ClipboardImagePolicy.normalizeSupportedImageMime("IMAGE/JPEG; charset=binary"))
        assertEquals("image/webp", ClipboardImagePolicy.normalizeSupportedImageMime("image/webp"))
        assertNull(ClipboardImagePolicy.normalizeSupportedImageMime("image/svg+xml"))
        assertNull(ClipboardImagePolicy.normalizeSupportedImageMime("text/html"))
        assertNull(ClipboardImagePolicy.normalizeSupportedImageMime(null))

        assertTrue(ClipboardImagePolicy.isAllowedImageUri("content://media/external/images/media/42"))
        assertFalse(ClipboardImagePolicy.isAllowedImageUri("file:///etc/passwd"))
        assertFalse(ClipboardImagePolicy.isAllowedImageUri("content://ec0.android.provider/private/keys"))
        assertFalse(ClipboardImagePolicy.isAllowedImageUri("content://ec0.android/cache/secret"))
        assertFalse(ClipboardImagePolicy.isAllowedImageUri("http://example.com/a.png"))

        val okData = ByteArray(1024) { 0x2A }
        val readOk = ClipboardImagePolicy.readBoundedImageBytes(ByteArrayInputStream(okData), maxBytes = 1024)
        assertNotNull(readOk)
        assertContentEquals(okData, readOk)

        val overLimit = ByteArray(1025) { 0x2A }
        assertNull(ClipboardImagePolicy.readBoundedImageBytes(ByteArrayInputStream(overLimit), maxBytes = 1024))
        assertNull(ClipboardImagePolicy.readBoundedImageBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 1024))
    }

    // -------------------------------------------------------------------------
    // 3. Cached Notification Action Buttons (PendingIntent-only, S7)
    // -------------------------------------------------------------------------

    @Test
    fun notificationListenerExecutesOnlyCachedActionButtonsByIndex() {
        val markReadCalls = AtomicInteger(0)
        val archiveCalls = AtomicInteger(0)

        Ec0NotificationListener.registerActionButtons(
            notifId = "notif-100",
            buttons = listOf(
                RegisteredActionButton(label = "Mark as read", customExecutor = {
                    markReadCalls.incrementAndGet()
                    true
                }),
                RegisteredActionButton(label = "Archive", customExecutor = {
                    archiveCalls.incrementAndGet()
                    true
                }),
            ),
        )

        assertTrue(
            Ec0NotificationListener.executeCachedAction(
                NotificationActionCommand(notifId = "notif-100", actionIndex = 0, actionLabel = "Mark as read"),
            ),
        )
        assertEquals(1, markReadCalls.get())
        assertEquals(0, archiveCalls.get())

        assertTrue(
            Ec0NotificationListener.executeCachedAction(
                NotificationActionCommand(notifId = "notif-100", actionIndex = 1, actionLabel = "Archive"),
            ),
        )
        assertEquals(1, archiveCalls.get())

        // Out-of-bounds index or unknown notifId safely returns false
        assertFalse(
            Ec0NotificationListener.executeCachedAction(
                NotificationActionCommand(notifId = "notif-100", actionIndex = 5, actionLabel = "Missing"),
            ),
        )
        assertFalse(
            Ec0NotificationListener.executeCachedAction(
                NotificationActionCommand(notifId = "unknown-id", actionIndex = 0, actionLabel = "Nope"),
            ),
        )

        // After unregistering, stale actions are rejected
        Ec0NotificationListener.unregisterActionButtons("notif-100")
        assertFalse(
            Ec0NotificationListener.executeCachedAction(
                NotificationActionCommand(notifId = "notif-100", actionIndex = 0, actionLabel = "Stale"),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // 4. CallStateMapper transitions & sanitization
    // -------------------------------------------------------------------------

    @Test
    fun callStateMapperTransitionsAndSanitizesCallerFields() {
        val (ringId, ringInfo) = CallStateMapper.mapCallState(
            androidState = TelephonyManager.CALL_STATE_RINGING,
            activeCallId = null,
            incomingNumber = "+1\u0000-555-0199\n",
            nowMs = 1_000L,
        )
        assertNotNull(ringId)
        assertNotNull(ringInfo)
        assertEquals(PhoneCallState.RINGING, ringInfo.state)
        assertEquals("+1-555-0199", ringInfo.callerNumber)

        val (activeId, activeInfo) = CallStateMapper.mapCallState(
            androidState = TelephonyManager.CALL_STATE_OFFHOOK,
            activeCallId = ringId,
            incomingNumber = "+1-555-0199",
            nowMs = 2_000L,
        )
        assertEquals(ringId, activeId)
        assertEquals(PhoneCallState.ACTIVE, activeInfo?.state)

        val (endedId, endedInfo) = CallStateMapper.mapCallState(
            androidState = TelephonyManager.CALL_STATE_IDLE,
            activeCallId = activeId,
            incomingNumber = "+1-555-0199",
            nowMs = 3_000L,
        )
        assertNull(endedId)
        assertEquals(ringId, endedInfo?.callId)
        assertEquals(PhoneCallState.ENDED, endedInfo?.state)

        // Idle without an active call emits nothing
        val (idleId, idleInfo) = CallStateMapper.mapCallState(
            androidState = TelephonyManager.CALL_STATE_IDLE,
            activeCallId = null,
            incomingNumber = null,
            nowMs = 4_000L,
        )
        assertNull(idleId)
        assertNull(idleInfo)
    }

    // -------------------------------------------------------------------------
    // 5. Universal Control VirtualCursorState bounds clamping
    // -------------------------------------------------------------------------

    @Test
    fun virtualCursorStateClampsCoordinatesHandlesHandoffAndComputesSafeScrollStrokes() {
        val cursor = VirtualCursorState(initialWidth = 1080, initialHeight = 2400)
        assertFalse(cursor.active)

        cursor.onHandoff(
            CursorHandoffCommand(
                action = CursorHandoffCommand.ACTION_ENTER,
                edge = ScreenEdge.LEFT,
                positionPermille = 500,
            ),
        )
        assertTrue(cursor.active)
        assertEquals(4f, cursor.x)
        assertTrue(cursor.y in 1190f..1205f)

        // Huge deltas are strictly clamped inside screen bounds
        val (clampedX, clampedY) = cursor.applyMove(dx = 99_999, dy = -99_999)
        assertEquals(1079f, clampedX)
        assertEquals(0f, clampedY)

        // Scroll stroke stays strictly inside valid Android dispatchGesture bounds [1, dim - 2]
        val (start, end) = cursor.computeScrollStroke(scrollY = 500)
        assertTrue(start.first in 1f..1078f)
        assertTrue(start.second in 1f..2398f)
        assertTrue(end.second in 1f..2398f)

        cursor.onHandoff(
            CursorHandoffCommand(
                action = CursorHandoffCommand.ACTION_LEAVE,
                edge = ScreenEdge.LEFT,
            ),
        )
        assertFalse(cursor.active)
    }
}
