/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Bidirectional Clipboard Sync Service (`docs/engine/design.md` §6.1):
 *   - Supports `text/plain`, `image/png`, and `image/jpeg`.
 *   - Inline base64url transfer for payloads <= 64 KiB; chunked binary stream
 *     (`BulkStreamFrame`) for payloads > 64 KiB up to `maxClipboardBytes` (16 MiB).
 *   - SHA-256 loopback echo suppression so applying a remote clipboard item does
 *     not trigger a re-broadcast when the local OS clipboard listener fires.
 *   - Private mode & Android `EXTRA_IS_SENSITIVE` filtering.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardMime
import ec0.core.engine.protocol.ClipboardSyncPayload
import ec0.core.engine.transport.BulkStreamFrame
import ec0.core.engine.transport.Ec0Session
import ec0.core.kdeconnect.Sha256
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ClipboardPushOutcome {
    SENT_INLINE,
    SENT_STREAMED,
    SUPPRESSED_PRIVATE_OR_SENSITIVE,
    SUPPRESSED_LOOPBACK_ECHO,
    SUPPRESSED_DUPLICATE_LOCAL,
    REJECTED_UNSUPPORTED_MIME,
    REJECTED_TOO_LARGE,
}

class ClipboardService(
    @Volatile var privateMode: Boolean = false,
    val maxClipboardBytes: Int = ClipboardSyncPayload.DEFAULT_MAX_CLIPBOARD_BYTES,
    val inlineThresholdBytes: Int = ClipboardSyncPayload.INLINE_THRESHOLD_BYTES,
    val chunkSizeBytes: Int = EngineConstants.DEFAULT_FILE_CHUNK_BYTES,
    private val onRemoteClipboardApplied: (ClipboardItem) -> Unit = {},
) {
    private val streamIdCounter = AtomicInteger(10_000)

    @Volatile
    var lastAppliedRemoteSha256: String? = null
        private set

    @Volatile
    var lastSentLocalSha256: String? = null
        private set

    private data class PendingInboundStream(
        val expectedSha256: String,
        val mime: String,
        val expectedSizeBytes: Int,
        var nextChunkIndex: Int = 0,
        val buffer: ByteArrayOutputStream,
    )

    private val inboundStreams = ConcurrentHashMap<Int, PendingInboundStream>()

    /**
     * Pushes a local clipboard change over an authenticated [Ec0Session].
     */
    fun pushLocalClipboard(
        item: ClipboardItem,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ): ClipboardPushOutcome = pushLocalClipboard(
        item = item,
        sendControl = { payloadJson ->
            session.sendCmd(
                cmdType = ClipboardSyncPayload.CMD_CLIPBOARD_SYNC,
                cmdBody = payloadJson,
                category = ClipboardSyncPayload.CATEGORY_CLIPBOARD,
                timestampMs = timestampMs,
            )
        },
        sendChunk = { chunk -> session.sendBulkChunk(chunk) },
    )

    /**
     * Transport-decoupled clipboard push for direct unit testing and offline outbox routing.
     */
    fun pushLocalClipboard(
        item: ClipboardItem,
        sendControl: (JsonObject) -> Unit,
        sendChunk: (BulkStreamFrame) -> Unit = {},
    ): ClipboardPushOutcome {
        if (privateMode || item.isSensitive) {
            return ClipboardPushOutcome.SUPPRESSED_PRIVATE_OR_SENSITIVE
        }
        if (item.mime !in ClipboardMime.SUPPORTED) {
            return ClipboardPushOutcome.REJECTED_UNSUPPORTED_MIME
        }
        if (item.bytes.isEmpty() || item.bytes.size > maxClipboardBytes) {
            return ClipboardPushOutcome.REJECTED_TOO_LARGE
        }

        val sha = item.sha256Hex.lowercase()
        if (sha == lastAppliedRemoteSha256) {
            // The local OS clipboard watcher just fired for the item we wrote from a remote peer
            return ClipboardPushOutcome.SUPPRESSED_LOOPBACK_ECHO
        }
        if (sha == lastSentLocalSha256) {
            return ClipboardPushOutcome.SUPPRESSED_DUPLICATE_LOCAL
        }

        return if (item.bytes.size <= inlineThresholdBytes) {
            val payload = ClipboardSyncPayload.fromInlineItem(item)
            sendControl(payload.toJsonObject())
            lastSentLocalSha256 = sha
            ClipboardPushOutcome.SENT_INLINE
        } else {
            val streamId = streamIdCounter.incrementAndGet()
            val payload = ClipboardSyncPayload(
                clipSha256 = sha,
                mime = item.mime,
                sizeBytes = item.bytes.size.toLong(),
                inlineB64u = null,
                streamId = streamId,
                sensitive = false,
            )
            sendControl(payload.toJsonObject())

            var offset = 0
            var chunkIndex = 0
            while (offset < item.bytes.size) {
                val end = minOf(offset + chunkSizeBytes, item.bytes.size)
                val slice = item.bytes.copyOfRange(offset, end)
                val isFinal = end == item.bytes.size
                val flags = if (isFinal) BulkStreamFrame.FLAG_FINAL_CHUNK else BulkStreamFrame.FLAG_DATA
                sendChunk(
                    BulkStreamFrame(
                        streamId = streamId,
                        chunkIndex = chunkIndex,
                        flags = flags,
                        sha256 = Sha256.digest(slice),
                        payload = slice,
                    ),
                )
                offset = end
                chunkIndex++
            }
            lastSentLocalSha256 = sha
            ClipboardPushOutcome.SENT_STREAMED
        }
    }

    /**
     * Processes an incoming verified `clipboard_sync` [Ec0Envelope].
     * Returns `true` if handled by this service.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        if (envelope.bodyType != ClipboardSyncPayload.CMD_CLIPBOARD_SYNC) return false
        val inner = envelope.innerBodyObject ?: return false
        val sync = ClipboardSyncPayload.fromJsonObject(inner)

        if (sync.sensitive || privateMode) {
            return true
        }
        if (sync.mime !in ClipboardMime.SUPPORTED) {
            throw IllegalArgumentException("Unsupported clipboard MIME type: ${sync.mime}")
        }
        if (sync.sizeBytes <= 0L || sync.sizeBytes > maxClipboardBytes.toLong()) {
            throw IllegalArgumentException("Incoming clipboard size ${sync.sizeBytes} out of bounds (max $maxClipboardBytes)")
        }

        val inlineBytes = sync.decodeInlineBytes()
        if (inlineBytes != null) {
            if (inlineBytes.size.toLong() != sync.sizeBytes) {
                throw IllegalArgumentException(
                    "Clipboard inline size mismatch: header=${sync.sizeBytes}, actual=${inlineBytes.size}",
                )
            }
            verifyAndApplyRemoteItem(sync.mime, inlineBytes, sync.clipSha256)
            return true
        }

        val streamId = sync.streamId
            ?: throw IllegalArgumentException("Non-inline clipboard_sync missing stream_id")
        inboundStreams[streamId] = PendingInboundStream(
            expectedSha256 = sync.clipSha256,
            mime = sync.mime,
            expectedSizeBytes = sync.sizeBytes.toInt(),
            buffer = ByteArrayOutputStream(sync.sizeBytes.toInt().coerceAtMost(256 * 1024)),
        )
        return true
    }

    /**
     * Processes a binary [BulkStreamFrame] belonging to an in-flight large clipboard transfer.
     * Returns `true` if `frame.streamId` matched an active clipboard stream.
     */
    fun handleBulkChunk(frame: BulkStreamFrame): Boolean {
        val pending = inboundStreams[frame.streamId] ?: return false
        if (frame.flags == BulkStreamFrame.FLAG_STREAM_ABORT) {
            inboundStreams.remove(frame.streamId)
            return true
        }
        if (frame.chunkIndex != pending.nextChunkIndex) {
            inboundStreams.remove(frame.streamId)
            throw IllegalArgumentException(
                "Out-of-order clipboard chunk on stream ${frame.streamId}: expected ${pending.nextChunkIndex}, got ${frame.chunkIndex}",
            )
        }
        val actualChunkSha = Sha256.digest(frame.payload)
        if (!EngineEncoding.constantTimeEquals(actualChunkSha, frame.sha256)) {
            inboundStreams.remove(frame.streamId)
            throw IllegalArgumentException("Clipboard chunk SHA-256 mismatch on stream ${frame.streamId}")
        }
        if (pending.buffer.size() + frame.payload.size > pending.expectedSizeBytes) {
            inboundStreams.remove(frame.streamId)
            throw IllegalArgumentException("Clipboard stream ${frame.streamId} exceeded declared size ${pending.expectedSizeBytes}")
        }

        pending.buffer.write(frame.payload)
        pending.nextChunkIndex++

        val isFinal = (frame.flags and BulkStreamFrame.FLAG_FINAL_CHUNK) != 0
        if (isFinal) {
            inboundStreams.remove(frame.streamId)
            val fullBytes = pending.buffer.toByteArray()
            if (fullBytes.size != pending.expectedSizeBytes) {
                throw IllegalArgumentException(
                    "Clipboard stream ${frame.streamId} final size ${fullBytes.size} != expected ${pending.expectedSizeBytes}",
                )
            }
            verifyAndApplyRemoteItem(pending.mime, fullBytes, pending.expectedSha256)
        }
        return true
    }

    private fun verifyAndApplyRemoteItem(mime: String, bytes: ByteArray, expectedShaHex: String) {
        val actualShaHex = Sha256.digest(bytes).toHex()
        if (!EngineEncoding.constantTimeEquals(
                actualShaHex.encodeToByteArray(),
                expectedShaHex.lowercase().encodeToByteArray(),
            )
        ) {
            throw IllegalArgumentException(
                "Clipboard payload SHA-256 mismatch: expected $expectedShaHex, got $actualShaHex",
            )
        }
        lastAppliedRemoteSha256 = actualShaHex
        val item = ClipboardItem(
            mime = mime,
            bytes = bytes,
            sha256Hex = actualShaHex,
            isSensitive = false,
        )
        onRemoteClipboardApplied(item)
    }
}
