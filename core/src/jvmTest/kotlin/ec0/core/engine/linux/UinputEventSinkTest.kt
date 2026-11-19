/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Uinput sink tests — true little-endian evdev golden bytes, ring batching
 * (≤8 ms flush contract), InputEventSink integration, probe-chain selection,
 * and presentation/navigation keys. All I/O faked via an in-memory channel.
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            openChannel = { channel = capture; capture },
        )
        return sink to { channel?.out?.toByteArray() ?: ByteArray(0) }
    }

    // ---- codec golden bytes (true little-endian verification) ---------------

    @Test
    fun evEventIs24BytesLittleEndian() {
        val bytes = UinputEvdevCodec.encodeEvent(timeSec = 1, timeUsec = 2, type = 0x02, code = 0x01, value = -3)
        assertEquals(24, bytes.size)
        // Explicit raw byte-order check: LSB first (0x01 at offset 0 for timeSec=1; type 0x02 at offset 16)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[7])
        assertEquals(0x02.toByte(), bytes[8])
        assertEquals(0x02.toByte(), bytes[16])
        assertEquals(0x00.toByte(), bytes[17])
        assertEquals(0x01.toByte(), bytes[18])
        assertEquals(0x00.toByte(), bytes[19])

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1L, buffer.long)
        assertEquals(2L, buffer.long)
        assertEquals(0x02.toShort(), buffer.short)
        assertEquals(0x01.toShort(), buffer.short)
        assertEquals(-3, buffer.int)
    }

    @Test
    fun moveSequenceIsTwoRelAxesPlusSyn() {
        val events = UinputEvdevCodec.moveEvents(dx = -15, dy = 24, timeSec = 0, timeUsec = 0)
        assertEquals(3, events.size) // REL_X, REL_Y, SYN
        val first = ByteBuffer.wrap(events[0]).order(ByteOrder.LITTLE_ENDIAN)
        first.long; first.long
        assertEquals(UinputEvdevCodec.EV_REL, first.short)
        assertEquals(UinputEvdevCodec.REL_X, first.short)
        assertEquals(-15, first.int)
    }

    @Test
    fun scrollUsesWheelAxisWithInversion() {
        val events = UinputEvdevCodec.scrollEvents(scrollY = -2, timeSec = 0, timeUsec = 0)
        val first = ByteBuffer.wrap(events[0]).order(ByteOrder.LITTLE_ENDIAN)
        first.long; first.long
        assertEquals(UinputEvdevCodec.EV_REL, first.short)
        assertEquals(UinputEvdevCodec.REL_WHEEL, first.short)
        assertEquals(2, first.int) // inverted: phone scroll-up → wheel up
    }

    @Test
    fun asciiTextMapsToKeyEventsWithShiftAndPunctuation() {
        val events = UinputEvdevCodec.textToKeyEvents("Ab", timeSec = 0, timeUsec = 0)
        // 'A' = shift-down, a-down, a-up, shift-up; 'b' = b-down, b-up (+SYN each) = 12
        assertEquals(12, events.size)
        assertEquals(UinputEvdevCodec.KEY_ENTER to false, UinputEvdevCodec.asciiToKey('\n'))
        assertEquals(0x1c.toShort(), UinputEvdevCodec.KEY_ENTER)
        assertEquals(0x34.toShort() to false, UinputEvdevCodec.asciiToKey('.'))
        assertEquals(0x02.toShort() to true, UinputEvdevCodec.asciiToKey('!'))
    }

    // ---- batching & InputEventSink integration ------------------------------

    @Test
    fun ringFlushesAtCapacityAndEncodesAllEventsLittleEndian() {
        val capture = CapturingChannel()
        val (sink, written) = sinkWithCapture(capture)
        repeat(3) { sink.onPointerEvent(dx = 1, dy = 2, buttonsMask = 0, scrollX = 0, scrollY = 0, action = "move") }
        sink.flush()
        val bytes = written()
        assertEquals(3 * 3 * 24, bytes.size) // 9 events × 24B
        val first = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        first.long; first.long
        assertEquals(UinputEvdevCodec.EV_REL, first.short)
        assertEquals(UinputEvdevCodec.REL_X, first.short)
        assertEquals(1, first.int)
        sink.close()
    }

    @Test
    fun scheduledFlushBatchesWithinEightMs() {
        val capture = CapturingChannel()
        val (sink, written) = sinkWithCapture(capture)
        sink.onPointerEvent(dx = 5, dy = 5, buttonsMask = 0, scrollX = 0, scrollY = 0, action = "move")
        assertEquals(0, written().size)
        sink.flush()
        assertEquals(3 * 24, written().size)
        sink.close()
    }

    @Test
    fun implementsInputEventSinkForWirePointerAndKeyModels() {
        val capture = CapturingChannel()
        val (sink, written) = sinkWithCapture(capture)
        sink.onPointerEvent(InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = 7, dy = -4))
        sink.onKeyEvent(InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = UinputEvdevCodec.KEY_ENTER.toInt()))
        sink.onKeyEvent(InputKeyEvent(action = InputProtocol.KEY_ACTION_UP, keyCode = UinputEvdevCodec.KEY_ENTER.toInt()))
        sink.flush()
        // 3 pointer events + 2 key-down events + 2 key-up events = 7 events * 24 bytes
        assertEquals(7 * 24, written().size)
        sink.close()
    }

    @Test
    fun presentationKeysMapToEvdevCodes() {
        assertEquals(UinputEvdevCodec.KEY_F5, UinputEvdevCodec.KEY_F5)
        val f5 = UinputEvdevCodec.keyEvents(UinputEvdevCodec.KEY_F5, true, timeSec = 0, timeUsec = 0)
        assertEquals(2, f5.size) // press + SYN
        val arrow = ByteBuffer.wrap(
            UinputEvdevCodec.keyEvents(UinputEvdevCodec.KEY_RIGHT, true, timeSec = 0, timeUsec = 0)[0],
        ).order(ByteOrder.LITTLE_ENDIAN)
        arrow.long; arrow.long
        assertEquals(0x01.toShort(), arrow.short) // EV_KEY
        assertEquals(0x6a.toShort(), arrow.short) // KEY_RIGHT
    }

    @Test
    fun daemonScriptKeepsFdOpenAndDestroysCleanly() {
        val script = UinputSetupScript.daemonScript()
        assertTrue(script.contains("UI_DEV_CREATE"))
        assertTrue(script.contains("UI_DEV_DESTROY"))
        assertTrue(script.contains("stdin.read(24)"))
    }

    // ---- probe chain -------------------------------------------------------

    @Test
    fun selectorPrefersDirectUinputWhenWritable() {
        val selection = UinputSinkSelector.select(
            uinputWritable = true,
            ydotooldLive = true,
            sinkFactory = { UinputEventSink(forceChannel = true, openChannel = { CapturingChannel() }) },
        )
        assertTrue(selection is InputSinkSelection.Uinput)
        (selection as InputSinkSelection.Uinput).sink.close()
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
}
