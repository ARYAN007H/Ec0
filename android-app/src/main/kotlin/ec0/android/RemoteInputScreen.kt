/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature A1 (plan §P2): phone = touchpad/keyboard for the PC. The engine's
 * input share wire (`category="input"`, `input_pointer` / `input_key`) already
 * exists; this screen is the sender side:
 *   - touchpad surface: drag = pointer MOVE deltas (sensitivity-scaled),
 *     two-finger drag = SCROLL, tap = CLICK left, two-finger tap = CLICK right,
 *     long-press = CLICK middle,
 *   - keyboard bar: commit_text stream (InputKeyEvent COMMIT_TEXT),
 *   - volume rocker: PC Volume Up / Down / Mute as key events.
 *
 * Latency contract (plan §P2): pointer deltas are batched ≤8 ms and coalesced —
 * one MOVE per flush, clicks/scrolls ride in the same burst. Every burst is a
 * signed Ec0Envelope inside the AEAD session (sendCmd → Ed25519 + ChaCha20-Poly1305
 * per flush — the desktop receiver's replay window and rate limiter see the
 * coalesced stream, so burst cost is one envelope per ≤8 ms).
 *
 * Pure logic ([PointerEventBatcher], [PointerGestures]) is same-commit
 * unit-tested without android.jar.
 */

package ec0.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

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
 *
 * The flush cadence is the caller's tick (≤8 ms per the plan's latency budget).
 */
class PointerEventBatcher {
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingScrollX = 0
    private var pendingScrollY = 0
    private val queued = ArrayDeque<InputPointerEvent>()

    // Gestures call add* on the UI thread while the flush tick drains on the
    // injected IO dispatcher — every access guards against that race.
    /** Accumulate touchpad drag deltas. */
    @Synchronized
    fun addMove(dx: Int, dy: Int) {
        pendingDx += dx
        pendingDy += dy
    }

    /** Accumulate two-finger scroll deltas. */
    @Synchronized
    fun addScroll(dx: Int, dy: Int) {
        pendingScrollX += dx
        pendingScrollY += dy
    }

    /** Queue a button click (the receiver turns one CLICK into down+up). */
    @Synchronized
    fun addClick(buttonsMask: Int) {
        queued.addLast(
            InputPointerEvent(action = InputProtocol.POINTER_ACTION_CLICK, buttonsMask = buttonsMask),
        )
    }

    /**
     * Drain the pending burst: the coalesced MOVE (if any), then queued
     * clicks/scrolls. Called from the ≤8 ms flush tick; empty result = no-op.
     */
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
 * Pure gesture → wire-event interpretation for the touchpad surface
 * (same-commit tested): tap=LEFT, two-finger tap=RIGHT, long-press=MIDDLE,
 * drag=scaled MOVE, two-finger vertical drag=SCROLL.
 */
object PointerGestures {
    const val TOUCHPAD_SENSITIVITY_DEFAULT: Float = 1.6f
    const val LONG_PRESS_MS: Long = 350

    fun tapClick(twoFinger: Boolean): Int =
        if (twoFinger) PointerButtons.RIGHT else PointerButtons.LEFT

    fun longPressClick(): Int = PointerButtons.MIDDLE

    /** Raw drag distance → wire delta, sensitivity-scaled and clamped. */
    fun scaleDrag(dx: Float, dy: Float, sensitivity: Float = TOUCHPAD_SENSITIVITY_DEFAULT): Pair<Int, Int> {
        val clamped = sensitivity.coerceIn(0.5f, 4f)
        return (dx * clamped).toInt().coerceIn(-500, 500) to (dy * clamped).toInt().coerceIn(-500, 500)
    }

    /** Two-finger vertical drag → scroll ticks (fingers down = content follows). */
    fun scaleScroll(dy: Float): Int = (dy / 24f).toInt().coerceIn(-30, 30)
}

/**
 * Sends input frames to the paired desktop over the live engine session.
 * Owns the ≤8 ms flush tick: the batcher accumulates while gestures run, the
 * ticker drains and sends through the node's input share service.
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

    /**
     * Test seam (the relayDispatcherOverride precedent): thread name of the
     * last send dispatch. The NetworkOnMainThreadException regression is
     * Android-StrictMode-only, so JVM tests pin the contract through this.
     */
    @Volatile
    internal var lastSendThreadName: String? = null
        private set

    /** Peers that refuse the input capability get an honest no-send. */
    var remoteInputAllowed: Boolean = true

    private fun session() = peerDeviceId()?.let { node.activeSessionFor(it) }

    private fun startFlushTick() {
        if (flushJob?.isActive == true) return
        // Sends are blocking socket writes — they MUST run off the Android main
        // thread or StrictMode kills the process (NetworkOnMainThreadException,
        // the exact crash the notification relay hit before its daemon executor).
        // The tick therefore runs on [ioDispatcher]; the batcher synchronizes
        // add* (UI thread) against flush (here).
        flushJob = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(flushIntervalMs)
                val session = session() ?: continue
                for (event in batcher.flush()) {
                    // A session closing mid-churn must not kill the tick.
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

    /** Keyboard bar: commit the text as one event (≤4 KB wire limit). */
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

    /** Volume rocker: PC volume keys (desktop maps to XF86Audio* / uinput). */
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

/** PC volume/mute keycodes (the desktop receiver maps them to media/volume). */
object PcKeys {
    const val VOLUME_UP: Int = 115
    const val VOLUME_DOWN: Int = 114
    const val VOLUME_MUTE: Int = 113
}

/**
 * The remote input screen. Gated on a live engine session + the negotiated
 * INPUT/REMOTE_INPUT capability; otherwise it honestly explains what's missing.
 */
@Composable
fun RemoteInputScreen(
    node: Ec0EngineNode,
    connectedPeerId: String?,
    peerCapabilities: Set<EngineCapability>,
    modifier: Modifier = Modifier,
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // Keyed on the peer id too: remember(node) alone would freeze the FIRST
    // composition's peer lambda forever, so a sender built before the session
    // resolved stayed a silent no-op even after CONNECTED.
    val sender = remember(node, connectedPeerId) {
        RemoteInputSender(node, scope, peerDeviceId = { connectedPeerId })
    }
    val sessionLive = connectedPeerId != null
    val negotiated = EngineCapability.INPUT in peerCapabilities ||
        EngineCapability.REMOTE_INPUT in peerCapabilities

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Remote input", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (!sessionLive) {
            HonestCard("No live session — pair and connect to a desktop first.")
        } else if (!negotiated) {
            HonestCard(
                "The desktop has not negotiated the remote-input capability yet " +
                    "(uinput receiver not running). This screen unlocks the moment " +
                    "the desktop advertises `input` in its beacon.",
            )
        } else {
            TouchpadSurface(
                onTap = { twoFinger -> sender.sendClick(PointerGestures.tapClick(twoFinger)) },
                onLongPress = { sender.sendClick(PointerGestures.longPressClick()) },
                onDrag = { dx, dy -> sender.sendPointerMove(dx, dy) },
                onScroll = { dy -> sender.sendScroll(0, PointerGestures.scaleScroll(dy)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            KeyboardBar(onCommit = { sender.sendCommitText(it) })

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { sender.sendKey(PcKeys.VOLUME_DOWN) },
                    modifier = Modifier.weight(1f),
                ) { Text("Vol −") }
                OutlinedButton(
                    onClick = { sender.sendKey(PcKeys.VOLUME_MUTE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Mute") }
                OutlinedButton(
                    onClick = { sender.sendKey(PcKeys.VOLUME_UP) },
                    modifier = Modifier.weight(1f),
                ) { Text("Vol +") }
            }
            Text(
                "Deltas batch ≤8 ms and coalesce — the AEAD session authenticates the burst.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HonestCard(text: String, modifier: Modifier = Modifier) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * The touchpad surface — a single awaitEachGesture interpreter (one lane, no
 * cross-lane consume conflicts): stable-pointer frames emit drags/scroll, a
 * 350 ms still window emits long-press, release emits tap (finger count decides
 * left/right).
 */
@Composable
private fun TouchpadSurface(
    onTap: (twoFinger: Boolean) -> Unit,
    onLongPress: () -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var maxPointers = 1
                    var moved = false
                    var longPressed = false
                    var lastPositions = mapOf(down.id.value to down.position)

                    while (true) {
                        // Still-window long-press: only before any movement.
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
                                val avgY = current.values.map { it.y }.average().toFloat()
                                val prevAvg = lastPositions.values.map { it.y }.average().toFloat()
                                if (abs(avgY - prevAvg) > 0.5f) {
                                    moved = true
                                    onScroll(avgY - prevAvg)
                                }
                            } else if (!longPressed) {
                                val pos = current.values.first()
                                val prev = lastPositions.values.first()
                                if (pos != prev) {
                                    moved = true
                                    val (dx, dy) = PointerGestures.scaleDrag(pos.x - prev.x, pos.y - prev.y)
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
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "touchpad\n\ntap = left · two-finger tap = right\nlong-press = middle · two-finger drag = scroll",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeyboardBar(onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                ),
                placeholder = { Text("type → PC keyboard") },
            )
            Button(
                onClick = {
                    onCommit(text)
                    text = ""
                },
                enabled = text.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Send")
            }
        }
    }
}
