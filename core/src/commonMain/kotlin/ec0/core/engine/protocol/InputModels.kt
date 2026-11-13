/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Wire models for ec0-engine Input Share (`category = "input"`,
 * `cmd_type = "input_key" | "input_pointer"`, docs/engine/design.md §6.3).
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object InputProtocol {
    const val CATEGORY_INPUT: String = "input"
    const val CMD_INPUT_KEY: String = "input_key"
    const val CMD_INPUT_POINTER: String = "input_pointer"

    const val KEY_ACTION_DOWN: String = "down"
    const val KEY_ACTION_UP: String = "up"
    const val KEY_ACTION_COMMIT_TEXT: String = "commit_text"

    const val POINTER_ACTION_MOVE: String = "move"
    const val POINTER_ACTION_DOWN: String = "down"
    const val POINTER_ACTION_UP: String = "up"
    const val POINTER_ACTION_SCROLL: String = "scroll"
    const val POINTER_ACTION_CLICK: String = "click"

    const val MOD_SHIFT: Int = 1 shl 0
    const val MOD_CTRL: Int = 1 shl 1
    const val MOD_ALT: Int = 1 shl 2
    const val MOD_META: Int = 1 shl 3

    const val MAX_COMMIT_TEXT_BYTES: Int = 4096
}

data class InputKeyEvent(
    val action: String,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val text: String? = null,
) {
    init {
        require(
            action == InputProtocol.KEY_ACTION_DOWN ||
                action == InputProtocol.KEY_ACTION_UP ||
                action == InputProtocol.KEY_ACTION_COMMIT_TEXT,
        ) { "Unsupported key action: '$action'" }
        if (text != null) {
            require(text.encodeToByteArray().size <= InputProtocol.MAX_COMMIT_TEXT_BYTES) {
                "InputKeyEvent text exceeds ${InputProtocol.MAX_COMMIT_TEXT_BYTES} UTF-8 bytes"
            }
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("key_code", keyCode)
        put("modifiers", modifiers)
        if (text != null) {
            put("text", text)
        }
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): InputKeyEvent {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in input_key")
            val keyCode = (obj["key_code"] as? JsonPrimitive)?.intOrNull ?: 0
            val modifiers = (obj["modifiers"] as? JsonPrimitive)?.intOrNull ?: 0
            val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return InputKeyEvent(
                action = action,
                keyCode = keyCode,
                modifiers = modifiers,
                text = text,
            )
        }
    }
}

data class InputPointerEvent(
    val action: String,
    val dx: Int = 0,
    val dy: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val buttonsMask: Int = 0,
) {
    init {
        require(
            action == InputProtocol.POINTER_ACTION_MOVE ||
                action == InputProtocol.POINTER_ACTION_DOWN ||
                action == InputProtocol.POINTER_ACTION_UP ||
                action == InputProtocol.POINTER_ACTION_SCROLL ||
                action == InputProtocol.POINTER_ACTION_CLICK,
        ) { "Unsupported pointer action: '$action'" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("buttons", buttonsMask)
        put("dx", dx)
        put("dy", dy)
        put("scroll_x", scrollX)
        put("scroll_y", scrollY)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): InputPointerEvent {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in input_pointer")
            val buttons = (obj["buttons"] as? JsonPrimitive)?.intOrNull ?: 0
            val dx = (obj["dx"] as? JsonPrimitive)?.intOrNull ?: 0
            val dy = (obj["dy"] as? JsonPrimitive)?.intOrNull ?: 0
            val scrollX = (obj["scroll_x"] as? JsonPrimitive)?.intOrNull ?: 0
            val scrollY = (obj["scroll_y"] as? JsonPrimitive)?.intOrNull ?: 0
            return InputPointerEvent(
                action = action,
                dx = dx,
                dy = dy,
                scrollX = scrollX,
                scrollY = scrollY,
                buttonsMask = buttons,
            )
        }
    }
}
