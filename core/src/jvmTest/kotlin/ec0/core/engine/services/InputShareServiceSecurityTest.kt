/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Walled-garden input-receive tests (audit H1): event flooding is rate-limited,
 * peers without an advertised input capability are denied, legitimate batched
 * streams pass, and out-of-range key codes are clamped at the injection bridge.
 */
package ec0.core.engine.services

import ec0.core.engine.EngineCapability
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.linux.LinuxInputBackend
import ec0.core.engine.linux.LinuxInputInjectionBridge
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.PresenceProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputShareServiceSecurityTest {

    private fun envelope(cmdType: String, body: kotlinx.serialization.json.JsonObject) =
        ec0.core.engine.Ec0Envelope(
            msgType = "cmd",
            msgBody = buildJsonObject {
                put("cmd_type", cmdType)
                put("cmd_body", body)
            },
            srcRole = "phone",
            category = InputProtocol.CATEGORY_INPUT,
            timestamp = 0L,
            seq = 1L,
            nonce = 0L,
            sig = "test",
        )

    private fun keyEventEnvelope() = envelope(
        InputProtocol.CMD_INPUT_KEY,
        InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = 30).toJsonObject(),
    )

    @Test
    fun legitimateBatchedStreamPassesUnthrottled() {
        var injected = 0
        val receiver = InputShareService(
            sink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    injected++
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
        )
        // 125 events/s for 2 s = 250 events — under the 600 burst, far below any cap.
        repeat(250) { i ->
            assertTrue(
                receiver.handleControlEnvelope("phone-a", keyEventEnvelope()),
                "event $i must be handled",
            )
        }
        assertEquals(250, injected)
        assertEquals(0L, receiver.rateLimitedDrops)
    }

    @Test
    fun eventFloodingIsRateLimited() {
        var injected = 0
        val receiver = InputShareService(
            sink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    injected++
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
            rateLimiter = TokenBucketRateLimiter(
                capacityPerPeer = 10,
                refillPerSecond = 1,
                clockMs = { 0L },
            ),
        )
        repeat(50) {
            receiver.handleControlEnvelope("phone-a", keyEventEnvelope())
        }
        assertEquals(10, injected, "only the burst size reaches the sink")
        assertEquals(40L, receiver.rateLimitedDrops, "the flood tail is dropped honestly")
    }

    @Test
    fun peersAreRateLimitedIndependently() {
        var injected = 0
        val receiver = InputShareService(
            sink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    injected++
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
            rateLimiter = TokenBucketRateLimiter(
                capacityPerPeer = 1,
                refillPerSecond = 1,
                clockMs = { 0L },
            ),
        )
        assertTrue(receiver.handleControlEnvelope("phone-a", keyEventEnvelope()))
        assertTrue(receiver.handleControlEnvelope("phone-b", keyEventEnvelope()))
        assertEquals(2, injected, "each peer gets its own bucket")
        assertEquals(0L, receiver.rateLimitedDrops)
    }

    @Test
    fun capabilityGateDeniesPeersWithoutInputCapability() {
        var injected = 0
        val receiver = InputShareService(
            sink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    injected++
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
            capabilityGate = { peerId ->
                when (peerId) {
                    // Verified beacon present WITHOUT an input capability.
                    "clips-only-phone" -> false
                    // Verified beacon advertising REMOTE_INPUT.
                    "granted-phone" -> true
                    // No beacon received yet — first-use before first beacon.
                    else -> true
                }
            },
        )
        assertTrue(receiver.handleControlEnvelope("clips-only-phone", keyEventEnvelope()))
        assertEquals(0, injected, "peer without input capability is denied")
        assertEquals(1L, receiver.capabilityDenied)

        assertTrue(receiver.handleControlEnvelope("granted-phone", keyEventEnvelope()))
        assertEquals(1, injected, "peer advertising remote_input is injected")

        assertTrue(receiver.handleControlEnvelope("unknown-phone", keyEventEnvelope()))
        assertEquals(2, injected, "no beacon yet — legacy behavior preserved")
    }

    @Test
    fun engineNodeGateReadsVerifiedBeaconCapabilities() {
        // End-to-end through Ec0EngineNode: a peer whose verified beacon lacks
        // input caps is denied; after advertising REMOTE_INPUT it is injected.
        val injected = mutableListOf<InputKeyEvent>()
        val desktopNode = ec0.core.engine.Ec0EngineNode(
            localIdentity = Ed25519Identity.generateKeyPair(),
            displayName = "audit-desktop",
            role = ec0.core.engine.EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            pinStore = ec0.core.engine.crypto.InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            inputEventSink = object : InputEventSink {
                override fun onKeyEvent(event: InputKeyEvent) {
                    injected.add(event)
                }

                override fun onPointerEvent(event: InputPointerEvent) = Unit
            },
        )
        val phoneId = Ed25519Identity.generateKeyPair().deviceId

        fun beacon(capabilities: Set<EngineCapability>) = ec0.core.engine.Ec0Envelope(
            msgType = "info",
            msgBody = buildJsonObject {
                put("info_type", PresenceProtocol.INFO_PRESENCE_BEACON)
                put(
                    "info_body",
                    PresenceBeaconInfo(
                        batteryPct = 80,
                        isCharging = false,
                        screenOn = true,
                        capabilities = capabilities,
                    ).toJsonObject(),
                )
            },
            srcRole = "phone",
            category = PresenceProtocol.CATEGORY_PRESENCE,
            timestamp = 0L,
            seq = 1L,
            nonce = 0L,
            sig = "test",
        )

        fun keyEnvelope() = ec0.core.engine.Ec0Envelope(
            msgType = "cmd",
            msgBody = buildJsonObject {
                put("cmd_type", InputProtocol.CMD_INPUT_KEY)
                put(
                    "cmd_body",
                    InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = 30).toJsonObject(),
                )
            },
            srcRole = "phone",
            category = InputProtocol.CATEGORY_INPUT,
            timestamp = 0L,
            seq = 1L,
            nonce = 0L,
            sig = "test",
        )

        // Verified beacon WITHOUT input capability → denied.
        assertTrue(desktopNode.presenceService.handleControlEnvelope(phoneId, beacon(setOf(EngineCapability.CLIPBOARD))))
        assertTrue(desktopNode.inputShareService.handleControlEnvelope(phoneId, keyEnvelope()))
        assertEquals(0, injected.size, "no-input-capability beacon denies injection")
        assertEquals(1L, desktopNode.inputShareService.capabilityDenied)

        // Verified beacon advertising REMOTE_INPUT → allowed.
        assertTrue(
            desktopNode.presenceService.handleControlEnvelope(phoneId, beacon(setOf(EngineCapability.REMOTE_INPUT))),
        )
        assertTrue(desktopNode.inputShareService.handleControlEnvelope(phoneId, keyEnvelope()))
        assertEquals(1, injected.size, "remote_input beacon admits injection")
        assertEquals(1L, desktopNode.inputShareService.capabilityDenied)
        assertEquals(0L, desktopNode.inputShareService.rateLimitedDrops)
    }
}

/** Bridge-level clamp tests (audit L2). */
class LinuxInputInjectionBridgeClampTest {

    @Test
    fun keyCodeClampedToEvdevDomain() {
        val bridge = LinuxInputInjectionBridge()
        val huge = InputKeyEvent(action = InputProtocol.KEY_ACTION_DOWN, keyCode = Int.MAX_VALUE)
        assertEquals(
            listOf("ydotool", "key", "${LinuxInputInjectionBridge.MAX_KEY_CODE}:1"),
            bridge.buildKeyCommand(LinuxInputBackend.YDOTOOL_WAYLAND, huge),
        )
        val negative = InputKeyEvent(action = InputProtocol.KEY_ACTION_UP, keyCode = -99)
        assertEquals(
            listOf("xdotool", "keyup", "0"),
            bridge.buildKeyCommand(LinuxInputBackend.XDOTOOL_X11, negative),
        )
    }

    @Test
    fun pointerDeltasAndScrollClamped() {
        val bridge = LinuxInputInjectionBridge()
        val hugeMove = InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = 2_000_000, dy = -2_000_000)
        assertEquals(
            listOf(
                "ydotool", "mousemove", "--",
                LinuxInputInjectionBridge.MAX_AXIS_DELTA.toString(),
                LinuxInputInjectionBridge.MIN_AXIS_DELTA.toString(),
            ),
            bridge.buildPointerCommand(LinuxInputBackend.YDOTOOL_WAYLAND, hugeMove),
        )
        val hugeScroll = InputPointerEvent(action = InputProtocol.POINTER_ACTION_SCROLL, scrollY = -99_999)
        // negative scrollY clamps in-range and maps to wheel button 5 (scroll up)
        assertEquals(
            listOf("xdotool", "click", "5"),
            bridge.buildPointerCommand(LinuxInputBackend.XDOTOOL_X11, hugeScroll),
        )
    }

    @Test
    fun normalEventsUnchanged() {
        val bridge = LinuxInputInjectionBridge()
        assertEquals(
            listOf("ydotool", "mousemove", "--", "-18", "42"),
            bridge.buildPointerCommand(
                LinuxInputBackend.YDOTOOL_WAYLAND,
                InputPointerEvent(action = InputProtocol.POINTER_ACTION_MOVE, dx = -18, dy = 42),
            ),
        )
    }
}
