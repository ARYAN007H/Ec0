/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Direct /dev/uinput event sink — the real uinput virtual device path.
 *
 * Event EMISSION is plain write() of evdev `struct input_event` records
 * (24 bytes on 64-bit Linux, little-endian: i64 tv_sec, i64 tv_usec,
 * u16 type, u16 code, i32 value) — no ioctls on the hot path.
 *
 * Kernel lifecycle: a uinput virtual device only persists while the file
 * descriptor that issued `UI_DEV_CREATE` stays open, and events must be
 * written to that same fd. [UinputSetupScript.daemonScript] runs a tiny
 * stdlib-only python3 helper that creates the virtual mouse+keyboard device,
 * keeps `fd` open, and forwards 24-byte little-endian `struct input_event`
 * frames piped from stdin directly into `fd` until EOF (then calls
 * `UI_DEV_DESTROY`). [UinputEventSink] can write either to a direct channel
 * (tests / pre-created fd) or to the persistent daemon's stdin pipe, and
 * implements [InputEventSink] directly so [Ec0EngineNode] wires into it
 * without spawning a subprocess per event.
 *
 * Probe chain (in [UinputSinkSelector.select]):
 *   1. /dev/uinput writable  → UINPUT_DIRECT (this sink, batched ≤8 ms)
 *   2. ydotoold socket live  → existing ydotool exec bridge (daemon owns uinput)
 *   3. neither               → honest setup hint (uinput perms + ydotoold install)
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.services.InputEventSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pure evdev encoding — golden-byte tested, little-endian, no I/O. */
object UinputEvdevCodec {
    const val EV_SYN: Short = 0x00
    const val EV_KEY: Short = 0x01
    const val EV_REL: Short = 0x02

    const val REL_X: Short = 0x00
    const val REL_Y: Short = 0x01
    const val REL_HWHEEL: Short = 0x06
    const val REL_WHEEL: Short = 0x08

    const val BTN_LEFT: Short = 0x110
    const val BTN_RIGHT: Short = 0x111
    const val BTN_MIDDLE: Short = 0x112

    // Navigation, editing & modifier keys (Linux input-event-codes.h)
    const val KEY_ESC: Short = 0x01
    const val KEY_BACKSPACE: Short = 0x0e
    const val KEY_TAB: Short = 0x0f
    const val KEY_ENTER: Short = 0x1c
    const val KEY_LEFT_CTRL: Short = 0x1d
    const val KEY_LEFT_SHIFT: Short = 0x2a
    const val KEY_LEFT_ALT: Short = 0x38
    const val KEY_SPACE: Short = 0x39
    const val KEY_F5: Short = 0x3f
    const val KEY_UP: Short = 0x67
    const val KEY_LEFT: Short = 0x69
    const val KEY_RIGHT: Short = 0x6a
    const val KEY_DOWN: Short = 0x6c
    const val KEY_MUTE: Short = 0x71
    const val KEY_VOLUMEDOWN: Short = 0x72
    const val KEY_VOLUMEUP: Short = 0x73
    const val KEY_LEFT_META: Short = 0x7d
    const val KEY_NEXTSONG: Short = 0xa3
    const val KEY_PLAYPAUSE: Short = 0xa4
    const val KEY_PREVIOUSSONG: Short = 0xa5

    /** Encodes one 64-bit Linux `struct input_event` (24 bytes, little-endian). */
    fun encodeEvent(timeSec: Long, timeUsec: Long, type: Short, code: Short, value: Int): ByteArray {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(timeSec)
        buffer.putLong(timeUsec)
        buffer.putShort(type)
        buffer.putShort(code)
        buffer.putInt(value)
        return buffer.array()
    }

    /** REL move + SYN — the trackpad delta pair. */
    fun moveEvents(dx: Int, dy: Int, timeSec: Long, timeUsec: Long): List<ByteArray> = listOf(
        encodeEvent(timeSec, timeUsec, EV_REL, REL_X, dx),
        encodeEvent(timeSec, timeUsec, EV_REL, REL_Y, dy),
        encodeEvent(timeSec, timeUsec, EV_SYN, 0, 0),
    )

    /** EV_KEY press/release pair + SYN. */
    fun keyEvents(code: Short, down: Boolean, timeSec: Long, timeUsec: Long): List<ByteArray> = listOf(
        encodeEvent(timeSec, timeUsec, EV_KEY, code, if (down) 1 else 0),
        encodeEvent(timeSec, timeUsec, EV_SYN, 0, 0),
    )

    /** Wheel scroll (positive = down) + SYN. */
    fun scrollEvents(scrollY: Int, timeSec: Long, timeUsec: Long): List<ByteArray> = listOf(
        encodeEvent(timeSec, timeUsec, EV_REL, REL_WHEEL, -scrollY),
        encodeEvent(timeSec, timeUsec, EV_SYN, 0, 0),
    )

    /** Button click (press + release + SYN). */
    fun clickEvents(code: Short, timeSec: Long, timeUsec: Long): List<ByteArray> =
        keyEvents(code, true, timeSec, timeUsec) + keyEvents(code, false, timeSec, timeUsec)

    /** UTF-8 ASCII text → evdev key events (shift applied for uppercase and shifted symbols). */
    fun textToKeyEvents(text: String, timeSec: Long, timeUsec: Long): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (ch in text) {
            val (code, shift) = asciiToKey(ch) ?: continue
            if (shift) out += keyEvents(KEY_LEFT_SHIFT, true, timeSec, timeUsec)
            out += keyEvents(code, true, timeSec, timeUsec)
            out += keyEvents(code, false, timeSec, timeUsec)
            if (shift) out += keyEvents(KEY_LEFT_SHIFT, false, timeSec, timeUsec)
        }
        return out
    }

    /** Full US-ASCII → Linux evdev keycode + shift map (input-event-codes.h). */
    fun asciiToKey(ch: Char): Pair<Short, Boolean>? {
        if (ch in 'a'..'z' || ch in 'A'..'Z') {
            val shifted = ch in 'A'..'Z'
            val base = when (ch.lowercaseChar()) {
                'a' -> 0x1e; 'b' -> 0x30; 'c' -> 0x2e; 'd' -> 0x20; 'e' -> 0x12
                'f' -> 0x21; 'g' -> 0x22; 'h' -> 0x23; 'i' -> 0x17; 'j' -> 0x24
                'k' -> 0x25; 'l' -> 0x26; 'm' -> 0x32; 'n' -> 0x31; 'o' -> 0x18
                'p' -> 0x19; 'q' -> 0x10; 'r' -> 0x13; 's' -> 0x1f; 't' -> 0x14
                'u' -> 0x16; 'v' -> 0x2f; 'w' -> 0x11; 'x' -> 0x2d; 'y' -> 0x15
                'z' -> 0x2c
                else -> return null
            }
            return base.toShort() to shifted
        }
        return when (ch) {
            ' ' -> KEY_SPACE to false
            '\n', '\r' -> KEY_ENTER to false
            '\t' -> KEY_TAB to false
            '1' -> 0x02.toShort() to false; '!' -> 0x02.toShort() to true
            '2' -> 0x03.toShort() to false; '@' -> 0x03.toShort() to true
            '3' -> 0x04.toShort() to false; '#' -> 0x04.toShort() to true
            '4' -> 0x05.toShort() to false; '$' -> 0x05.toShort() to true
            '5' -> 0x06.toShort() to false; '%' -> 0x06.toShort() to true
            '6' -> 0x07.toShort() to false; '^' -> 0x07.toShort() to true
            '7' -> 0x08.toShort() to false; '&' -> 0x08.toShort() to true
            '8' -> 0x09.toShort() to false; '*' -> 0x09.toShort() to true
            '9' -> 0x0a.toShort() to false; '(' -> 0x0a.toShort() to true
            '0' -> 0x0b.toShort() to false; ')' -> 0x0b.toShort() to true
            '-' -> 0x0c.toShort() to false; '_' -> 0x0c.toShort() to true
            '=' -> 0x0d.toShort() to false; '+' -> 0x0d.toShort() to true
            '[' -> 0x1a.toShort() to false; '{' -> 0x1a.toShort() to true
            ']' -> 0x1b.toShort() to false; '}' -> 0x1b.toShort() to true
            ';' -> 0x27.toShort() to false; ':' -> 0x27.toShort() to true
            '\'' -> 0x28.toShort() to false; '"' -> 0x28.toShort() to true
            '`' -> 0x29.toShort() to false; '~' -> 0x29.toShort() to true
            '\\' -> 0x2b.toShort() to false; '|' -> 0x2b.toShort() to true
            ',' -> 0x33.toShort() to false; '<' -> 0x33.toShort() to true
            '.' -> 0x34.toShort() to false; '>' -> 0x34.toShort() to true
            '/' -> 0x35.toShort() to false; '?' -> 0x35.toShort() to true
            else -> null
        }
    }
}

/**
 * Stdlib-only python3 uinput daemon script: opens `/dev/uinput`, registers all
 * pointer/wheel/keyboard/media codes, issues `UI_DEV_CREATE`, and stays alive
 * forwarding 24-byte little-endian `struct input_event` records from stdin to
 * the kernel fd until stdin closes (at which point it calls `UI_DEV_DESTROY`).
 */
object UinputSetupScript {
    fun script(): String = daemonScript()

    fun daemonScript(): String = """
        import fcntl, struct, os, sys
        UINPUT = "/dev/uinput"
        UI_SET_EVBIT = 0x40045564
        UI_SET_RELBIT = 0x40045565
        UI_SET_KEYBIT = 0x40045566
        UI_DEV_SETUP = 0x405c5503
        UI_DEV_CREATE = 0x5501
        UI_DEV_DESTROY = 0x5502
        fd = os.open(UINPUT, os.O_WRONLY | os.O_NONBLOCK)
        try:
            for bit in (0x01, 0x02):  # EV_KEY, EV_REL
                fcntl.ioctl(fd, UI_SET_EVBIT, bit)
            for rel in (0x00, 0x01, 0x06, 0x08):  # REL_X, REL_Y, REL_HWHEEL, REL_WHEEL
                fcntl.ioctl(fd, UI_SET_RELBIT, rel)
            for key in range(1, 249):  # Standard keyboard + media/volume keys
                fcntl.ioctl(fd, UI_SET_KEYBIT, key)
            for btn in (0x110, 0x111, 0x112):  # BTN_LEFT, BTN_RIGHT, BTN_MIDDLE
                fcntl.ioctl(fd, UI_SET_KEYBIT, btn)
            # struct uinput_setup { struct input_id id (4x u16); char name[80]; u32 ff_effects_max; }
            setup = struct.pack("<HHHH80sI", 0x06, 0x1ac0, 0x0001, 0x0001, b"ec0 virtual input", 0)
            fcntl.ioctl(fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(fd, UI_DEV_CREATE)
            stdin = sys.stdin.buffer
            while True:
                chunk = stdin.read(24)
                if not chunk or len(chunk) < 24:
                    break
                os.write(fd, chunk)
        finally:
            try:
                fcntl.ioctl(fd, UI_DEV_DESTROY)
            except Exception:
                pass
            os.close(fd)
    """.trimIndent()
}

/**
 * Direct-uinput [InputEventSink] with ≤8 ms batched flushes and zero
 * per-event allocation churn (primitive ring buffer, reused little-endian ByteBuffer).
 */
class UinputEventSink(
    private val devicePath: String = "/dev/uinput",
    private val flushIntervalMs: Long = 8,
    private val ringCapacity: Int = 512,
    /** Injectable so tests can drive flushes deterministically. */
    private val timeSource: () -> Long = System::nanoTime,
    /** Bypass the /dev/uinput writability probe (tests with a fake device). */
    private val forceChannel: Boolean = false,
    private val openChannel: (String) -> WritableByteChannel = {
        FileChannel.open(
            java.nio.file.Path.of(it),
            StandardOpenOption.WRITE,
        )
    },
    private val onCloseExtra: () -> Unit = {},
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "uinput-flush").apply { isDaemon = true } },
) : InputEventSink, AutoCloseable {
    private val stride = 5
    private val ring = LongArray(ringCapacity * stride)
    private var ringSize = 0
    private var usecAccumulator = 0L
    private val buffer = ByteBuffer.allocateDirect(ringCapacity * 24).order(ByteOrder.LITTLE_ENDIAN)
    private var channel: WritableByteChannel? = null
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    val sinkAvailable: Boolean = forceChannel || File(devicePath).canWrite()
    private var lastFlushNanos = timeSource()

    init {
        if (sinkAvailable) {
            channel = runCatching { openChannel(devicePath) }.getOrNull()
        }
    }

    /** Applies queued events at most every [flushIntervalMs]; never blocks on the phone's send path. */
    private fun scheduleFlushIfDue() {
        val now = timeSource()
        val due = now - lastFlushNanos >= TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)
        if (due) {
            flush()
        } else {
            scheduler.schedule({ synchronized(lock) { flushLocked() } }, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    // ---- InputEventSink wire-model overrides --------------------------------

    override fun onPointerEvent(event: InputPointerEvent) {
        onPointerEvent(
            dx = event.dx,
            dy = event.dy,
            buttonsMask = event.buttonsMask,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            action = event.action,
        )
    }

    override fun onKeyEvent(event: InputKeyEvent) {
        when (event.action) {
            InputProtocol.KEY_ACTION_COMMIT_TEXT -> {
                val text = event.text ?: return
                onKeyEvent(code = 0, down = true, text = text)
            }
            InputProtocol.KEY_ACTION_DOWN -> {
                val code = event.keyCode?.toShort() ?: return
                applyModifiersAndKey(code, event.modifiers, down = true)
            }
            InputProtocol.KEY_ACTION_UP -> {
                val code = event.keyCode?.toShort() ?: return
                applyModifiersAndKey(code, event.modifiers, down = false)
            }
        }
    }

    private fun applyModifiersAndKey(code: Short, modifiers: Int, down: Boolean) {
        if (!sinkAvailable || closed.get()) return
        synchronized(lock) {
            val now = timeSource()
            val timeSec = now / 1_000_000_000L
            val timeUsec = (usecAccumulator++ % 1_000_000)
            val modKeys = buildList {
                if ((modifiers and 1) != 0) add(UinputEvdevCodec.KEY_LEFT_SHIFT)
                if ((modifiers and 2) != 0) add(UinputEvdevCodec.KEY_LEFT_CTRL)
                if ((modifiers and 4) != 0) add(UinputEvdevCodec.KEY_LEFT_ALT)
                if ((modifiers and 8) != 0) add(UinputEvdevCodec.KEY_LEFT_META)
            }
            if (down) {
                modKeys.forEach { append(UinputEvdevCodec.EV_KEY, it, 1, timeSec, timeUsec) }
                append(UinputEvdevCodec.EV_KEY, code, 1, timeSec, timeUsec)
                append(UinputEvdevCodec.EV_SYN, 0, 0, timeSec, timeUsec + 1)
            } else {
                append(UinputEvdevCodec.EV_KEY, code, 0, timeSec, timeUsec)
                modKeys.asReversed().forEach { append(UinputEvdevCodec.EV_KEY, it, 0, timeSec, timeUsec) }
                append(UinputEvdevCodec.EV_SYN, 0, 0, timeSec, timeUsec + 1)
            }
        }
        scheduleFlushIfDue()
    }

    // ---- Primitive fast-path overloads --------------------------------------

    fun onPointerEvent(dx: Int, dy: Int, buttonsMask: Int, scrollX: Int, scrollY: Int, action: String) {
        if (!sinkAvailable || closed.get()) return
        synchronized(lock) {
            val now = timeSource()
            val timeUsec = (usecAccumulator++ % 1_000_000)
            val timeSec = now / 1_000_000_000L
            when (action) {
                "move" -> {
                    append(UinputEvdevCodec.EV_REL, UinputEvdevCodec.REL_X, dx, timeSec, timeUsec)
                    append(UinputEvdevCodec.EV_REL, UinputEvdevCodec.REL_Y, dy, timeSec, timeUsec + 1)
                }
                "scroll" -> {
                    if (scrollY != 0) {
                        append(UinputEvdevCodec.EV_REL, UinputEvdevCodec.REL_WHEEL, -scrollY, timeSec, timeUsec)
                    }
                    if (scrollX != 0) {
                        append(UinputEvdevCodec.EV_REL, UinputEvdevCodec.REL_HWHEEL, scrollX, timeSec, timeUsec + 1)
                    }
                }
                "click", "down", "up" -> {
                    val code = buttonCode(buttonsMask)
                    val value = if (action == "up") 0 else 1
                    append(UinputEvdevCodec.EV_KEY, code, value, timeSec, timeUsec)
                    if (action == "click") {
                        append(UinputEvdevCodec.EV_SYN, 0, 0, timeSec, timeUsec + 1)
                        append(UinputEvdevCodec.EV_KEY, code, 0, timeSec, timeUsec + 2)
                    }
                }
            }
            append(UinputEvdevCodec.EV_SYN, 0, 0, timeSec, timeUsec + 3)
        }
        scheduleFlushIfDue()
    }

    fun onKeyEvent(code: Short, down: Boolean, text: String? = null) {
        if (!sinkAvailable || closed.get()) return
        synchronized(lock) {
            val now = timeSource()
            val timeSec = now / 1_000_000_000L
            val timeUsec = (usecAccumulator++ % 1_000_000)
            if (text != null) {
                UinputEvdevCodec.textToKeyEvents(text, timeSec, timeUsec).forEach {
                    appendEncoded(it)
                }
            } else {
                append(UinputEvdevCodec.EV_KEY, code, if (down) 1 else 0, timeSec, timeUsec)
                append(UinputEvdevCodec.EV_SYN, 0, 0, timeSec, timeUsec + 1)
            }
        }
        scheduleFlushIfDue()
    }

    /** Presentation mode: F5 to start, arrows to advance (to the focused window). */
    fun sendPresentationKey(code: Short) {
        onKeyEvent(code, down = true)
        onKeyEvent(code, down = false)
    }

    private fun buttonCode(buttonsMask: Int): Short = when (buttonsMask) {
        2 -> UinputEvdevCodec.BTN_RIGHT
        3, 4 -> UinputEvdevCodec.BTN_MIDDLE
        else -> UinputEvdevCodec.BTN_LEFT
    }

    /** Appends (type, code, value, timeSec, timeUsec) into the ring; flushes when full. */
    private fun append(type: Short, code: Short, value: Int, timeSec: Long, timeUsec: Long) {
        if (ringSize >= ringCapacity) {
            flushLocked()
        }
        val base = ringSize * stride
        ring[base] = type.toLong()
        ring[base + 1] = code.toLong()
        ring[base + 2] = value.toLong()
        ring[base + 3] = timeSec
        ring[base + 4] = timeUsec
        ringSize++
    }

    private fun appendEncoded(encoded: ByteArray) {
        flushLocked()
        runCatching { channel?.write(ByteBuffer.wrap(encoded)) }
    }

    private fun flushLocked() {
        val ch = channel ?: return
        if (ringSize == 0) {
            lastFlushNanos = timeSource()
            return
        }
        buffer.clear()
        for (i in 0 until ringSize) {
            val base = i * stride
            buffer.putLong(ring[base + 3])
            buffer.putLong(ring[base + 4])
            buffer.putShort(ring[base].toShort())
            buffer.putShort(ring[base + 1].toShort())
            buffer.putInt(ring[base + 2].toInt())
        }
        buffer.flip()
        runCatching {
            while (buffer.hasRemaining()) {
                ch.write(buffer)
            }
        }
        ringSize = 0
        lastFlushNanos = timeSource()
    }

    fun flush() = synchronized(lock) { flushLocked() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        flush()
        scheduler.shutdownNow()
        runCatching { channel?.close() }
        runCatching { onCloseExtra() }
    }

    companion object {
        /**
         * Launches the persistent python3 uinput helper process and returns a
         * [UinputEventSink] streaming 24-byte little-endian evdev frames into it.
         * Returns null when `/dev/uinput` is not writable or python3 is unavailable.
         */
        fun openWithPersistentHelper(): UinputEventSink? {
            if (!File("/dev/uinput").canWrite()) return null
            val proc = runCatching {
                ProcessBuilder("python3", "-u", "-c", UinputSetupScript.daemonScript())
                    .redirectErrorStream(false)
                    .start()
            }.getOrNull() ?: return null
            if (!proc.isAlive) return null
            val pipeChannel = Channels.newChannel(proc.outputStream)
            return UinputEventSink(
                forceChannel = true,
                openChannel = { pipeChannel },
                onCloseExtra = {
                    runCatching { proc.outputStream.close() }
                    runCatching { proc.destroy() }
                },
            )
        }
    }
}

/**
 * Adaptive Linux [InputEventSink] wired into [ContinuityHubController]:
 * prefers zero-fork persistent `/dev/uinput` streaming when available and
 * falls back seamlessly to [LinuxInputInjectionBridge] (`ydotool` / `xdotool`).
 */
class AdaptiveLinuxInputSink(
    private val uinputSink: UinputEventSink? = UinputEventSink.openWithPersistentHelper(),
    private val fallbackBridge: LinuxInputInjectionBridge = LinuxInputInjectionBridge(),
) : InputEventSink, AutoCloseable {

    val usingDirectUinput: Boolean
        get() = uinputSink?.sinkAvailable == true

    override fun onPointerEvent(event: InputPointerEvent) {
        if (uinputSink != null && uinputSink.sinkAvailable) {
            uinputSink.onPointerEvent(event)
        } else {
            fallbackBridge.onPointerEvent(event)
        }
    }

    override fun onKeyEvent(event: InputKeyEvent) {
        if (uinputSink != null && uinputSink.sinkAvailable) {
            uinputSink.onKeyEvent(event)
        } else {
            fallbackBridge.onKeyEvent(event)
        }
    }

    override fun close() {
        runCatching { uinputSink?.close() }
    }
}

/** Selects the best available input sink per the probe chain. */
sealed interface InputSinkSelection {
    /** Direct /dev/uinput — real virtual device, batched. */
    data class Uinput(val sink: UinputEventSink) : InputSinkSelection

    /** ydotoold socket live — the existing exec bridge handles it. */
    data class Ydotoold(val backend: LinuxInputBackend) : InputSinkSelection

    /** Neither works — honest hint for the UI. */
    data class Unavailable(val hint: String) : InputSinkSelection
}

object UinputSinkSelector {
    const val YDOTOOLD_SOCKETS: String = "/run/ydotoold.pkg/.socket,/tmp/.ydotool_socket,/run/user/1000/.ydotool_socket"

    fun ydotooldSocketLive(paths: String = YDOTOOLD_SOCKETS): Boolean =
        paths.split(",").any { File(it.trim()).exists() }

    fun select(
        uinputWritable: Boolean = File("/dev/uinput").canWrite(),
        ydotooldLive: Boolean = ydotooldSocketLive(),
        sinkFactory: () -> UinputEventSink = { UinputEventSink.openWithPersistentHelper() ?: UinputEventSink() },
    ): InputSinkSelection = when {
        uinputWritable -> InputSinkSelection.Uinput(sinkFactory())
        ydotooldLive -> InputSinkSelection.Ydotoold(LinuxInputBackend.YDOTOOL_WAYLAND)
        else -> InputSinkSelection.Unavailable(
            "Remote input needs /dev/uinput write access (add yourself to the 'input' group and re-login) " +
                "or a running ydotoold daemon. Setup: sudo gpasswd -a \${USER:-\$LOGNAME} input && sudo modprobe uinput",
        )
    }
}
