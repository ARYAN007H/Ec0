/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Continuity Camera orchestration: ec0 prefers the owner's proven CamDroid
 * stack (hardware H.264) over the in-app MJPEG streamer. These tests pin the
 * discovery/decision logic: server reachability probe, consumer resolution
 * order (PATH CLI first, then the Studio AppImage), and the honest null when
 * nothing is installed.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidConstants
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CamDroidOrchestrationTest {

    private val openSockets = mutableListOf<ServerSocket>()
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun tempDir(): File = Files.createTempDirectory("ec0-camdroid").toFile().also { tempDirs.add(it) }

    private fun closedPort(): Int = ServerSocket(0).also { it.close() }.localPort

    @Test
    fun serverReachableSeesAListenerAndHonestFalseOtherwise() {
        val server = ServerSocket(0).also { openSockets.add(it) }
        assertTrue(
            CamDroidOrchestration.serverReachable("127.0.0.1", server.localPort, timeoutMs = 500),
            "an open server must be reachable",
        )
        val port = closedPort()
        assertFalse(
            CamDroidOrchestration.serverReachable("127.0.0.1", port, timeoutMs = 500),
            "a closed port must be honestly unreachable",
        )
    }

    @Test
    fun pathCliWinsWithFullV4l2Arguments() {
        val dir = tempDir()
        val cli = File(dir, "camdroid-client").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val consumer = CamDroidOrchestration.resolveDesktopConsumer(
            host = "10.115.38.71",
            port = 4747,
            homeDir = dir.absolutePath,
            pathEnv = dir.absolutePath,
        )
        assertEquals(
            listOf(
                cli.absolutePath,
                "--connect", "10.115.38.71:4747",
                "--device", CamDroidConstants.DEFAULT_V4L2_DEVICE,
                "--codec", "h264",
                "--resolution", "1080p",
                "--fps", "30",
                "--no-audio",
            ),
            consumer,
            "the CLI consumer must carry the full hardware-H.264 v4l2 pipeline",
        )
    }

    @Test
    fun studioAppImageIsTheFallbackConsumer() {
        val home = tempDir()
        val apps = File(home, "Applications").apply { mkdirs() }
        File(apps, "CamDroid_2.1.0_amd64.appimage").writeText("stub")
        File(apps, "unrelated.appimage").writeText("stub")

        val consumer = CamDroidOrchestration.resolveDesktopConsumer(
            host = "10.115.38.71",
            port = 4747,
            homeDir = home.absolutePath,
            pathEnv = "",
        )
        assertTrue(
            consumer?.singleOrNull()?.endsWith("CamDroid_2.1.0_amd64.appimage") == true,
            "the newest CamDroid AppImage is the GUI fallback, got: $consumer",
        )
    }

    @Test
    fun noConsumerInstalledResolvesNull() {
        val home = tempDir()
        assertNull(
            CamDroidOrchestration.resolveDesktopConsumer(
                host = "10.115.38.71",
                port = 4747,
                homeDir = home.absolutePath,
                pathEnv = home.absolutePath,
            ),
            "without a CLI or AppImage the caller must get null (honest banner, no camera fight)",
        )
    }
}
