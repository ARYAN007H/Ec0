/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Wire models for ec0-engine Clipboard Sync (`category = "clipboard"`,
 * `cmd_type = "clipboard_sync"`, docs/engine/design.md §6.1).
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.kdeconnect.Sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object ClipboardMime {
    const val TEXT_PLAIN: String = "text/plain"
    const val IMAGE_PNG: String = "image/png"
    const val IMAGE_JPEG: String = "image/jpeg"

    val SUPPORTED: Set<String> = setOf(TEXT_PLAIN, IMAGE_PNG, IMAGE_JPEG)
}

data class ClipboardItem(
    val mime: String,
    val bytes: ByteArray,
    val sha256Hex: String = Sha256.digest(bytes).toHex(),
    val isSensitive: Boolean = false,
) {
    fun asUtf8Text(): String = bytes.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardItem) return false
        return mime == other.mime &&
            sha256Hex == other.sha256Hex &&
            isSensitive == other.isSensitive &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = sha256Hex.hashCode()

    companion object {
        fun text(text: String, isSensitive: Boolean = false): ClipboardItem {
            val raw = text.encodeToByteArray()
            return ClipboardItem(
                mime = ClipboardMime.TEXT_PLAIN,
                bytes = raw,
                sha256Hex = Sha256.digest(raw).toHex(),
                isSensitive = isSensitive,
            )
        }
    }
}

data class ClipboardSyncPayload(
    val clipSha256: String,
    val mime: String,
    val sizeBytes: Long,
    val inlineB64u: String? = null,
    val streamId: Int? = null,
    val sensitive: Boolean = false,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("clip_sha256", clipSha256)
        put("mime", mime)
        put("size_bytes", sizeBytes)
        if (inlineB64u != null) {
            put("inline_b64u", inlineB64u)
        }
        if (streamId != null) {
            put("stream_id", streamId)
        }
        if (sensitive) {
            put("sensitive", true)
        }
    }

    fun decodeInlineBytes(): ByteArray? = inlineB64u?.decodeBase64Url()

    companion object {
        const val CATEGORY_CLIPBOARD: String = "clipboard"
        const val CMD_CLIPBOARD_SYNC: String = "clipboard_sync"
        const val INLINE_THRESHOLD_BYTES: Int = 64 * 1024 // 64 KiB
        const val DEFAULT_MAX_CLIPBOARD_BYTES: Int = 16 * 1024 * 1024 // 16 MiB

        fun fromInlineItem(item: ClipboardItem): ClipboardSyncPayload = ClipboardSyncPayload(
            clipSha256 = item.sha256Hex,
            mime = item.mime,
            sizeBytes = item.bytes.size.toLong(),
            inlineB64u = item.bytes.toBase64UrlUnpadded(),
            streamId = null,
            sensitive = item.isSensitive,
        )

        fun fromJsonObject(obj: JsonObject): ClipboardSyncPayload {
            val sha = (obj["clip_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'clip_sha256' in clipboard_sync")
            val mime = (obj["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'mime' in clipboard_sync")
            val sizeBytes = (obj["size_bytes"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Missing or non-integer 'size_bytes' in clipboard_sync")
            val inlineB64u = (obj["inline_b64u"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val streamId = (obj["stream_id"] as? JsonPrimitive)?.intOrNull
            val sensitive = (obj["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false

            return ClipboardSyncPayload(
                clipSha256 = sha.lowercase(),
                mime = mime,
                sizeBytes = sizeBytes,
                inlineB64u = inlineB64u,
                streamId = streamId,
                sensitive = sensitive,
            )
        }
    }
}
