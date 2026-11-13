/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Universal Control (Screen-Edge Cursor Barrier & Seamless Keyboard/Pointer Handoff)
 * wire models (`category = "input"`, `cmd_type = "cursor_handoff"`).
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object UniversalControlProtocol {
    const val CATEGORY_INPUT: String = "input"
    const val CMD_CURSOR_HANDOFF: String = "cursor_handoff"
}

enum class ScreenEdge(val wireName: String) {
    LEFT("left"),
    RIGHT("right"),
    TOP("top"),
    BOTTOM("bottom");

    fun opposite(): ScreenEdge = when (this) {
        LEFT -> RIGHT
        RIGHT -> LEFT
        TOP -> BOTTOM
        BOTTOM -> TOP
    }

    companion object {
        fun fromWireName(wire: String): ScreenEdge =
            entries.firstOrNull { it.wireName == wire.lowercase() }
                ?: throw IllegalArgumentException("Unknown screen edge: '$wire'")
    }
}

/**
 * Signed `cursor_handoff` command emitted when the user's cursor crosses the
 * boundary between the Linux desktop monitor and a paired Android screen.
 *
 * @property action `"enter"` (cursor enters remote peer) or `"leave"` (cursor returns to local host)
 * @property edge Which edge of the target screen the cursor enters/leaves (`left`, `right`, `top`, `bottom`)
 * @property positionPermille Normalized entry position `0..1000` along that edge (integer-only for `CanonicalJson`)
 */
data class CursorHandoffCommand(
    val action: String,
    val edge: ScreenEdge,
    val positionPermille: Int = 500,
) {
    init {
        require(action == ACTION_ENTER || action == ACTION_LEAVE) {
            "CursorHandoffCommand action must be 'enter' or 'leave' (got '$action')"
        }
        require(positionPermille in 0..1_000) {
            "positionPermille must be in 0..1000 (got $positionPermille)"
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("edge", edge.wireName)
        put("pos_permille", positionPermille)
    }

    companion object {
        const val ACTION_ENTER: String = "enter"
        const val ACTION_LEAVE: String = "leave"

        fun fromJsonObject(obj: JsonObject): CursorHandoffCommand {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in cursor_handoff")
            val edgeWire = (obj["edge"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'edge' in cursor_handoff")
            val pos = (obj["pos_permille"] as? JsonPrimitive)?.intOrNull ?: 500
            return CursorHandoffCommand(
                action = action,
                edge = ScreenEdge.fromWireName(edgeWire),
                positionPermille = pos,
            )
        }
    }
}
