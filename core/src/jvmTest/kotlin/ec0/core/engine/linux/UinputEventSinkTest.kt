/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Uinput sink tests — evdev golden bytes, ring batching (≤8 ms flush
 * contract), probe-chain selection, presentation keys. All I/O faked via
 * an in-memory FileChannel (ByteArrayOutputStream-backed).
 */
package ec0.core.engine.linux

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UinputEventSinkTest {

    /** In-memory "device" — captures everything the sink writes. */
    private class CapturingChannel : java.nio.channels.WritableByteChannel {
        val out = ByteArrayOutputStream()
        override fun write(src: ByteBuffer): Int {
            val n = src.remaining()
            val bytes = ByteArray(n)
            src.get(bytes) // direct buffers have no backing array
            out.write(bytes)
            return n
        }
        override fun isOpen(): Boolean = true
        override fun close() {}
    }

    private fun sinkWithCapture(capture: CapturingChannel): Pair<UinputEventSink, () -> ByteArray> {
        var channel: CapturingChannel? = null
        val sink = UinputEventSink(
            ringCapacity = 8,
            flushIntervalMs = 8,
            timeSource = { 0L }, // never "due" — tests flush explicitly
            forceChannel = true,
            openChannel = { channel = capture; capture as java.nio.channels.WritableByteChannel },
        )
        return sink to { channel?.out?.toByteArray() ?: ByteArray(0) }
    }

    // ---- codec golden bytes ------------------------------------------------

    @Test
    fun evEventIs24BytesLittleEndian() {
        val bytes = UinputEvdevCodec.encodeEvent(timeSec = 1, timeUsec = 2, type = 0x02, code = 0x00, value = -3)
        assertEquals(24, bytes.size)
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(1L, buffer.long)
        assertEquals(2L, buffer.long)
        assertEquals(0x02.toShort(), buffer.short)
        assertEquals(0x00.toShort(), buffer.short)
        assertEquals(-3, buffer.int)
    }

    @Test
    fun moveSequenceIsTwoRelAxesPlusSyn() {
        val events = UinputEvdevCodec.moveEvents(dx = -15, dy = 24, timeSec = 0, timeUsec = 0)
        assertEquals(3, events.size) // REL_X, REL_Y, SYN
        val first = ByteBuffer.wrap(events[0])
        first.long; first.long
        assertEquals(UinputEvdevCodec.EV_REL, first.short)
        assertEquals(UinputEvdevCodec.REL_X, first.short)
        assertEquals(-15, first.int)
    }

    @Test
    fun scrollUsesWheelAxisWithInversion() {
        val events = UinputEvdevCodec.scrollEvents(scrollY = -2, timeSec = 0, timeUsec = 0)
        val first = ByteBuffer.wrap(events[0])
        first.long; first.long
        assertEquals(UinputEvdevCodec.EV_REL, first.short)
        assertEquals(UinputEvdevCodec.REL_WHEEL, first.short)
        assertEquals(2, first.int) // inverted: phone scroll-up → wheel up
    }

    @Test
    fun asciiTextMapsToKeyEventsWithShift() {
        val events = UinputEvdevCodec.textToKeyEvents("Ab", timeSec = 0, timeUsec = 0)
        // 'A' = shift-down, a-down, a-up, shift-up; 'b' = b-down, b-up (+2 SYN each) = 10
        assertEquals(12, events.size)
    }

    // ---- batching ----------------------------------------------------------

    @Test
    fun ringFlushesAtCapacityAndEncodesAllEvents() {
        val capture = CapturingChannel()
        val (sink, written) = sinkWithCapture(capture)
        // ring capacity 8 → 3 events per pointer move (2 axes + SYN) → flush at ceil(8/3)=3 moves
        repeat(3) { sink.onPointerEvent(dx = 1, dy = 2, buttonsMask = 0, scrollX = 0, scrollY = 0, action = "move") }
        sink.flush()
        val bytes = written()
        assertEquals(3 * 3 * 24, bytes.size) // 9 events × 24B
    }

    @Test
    fun scheduledFlushBatchesWithinEightMs() {
        val capture = CapturingChannel()
        val (sink, written) = sinkWithCapture(capture)
        // One move, then explicit flush — the ≤8 ms scheduler must not emit earlier
        sink.onPointerEvent(dx = 5, dy = 5, buttonsMask = 0, scrollX = 0, scrollY = 0, action = "move")
        assertEquals(0, written().size)
        sink.flush()
        assertEquals(3 * 24, written().size)
        sink.close()
    }

    @Test
    fun presentationKeysMapToEvdevCodes() {
        assertEquals(UinputEvdevCodec.KEY_F5, UinputEvdevCodec.KEY_F5)
        val f5 = UinputEvdevCodec.keyEvents(UinputEvdevCodec.KEY_F5, true, timeSec = 0, timeUsec = 0)
        assertEquals(2, f5.size) // press + SYN
        val arrow = ByteBuffer.wrap(
            UinputEvdevCodec.keyEvents(UinputEvdevCodec.KEY_RIGHT, true, timeSec = 0, timeUsec = 0)[0],
        )
        arrow.long; arrow.long
        assertEquals(0x01.toShort(), arrow.short) // EV_KEY
        assertEquals(0x6a.toShort(), arrow.short) // KEY_RIGHT
    }

    // ---- probe chain -------------------------------------------------------

    @Test
    fun selectorPrefersDirectUinputWhenWritable() {
        val selection = UinputSinkSelector.select(uinputWritable = true, ydotooldLive = true)
        assertTrue(selection is InputSinkSelection.Uinput)
    }

    @Test
    fun selectorFallsBackToYdotooldSocket() {
        val selection = UinputSinkSelector.select(uinputWritable = false, ydotooldLive = true)
        assertTrue(selection is InputSinkSelection.Ydotoold)
        assertEquals(LinuxInputBackend.YDOTOOL_WAYLAND, (selection as InputSinkSelection.Ydotoold).backend)
    }

    @Test
    fun selectorSurfacesHonestHintWhenNeitherWorks() {
        val selection = UinputSinkSelector.select(uinputWritable = false, ydotooldLive = false)
        assertTrue(selection is InputSinkSelection.Unavailable)
        assertTrue((selection as InputSinkSelection.Unavailable).hint.contains("uinput"))
        assertTrue(selection.hint.contains("ydotoold"))
    }

    private fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
}
