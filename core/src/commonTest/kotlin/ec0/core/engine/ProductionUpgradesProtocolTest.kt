/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Protocol & pure-logic unit + security regression tests covering the 10
 * production Continuity upgrades:
 *   - Individual App Streaming (`ScrcpyMirrorStartCommand.startApp`/`newDisplay` & `AppListInfo`)
 *   - Notification App Icons & Action Buttons (`NotificationActionCommand` & `NotificationPostInfo`)
 *   - BLE Proximity Tag HKDF rotation & constant-time verification (`BleProximityTag`)
 *   - EMA RSSI hysteresis & walk-away grace window (`BleProximityStateMachine`)
 */
package ec0.core.engine

import ec0.core.engine.protocol.AndroidAppInfo
import ec0.core.engine.protocol.AppListInfo
import ec0.core.engine.protocol.BleProximityStateMachine
import ec0.core.engine.protocol.BleProximityTag
import ec0.core.engine.protocol.NotificationActionCommand
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.ProximityEvent
import ec0.core.engine.protocol.ProximityZone
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProductionUpgradesProtocolTest {

    // -------------------------------------------------------------------------
    // 1. Individual Android App Streaming wire validation (S6)
    // -------------------------------------------------------------------------

    @Test
    fun scrcpyStartCommandAcceptsValidAppPackageAndNewDisplaySpec() {
        val cmd = ScrcpyMirrorStartCommand(
            host = "192.168.1.42",
            maxSize = 1440,
            bitrateKbps = 12_000,
            maxFps = 60,
            audioEnabled = true,
            startApp = "com.whatsapp",
            newDisplay = "1080x2400/420",
        )
        val roundTripped = ScrcpyMirrorStartCommand.fromJsonObject(cmd.toJsonObject())
        assertEquals("com.whatsapp", roundTripped.startApp)
        assertEquals("1080x2400/420", roundTripped.newDisplay)

        val telegram = ScrcpyMirrorStartCommand(
            host = "192.168.1.42",
            startApp = "org.telegram.messenger",
            newDisplay = "1280x720",
        )
        assertEquals("org.telegram.messenger", telegram.startApp)
        assertEquals("1280x720", telegram.newDisplay)
    }

    @Test
    fun scrcpyStartCommandRejectsInjectedStartAppAndDisplaySpecs() {
        val badPackages = listOf(
            "--record=/tmp/pwn.mp4",
            "com.whatsapp;rm -rf /",
            "../etc/passwd",
            "com.bad space.app",
            "singleword",
            "a." + "b".repeat(130),
            "com.foo\nbar",
        )
        for (pkg in badPackages) {
            assertFailsWith<IllegalArgumentException>("Expected rejection for startApp='$pkg'") {
                ScrcpyMirrorStartCommand(host = "192.168.1.42", startApp = pkg)
            }
        }

        val badDisplays = listOf(
            "1080x2400;id",
            "--window-title=evil",
            "1080x2400/420/extra",
            "abcxdef",
            "1080x2400\n",
        )
        for (spec in badDisplays) {
            assertFailsWith<IllegalArgumentException>("Expected rejection for newDisplay='$spec'") {
                ScrcpyMirrorStartCommand(host = "192.168.1.42", newDisplay = spec)
            }
        }
    }

    @Test
    fun appListInfoCapsEntriesAndRoundTripsJson() {
        val manyApps = (1..150).map { i ->
            AndroidAppInfo(packageName = "com.example.app$i", label = "App $i")
        }
        val info = AppListInfo(apps = manyApps)
        val decoded = AppListInfo.fromJsonObject(info.toJsonObject())
        assertEquals(AppListInfo.MAX_APPS, decoded.apps.size)
        assertEquals("com.example.app1", decoded.apps.first().packageName)
        assertEquals("App 1", decoded.apps.first().label)
    }

    // -------------------------------------------------------------------------
    // 2. Notification App Icon & Action Button bounds (S7)
    // -------------------------------------------------------------------------

    @Test
    fun notificationActionCommandValidatesActionIndexBounds() {
        val valid = NotificationActionCommand(
            notifId = "notif-42",
            actionIndex = 2,
            actionLabel = "Mark as read",
        )
        val roundTripped = NotificationActionCommand.fromJsonObject(valid.toJsonObject())
        assertEquals("notif-42", roundTripped.notifId)
        assertEquals(2, roundTripped.actionIndex)
        assertEquals("Mark as read", roundTripped.actionLabel)

        assertFailsWith<IllegalArgumentException> {
            NotificationActionCommand(notifId = "notif-42", actionIndex = -1, actionLabel = "Neg")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationActionCommand(notifId = "notif-42", actionIndex = 8, actionLabel = "Overflow")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationActionCommand(notifId = "   ", actionIndex = 0, actionLabel = "Blank")
        }
    }

    @Test
    fun notificationPostInfoBoundsAppIconBase64Payload() {
        val smallIcon = "A".repeat(128)
        val post = NotificationPostInfo(
            notifId = "n-1",
            packageName = "org.signal",
            appName = "Signal",
            title = "Alex",
            body = "On my way",
            timestampMs = 1_700_000_000_000L,
            actions = listOf("Mark as read", "Mute"),
            appIconB64 = smallIcon,
        )
        val roundTripped = NotificationPostInfo.fromJsonObject(post.toJsonObject())
        assertEquals(smallIcon, roundTripped.appIconB64)
        assertEquals(listOf("Mark as read", "Mute"), roundTripped.actions)

        val oversizedIcon = "A".repeat(NotificationPostInfo.MAX_ICON_B64_CHARS + 1)
        val oversizedPost = NotificationPostInfo(
            notifId = "n-2",
            packageName = "org.signal",
            appName = "Signal",
            title = "Alex",
            body = "Huge icon",
            timestampMs = 1_700_000_000_000L,
            appIconB64 = oversizedIcon,
        )
        assertEquals(null, oversizedPost.sanitized().appIconB64, "sanitized() must drop oversized icon")
        assertEquals(null, NotificationPostInfo.fromJsonObject(oversizedPost.toJsonObject()).appIconB64, "wire serialization must drop oversized icon")
    }

    // -------------------------------------------------------------------------
    // 3. BLE Proximity Rotating HKDF Tags (S8)
    // -------------------------------------------------------------------------

    @Test
    fun bleProximityTagRotatesPerEpochAndMatchesAdjacentSkewWindows() {
        val peerKey = ByteArray(32) { idx -> (idx * 7 + 3).toByte() }
        val otherKey = ByteArray(32) { idx -> (idx * 11 + 5).toByte() }

        val baseMs = 100L * BleProximityTag.EPOCH_DURATION_MS
        val epoch = BleProximityTag.currentEpoch(baseMs)

        val tagCurrent = BleProximityTag.deriveTagHex(peerKey, epoch)
        val tagPrev = BleProximityTag.deriveTagHex(peerKey, epoch - 1)
        val tagNext = BleProximityTag.deriveTagHex(peerKey, epoch + 1)
        val tagTooOld = BleProximityTag.deriveTagHex(peerKey, epoch - 2)
        val tagWrongKey = BleProximityTag.deriveTagHex(otherKey, epoch)

        assertEquals(BleProximityTag.TAG_BYTES * 2, tagCurrent.length)
        assertNotEquals(tagCurrent, tagPrev)
        assertNotEquals(tagCurrent, tagNext)
        assertNotEquals(tagCurrent, tagWrongKey)

        // Matches [epoch - 1, epoch, epoch + 1]
        assertTrue(BleProximityTag.matchesPeer(tagCurrent, peerKey, baseMs))
        assertTrue(BleProximityTag.matchesPeer(tagPrev, peerKey, baseMs))
        assertTrue(BleProximityTag.matchesPeer(tagNext, peerKey, baseMs))
        // Uppercase normalization works
        assertTrue(BleProximityTag.matchesPeer(tagCurrent.uppercase(), peerKey, baseMs))

        // Rejects expired epoch, wrong key, and wrong length
        assertFalse(BleProximityTag.matchesPeer(tagTooOld, peerKey, baseMs))
        assertFalse(BleProximityTag.matchesPeer(tagWrongKey, peerKey, baseMs))
        assertFalse(BleProximityTag.matchesPeer("deadbeef", peerKey, baseMs))
    }

    // -------------------------------------------------------------------------
    // 4. BLE Proximity EMA Smoother + Hysteresis + Grace Timer
    // -------------------------------------------------------------------------

    @Test
    fun bleProximityStateMachinePreventsFlappingAndEnforcesGraceWindow() {
        val sm = BleProximityStateMachine(
            nearThresholdDbm = -68.0,
            farThresholdDbm = -82.0,
            lockGraceMs = 15_000L,
            emaAlpha = 0.5,
        )

        // Initial close sample triggers APPROACH_WAKE -> NEAR
        val nearEval = sm.onRssiSample(-58, nowMs = 1_000L)
        assertEquals(ProximityZone.NEAR, nearEval.zone)
        assertEquals(ProximityEvent.APPROACH_WAKE, nearEval.event)

        // Samples inside hysteresis deadband (-75 dBm) must NOT lock or flap
        val deadband1 = sm.onRssiSample(-75, nowMs = 5_000L)
        val deadband2 = sm.onRssiSample(-75, nowMs = 25_000L)
        assertEquals(ProximityZone.NEAR, deadband1.zone)
        assertEquals(ProximityEvent.NONE, deadband1.event)
        assertEquals(ProximityZone.NEAR, deadband2.zone)
        assertEquals(ProximityEvent.NONE, deadband2.event)

        // Strong drop to FAR starts 15s grace timer — does NOT lock immediately
        var t = 30_000L
        repeat(4) {
            val ev = sm.onRssiSample(-95, nowMs = t)
            assertEquals(ProximityEvent.NONE, ev.event)
            t += 1_000L
        }
        // Still within 15s grace window from when smoothed RSSI first crossed <= -82 dBm
        assertEquals(ProximityZone.NEAR, sm.zone)

        // After >= 15s continuously in FAR zone -> emits WALK_AWAY_LOCK once
        val lockEval = sm.onRssiSample(-95, nowMs = t + 16_000L)
        assertEquals(ProximityZone.FAR, lockEval.zone)
        assertEquals(ProximityEvent.WALK_AWAY_LOCK, lockEval.event)

        // Repeated FAR samples must NOT re-emit WALK_AWAY_LOCK for the same departure
        val repeatFar = sm.onRssiSample(-95, nowMs = t + 32_000L)
        assertEquals(ProximityEvent.NONE, repeatFar.event)

        // Returning close emits APPROACH_WAKE again
        var wakeEv = ProximityEvent.NONE
        var wt = t + 35_000L
        repeat(6) {
            val ev = sm.onRssiSample(-55, nowMs = wt)
            if (ev.event == ProximityEvent.APPROACH_WAKE) wakeEv = ev.event
            wt += 1_000L
        }
        assertEquals(ProximityEvent.APPROACH_WAKE, wakeEv)
        assertEquals(ProximityZone.NEAR, sm.zone)

        // Total signal disappearance for >= 15s triggers WALK_AWAY_LOCK
        val timeoutBeforeGrace = sm.onTickWithoutSignal(nowMs = wt + 5_000L)
        assertEquals(ProximityEvent.NONE, timeoutBeforeGrace.event)
        val timeoutAfterGrace = sm.onTickWithoutSignal(nowMs = wt + 20_000L)
        assertEquals(ProximityZone.FAR, timeoutAfterGrace.zone)
        assertEquals(ProximityEvent.WALK_AWAY_LOCK, timeoutAfterGrace.event)
    }
}
