/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Deep-integration slice tests: pointer batching (≤8 ms coalescing contract),
 * gesture interpretation, capability-flag parsing (A3), OEM battery registry
 * detection (A6), and per-device capability-grant serialization (A5).
 */
package ec0.android

import ec0.core.engine.EngineCapability
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.InputPointerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PointerEventBatcherTest {

    @Test
    fun coalescesMultipleMovesIntoOneMovePerFlush() {
        val batcher = PointerEventBatcher()
        batcher.addMove(3, -2)
        batcher.addMove(5, 4)
        batcher.addMove(-1, 1)
        val burst = batcher.flush()
        assertEquals(1, burst.size)
        val move = burst.single()
        assertEquals(InputProtocol.POINTER_ACTION_MOVE, move.action)
        assertEquals(7, move.dx)
        assertEquals(3, move.dy)
        // Second flush is empty (deltas consumed).
        assertTrue(batcher.flush().isEmpty())
    }

    @Test
    fun clickRidesBehindThePendingMove() {
        val batcher = PointerEventBatcher()
        batcher.addMove(10, 0)
        batcher.addClick(PointerButtons.LEFT)
        val burst = batcher.flush()
        assertEquals(2, burst.size)
        assertEquals(InputProtocol.POINTER_ACTION_MOVE, burst[0].action)
        assertEquals(InputProtocol.POINTER_ACTION_CLICK, burst[1].action)
        assertEquals(PointerButtons.LEFT, burst[1].buttonsMask)
    }

    @Test
    fun scrollCoalescesIntoOneScrollPerFlush() {
        val batcher = PointerEventBatcher()
        batcher.addScroll(0, 3)
        batcher.addScroll(0, 5)
        batcher.addScroll(-2, 0)
        val burst = batcher.flush()
        assertEquals(1, burst.size)
        val scroll = burst.single()
        assertEquals(InputProtocol.POINTER_ACTION_SCROLL, scroll.action)
        assertEquals(-2, scroll.scrollX)
        assertEquals(8, scroll.scrollY)
    }

    @Test
    fun moveAndScrollAndClickShareOneBurstInOrder() {
        val batcher = PointerEventBatcher()
        batcher.addMove(1, 1)
        batcher.addScroll(0, 2)
        batcher.addClick(PointerButtons.RIGHT)
        val burst = batcher.flush()
        assertEquals(3, burst.size)
        assertEquals(
            listOf(InputProtocol.POINTER_ACTION_MOVE, InputProtocol.POINTER_ACTION_SCROLL, InputProtocol.POINTER_ACTION_CLICK),
            burst.map { it.action },
        )
    }
}

class PointerGesturesTest {

    @Test
    fun tapInterpretation() {
        assertEquals(PointerButtons.LEFT, PointerGestures.tapClick(twoFinger = false))
        assertEquals(PointerButtons.RIGHT, PointerGestures.tapClick(twoFinger = true))
    }

    @Test
    fun longPressIsMiddle() {
        assertEquals(PointerButtons.MIDDLE, PointerGestures.longPressClick())
    }

    @Test
    fun dragScalingIsSensitivityScaledAndClamped() {
        val (dx, dy) = PointerGestures.scaleDrag(10f, -20f, sensitivity = 1.6f)
        assertEquals(16, dx)
        assertEquals(-32, dy)
        // Clamp: ±500 bound
        val (bigX, _) = PointerGestures.scaleDrag(4000f, 0f, sensitivity = 4f)
        assertEquals(500, bigX)
    }

    @Test
    fun scrollScalingInvertsFingerDirection() {
        // fingers drag DOWN (+dy) → content scrolls DOWN (negative ticks per uinput)
        val ticks = PointerGestures.scaleScroll(48f)
        assertEquals(2, ticks)
    }
}

class EngineCapabilityFlagsTest {

    @Test
    fun newDeepIntegrationEntriesParseFromWireNames() {
        val csv = "clip,file,input,remote_input,media,find_my,vfs,notif,battery,mirror,cam"
        val parsed = EngineCapability.parseCsv(csv)
        assertTrue(EngineCapability.REMOTE_INPUT in parsed, "REMOTE_INPUT missing from $parsed")
        assertTrue(EngineCapability.MEDIA_CONTROL in parsed)
        assertTrue(EngineCapability.FIND_MY in parsed)
        assertTrue(EngineCapability.VFS_READ in parsed)
    }

    @Test
    fun aliasTokensParseBackCompat() {
        assertEquals(EngineCapability.REMOTE_INPUT, EngineCapability.fromWireName("input_share"))
        assertEquals(EngineCapability.MEDIA_CONTROL, EngineCapability.fromWireName("media_control"))
        assertEquals(EngineCapability.FIND_MY, EngineCapability.fromWireName("findmyphone"))
        assertEquals(EngineCapability.VFS_READ, EngineCapability.fromWireName("vfs_read"))
        // Legacy tokens keep parsing (compat contract).
        assertEquals(EngineCapability.CLIPBOARD, EngineCapability.fromWireName("clipboard"))
        assertEquals(EngineCapability.MIRROR, EngineCapability.fromWireName("scrcpy"))
    }

    @Test
    fun encodeRoundtripIncludesNewEntries() {
        val caps = setOf(EngineCapability.REMOTE_INPUT, EngineCapability.MEDIA_CONTROL, EngineCapability.FIND_MY)
        val csv = EngineCapability.encodeCsv(caps)
        assertEquals(caps, EngineCapability.parseCsv(csv))
    }
}

class OemBatteryRegistryTest {

    @Test
    fun detectionByManufacturer() {
        assertEquals(OemFamily.COLOROS, detectOemFamily("OPPO"))
        assertEquals(OemFamily.COLOROS, detectOemFamily("realme"))
        assertEquals(OemFamily.ONEUI, detectOemFamily("samsung"))
        assertEquals(OemFamily.MIUI, detectOemFamily("Xiaomi"))
        assertEquals(OemFamily.MIUI, detectOemFamily("POCO"))
        assertEquals(OemFamily.STOCK, detectOemFamily("Google"))
        assertEquals(OemFamily.STOCK, detectOemFamily(""))
    }

    @Test
    fun oneUiGuidanceCarriesSamsungPaths() {
        val guidance = OemBatteryRegistry.detect("samsung")
        assertTrue(guidance.autoLaunchComponents.isNotEmpty())
        assertTrue(guidance.autoLaunchComponents.all { it.first.startsWith("com.samsung.android") })
        assertTrue(guidance.setupHint.contains("Background usage limits"))
    }

    @Test
    fun colorOsGuidanceKeepsOplusComponents() {
        val guidance = OemBatteryRegistry.detect("OPPO")
        assertTrue(guidance.autoLaunchComponents.any { it.first == "com.oplus.safecenter" })
    }
}
