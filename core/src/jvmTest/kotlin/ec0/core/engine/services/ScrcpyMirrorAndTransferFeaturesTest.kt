/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Verifies ScrcpyMirrorCoordinator extended flags (audio toggle, screen-off,
 * stay-awake, max-size) and FileTransferService displayName / clearFinishedTransfers.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.transport.BulkStreamFrame
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrcpyMirrorAndTransferFeaturesTest {

    @Test
    fun mirrorArgsHonorAudioOffTurnScreenOffStayAwakeAndMaxSize() {
        val coordinator = ScrcpyMirrorCoordinator()
        val defaultCmd = ScrcpyMirrorStartCommand(host = "192.168.1.42", adbPort = 5555)
        val defaultArgs = coordinator.buildScrcpyArgs(defaultCmd)
        assertFalse("--no-audio" in defaultArgs, "audio enabled by default should omit --no-audio")
        assertFalse("--turn-screen-off" in defaultArgs)
        assertFalse("--stay-awake" in defaultArgs)

        val customCmd = ScrcpyMirrorStartCommand(
            host = "192.168.1.42",
            adbPort = 5555,
            maxFps = 60,
            bitrateKbps = 12_000,
            audioEnabled = false,
            turnScreenOff = true,
            stayAwake = true,
            maxSize = 1920,
        )
        val customArgs = coordinator.buildScrcpyArgs(customCmd)
        assertTrue("--no-audio" in customArgs, "expected --no-audio in $customArgs")
        assertTrue("--turn-screen-off" in customArgs, "expected --turn-screen-off in $customArgs")
        assertTrue("--stay-awake" in customArgs, "expected --stay-awake in $customArgs")
        val maxSizeIdx = customArgs.indexOf("--max-size")
        assertTrue(maxSizeIdx >= 0 && customArgs.getOrNull(maxSizeIdx + 1) == "1920", "expected --max-size 1920 in $customArgs")

        // Round-trip JSON serialization preserves new optional flags.
        val roundTripped = ScrcpyMirrorStartCommand.fromJsonObject(customCmd.toJsonObject())
        assertEquals(false, roundTripped.audioEnabled)
        assertEquals(true, roundTripped.turnScreenOff)
        assertEquals(true, roundTripped.stayAwake)
        assertEquals(1920, roundTripped.maxSize)
    }

    @Test
    fun fileTransferSnapshotCarriesDisplayNameAndClearFinishedDropsTerminalOnly() {
        val dir = Files.createTempDirectory("ec0-ft-display").toFile()
        try {
            val sample = File(dir, "quarterly-report.pdf").apply { writeText("hello pdf") }
            val svc = FileTransferService(downloadDir = File(dir, "rx"))
            val offer = svc.createOfferFromDirectoryOrFile(sample, transferId = "tx-1")
            val snap = svc.transfers.value[offer.transferId]!!
            assertEquals("quarterly-report.pdf", snap.displayName)
            assertEquals(FileTransferState.OFFERED, snap.state)

            // Active transfer survives clearFinishedTransfers().
            svc.clearFinishedTransfers()
            assertTrue(offer.transferId in svc.transfers.value)

            // Cancelled (terminal) transfer is removed by clearFinishedTransfers().
            assertTrue(svc.cancelTransfer(offer.transferId))
            assertEquals(FileTransferState.CANCELLED, svc.transfers.value[offer.transferId]?.state)
            svc.clearFinishedTransfers()
            assertFalse(offer.transferId in svc.transfers.value)
        } finally {
            dir.deleteRecursively()
        }
    }
}
