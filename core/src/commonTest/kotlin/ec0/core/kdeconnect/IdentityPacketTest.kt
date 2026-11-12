/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Identity packet roundtrip mirroring upstream NetworkPacketTest.testIdentity
 * semantics (kotlin.test port, no Android deps).
 */
package ec0.core.kdeconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityPacketTest {

    // Upstream testIdentity fixture shape (protocolVersion 12, caps ASDFG/QWERTY)
    private val identity = DeviceIdentity(
        id = "myid",
        name = "myname",
        type = DeviceType.TV,
        protocolVersion = 12,
        incomingCapabilities = setOf("ASDFG"),
        outgoingCapabilities = setOf("QWERTY"),
    )

    @Test
    fun testIdentityRoundtrip() {
        val np = identity.toIdentityPacket()

        assertEquals(12, np.getInt("protocolVersion"))
        assertEquals("kdeconnect.identity", np.type)
        assertEquals("myid", np.getString("deviceId"))
        assertEquals("myname", np.getString("deviceName"))
        assertEquals("tv", np.getString("deviceType"))
        assertEquals(setOf("ASDFG"), np.getStringSet("incomingCapabilities"))
        assertEquals(setOf("QWERTY"), np.getStringSet("outgoingCapabilities"))
        assertNull(np.getIntOrNull("tcpPort"))

        val parsed = DeviceIdentity.fromIdentityPacket(np)

        assertEquals(identity.id, parsed.id)
        assertEquals(identity.name, parsed.name)
        assertEquals(identity.type, parsed.type)
        assertEquals(identity.protocolVersion, parsed.protocolVersion)
        assertEquals(identity.incomingCapabilities, parsed.incomingCapabilities)
        assertEquals(identity.outgoingCapabilities, parsed.outgoingCapabilities)
    }

    @Test
    fun testTcpPortIncludedWhenPresent() {
        val np = identity.copy(tcpPort = 1716).toIdentityPacket()
        assertEquals(1716, np.getInt("tcpPort"))
        assertTrue(Discovery.isValidTcpPort(np.getInt("tcpPort")))
        val parsed = DeviceIdentity.fromIdentityPacket(np)
        assertEquals(1716, parsed.tcpPort)
    }

    @Test
    fun testDeviceTypeWireNames() {
        assertEquals("phone", DeviceType.PHONE.toString())
        assertEquals("tablet", DeviceType.TABLET.toString())
        assertEquals("desktop", DeviceType.DESKTOP.toString())
        assertEquals("laptop", DeviceType.LAPTOP.toString())
        assertEquals("tv", DeviceType.TV.toString())
        assertEquals(DeviceType.PHONE, DeviceType.fromString("phone"))
        assertEquals(DeviceType.DESKTOP, DeviceType.fromString("anything-else"))
    }

    @Test
    fun testDeviceIdValidation() {
        // 32 chars of [a-zA-Z0-9_-] — upstream DEVICE_ID_REGEX ^[a-zA-Z0-9_-]{32,38}$
        val valid = "ee061a75_e403_4ecc_9261_5ffe2722f698" // 36 chars (upstream cert CN shape)
        assertTrue(DeviceIdentity.isValidDeviceId(valid))
        assertFalse(DeviceIdentity.isValidDeviceId("short"))
        assertFalse(DeviceIdentity.isValidDeviceId("has spaces1234567890123456789012"))
        assertFalse(DeviceIdentity.isValidDeviceId("exactly38chars_long______________________________________0".take(39)))
    }

    @Test
    fun testNameSanitization() {
        // Upstream DeviceHelper: strip ["',;:.!?()[]<>], trim, cap 32 chars
        assertEquals("My Phone", DeviceIdentity.filterInvalidCharactersFromDeviceNameAndLimitLength("My, Phone!"))
        assertEquals(32, DeviceIdentity.filterInvalidCharactersFromDeviceNameAndLimitLength("x".repeat(40)).length)
        assertEquals("ab", DeviceIdentity.filterInvalidCharactersFromDeviceNameAndLimitLength("'a:b'"))
    }

    @Test
    fun testIsValidIdentityPacket() {
        assertTrue(DeviceIdentity.isValidIdentityPacket(identity.toIdentityPacket()))

        val np = identity.toIdentityPacket()
        np["deviceName"] = "   "
        assertFalse(DeviceIdentity.isValidIdentityPacket(np))

        val np2 = identity.toIdentityPacket()
        np2["deviceId"] = "invalid-id"
        assertFalse(DeviceIdentity.isValidIdentityPacket(np2))

        val notIdentity = NetworkPacket("com.test")
        assertFalse(DeviceIdentity.isValidIdentityPacket(notIdentity))
    }
}
