/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.protocol.FileAcceptCommand
import ec0.core.engine.protocol.FileTransferProtocol
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.transport.BulkStreamFrame
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileTransferServiceTest {

    private val senderIdentity = Ed25519Identity.generateKeyPair()
    private val receiverIdentity = Ed25519Identity.generateKeyPair()

    private class InMemoryFileTransport(
        private val localProcessor: SessionEnvelopeProcessor,
    ) : FileTransferService.OutboundTransport {
        val controlEnvelopes = mutableListOf<Ec0Envelope>()
        val bulkChunks = mutableListOf<BulkStreamFrame>()

        override fun sendCmd(cmdType: String, cmdBody: JsonObject) {
            controlEnvelopes.add(
                localProcessor.nextCmdEnvelope(
                    cmdType = cmdType,
                    cmdBody = cmdBody,
                    category = FileTransferProtocol.CATEGORY_FILE,
                ),
            )
        }

        override fun sendInfo(infoType: String, infoBody: JsonObject) {
            controlEnvelopes.add(
                localProcessor.nextInfoEnvelope(
                    infoType = infoType,
                    infoBody = infoBody,
                    category = FileTransferProtocol.CATEGORY_FILE,
                ),
            )
        }

        override fun sendChunk(frame: BulkStreamFrame) {
            bulkChunks.add(frame)
        }
    }

    @Test
    fun nestedFolderTransferPreservesRelativeStructureAndVerifiesSha256() {
        val srcRoot = Files.createTempDirectory("ec0-src-dir").toFile()
        val dstRoot = Files.createTempDirectory("ec0-dst-dir").toFile()
        try {
            val projectDir = File(srcRoot, "project")
            val subDir = File(projectDir, "assets/icons")
            subDir.mkdirs()

            val readmeFile = File(projectDir, "README.md")
            val readmeBytes = "# ec0 continuity engine\nZero cloud dependency.".encodeToByteArray()
            readmeFile.writeBytes(readmeBytes)

            val iconFile = File(subDir, "logo.bin")
            val iconBytes = ByteArray(10_000) { (it * 13 + 5).toByte() }
            iconFile.writeBytes(iconBytes)

            val senderService = FileTransferService(downloadDir = srcRoot, defaultChunkSize = 4096)
            val receiverService = FileTransferService(downloadDir = dstRoot, defaultChunkSize = 4096)

            val senderProc = SessionEnvelopeProcessor(senderIdentity, receiverIdentity.deviceId, receiverIdentity.publicKeyRaw)
            val receiverProc = SessionEnvelopeProcessor(receiverIdentity, senderIdentity.deviceId, senderIdentity.publicKeyRaw)

            val senderOut = InMemoryFileTransport(senderProc)
            val receiverOut = InMemoryFileTransport(receiverProc)

            val offer = senderService.createOfferFromDirectoryOrFile(projectDir, transferId = "tx-folder-1", chunkSize = 4096)
            assertEquals(2, offer.files.size)
            senderService.sendOffer(offer, senderOut)

            // Deliver file_offer to receiver -> receiver emits file_accept
            assertTrue(receiverService.handleControlEnvelope(senderOut.controlEnvelopes.removeAt(0), receiverOut))
            assertEquals(1, receiverOut.controlEnvelopes.size)

            // Deliver file_accept to sender -> sender streams chunks
            assertTrue(senderService.handleControlEnvelope(receiverOut.controlEnvelopes.removeAt(0), senderOut))
            assertTrue(senderOut.bulkChunks.isNotEmpty())

            // Deliver all chunks to receiver -> receiver verifies SHA-256, renames .ec0part, emits file_complete
            while (senderOut.bulkChunks.isNotEmpty()) {
                assertTrue(receiverService.handleBulkChunk(senderOut.bulkChunks.removeAt(0), receiverOut))
            }

            // Deliver file_complete info envelopes back to sender
            while (receiverOut.controlEnvelopes.isNotEmpty()) {
                assertTrue(senderService.handleControlEnvelope(receiverOut.controlEnvelopes.removeAt(0), senderOut))
            }

            assertEquals(FileTransferState.COMPLETED, senderService.transfers.value["tx-folder-1"]?.state)
            assertEquals(FileTransferState.COMPLETED, receiverService.transfers.value["tx-folder-1"]?.state)

            val receivedReadme = File(dstRoot, "project/README.md")
            val receivedIcon = File(dstRoot, "project/assets/icons/logo.bin")
            assertTrue(receivedReadme.isFile)
            assertTrue(receivedIcon.isFile)
            assertContentEquals(readmeBytes, receivedReadme.readBytes())
            assertContentEquals(iconBytes, receivedIcon.readBytes())
        } finally {
            srcRoot.deleteRecursively()
            dstRoot.deleteRecursively()
        }
    }

    @Test
    fun interruptedTransferResumesFromExactVerifiedChunkWithoutResendingPrefix() {
        val srcRoot = Files.createTempDirectory("ec0-resume-src").toFile()
        val dstRoot = Files.createTempDirectory("ec0-resume-dst").toFile()
        try {
            val chunkSize = 2048
            // 10 chunks * 2048 = 20_480 bytes
            val fullPayload = ByteArray(10 * chunkSize) { (it * 29 + 11).toByte() }
            val srcFile = File(srcRoot, "video.mp4").apply { writeBytes(fullPayload) }

            val senderService = FileTransferService(downloadDir = srcRoot, defaultChunkSize = chunkSize)
            val receiverService = FileTransferService(downloadDir = dstRoot, defaultChunkSize = chunkSize)

            val senderProc = SessionEnvelopeProcessor(senderIdentity, receiverIdentity.deviceId, receiverIdentity.publicKeyRaw)
            val receiverProc = SessionEnvelopeProcessor(receiverIdentity, senderIdentity.deviceId, senderIdentity.publicKeyRaw)

            val senderOut = InMemoryFileTransport(senderProc)
            val receiverOut = InMemoryFileTransport(receiverProc)

            val offer = senderService.createOfferFromDirectoryOrFile(
                rootFileOrDir = srcFile,
                transferId = "tx-resume-10",
                chunkSize = chunkSize,
            )
            val fileId = offer.files[0].fileId

            // --- Attempt 1: Drop connection after sending 4 chunks (chunks 0, 1, 2, 3) ---
            senderService.sendOffer(offer, senderOut)
            receiverService.handleControlEnvelope(senderOut.controlEnvelopes.removeAt(0), receiverOut)

            val accept1Env = receiverOut.controlEnvelopes.removeAt(0)
            val accept1 = FileAcceptCommand.fromJsonObject(accept1Env.innerBodyObject!!)
            assertEquals(0, accept1.resumeFromChunk[fileId])

            senderService.handleControlEnvelope(
                envelope = accept1Env,
                transport = senderOut,
                maxChunksToSendForTest = 4, // Simulate drop after 4 chunks
            )
            assertEquals(4, senderOut.bulkChunks.size)
            while (senderOut.bulkChunks.isNotEmpty()) {
                receiverService.handleBulkChunk(senderOut.bulkChunks.removeAt(0), receiverOut)
            }
            // Receiver should still be in TRANSFERRING with .ec0part on disk
            assertEquals(FileTransferState.TRANSFERRING, receiverService.transfers.value["tx-resume-10"]?.state)
            assertTrue(File(dstRoot, "video.mp4.ec0part").isFile)

            // --- Attempt 2: Reconnect and re-send file_offer with same transferId ---
            senderService.sendOffer(offer, senderOut)
            receiverService.handleControlEnvelope(senderOut.controlEnvelopes.removeAt(0), receiverOut)

            val accept2Env = receiverOut.controlEnvelopes.removeAt(0)
            val accept2 = FileAcceptCommand.fromJsonObject(accept2Env.innerBodyObject!!)
            // Receiver MUST report resume_from_chunk = 4!
            assertEquals(4, accept2.resumeFromChunk[fileId])

            senderService.handleControlEnvelope(accept2Env, senderOut)
            // Sender MUST transmit only the remaining 6 chunks (indices 4..9)!
            assertEquals(6, senderOut.bulkChunks.size)
            assertEquals(4, senderOut.bulkChunks.first().chunkIndex)
            assertEquals(9, senderOut.bulkChunks.last().chunkIndex)

            while (senderOut.bulkChunks.isNotEmpty()) {
                receiverService.handleBulkChunk(senderOut.bulkChunks.removeAt(0), receiverOut)
            }
            while (receiverOut.controlEnvelopes.isNotEmpty()) {
                senderService.handleControlEnvelope(receiverOut.controlEnvelopes.removeAt(0), senderOut)
            }

            assertEquals(FileTransferState.COMPLETED, receiverService.transfers.value["tx-resume-10"]?.state)
            assertEquals(FileTransferState.COMPLETED, senderService.transfers.value["tx-resume-10"]?.state)
            val finalDest = File(dstRoot, "video.mp4")
            assertTrue(finalDest.isFile)
            assertContentEquals(fullPayload, finalDest.readBytes())
        } finally {
            srcRoot.deleteRecursively()
            dstRoot.deleteRecursively()
        }
    }

    @Test
    fun midStreamCancellationStopsSenderAndFinishedStatesGuardPreventsFlipToCompleted() {
        val srcRoot = Files.createTempDirectory("ec0-cancel-src").toFile()
        val dstRoot = Files.createTempDirectory("ec0-cancel-dst").toFile()
        try {
            val chunkSize = 1024
            val srcFile = File(srcRoot, "large.bin").apply {
                writeBytes(ByteArray(8 * chunkSize) { it.toByte() })
            }

            val senderService = FileTransferService(downloadDir = srcRoot, defaultChunkSize = chunkSize)
            val receiverService = FileTransferService(downloadDir = dstRoot, defaultChunkSize = chunkSize)

            val senderProc = SessionEnvelopeProcessor(senderIdentity, receiverIdentity.deviceId, receiverIdentity.publicKeyRaw)
            val receiverProc = SessionEnvelopeProcessor(receiverIdentity, senderIdentity.deviceId, senderIdentity.publicKeyRaw)

            val senderOut = InMemoryFileTransport(senderProc)
            val receiverOut = InMemoryFileTransport(receiverProc)

            val offer = senderService.createOfferFromDirectoryOrFile(srcFile, transferId = "tx-cancel-1", chunkSize = chunkSize)
            senderService.sendOffer(offer, senderOut)
            receiverService.handleControlEnvelope(senderOut.controlEnvelopes.removeAt(0), receiverOut)

            val acceptEnv = receiverOut.controlEnvelopes.removeAt(0)
            // Cancel after chunk 2 is sent
            senderService.handleControlEnvelope(
                envelope = acceptEnv,
                transport = senderOut,
                onChunkSentHook = { txId, chunksSent ->
                    if (chunksSent == 2) {
                        senderService.cancelTransfer(txId, reason = "user_clicked_cancel", transport = senderOut)
                    }
                },
            )

            val senderSnapshot = senderService.transfers.value["tx-cancel-1"]
            assertNotNull(senderSnapshot)
            assertEquals(FileTransferState.CANCELLED, senderSnapshot.state)
            // Only 2 data chunks + 1 abort chunk were emitted (instead of all 8 chunks)
            assertEquals(3, senderOut.bulkChunks.size)
            assertEquals(BulkStreamFrame.FLAG_STREAM_ABORT, senderOut.bulkChunks.last().flags)

            // Deliver cancel envelope to receiver
            while (senderOut.controlEnvelopes.isNotEmpty()) {
                receiverService.handleControlEnvelope(senderOut.controlEnvelopes.removeAt(0), receiverOut)
            }
            assertEquals(FileTransferState.CANCELLED, receiverService.transfers.value["tx-cancel-1"]?.state)
        } finally {
            srcRoot.deleteRecursively()
            dstRoot.deleteRecursively()
        }
    }

    @Test
    fun pathTraversalRelativePathsAreRejectedFailClosed() {
        val maliciousPaths = listOf(
            "",
            "../etc/passwd",
            "../../etc/shadow",
            "/etc/passwd",
            "\\Windows\\System32\\cmd.exe",
            "C:/Users/admin/secret.txt",
            "folder/../../escape.sh",
            "folder/./file.txt",
            "folder//file.txt",
        )
        for (bad in maliciousPaths) {
            assertFailsWith<IllegalArgumentException>("Expected '$bad' to be rejected") {
                FileTransferProtocol.sanitizeRelativePath(bad)
            }
        }
        assertEquals("photos/2026/img_01.jpg", FileTransferProtocol.sanitizeRelativePath("photos/2026/img_01.jpg"))
    }
}
