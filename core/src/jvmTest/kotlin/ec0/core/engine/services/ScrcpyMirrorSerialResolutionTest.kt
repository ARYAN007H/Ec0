/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The Start-mirror button must target the phone's LIVE adb serial: wireless-debugging
 * serials are ip:port and change on every re-pairing — a hardcoded host:5555 dial
 * could never survive real usage (observed: button dead on the A03 over wireless adb).
 */
package ec0.core.engine.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrcpyMirrorSerialResolutionTest {

    private val adbOut = """
        List of devices attached
        10.115.38.1:43877	device product:a03nnxx model:SM_A035F device:a03 transport_id:1
        R9ZT202LPRT	device usb:1-1 product:a03nnxx model:SM_A035F device:a03
    """.trimIndent()

    @Test
    fun matchesByNormalizedModelAgainstEngineAlias() {
        // Engine alias "SM-A035F" (dashes) vs adb model "SM_A035F" (underscores)
        assertEquals("10.115.38.1:43877", resolveMirrorSerial(adbOut, "SM-A035F", null))
    }

    @Test
    fun fallsBackToIpPrefixWhenModelDiffers() {
        val otherModel = adbOut.replace("model:SM_A035F", "model:XYZ_123")
        assertEquals("10.115.38.1:43877", resolveMirrorSerial(otherModel, "SM-A035F", "10.115.38.1"))
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(resolveMirrorSerial("List of devices attached", "SM-A035F", "10.115.38.1"))
        assertNull(resolveMirrorSerial("", "SM-A035F", null))
    }

    @Test
    fun offlineDeviceLinesAreIgnored() {
        val withOffline = adbOut + "\ndeadserial\toffline product:x model:SM_A035F device:y"
        assertEquals("10.115.38.1:43877", resolveMirrorSerial(withOffline, "SM-A035F", null))
    }
}
