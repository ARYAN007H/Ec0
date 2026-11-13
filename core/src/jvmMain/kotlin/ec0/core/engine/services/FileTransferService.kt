/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Chunked, Resumable File & Folder Transfer Service (`docs/engine/design.md` §6.2):
 *   - 256 KiB default chunk size with per-chunk SHA-256 + full-file SHA-256.
 *   - Persistent `.ec0part` + `.ec0part.meta` resume manifest on disk so interrupted
 *     transfers resume from the exact last verified chunk (`resume_from_chunk`).
 *   - Deterministic mid-stream cancellation (`cancelSignal` checked between every chunk)
 *     with `FINISHED_STATES` guard so `CANCELLED` never flips to `COMPLETED`.
 *   - Defense-in-depth path traversal validation (`sanitizeRelativePath` + canonical root check).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.protocol.FileAcceptCommand
import ec0.core.engine.protocol.FileCancelCommand
import ec0.core.engine.protocol.FileCompleteInfo
import ec0.core.engine.protocol.FileEntryManifest
import ec0.core.engine.protocol.FileOfferCommand
import ec0.core.engine.protocol.FileTransferProtocol
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.transport.BulkStreamFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TransferProgressSnapshot(
    val transferId: String,
    val isOutgoing: Boolean,
    val state: FileTransferState,
    val totalBytes: Long,
    val transferredBytes: Long,
    val totalFiles: Int,
    val completedFiles: Int,
    val failureReason: String? = null,
)

class FileTransferService(
    private val downloadDir: File,
    val defaultChunkSize: Int = EngineConstants.DEFAULT_FILE_CHUNK_BYTES,
    private val autoAcceptIncomingOffers: Boolean = true,
) {
    interface OutboundTransport {
        fun sendCmd(cmdType: String, cmdBody: JsonObject)
        fun sendInfo(infoType: String, infoBody: JsonObject)
        fun sendChunk(frame: BulkStreamFrame)
    }

    private data class OutgoingTransferContext(
        val offer: FileOfferCommand,
        val sourceFilesById: Map<String, File>,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private data class IncomingFileState(
        val transferId: String,
        val manifest: FileEntryManifest,
        val chunkSize: Int,
        val finalFile: File,
        val partFile: File,
        val metaFile: File,
        var nextChunkIndex: Int,
        var completed: Boolean = false,
    )

    private data class IncomingTransferContext(
        val offer: FileOfferCommand,
        val filesById: Map<String, IncomingFileState>,
        val filesByStreamId: Map<Int, IncomingFileState>,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val stateLock = ReentrantLock()
    private val streamIdCounter = AtomicInteger(50_000)
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingTransferContext>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransferContext>()
    private val streamIdToTransferId = ConcurrentHashMap<Int, String>()

    private val _transfers = MutableStateFlow<Map<String, TransferProgressSnapshot>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferProgressSnapshot>> = _transfers.asStateFlow()

    init {
        downloadDir.mkdirs()
    }

    /**
     * Recursively collects all regular files in [directory] (or a single file)
     * and sends a `file_offer` command.
     */
    fun createOfferFromDirectoryOrFile(
        rootFileOrDir: File,
        transferId: String = UUID.randomUUID().toString(),
        chunkSize: Int = defaultChunkSize,
    ): FileOfferCommand {
        require(rootFileOrDir.exists()) { "Source path does not exist: $rootFileOrDir" }
        val pairs = mutableListOf<Pair<File, String>>()
        if (rootFileOrDir.isFile) {
            pairs.add(rootFileOrDir to rootFileOrDir.name)
        } else {
            val baseDir = rootFileOrDir.parentFile ?: rootFileOrDir
            rootFileOrDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.invariantSeparatorsPath }
                .forEach { file ->
                    val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                    pairs.add(file to FileTransferProtocol.sanitizeRelativePath(rel))
                }
        }
        return registerOutgoingOffer(
            filesWithRelativePaths = pairs,
            transferId = transferId,
            chunkSize = chunkSize,
        )
    }

    /**
     * Builds and registers an outgoing [FileOfferCommand] for [filesWithRelativePaths].
     */
    fun registerOutgoingOffer(
        filesWithRelativePaths: List<Pair<File, String>>,
        transferId: String = UUID.randomUUID().toString(),
        chunkSize: Int = defaultChunkSize,
    ): FileOfferCommand {
        require(filesWithRelativePaths.isNotEmpty()) { "Cannot create empty file offer" }
        val manifests = mutableListOf<FileEntryManifest>()
        val sourceMap = linkedMapOf<String, File>()

        filesWithRelativePaths.forEachIndexed { idx, (file, rawRelPath) ->
            require(file.isFile) { "Not a regular file: $file" }
            val cleanRelPath = FileTransferProtocol.sanitizeRelativePath(rawRelPath)
            val fileId = "f-$idx-${Sha256FileHasher.sha256Hex(cleanRelPath.encodeToByteArray()).take(12)}"
            val streamId = streamIdCounter.incrementAndGet()
            val shaHex = Sha256FileHasher.sha256Hex(file)
            val entry = FileEntryManifest(
                fileId = fileId,
                relativePath = cleanRelPath,
                sizeBytes = file.length(),
                sha256 = shaHex,
                lastModifiedMs = file.lastModified(),
                streamId = streamId,
            )
            manifests.add(entry)
            sourceMap[fileId] = file
        }

        val offer = FileOfferCommand(
            transferId = transferId,
            chunkSize = chunkSize,
            files = manifests,
        )
        outgoingTransfers[transferId] = OutgoingTransferContext(
            offer = offer,
            sourceFilesById = sourceMap,
        )
        updateSnapshot(
            TransferProgressSnapshot(
                transferId = transferId,
                isOutgoing = true,
                state = FileTransferState.OFFERED,
                totalBytes = offer.totalBytes,
                transferredBytes = 0L,
                totalFiles = offer.files.size,
                completedFiles = 0,
            ),
        )
        return offer
    }

    /**
     * Sends the registered `file_offer` over [transport].
     */
    fun sendOffer(offer: FileOfferCommand, transport: OutboundTransport) {
        transport.sendCmd(FileTransferProtocol.CMD_FILE_OFFER, offer.toJsonObject())
    }

    /**
     * Cancels an active outgoing or incoming transfer immediately.
     * Enforces `FINISHED_STATES` so a completed transfer cannot be cancelled,
     * and a cancelled transfer can never later flip to `COMPLETED`.
     */
    fun cancelTransfer(
        transferId: String,
        reason: String = "cancelled_by_user",
        transport: OutboundTransport? = null,
    ): Boolean {
        val transitioned = stateLock.withLock {
            val current = _transfers.value[transferId] ?: return@withLock false
            if (current.state.isTerminal) return@withLock false
            outgoingTransfers[transferId]?.cancelled?.set(true)
            incomingTransfers[transferId]?.cancelled?.set(true)
            updateSnapshotLocked(
                current.copy(
                    state = FileTransferState.CANCELLED,
                    failureReason = reason,
                ),
            )
            true
        }
        if (transitioned && transport != null) {
            val cancelCmd = FileCancelCommand(transferId = transferId, reason = reason)
            transport.sendCmd(FileTransferProtocol.CMD_FILE_CANCEL, cancelCmd.toJsonObject())
        }
        return transitioned
    }

    /**
     * Handles an incoming control envelope (`file_offer`, `file_accept`, `file_cancel`, `file_complete`).
     * Returns `true` if handled by this service.
     */
    fun handleControlEnvelope(
        envelope: Ec0Envelope,
        transport: OutboundTransport,
        maxChunksToSendForTest: Int = Int.MAX_VALUE,
        onChunkSentHook: (transferId: String, chunksSentSoFar: Int) -> Unit = { _, _ -> },
    ): Boolean {
        val type = envelope.bodyType ?: return false
        val body = envelope.innerBodyObject ?: return false

        return when (type) {
            FileTransferProtocol.CMD_FILE_OFFER -> {
                val offer = FileOfferCommand.fromJsonObject(body)
                handleIncomingOffer(offer, transport)
                true
            }
            FileTransferProtocol.CMD_FILE_ACCEPT -> {
                val accept = FileAcceptCommand.fromJsonObject(body)
                handleIncomingAccept(accept, transport, maxChunksToSendForTest, onChunkSentHook)
                true
            }
            FileTransferProtocol.CMD_FILE_CANCEL -> {
                val cancel = FileCancelCommand.fromJsonObject(body)
                handleIncomingCancel(cancel)
                true
            }
            FileTransferProtocol.INFO_FILE_COMPLETE -> {
                val complete = FileCompleteInfo.fromJsonObject(body)
                handleIncomingFileComplete(complete)
                true
            }
            else -> false
        }
    }

    private fun handleIncomingOffer(offer: FileOfferCommand, transport: OutboundTransport) {
        val filesById = linkedMapOf<String, IncomingFileState>()
        val filesByStreamId = linkedMapOf<Int, IncomingFileState>()
        val resumeMap = linkedMapOf<String, Int>()
        var alreadyReceivedBytes = 0L

        for (manifest in offer.files) {
            val finalFile = resolveSafeTargetFile(manifest.relativePath)
            finalFile.parentFile?.mkdirs()
            val partFile = File(finalFile.parentFile, "${finalFile.name}.ec0part")
            val metaFile = File(finalFile.parentFile, "${finalFile.name}.ec0part.meta")

            val resumeChunk = inspectResumePosition(
                transferId = offer.transferId,
                manifest = manifest,
                chunkSize = offer.chunkSize,
                partFile = partFile,
                metaFile = metaFile,
            )
            resumeMap[manifest.fileId] = resumeChunk
            alreadyReceivedBytes += minOf(manifest.sizeBytes, resumeChunk.toLong() * offer.chunkSize.toLong())

            val fileState = IncomingFileState(
                transferId = offer.transferId,
                manifest = manifest,
                chunkSize = offer.chunkSize,
                finalFile = finalFile,
                partFile = partFile,
                metaFile = metaFile,
                nextChunkIndex = resumeChunk,
            )
            filesById[manifest.fileId] = fileState
            filesByStreamId[manifest.streamId] = fileState
            streamIdToTransferId[manifest.streamId] = offer.transferId
        }

        incomingTransfers[offer.transferId] = IncomingTransferContext(
            offer = offer,
            filesById = filesById,
            filesByStreamId = filesByStreamId,
        )

        updateSnapshot(
            TransferProgressSnapshot(
                transferId = offer.transferId,
                isOutgoing = false,
                state = if (autoAcceptIncomingOffers) FileTransferState.TRANSFERRING else FileTransferState.OFFERED,
                totalBytes = offer.totalBytes,
                transferredBytes = alreadyReceivedBytes,
                totalFiles = offer.files.size,
                completedFiles = 0,
            ),
        )

        if (autoAcceptIncomingOffers) {
            val acceptCmd = FileAcceptCommand(
                transferId = offer.transferId,
                accepted = true,
                resumeFromChunk = resumeMap,
            )
            transport.sendCmd(FileTransferProtocol.CMD_FILE_ACCEPT, acceptCmd.toJsonObject())
        }
    }

    /**
     * Streams chunks for an accepted outgoing transfer, starting each file from
     * `accept.resumeFromChunk[fileId] ?: 0` and polling `cancelled` before every chunk.
     */
    private fun handleIncomingAccept(
        accept: FileAcceptCommand,
        transport: OutboundTransport,
        maxChunksToSendForTest: Int,
        onChunkSentHook: (transferId: String, chunksSentSoFar: Int) -> Unit,
    ) {
        val ctx = outgoingTransfers[accept.transferId] ?: return
        if (!accept.accepted) {
            stateLock.withLock {
                val cur = _transfers.value[accept.transferId] ?: return
                if (!cur.state.isTerminal) {
                    updateSnapshotLocked(
                        cur.copy(
                            state = FileTransferState.FAILED,
                            failureReason = accept.reason.ifBlank { "declined_by_peer" },
                        ),
                    )
                }
            }
            return
        }

        val Offer = ctx.offer
        var bytesTransferred = 0L
        for (manifest in Offer.files) {
            val startChunk = (accept.resumeFromChunk[manifest.fileId] ?: 0).coerceAtLeast(0)
            bytesTransferred += minOf(manifest.sizeBytes, startChunk.toLong() * Offer.chunkSize.toLong())
        }

        stateLock.withLock {
            val cur = _transfers.value[accept.transferId] ?: return
            if (cur.state.isTerminal) return
            updateSnapshotLocked(
                cur.copy(
                    state = FileTransferState.TRANSFERRING,
                    transferredBytes = bytesTransferred,
                ),
            )
        }

        var chunksSentInSession = 0
        var filesCompleted = 0

        for (manifest in Offer.files) {
            if (ctx.cancelled.get() || isTerminalState(accept.transferId)) return
            val srcFile = ctx.sourceFilesById[manifest.fileId] ?: continue
            val totalChunks = manifest.totalChunks(Offer.chunkSize)
            val startChunk = (accept.resumeFromChunk[manifest.fileId] ?: 0).coerceIn(0, totalChunks)

            if (manifest.sizeBytes == 0L) {
                if (chunksSentInSession >= maxChunksToSendForTest) return
                transport.sendChunk(
                    BulkStreamFrame(
                        streamId = manifest.streamId,
                        chunkIndex = 0,
                        flags = BulkStreamFrame.FLAG_FINAL_CHUNK,
                        sha256 = Sha256FileHasher.sha256Bytes(ByteArray(0)),
                        payload = ByteArray(0),
                    ),
                )
                chunksSentInSession++
                filesCompleted++
                onChunkSentHook(accept.transferId, chunksSentInSession)
                continue
            }

            RandomAccessFile(srcFile, "r").use { raf ->
                for (chunkIdx in startChunk until totalChunks) {
                    if (ctx.cancelled.get() || isTerminalState(accept.transferId)) {
                        transport.sendChunk(
                            BulkStreamFrame(
                                streamId = manifest.streamId,
                                chunkIndex = chunkIdx,
                                flags = BulkStreamFrame.FLAG_STREAM_ABORT,
                                sha256 = ByteArray(32),
                                payload = ByteArray(0),
                            ),
                        )
                        return
                    }
                    if (chunksSentInSession >= maxChunksToSendForTest) {
                        return // Simulated connection drop for resume testing
                    }

                    val offset = chunkIdx.toLong() * Offer.chunkSize.toLong()
                    val remaining = (manifest.sizeBytes - offset).toInt()
                    val readLen = minOf(Offer.chunkSize, remaining)
                    val buf = ByteArray(readLen)
                    raf.seek(offset)
                    raf.readFully(buf)

                    val isFinal = chunkIdx == totalChunks - 1
                    val flags = if (isFinal) BulkStreamFrame.FLAG_FINAL_CHUNK else BulkStreamFrame.FLAG_DATA
                    transport.sendChunk(
                        BulkStreamFrame(
                            streamId = manifest.streamId,
                            chunkIndex = chunkIdx,
                            flags = flags,
                            sha256 = Sha256FileHasher.sha256Bytes(buf),
                            payload = buf,
                        ),
                    )
                    bytesTransferred += buf.size.toLong()
                    chunksSentInSession++
                    if (isFinal) filesCompleted++

                    stateLock.withLock {
                        val cur = _transfers.value[accept.transferId]
                        if (cur != null && !cur.state.isTerminal) {
                            updateSnapshotLocked(
                                cur.copy(
                                    transferredBytes = bytesTransferred,
                                    completedFiles = filesCompleted,
                                ),
                            )
                        }
                    }

                    onChunkSentHook(accept.transferId, chunksSentInSession)
                }
            }
        }
    }

    /**
     * Processes an incoming binary [BulkStreamFrame] for an active file transfer.
     * Verifies per-chunk SHA-256, writes at exact offset in `.ec0part`, updates `.ec0part.meta`,
     * and on `FLAG_FINAL_CHUNK` verifies the full-file SHA-256 before atomic rename to final destination.
     */
    fun handleBulkChunk(frame: BulkStreamFrame, transport: OutboundTransport): Boolean {
        val transferId = streamIdToTransferId[frame.streamId] ?: return false
        val ctx = incomingTransfers[transferId] ?: return false
        if (ctx.cancelled.get() || isTerminalState(transferId)) {
            return true
        }

        val fileState = ctx.filesByStreamId[frame.streamId] ?: return false
        if (frame.flags == BulkStreamFrame.FLAG_STREAM_ABORT) {
            cancelTransfer(transferId, reason = "peer_stream_abort", transport = null)
            return true
        }

        if (frame.chunkIndex != fileState.nextChunkIndex) {
            failTransfer(
                transferId,
                "Out-of-order file chunk for ${fileState.manifest.relativePath}: expected ${fileState.nextChunkIndex}, got ${frame.chunkIndex}",
            )
            return true
        }

        val actualChunkSha = Sha256FileHasher.sha256Bytes(frame.payload)
        if (!EngineEncoding.constantTimeEquals(actualChunkSha, frame.sha256)) {
            failTransfer(
                transferId,
                "Per-chunk SHA-256 mismatch on ${fileState.manifest.relativePath} chunk ${frame.chunkIndex}",
            )
            return true
        }

        val offset = frame.chunkIndex.toLong() * fileState.chunkSize.toLong()
        if (offset + frame.payload.size.toLong() > fileState.manifest.sizeBytes) {
            failTransfer(
                transferId,
                "Chunk ${frame.chunkIndex} exceeds declared file size ${fileState.manifest.sizeBytes}",
            )
            return true
        }

        RandomAccessFile(fileState.partFile, "rw").use { raf ->
            raf.seek(offset)
            if (frame.payload.isNotEmpty()) {
                raf.write(frame.payload)
            }
        }

        fileState.nextChunkIndex = frame.chunkIndex + 1
        writeResumeMeta(
            metaFile = fileState.metaFile,
            transferId = transferId,
            manifest = fileState.manifest,
            chunkSize = fileState.chunkSize,
            nextContiguousChunk = fileState.nextChunkIndex,
        )

        val isFinal = (frame.flags and BulkStreamFrame.FLAG_FINAL_CHUNK) != 0
        if (isFinal) {
            val wholeFileShaHex = Sha256FileHasher.sha256Hex(fileState.partFile)
            if (!EngineEncoding.constantTimeEquals(
                    wholeFileShaHex.encodeToByteArray(),
                    fileState.manifest.sha256.lowercase().encodeToByteArray(),
                )
            ) {
                failTransfer(
                    transferId,
                    "Full-file SHA-256 mismatch for ${fileState.manifest.relativePath}: expected ${fileState.manifest.sha256}, got $wholeFileShaHex",
                )
                return true
            }

            // Atomic rename .ec0part -> finalFile and clean up .ec0part.meta
            Files.move(
                fileState.partFile.toPath(),
                fileState.finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            if (fileState.manifest.lastModifiedMs > 0L) {
                fileState.finalFile.setLastModified(fileState.manifest.lastModifiedMs)
            }
            fileState.metaFile.delete()
            fileState.completed = true

            val completeInfo = FileCompleteInfo(
                transferId = transferId,
                fileId = fileState.manifest.fileId,
                sha256 = wholeFileShaHex,
            )
            transport.sendInfo(FileTransferProtocol.INFO_FILE_COMPLETE, completeInfo.toJsonObject())
        }

        stateLock.withLock {
            val cur = _transfers.value[transferId]
            if (cur != null && !cur.state.isTerminal) {
                val doneCount = ctx.filesById.values.count { it.completed }
                val allDone = doneCount == ctx.filesById.size
                updateSnapshotLocked(
                    cur.copy(
                        state = if (allDone) FileTransferState.COMPLETED else FileTransferState.TRANSFERRING,
                        transferredBytes = (cur.transferredBytes + frame.payload.size.toLong()).coerceAtMost(cur.totalBytes),
                        completedFiles = doneCount,
                    ),
                )
            }
        }
        return true
    }

    private fun handleIncomingCancel(cancel: FileCancelCommand) {
        cancelTransfer(cancel.transferId, reason = cancel.reason, transport = null)
    }

    private fun handleIncomingFileComplete(complete: FileCompleteInfo) {
        val ctx = outgoingTransfers[complete.transferId] ?: return
        val manifest = ctx.offer.files.firstOrNull { it.fileId == complete.fileId } ?: return
        if (!manifest.sha256.equals(complete.sha256, ignoreCase = true)) {
            failTransfer(complete.transferId, "Receiver reported mismatched SHA-256 for ${manifest.relativePath}")
            return
        }
        stateLock.withLock {
            val cur = _transfers.value[complete.transferId] ?: return
            if (cur.state.isTerminal) return
            val allComplete = cur.completedFiles >= cur.totalFiles
            if (allComplete) {
                updateSnapshotLocked(cur.copy(state = FileTransferState.COMPLETED))
            }
        }
    }

    private fun failTransfer(transferId: String, reason: String) {
        stateLock.withLock {
            val cur = _transfers.value[transferId] ?: return
            if (cur.state.isTerminal) return
            outgoingTransfers[transferId]?.cancelled?.set(true)
            incomingTransfers[transferId]?.cancelled?.set(true)
            updateSnapshotLocked(
                cur.copy(
                    state = FileTransferState.FAILED,
                    failureReason = reason,
                ),
            )
        }
    }

    private fun isTerminalState(transferId: String): Boolean = stateLock.withLock {
        _transfers.value[transferId]?.state?.isTerminal == true
    }

    private fun resolveSafeTargetFile(relativePath: String): File {
        val cleanRel = FileTransferProtocol.sanitizeRelativePath(relativePath)
        val rootCanonical = downloadDir.canonicalFile
        val candidate = File(rootCanonical, cleanRel).canonicalFile
        val rootPrefix = rootCanonical.path + File.separator
        if (!candidate.path.startsWith(rootPrefix)) {
            throw SecurityException("Path traversal blocked outside downloadDir: $cleanRel")
        }
        return candidate
    }

    private fun inspectResumePosition(
        transferId: String,
        manifest: FileEntryManifest,
        chunkSize: Int,
        partFile: File,
        metaFile: File,
    ): Int {
        if (!partFile.isFile || !metaFile.isFile) return 0
        val lines = runCatching { metaFile.readLines() }.getOrNull() ?: return 0
        val props = lines.mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
        }.toMap()

        if (props["transfer_id"] != transferId) return 0
        if (props["file_id"] != manifest.fileId) return 0
        if (props["size_bytes"]?.toLongOrNull() != manifest.sizeBytes) return 0
        if (!props["sha256"].equals(manifest.sha256, ignoreCase = true)) return 0
        if (props["chunk_size"]?.toIntOrNull() != chunkSize) return 0

        val recordedChunk = props["next_chunk"]?.toIntOrNull() ?: return 0
        if (recordedChunk <= 0) return 0
        val requiredBytes = minOf(manifest.sizeBytes, recordedChunk.toLong() * chunkSize.toLong())
        return if (partFile.length() >= requiredBytes) recordedChunk else 0
    }

    private fun writeResumeMeta(
        metaFile: File,
        transferId: String,
        manifest: FileEntryManifest,
        chunkSize: Int,
        nextContiguousChunk: Int,
    ) {
        val text = buildString {
            append("transfer_id=").append(transferId).append('\n')
            append("file_id=").append(manifest.fileId).append('\n')
            append("size_bytes=").append(manifest.sizeBytes).append('\n')
            append("sha256=").append(manifest.sha256).append('\n')
            append("chunk_size=").append(chunkSize).append('\n')
            append("next_chunk=").append(nextContiguousChunk).append('\n')
        }
        metaFile.writeText(text)
    }

    private fun updateSnapshot(snapshot: TransferProgressSnapshot) {
        stateLock.withLock {
            updateSnapshotLocked(snapshot)
        }
    }

    private fun updateSnapshotLocked(snapshot: TransferProgressSnapshot) {
        val next = LinkedHashMap(_transfers.value)
        next[snapshot.transferId] = snapshot
        _transfers.value = next
    }
}

internal object Sha256FileHasher {
    fun sha256Bytes(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256Bytes(bytes).toHex()

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }
}
