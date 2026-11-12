/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from kdeconnect-android NetworkPacketTest.kt (upstream SPDX:
 * GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL) to kotlin.test,
 * mirroring the upstream fixtures verbatim so roundtrip behavior stays 1:1.
 */
package ec0.core.kdeconnect

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkPacketTest {

    @Test
    fun testNetworkPacket() {
        val np = NetworkPacket("com.test")

        np["hello"] = "hola"
        assertEquals("hola", np.getString("hello", "bye"))

        np["hello"] = ""
        assertEquals("", np.getString("hello", "bye"))

        assertEquals("bye", np.getString("hi", "bye"))

        np["foo"] = "bar"
        val serialized = np.serialize()
        val np2 = NetworkPacket.unserialize(serialized)

        assertEquals(np.type, np2.type)
        assertEquals("bar", np2.getString("foo"))
        assertEquals(np.getString("hello"), np2.getString("hello"))

        val json = "{\"type\":\"test\",\"body\":{\"testing\":true}}"
        val np3 = NetworkPacket.unserialize(json)
        assertTrue(np3.getBoolean("testing"))
        assertFalse(np3.getBoolean("not_testing"))
        assertTrue(np3.getBoolean("not_testing", true))
    }

    @Test
    fun testSerializePayload() {
        val np = NetworkPacket("com.test")
        np["path"] = "/a/b"
        np.payload = NetworkPacket.Payload(42)
        np.payloadTransferInfo = buildJsonObject { put("port", 1739) }

        val serialized = np.serialize()
        assertTrue(serialized.endsWith("\n"))
        assertTrue(serialized.contains("\"path\":\"/a/b\""))
        assertTrue(serialized.contains("\"payloadSize\":42"))
        assertTrue(serialized.contains("\"port\":1739"))

        val np2 = NetworkPacket.unserialize(serialized)
        assertEquals("com.test", np2.type)
        assertEquals("/a/b", np2.getString("path"))
        assertEquals(42L, np2.payloadSize)
        assertEquals(np.payloadTransferInfo, np2.payloadTransferInfo)
        assertEquals(1739, np2.payloadTransferInfo["port"]!!.toString().toInt())
        assertTrue(np2.hasPayload())
        assertTrue(np2.hasPayloadTransferInfo())

        val noPayload = NetworkPacket("com.test").serialize()
        assertFalse(noPayload.contains("payloadSize"))
        assertFalse(noPayload.contains("payloadTransferInfo"))
    }

    @Test
    fun testTypeCoercion() {
        // Upstream fixture: unknown top-level key ignored, string coercion of ints/bools
        val np = NetworkPacket.unserialize("{\"id\":1,\"type\":\"test\",\"body\":{\"int\":8,\"str\":\"12\",\"bool\":\"true\",\"nil\":null},\"extra\":0}")
        assertEquals("8", np.getString("int"))
        assertEquals(12, np.getInt("str"))
        assertEquals(12L, np.getLong("str"))
        assertTrue(np.getBoolean("bool"))
        assertNull(np.getStringOrNull("nil"))
        assertEquals(-1, np.getInt("missing"))
        assertFalse(np.hasPayload())
        assertFalse(np.hasPayloadTransferInfo())
    }

    @Test
    fun testUnserializeInvalidMissingType() {
        // Upstream: unserialize("{\"body\":{}}") throws SerializationException
        assertFailsWith<SerializationException> {
            NetworkPacket.unserialize("{\"body\":{}}")
        }
    }

    @Test
    fun testUnserializePayloadSizeWithoutTransferInfo() {
        assertFailsWith<SerializationException> {
            NetworkPacket.unserialize("{\"id\":1,\"type\":\"test\",\"body\":{},\"payloadSize\":42}")
        }
    }

    @Test
    fun testEnvelopeIdPreservedAndOmittedFromBody() {
        val np = NetworkPacket.unserialize("{\"id\":1234,\"type\":\"test\",\"body\":{}}")
        assertEquals(1234L, np.envelopeId)
        assertEquals(-1L, np.getLong("id")) // envelope id is not a body key (upstream semantics)
    }

    @Test
    fun testIdProviderDrivesEnvelopeId() {
        val original = NetworkPacket.idProvider
        try {
            NetworkPacket.idProvider = { 777L }
            val serialized = NetworkPacket("com.test").serialize()
            assertTrue(serialized.startsWith("{\"id\":777,"))
        } finally {
            NetworkPacket.idProvider = original
        }
    }

    @Test
    fun testBodyCollectionsRoundtrip() {
        val np = NetworkPacket("com.test")
        np["caps"] = setOf("ASDFG", "QWERTY")
        np["list"] = listOf("a", "b")
        np["obj"] = buildJsonObject { put("x", 1) }
        np["d"] = 1.5

        val np2 = NetworkPacket.unserialize(np.serialize())
        assertEquals(setOf("ASDFG", "QWERTY"), np2.getStringSet("caps"))
        assertEquals(listOf("a", "b"), np2.getStringList("list"))
        assertEquals(1, np2.getJsonObject("obj")?.get("x")?.toString()?.toInt())
        assertEquals(1.5, np2.getDouble("d"))
        assertEquals(Double.NaN, np2.getDouble("missing"))
    }
}
