/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Deterministic Canonical JSON serializer (RFC 8785 JCS subset) for Ec0Envelope
 * signing preimages (docs/engine/design.md §4.2).
 *
 * Guarantees byte-identical output across JVM, Android, Python, and Rust:
 *  1. Null top-level msg_body encodes as "{}" (2 bytes).
 *  2. Zero whitespace outside string literals.
 *  3. Recursive lexicographical key sort by UTF-16 code unit order (String.compareTo);
 *     JsonNull object property values are omitted prior to sorting.
 *  4. Strict integer-only numbers: parsed as signed 64-bit Long and formatted via
 *     Long.toString() (base-10 ASCII, no '+', no leading zeros, no '.', no 'e'/'E',
 *     "-0" normalized to "0"). Any floating-point or out-of-range numeric literal
 *     throws IllegalArgumentException fail-closed.
 *  5. Minimal RFC 8785 string escaping: \" \\ \b \t \n \f \r and lowercase \u00xx
 *     for U+0000..U+001F; all characters >= U+0020 emitted as literal UTF-8.
 */
package ec0.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CanonicalJson {

    private const val LOWER_HEX = "0123456789abcdef"
    private val CANONICAL_INT_REGEX = Regex("^-?(0|[1-9][0-9]*)$")

    /**
     * Encodes [element] as a canonical top-level `msg_body` string per `docs/engine/design.md` §4.2.
     * If [element] is `null` or [JsonNull], returns `"{}"`.
     */
    fun encode(element: JsonElement?): String {
        if (element == null || element is JsonNull) return "{}"
        val sb = StringBuilder(128)
        appendElement(sb, element)
        return sb.toString()
    }

    private fun appendElement(sb: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonObject -> appendObject(sb, element)
            is JsonArray -> appendArray(sb, element)
            is JsonPrimitive -> appendPrimitive(sb, element)
        }
    }

    private fun appendObject(sb: StringBuilder, obj: JsonObject) {
        sb.append('{')
        // Omit JsonNull entries first, then sort keys by UTF-16 code unit order
        val sortedKeys = obj.entries
            .filter { (_, v) -> v !is JsonNull }
            .map { it.key }
            .sortedWith { a, b -> a.compareTo(b) }

        for (i in sortedKeys.indices) {
            if (i > 0) sb.append(',')
            val key = sortedKeys[i]
            val value = obj.getValue(key)
            appendString(sb, key)
            sb.append(':')
            appendElement(sb, value)
        }
        sb.append('}')
    }

    private fun appendArray(sb: StringBuilder, arr: JsonArray) {
        sb.append('[')
        for (i in arr.indices) {
            if (i > 0) sb.append(',')
            appendElement(sb, arr[i])
        }
        sb.append(']')
    }

    private fun appendPrimitive(sb: StringBuilder, prim: JsonPrimitive) {
        if (prim.isString) {
            appendString(sb, prim.content)
            return
        }
        when (val raw = prim.content) {
            "true", "false" -> sb.append(raw)
            "-0" -> sb.append('0')
            else -> {
                if (!CANONICAL_INT_REGEX.matches(raw)) {
                    throw IllegalArgumentException(
                        "Non-integer or non-canonical number '$raw' prohibited in canonical Ec0Envelope",
                    )
                }
                val longVal = raw.toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "Numeric literal '$raw' overflows signed 64-bit Long in canonical Ec0Envelope",
                    )
                sb.append(longVal.toString())
            }
        }
    }

    internal fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (i in value.indices) {
            when (val c = value[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                else -> {
                    val code = c.code
                    if (code in 0x00..0x1f) {
                        sb.append("\\u00")
                        sb.append(LOWER_HEX[(code ushr 4) and 0x0f])
                        sb.append(LOWER_HEX[code and 0x0f])
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }
}
