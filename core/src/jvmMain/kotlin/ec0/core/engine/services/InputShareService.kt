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
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.transport.Ec0Session
import kotlinx.serialization.json.JsonObject

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
) {
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
     * Handles an incoming verified `input_key` or `input_pointer` [Ec0Envelope].
     * Returns `true` if handled by this service.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            InputProtocol.CMD_INPUT_KEY -> {
                val event = InputKeyEvent.fromJsonObject(body)
                if (enabled) sink.onKeyEvent(event)
                true
            }
            InputProtocol.CMD_INPUT_POINTER -> {
                val event = InputPointerEvent.fromJsonObject(body)
                if (enabled) sink.onPointerEvent(event)
                true
            }
            else -> false
        }
    }
}
