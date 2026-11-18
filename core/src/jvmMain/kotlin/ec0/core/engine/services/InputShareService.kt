/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Bidirectional Input Share Service (`docs/engine/design.md` §6.3):
 *   - Streams keyboard key events (`down`, `up`, `commit_text`) and pointer/touchpad
 *     delta/scroll/button events (`move`, `down`, `up`, `scroll`, `click`).
 *   - Pluggable [InputEventSink] allows Desktop (X11/Wayland/uinput/Robot) and
 *     Android (AccessibilityService / IME / HID) to inject events natively.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.transport.Ec0Session
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

interface InputEventSink {
    fun onKeyEvent(event: InputKeyEvent)
    fun onPointerEvent(event: InputPointerEvent)
}

class InputShareService(
    @Volatile var enabled: Boolean = true,
    private val sink: InputEventSink = object : InputEventSink {
        override fun onKeyEvent(event: InputKeyEvent) = Unit
        override fun onPointerEvent(event: InputPointerEvent) = Unit
    },
    /**
     * Walled-garden scoping (H1): per-peer token bucket over injected events.
     * Real senders batch ≤8 ms (~125 events/s per surface); the default cap
     * admits a sustained 240 events/s with a 600-event burst — legitimate use
     * never hits it, a flooded peer is dropped honestly.
     */
    private val rateLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(
        capacityPerPeer = DEFAULT_EVENT_BURST,
        refillPerSecond = DEFAULT_EVENTS_PER_SECOND,
    ),
    /**
     * Receive-side capability gate: when the sending peer's *verified* presence
     * beacon is known and does not advertise an input capability, injection is
     * denied. Peers with no beacon yet pass (first-use before the first
     * wake-triggered beacon must keep working — the phone only broadcasts on
     * `onDeviceWakeOrNetworkValidated`).
     */
    private val capabilityGate: (peerDeviceId: String) -> Boolean = { true },
) {
    companion object {
        const val DEFAULT_EVENTS_PER_SECOND: Long = 240
        const val DEFAULT_EVENT_BURST: Long = 600
    }

    /** Honest telemetry: dropped events by cause. */
    val rateLimitedDrops: Long get() = rateLimitedDropCount.get()
    val capabilityDenied: Long get() = capabilityDenyCount.get()

    private val rateLimitedDropCount = AtomicLong(0L)
    private val capabilityDenyCount = AtomicLong(0L)

    fun sendKeyEvent(
        event: InputKeyEvent,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) = sendKeyEvent(event) { body ->
        session.sendCmd(
            cmdType = InputProtocol.CMD_INPUT_KEY,
            cmdBody = body,
            category = InputProtocol.CATEGORY_INPUT,
            timestampMs = timestampMs,
        )
    }

    fun sendKeyEvent(
        event: InputKeyEvent,
        sendControl: (JsonObject) -> Unit,
    ): Boolean {
        if (!enabled) return false
        sendControl(event.toJsonObject())
        return true
    }

    fun sendPointerEvent(
        event: InputPointerEvent,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) = sendPointerEvent(event) { body ->
        session.sendCmd(
            cmdType = InputProtocol.CMD_INPUT_POINTER,
            cmdBody = body,
            category = InputProtocol.CATEGORY_INPUT,
            timestampMs = timestampMs,
        )
    }

    fun sendPointerEvent(
        event: InputPointerEvent,
        sendControl: (JsonObject) -> Unit,
    ): Boolean {
        if (!enabled) return false
        sendControl(event.toJsonObject())
        return true
    }

    /**
     * Handles an incoming verified `input_key` or `input_pointer` [Ec0Envelope]
     * (legacy single-node overload: no capability gating, shared limiter key).
     * Returns `true` if handled by this service.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean =
        handleControlEnvelope(peerDeviceId = null, envelope = envelope)

    /**
     * Handles an incoming verified `input_key`/`input_pointer` [Ec0Envelope]
     * from [peerDeviceId] with rate limiting + capability scoping applied.
     * Returns `true` if handled by this service (dropped events still count as
     * handled — the service owns the input category).
     */
    fun handleControlEnvelope(peerDeviceId: String?, envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            InputProtocol.CMD_INPUT_KEY, InputProtocol.CMD_INPUT_POINTER -> {
                if (!enabled) return true
                if (peerDeviceId != null && !capabilityGate(peerDeviceId)) {
                    capabilityDenyCount.incrementAndGet()
                    return true
                }
                if (!rateLimiter.tryAcquire(peerDeviceId ?: "")) {
                    rateLimitedDropCount.incrementAndGet()
                    return true
                }
                if (envelope.bodyType == InputProtocol.CMD_INPUT_KEY) {
                    sink.onKeyEvent(InputKeyEvent.fromJsonObject(body))
                } else {
                    sink.onPointerEvent(InputPointerEvent.fromJsonObject(body))
                }
                true
            }
            else -> false
        }
    }
}
