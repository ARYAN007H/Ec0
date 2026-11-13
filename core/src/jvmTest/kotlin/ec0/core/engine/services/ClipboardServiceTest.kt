/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.services

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardMime
import ec0.core.engine.protocol.ClipboardSyncPayload
import ec0.core.engine.transport.BulkStreamFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClipboardServiceTest {

    private val desktopKey = Ed25519Identity.generateKeyPair()
    private val phoneKey = Ed25519Identity.generateKeyPair()

    @Test
    fun bidirectionalInlineTextSyncAndLoopbackEchoSuppression() {
        val phoneReceived = mutableListOf<ClipboardItem>()
        val desktopReceived = mutableListOf<ClipboardItem>()

        val desktopClip = ClipboardService(
            onRemoteClipboardApplied = { desktopReceived.add(it) },
        )
        val phoneClip = ClipboardService(
            onRemoteClipboardApplied = { phoneReceived.add(it) },
        )

        val desktopProcessor = SessionEnvelopeProcessor(
            localIdentity = desktopKey,
            peerDeviceId = phoneKey.deviceId,
            peerPublicKeyRaw32 = phoneKey.publicKeyRaw,
        )

        val textItem = ClipboardItem.text("https://github.com/ARYAN007H/Ec0")
        val outboundControl = mutableListOf<JsonObject>()

        val outcome1 = desktopClip.pushLocalClipboard(
            item = textItem,
            sendControl = { outboundControl.add(it) },
        )
        assertEquals(ClipboardPushOutcome.SENT_INLINE, outcome1)
        assertEquals(1, outboundControl.size)

        // Consecutive duplicate copy on Desktop is suppressed
        val dupOutcome = desktopClip.pushLocalClipboard(
            item = textItem,
            sendControl = { outboundControl.add(it) },
        )
        assertEquals(ClipboardPushOutcome.SUPPRESSED_DUPLICATE_LOCAL, dupOutcome)

        // Deliver envelope to Phone
        val env = desktopProcessor.nextCmdEnvelope(
            cmdType = ClipboardSyncPayload.CMD_CLIPBOARD_SYNC,
            cmdBody = outboundControl[0],
            category = ClipboardSyncPayload.CATEGORY_CLIPBOARD,
        )
        assertTrue(phoneClip.handleControlEnvelope(env))
        assertEquals(1, phoneReceived.size)
        assertEquals("https://github.com/ARYAN007H/Ec0", phoneReceived[0].asUtf8Text())

        // When Phone's local OS clipboard listener fires with the newly applied text,
        // SHA-256 loopback deduplication suppresses echoing it back to Desktop!
        val echoOutcome = phoneClip.pushLocalClipboard(
            item = phoneReceived[0],
            sendControl = { error("Should not send loopback echo") },
        )
        assertEquals(ClipboardPushOutcome.SUPPRESSED_LOOPBACK_ECHO, echoOutcome)
    }

    @Test
    fun largeImagePayloadStreamsOverMultiChunkBinaryChannelWithSha256Verification() {
        val phoneReceived = mutableListOf<ClipboardItem>()
        val desktopClip = ClipboardService(
            inlineThresholdBytes = 64 * 1024,
            chunkSizeBytes = 32 * 1024,
        )
        val phoneClip = ClipboardService(
            inlineThresholdBytes = 64 * 1024,
            chunkSizeBytes = 32 * 1024,
            onRemoteClipboardApplied = { phoneReceived.add(it) },
        )
        val desktopProcessor = SessionEnvelopeProcessor(
            localIdentity = desktopKey,
            peerDeviceId = phoneKey.deviceId,
            peerPublicKeyRaw32 = phoneKey.publicKeyRaw,
        )

        // 100 KiB synthetic PNG (> 64 KiB inline threshold -> 4 chunks of 32 KiB)
        val pngBytes = ByteArray(100 * 1024) { (it * 17 + 3).toByte() }
        val pngItem = ClipboardItem(mime = ClipboardMime.IMAGE_PNG, bytes = pngBytes)

        val controlFrames = mutableListOf<JsonObject>()
        val binaryChunks = mutableListOf<BulkStreamFrame>()

        val outcome = desktopClip.pushLocalClipboard(
            item = pngItem,
            sendControl = { controlFrames.add(it) },
            sendChunk = { binaryChunks.add(it) },
        )
        assertEquals(ClipboardPushOutcome.SENT_STREAMED, outcome)
        assertEquals(1, controlFrames.size)
        assertEquals(4, binaryChunks.size)

        val env = desktopProcessor.nextCmdEnvelope(
            cmdType = ClipboardSyncPayload.CMD_CLIPBOARD_SYNC,
            cmdBody = controlFrames[0],
            category = ClipboardSyncPayload.CATEGORY_CLIPBOARD,
        )
        assertTrue(phoneClip.handleControlEnvelope(env))
        assertEquals(0, phoneReceived.size)

        for (chunk in binaryChunks) {
            assertTrue(phoneClip.handleBulkChunk(chunk))
        }
        assertEquals(1, phoneReceived.size)
        assertEquals(ClipboardMime.IMAGE_PNG, phoneReceived[0].mime)
        assertContentEquals(pngBytes, phoneReceived[0].bytes)
    }

    @Test
    fun privateModeSensitiveFlagOversizeAndTamperedHashAreEnforced() {
        val clip = ClipboardService(
            privateMode = false,
            maxClipboardBytes = 1024,
        )

        // 1. Sensitive item (Android EXTRA_IS_SENSITIVE password manager copy) suppressed
        val sensitiveItem = ClipboardItem.text("hunter2-secret-password", isSensitive = true)
        assertEquals(
            ClipboardPushOutcome.SUPPRESSED_PRIVATE_OR_SENSITIVE,
            clip.pushLocalClipboard(sensitiveItem, sendControl = {}),
        )

        // 2. Private mode enabled suppresses all outbound clipboard items
        clip.privateMode = true
        assertEquals(
            ClipboardPushOutcome.SUPPRESSED_PRIVATE_OR_SENSITIVE,
            clip.pushLocalClipboard(ClipboardItem.text("normal text"), sendControl = {}),
        )
        clip.privateMode = false

        // 3. Oversize payload (> 1024B cap) rejected
        val hugeItem = ClipboardItem(mime = ClipboardMime.IMAGE_JPEG, bytes = ByteArray(2048))
        assertEquals(
            ClipboardPushOutcome.REJECTED_TOO_LARGE,
            clip.pushLocalClipboard(hugeItem, sendControl = {}),
        )

        // 4. Tampered SHA-256 on incoming envelope throws IllegalArgumentException
        val processor = SessionEnvelopeProcessor(
            localIdentity = desktopKey,
            peerDeviceId = phoneKey.deviceId,
            peerPublicKeyRaw32 = phoneKey.publicKeyRaw,
        )
        val tamperedEnv = processor.nextCmdEnvelope(
            cmdType = ClipboardSyncPayload.CMD_CLIPBOARD_SYNC,
            cmdBody = buildJsonObject {
                put("clip_sha256", "0000000000000000000000000000000000000000000000000000000000000000")
                put("mime", "text/plain")
                put("size_bytes", 5)
                put("inline_b64u", "aGVsbG8") // "hello"
            },
            category = ClipboardSyncPayload.CATEGORY_CLIPBOARD,
        )
        assertFailsWith<IllegalArgumentException> {
            clip.handleControlEnvelope(tamperedEnv)
        }
    }
}
