/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Linux Input Injection Bridge (`ec0.core.engine.linux`):
 *   - Implements `InputEventSink` so an Android phone can act as a low-latency
 *     wireless trackpad, scroll wheel, and keyboard for the Linux desktop.
 *   - Supports Wayland (`ydotool` via `/dev/uinput`) and X11 (`xdotool`), with
 *     strict discrete argv execution (never `/bin/sh -c`).
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.services.InputEventSink

enum class LinuxInputBackend {
    YDOTOOL_WAYLAND,
    XDOTOOL_X11,
    NOOP_HEADLESS,
}

class LinuxInputInjectionBridge(
    private val executor: BinaryCommandExecutor = SystemBinaryCommandExecutor,
    private val backendProvider: () -> LinuxInputBackend = { detectDefaultBackend() },
) : InputEventSink {

    override fun onKeyEvent(event: InputKeyEvent) {
        val backend = backendProvider()
        val cmd = buildKeyCommand(backend, event) ?: return
        runCatching { executor.execute(cmd, timeoutMs = 1_000L) }
    }

    override fun onPointerEvent(event: InputPointerEvent) {
        val backend = backendProvider()
        val cmd = buildPointerCommand(backend, event) ?: return
        runCatching { executor.execute(cmd, timeoutMs = 1_000L) }
    }

    /**
     * Builds the discrete argv command for a pointer event (`move`, `down`, `up`, `scroll`, `click`)
     * targeting either `ydotool` (Wayland/uinput) or `xdotool` (X11).
     */
    fun buildPointerCommand(backend: LinuxInputBackend, event: InputPointerEvent): List<String>? =
        when (backend) {
            LinuxInputBackend.YDOTOOL_WAYLAND -> when (event.action) {
                InputProtocol.POINTER_ACTION_MOVE ->
                    listOf("ydotool", "mousemove", "--", event.dx.toString(), event.dy.toString())
                InputProtocol.POINTER_ACTION_DOWN ->
                    listOf("ydotool", "click", mapYdotoolButtonDown(event.buttonsMask))
                InputProtocol.POINTER_ACTION_UP ->
                    listOf("ydotool", "click", mapYdotoolButtonUp(event.buttonsMask))
                InputProtocol.POINTER_ACTION_CLICK ->
                    listOf("ydotool", "click", mapYdotoolButtonClick(event.buttonsMask))
                InputProtocol.POINTER_ACTION_SCROLL ->
                    listOf("ydotool", "mousemove", "--wheel", "--", event.scrollX.toString(), event.scrollY.toString())
                else -> null
            }
            LinuxInputBackend.XDOTOOL_X11 -> when (event.action) {
                InputProtocol.POINTER_ACTION_MOVE ->
                    listOf("xdotool", "mousemove_relative", "--", event.dx.toString(), event.dy.toString())
                InputProtocol.POINTER_ACTION_DOWN ->
                    listOf("xdotool", "mousedown", mapXdotoolButton(event.buttonsMask))
                InputProtocol.POINTER_ACTION_UP ->
                    listOf("xdotool", "mouseup", mapXdotoolButton(event.buttonsMask))
                InputProtocol.POINTER_ACTION_CLICK ->
                    listOf("xdotool", "click", mapXdotoolButton(event.buttonsMask))
                InputProtocol.POINTER_ACTION_SCROLL -> {
                    if (event.scrollY != 0) {
                        val wheelBtn = if (event.scrollY > 0) "4" else "5"
                        listOf("xdotool", "click", wheelBtn)
                    } else {
                        null
                    }
                }
                else -> null
            }
            LinuxInputBackend.NOOP_HEADLESS -> null
        }

    /**
     * Builds the discrete argv command for a keyboard event (`down`, `up`, `commit_text`)
     * targeting either `ydotool` or `xdotool`.
     */
    fun buildKeyCommand(backend: LinuxInputBackend, event: InputKeyEvent): List<String>? =
        when (backend) {
            LinuxInputBackend.YDOTOOL_WAYLAND -> when (event.action) {
                InputProtocol.KEY_ACTION_COMMIT_TEXT -> {
                    val text = event.text?.takeIf { it.isNotEmpty() } ?: return null
                    listOf("ydotool", "type", "--", text)
                }
                InputProtocol.KEY_ACTION_DOWN ->
                    listOf("ydotool", "key", "${event.keyCode}:1")
                InputProtocol.KEY_ACTION_UP ->
                    listOf("ydotool", "key", "${event.keyCode}:0")
                else -> null
            }
            LinuxInputBackend.XDOTOOL_X11 -> when (event.action) {
                InputProtocol.KEY_ACTION_COMMIT_TEXT -> {
                    val text = event.text?.takeIf { it.isNotEmpty() } ?: return null
                    listOf("xdotool", "type", "--clearmodifiers", "--", text)
                }
                InputProtocol.KEY_ACTION_DOWN ->
                    listOf("xdotool", "keydown", event.keyCode.toString())
                InputProtocol.KEY_ACTION_UP ->
                    listOf("xdotool", "keyup", event.keyCode.toString())
                else -> null
            }
            LinuxInputBackend.NOOP_HEADLESS -> null
        }

    private fun mapYdotoolButtonDown(buttonsMask: Int): String = when (buttonsMask) {
        2 -> "0x41" // Right button down
        3 -> "0x42" // Middle button down
        else -> "0x40" // Left button down
    }

    private fun mapYdotoolButtonUp(buttonsMask: Int): String = when (buttonsMask) {
        2 -> "0x81" // Right button up
        3 -> "0x82" // Middle button up
        else -> "0x80" // Left button up
    }

    private fun mapYdotoolButtonClick(buttonsMask: Int): String = when (buttonsMask) {
        2 -> "0xC1" // Right click
        3 -> "0xC2" // Middle click
        else -> "0xC0" // Left click
    }

    private fun mapXdotoolButton(buttonsMask: Int): String = when (buttonsMask) {
        2 -> "3" // X11 button 3 is right click
        3 -> "2" // X11 button 2 is middle click
        else -> "1"
    }

    companion object {
        fun detectDefaultBackend(
            probe: LinuxEnvironmentProbe = LinuxEnvironmentProbe(),
        ): LinuxInputBackend = when (probe.detectDisplayServer()) {
            LinuxDisplayServer.WAYLAND -> LinuxInputBackend.YDOTOOL_WAYLAND
            LinuxDisplayServer.X11 -> LinuxInputBackend.XDOTOOL_X11
            LinuxDisplayServer.HEADLESS -> LinuxInputBackend.NOOP_HEADLESS
        }
    }
}
