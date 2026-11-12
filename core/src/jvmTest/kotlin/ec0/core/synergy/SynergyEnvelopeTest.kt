/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Envelope codec tests — pin the base signing path to upstream semantics:
 * java.util.Random(timestamp).nextLong() + constant 0x0FFFFFFF signature
 * (WebSocketConnectManager.java:558), plus the T2 catalog's envelope schema.
 */
package ec0.core.synergy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynergyEnvelopeTest {

    private val ts = 1_737_228_658_000L // ms

    @Test
    fun basePathSigningMatchesUpstreamSemantics() {
        val meta = SynergyEnvelopeCodec.baseSignedMeta(ts)
        // Seeded java.util.Random — deterministic and reproducible
        assertEquals(java.util.Random(ts).nextLong(), meta.nonceStr)
        assertEquals(0x0FFFFFFF, meta.signature) // 268435455 — WSCM:558 constant
        assertEquals(ts, meta.timestamp)
    }

    @Test
    fun cmdEnvelopeWireShape() {
        val envelope = SynergyEnvelopeCodec.cmdEnvelope(
            cmdType = "start_mirror",
            cmdBody = kotlinx.serialization.json.buildJsonObject { put("quality", 2) },
            cmdValue = 1,
            category = "cast",
            signed = SynergyEnvelopeCodec.baseSignedMeta(ts),
        )

        val json = Json.parseToJsonElement(SynergyEnvelopeCodec.encode(envelope)).jsonObject

        // Envelope keys — 1:1 with protocol/SynergyMessage.java @SerializedName
        assertEquals("cmd", json["msg_type"]!!.jsonPrimitive.content)
        assertEquals("synergy", json["src_role"]!!.jsonPrimitive.content)
        assertEquals("cast", json["category"]!!.jsonPrimitive.content)
        assertEquals(ts, json["timestamp"]!!.jsonPrimitive.long)
        assertEquals(java.util.Random(ts).nextLong(), json["noncestr"]!!.jsonPrimitive.long)
        assertEquals(268435455L, json["signature"]!!.jsonPrimitive.long)

        // SynergyCmd body schema (T2 catalog): cmd_type/cmd_body/cmd_value/reason
        val body = json["msg_body"]!!.jsonObject
        assertEquals("start_mirror", body["cmd_type"]!!.jsonPrimitive.content)
        assertEquals(2, body["cmd_body"]!!.jsonObject["quality"]!!.jsonPrimitive.int)
        assertEquals(1, body["cmd_value"]!!.jsonPrimitive.int)
        assertEquals("", body["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun infoEnvelopeWireShape() {
        val envelope = SynergyEnvelopeCodec.infoEnvelope(
            infoType = "vdc_phone_init",
            infoValue = 1,
            signed = SynergyEnvelopeCodec.baseSignedMeta(ts),
        )
        val json = Json.parseToJsonElement(SynergyEnvelopeCodec.encode(envelope)).jsonObject
        assertEquals("info", json["msg_type"]!!.jsonPrimitive.content)
        val body = json["msg_body"]!!.jsonObject
        assertEquals("vdc_phone_init", body["info_type"]!!.jsonPrimitive.content)
        assertEquals(1, body["info_value"]!!.jsonPrimitive.int)
    }

    @Test
    fun decodeToleratesUnknownKeysAndCoercion() {
        // Gson-style upstream frame with extra keys (synergy peers may add fields)
        val frame = """
            {"msg_type":"cmd","msg_body":{"cmd_type":"rspksc","cmd_value":0,"reason":"","extra":1},
             "src_role":"synergy","category":"ksc","timestamp":1737228658000,
             "noncestr":123,"signature":268435455,"unknown_field":{"a":1}}
        """.trimIndent()
        val envelope = SynergyEnvelopeCodec.decode(frame)
        assertEquals("cmd", envelope.msgType)
        assertEquals("rspksc", envelope.bodyType)
        assertEquals(268435455L, envelope.signature)
        assertEquals(123L, envelope.nonceStr)
        assertNull(envelope.bodyObject?.get("missing"))
    }

    @Test
    fun roundtripPreservesLongNonceAndSignature() {
        val meta = SynergyEnvelopeCodec.baseSignedMeta(ts)
        val envelope = SynergyEnvelopeCodec.cmdEnvelope("stream_start", signed = meta)
        val decoded = SynergyEnvelopeCodec.decode(SynergyEnvelopeCodec.encode(envelope))
        assertEquals(meta.nonceStr, decoded.nonceStr) // long survives the wire
        assertEquals(meta.signature, decoded.signature)
        assertEquals(meta.timestamp, decoded.timestamp)
        assertEquals("stream_start", decoded.bodyType)
        assertTrue(decoded.bodyObject!!.containsKey("cmd_value"))
    }
}
