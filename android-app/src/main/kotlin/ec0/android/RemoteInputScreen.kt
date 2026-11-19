/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Remote Input controller (touchpad + precision pointer acceleration + haptics
 * + special keys strip + live typing + presentation remote + integrated PC Now Playing).
 *
 * Latency contract: pointer deltas batch <= 8 ms and coalesce — one MOVE per flush,
 * clicks/scrolls ride in the same burst inside the AEAD-authenticated session.
 */
package ec0.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.ui.models.MediaSessionUi
import ec0.ui.theme.Ec0Shapes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** PC-side button bits (uinput convention shared with the desktop receiver). */
object PointerButtons {
    const val LEFT: Int = 1
    const val RIGHT: Int = 2
    const val MIDDLE: Int = 4
}

/**
 * Pure batcher: pointer MOVE deltas accumulate and flush as ONE coalesced
 * [InputPointerEvent] per tick; CLICK and SCROLL events queue in arrival order
 * behind the pending MOVE (so a click lands where the drag ended).
 */
class PointerEventBatcher {
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingScrollX = 0
    private var pendingScrollY = 0
    private val queued = ArrayDeque<InputPointerEvent>()

    @Synchronized
    fun addMove(dx: Int, dy: Int) {
        pendingDx += dx
        pendingDy += dy
    }

    @Synchronized
    fun addScroll(dx: Int, dy: Int) {
        pendingScrollX += dx
        pendingScrollY += dy
    }

    @Synchronized
    fun addClick(buttonsMask: Int) {
        queued.addLast(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_CLICK, buttonsMask = buttonsMask),
        )
    }

    @Synchronized
    fun flush(): List<InputPointerEvent> {
        val burst = mutableListOf<InputPointerEvent>()
        if (pendingDx != 0 || pendingDy != 0) {
            burst.add(
                InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = pendingDx, dy = pendingDy),
            )
            pendingDx = 0
            pendingDy = 0
        }
        if (pendingScrollX != 0 || pendingScrollY != 0) {
            burst.add(
                InputPointerEvent(
                    action = InputProtocol.POINTER_ACTION_SCROLL,
                    scrollX = pendingScrollX,
                    scrollY = pendingScrollY,
                ),
            )
            pendingScrollX = 0
            pendingScrollY = 0
        }
        while (queued.isNotEmpty()) {
            burst.add(queued.removeFirst())
        }
        return burst
    }
}

/**
 * Pure gesture → wire-event interpretation for the touchpad surface.
 */
object PointerGestures {
    const val TOUCHPAD_SENSITIVITY_DEFAULT: Float = 1.6f
    const val LONG_PRESS_MS: Long = 350

    fun tapClick(twoFinger: Boolean): Int =
        if (twoFinger) PointerButtons.RIGHT else PointerButtons.LEFT

    fun longPressClick(): Int = PointerButtons.MIDDLE

    /** Raw drag distance → wire delta, sensitivity-scaled and clamped (exact linear contract). */
    fun scaleDrag(dx: Float, dy: Float, sensitivity: Float = TOUCHPAD_SENSITIVITY_DEFAULT): Pair<Int, Int> {
        val clamped = sensitivity.coerceIn(0.5f, 4f)
        return (dx * clamped).toInt().coerceIn(-500, 500) to (dy * clamped).toInt().coerceIn(-500, 500)
    }

    /**
     * Smooth velocity-accelerated drag scaling: slow precision movements stay
     * 1:1 while brisk swipes accelerate smoothly up to 2.2× across wide monitors.
     */
    fun scaleDragAccelerated(
        dx: Float,
        dy: Float,
        sensitivity: Float = TOUCHPAD_SENSITIVITY_DEFAULT,
    ): Pair<Int, Int> {
        val clamped = sensitivity.coerceIn(0.5f, 4f)
        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gain = (1f + (speed / 28f).coerceAtMost(1.2f)) * clamped
        return (dx * gain).roundToInt().coerceIn(-500, 500) to (dy * gain).roundToInt().coerceIn(-500, 500)
    }

    /** Two-finger drag → scroll ticks. */
    fun scaleScroll(delta: Float): Int = (delta / 24f).toInt().coerceIn(-30, 30)
}

/**
 * Sends input frames to the paired desktop over the live engine session.
 */
class RemoteInputSender(
    private val node: Ec0EngineNode,
    private val scope: CoroutineScope,
    private val peerDeviceId: () -> String?,
    private val flushIntervalMs: Long = 8L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val batcher = PointerEventBatcher()
    private var flushJob: kotlinx.coroutines.Job? = null

    @Volatile
    internal var lastSendThreadName: String? = null
        private set

    var remoteInputAllowed: Boolean = true

    private fun session() = peerDeviceId()?.let { node.activeSessionFor(it) }

    private fun startFlushTick() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(flushIntervalMs)
                val session = session() ?: continue
                for (event in batcher.flush()) {
                    runCatching {
                        lastSendThreadName = Thread.currentThread().name
                        node.inputShareService.sendPointerEvent(event, session, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun sendPointerMove(dx: Int, dy: Int) {
        if (!remoteInputAllowed) return
        batcher.addMove(dx, dy)
        startFlushTick()
    }

    fun sendScroll(dx: Int, dy: Int) {
        if (!remoteInputAllowed) return
        batcher.addScroll(dx, dy)
        startFlushTick()
    }

    fun sendClick(buttonsMask: Int) {
        if (!remoteInputAllowed) return
        batcher.addClick(buttonsMask)
        startFlushTick()
    }

    fun sendCommitText(text: String) {
        if (!remoteInputAllowed || text.isEmpty()) return
        val session = session() ?: return
        scope.launch(ioDispatcher) {
            runCatching {
                lastSendThreadName = Thread.currentThread().name
                node.inputShareService.sendKeyEvent(
                    InputKeyEvent(action = InputProtocol.KEY_ACTION_COMMIT_TEXT, text = text),
                    session,
                    System.currentTimeMillis(),
                )
            }
        }
    }

    fun sendKey(keyCode: Int, modifiers: Int = 0) {
        if (!remoteInputAllowed) return
        val session = session() ?: return
        scope.launch(ioDispatcher) {
            runCatching {
                lastSendThreadName = Thread.currentThread().name
                node.inputShareService.sendKeyEvent(
                    InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = keyCode, modifiers = modifiers),
                    session,
                    System.currentTimeMillis(),
                )
                node.inputShareService.sendKeyEvent(
                    InputKeyEvent(action = InputProtocol.KEY_ACTION_UP, keyCode = keyCode, modifiers = modifiers),
                    session,
                    System.currentTimeMillis(),
                )
            }
        }
    }
}

/** Linux evdev keycodes matching UinputEvdevCodec on the desktop receiver. */
object PcKeys {
    const val ESC: Int = 1
    const val BACKSPACE: Int = 14
    const val TAB: Int = 15
    const val ENTER: Int = 28
    const val LEFT_CTRL: Int = 29
    const val LEFT_ALT: Int = 56
    const val F5: Int = 63
    const val UP: Int = 103
    const val PAGE_UP: Int = 104
    const val LEFT: Int = 105
    const val RIGHT: Int = 106
    const val DOWN: Int = 108
    const val PAGE_DOWN: Int = 109
    const val VOLUME_MUTE: Int = 113
    const val VOLUME_DOWN: Int = 114
    const val VOLUME_UP: Int = 115
    const val LEFT_META: Int = 125
}

@Composable
fun RemoteInputScreen(
    node: Ec0EngineNode,
    connectedPeerId: String?,
    peerCapabilities: Set<EngineCapability>,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    mediaSession: MediaSessionUi? = null,
    onMediaControl: ((action: String, volumePct: Int?) -> Unit)? = null,
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val sender = remember(node, connectedPeerId) {
        RemoteInputSender(node, scope, peerDeviceId = { connectedPeerId })
    }
    val haptics = LocalHapticFeedback.current
    var sensitivity by remember { mutableFloatStateOf(PointerGestures.TOUCHPAD_SENSITIVITY_DEFAULT) }
    var liveTyping by remember { mutableStateOf(false) }

    val sessionLive = connectedPeerId != null
    val negotiated = EngineCapability.INPUT in peerCapabilities ||
        EngineCapability.REMOTE_INPUT in peerCapabilities

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Sleek M3 Expressive top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Remote Input",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (sessionLive && negotiated) "Encrypted low-latency trackpad & keyboard"
                        else "Waiting for paired desktop",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Sensitivity toggle chip
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1.0f to "1×", 1.6f to "1.6×", 2.4f to "2.4×").forEach { (value, label) ->
                        val selected = abs(sensitivity - value) < 0.05f
                        FilledTonalButton(
                            onClick = { sensitivity = value },
                            shape = Ec0Shapes.pill,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (!sessionLive) {
                HonestCard("No active desktop connection — pair and connect to your PC first.")
            } else if (!negotiated) {
                HonestCard("Waiting for the desktop to advertise remote input support.")
            } else {
                // Integrated Now Playing & PC Media Controls
                if (mediaSession != null || onMediaControl != null) {
                    Card(
                        shape = Ec0Shapes.card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    mediaSession?.title ?: "PC Media & Volume",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                mediaSession?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                                    Text(
                                        artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (onMediaControl != null) {
                                FilledTonalIconButton(
                                    onClick = { onMediaControl("previous", null) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(18.dp))
                                }
                                FilledTonalIconButton(
                                    onClick = { onMediaControl("play_pause", null) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        if (mediaSession?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onMediaControl("next", null) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(18.dp))
                                }
                            }
                            FilledTonalIconButton(
                                onClick = { sender.sendKey(PcKeys.VOLUME_DOWN) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Filled.VolumeDown, contentDescription = "Vol -", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { sender.sendKey(PcKeys.VOLUME_MUTE) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Filled.VolumeOff, contentDescription = "Mute", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { sender.sendKey(PcKeys.VOLUME_UP) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Filled.VolumeUp, contentDescription = "Vol +", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Precision accelerated touchpad surface with haptics
                TouchpadSurface(
                    sensitivity = sensitivity,
                    onTap = { twoFinger ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        sender.sendClick(PointerGestures.tapClick(twoFinger))
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sender.sendClick(PointerGestures.longPressClick())
                    },
                    onDrag = { dx, dy -> sender.sendPointerMove(dx, dy) },
                    onScroll = { dx, dy ->
                        sender.sendScroll(PointerGestures.scaleScroll(dx), PointerGestures.scaleScroll(dy))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                // Dedicated Left / Right click tactile buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sender.sendClick(PointerButtons.LEFT)
                        },
                        shape = Ec0Shapes.chip,
                        modifier = Modifier.weight(1f),
                    ) { Text("Left click") }
                    FilledTonalButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sender.sendClick(PointerButtons.RIGHT)
                        },
                        shape = Ec0Shapes.chip,
                        modifier = Modifier.weight(1f),
                    ) { Text("Right click") }
                }

                // Special Keys & Presentation Controls strip
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val keys = listOf(
                        "Esc" to PcKeys.ESC,
                        "Tab" to PcKeys.TAB,
                        "Bksp" to PcKeys.BACKSPACE,
                        "Enter" to PcKeys.ENTER,
                        "←" to PcKeys.LEFT,
                        "↑" to PcKeys.UP,
                        "↓" to PcKeys.DOWN,
                        "→" to PcKeys.RIGHT,
                        "Prev Slide" to PcKeys.PAGE_UP,
                        "Next Slide" to PcKeys.PAGE_DOWN,
                        "F5" to PcKeys.F5,
                        "Super" to PcKeys.LEFT_META,
                    )
                    keys.forEach { (label, code) ->
                        FilledTonalButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                sender.sendKey(code)
                            },
                            shape = Ec0Shapes.pill,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Keyboard input bar with optional Live Typing toggle
                KeyboardBar(
                    liveTyping = liveTyping,
                    onToggleLiveTyping = { liveTyping = it },
                    onCommit = { sender.sendCommitText(it) },
                    onBackspace = { sender.sendKey(PcKeys.BACKSPACE) },
                )
            }
        }
    }
}

@Composable
private fun HonestCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@Composable
private fun TouchpadSurface(
    sensitivity: Float,
    onTap: (twoFinger: Boolean) -> Unit,
    onLongPress: () -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .pointerInput(sensitivity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var maxPointers = 1
                    var moved = false
                    var longPressed = false
                    var lastPositions = mapOf(down.id.value to down.position)

                    while (true) {
                        val event = if (!moved && !longPressed) {
                            withTimeoutOrNull(PointerGestures.LONG_PRESS_MS) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            longPressed = true
                            onLongPress()
                            continue
                        }

                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > maxPointers) maxPointers = pressed.size

                        val current = pressed.associate { it.id.value to it.position }
                        if (current.keys == lastPositions.keys) {
                            if (current.size >= 2) {
                                val avgX = current.values.map { it.x }.average().toFloat()
                                val prevAvgX = lastPositions.values.map { it.x }.average().toFloat()
                                val avgY = current.values.map { it.y }.average().toFloat()
                                val prevAvgY = lastPositions.values.map { it.y }.average().toFloat()
                                if (abs(avgY - prevAvgY) > 0.5f || abs(avgX - prevAvgX) > 0.5f) {
                                    moved = true
                                    onScroll(avgX - prevAvgX, avgY - prevAvgY)
                                }
                            } else if (!longPressed) {
                                val pos = current.values.first()
                                val prev = lastPositions.values.first()
                                if (pos != prev) {
                                    moved = true
                                    val (dx, dy) = PointerGestures.scaleDragAccelerated(
                                        pos.x - prev.x,
                                        pos.y - prev.y,
                                        sensitivity = sensitivity,
                                    )
                                    onDrag(dx, dy)
                                }
                            }
                        }
                        lastPositions = current
                        if (event.changes.all { !it.pressed }) break
                    }

                    if (!moved && !longPressed) {
                        onTap(maxPointers >= 2)
                    }
                }
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Touchpad\n\nTap = left · Two-finger tap = right\nLong-press = middle · Two-finger drag = scroll",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeyboardBar(
    liveTyping: Boolean,
    onToggleLiveTyping: (Boolean) -> Unit,
    onCommit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { next ->
                        if (liveTyping) {
                            when {
                                next.length > text.length && next.startsWith(text) -> {
                                    onCommit(next.substring(text.length))
                                }
                                next.length < text.length -> {
                                    repeat(text.length - next.length) { onBackspace() }
                                }
                                next.isNotEmpty() -> onCommit(next)
                            }
                            text = if (next.length > 32) next.takeLast(16) else next
                        } else {
                            text = next
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = Ec0Shapes.pill,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                    ),
                    placeholder = { Text(if (liveTyping) "Live typing → PC…" else "Type → PC keyboard") },
                )
                if (!liveTyping) {
                    Button(
                        onClick = {
                            onCommit(text)
                            text = ""
                        },
                        enabled = text.isNotEmpty(),
                        shape = Ec0Shapes.pill,
                    ) {
                        Text("Send")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Live keystroke mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = liveTyping,
                    onCheckedChange = { enabled ->
                        onToggleLiveTyping(enabled)
                        text = ""
                    },
                )
            }
        }
    }
}
