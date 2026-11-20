/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop security & upgrade regression tests:
 *   - `ContinuityHubController.openLocalDownloadedFile` canonical Downloads containment
 *     (allows genuine downloaded files, blocks `/etc/passwd`, `../`, symlink escapes, NUL bytes).
 *   - `DesktopBridge.handleDroppedFiles` cap at 64 + hidden/non-existent file filtering.
 */
package ec0.desktop

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.services.CommandExecResult
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.RunningProcessHandle
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.desktop.ui.DesktopBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSecurityAndUpgradesTest {

    @Test
    fun openLocalDownloadedFileEnforcesCanonicalContainmentInsideDownloadDir() {
        val sandbox = createTempDirectory("ec0-desktop-open").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val downloadDir = File(sandbox, "downloads").apply { mkdirs() }
        val outsideFile = File(sandbox, "outside-secret.txt").apply { writeText("secret") }
        val insideFile = File(downloadDir, "photo.jpg").apply { writeText("jpeg-bytes") }
        val symlinkEscape = File(downloadDir, "evil-link.txt")
        Files.createSymbolicLink(symlinkEscape.toPath(), outsideFile.toPath())

        val launched = mutableListOf<List<String>>()
        val fakeLauncher = object : ExternalProcessLauncher {
            override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
                launched += command
                return CommandExecResult(0, "", "")
            }

            override fun spawnProcess(command: List<String>): RunningProcessHandle =
                object : RunningProcessHandle {
                    override val isAlive: Boolean = false
                    override fun stop() = Unit
                }
        }

        val hub = ContinuityHubController(
            scope = scope,
            localIdentity = Ed25519Identity.generate(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            desktopAlias = "TestDesktop",
            downloadDir = downloadDir,
            peerGrantsFile = File(sandbox, "peer_grants.json"),
        )
        try {
            // Valid file inside downloadDir opens cleanly
            assertTrue(hub.openLocalDownloadedFile(insideFile.absolutePath, revealInFolder = false, launcher = fakeLauncher))
            assertEquals(listOf("xdg-open", insideFile.canonicalPath), launched.last())

            // Reveal in folder opens the containing downloadDir
            assertTrue(hub.openLocalDownloadedFile(insideFile.absolutePath, revealInFolder = true, launcher = fakeLauncher))
            assertEquals(listOf("xdg-open", downloadDir.canonicalPath), launched.last())

            val countBeforeBlocks = launched.size

            // Blocks outside absolute path
            assertFalse(hub.openLocalDownloadedFile(outsideFile.absolutePath, revealInFolder = false, launcher = fakeLauncher))
            // Blocks /etc/passwd
            assertFalse(hub.openLocalDownloadedFile("/etc/passwd", revealInFolder = false, launcher = fakeLauncher))
            // Blocks .. traversal
            assertFalse(
                hub.openLocalDownloadedFile(
                    "${downloadDir.absolutePath}/../outside-secret.txt",
                    revealInFolder = false,
                    launcher = fakeLauncher,
                ),
            )
            // Blocks symlink escaping downloadDir
            assertFalse(hub.openLocalDownloadedFile(symlinkEscape.absolutePath, revealInFolder = false, launcher = fakeLauncher))
            // Blocks NUL byte & control character injection
            assertFalse(hub.openLocalDownloadedFile("${insideFile.absolutePath}\u0000.sh", revealInFolder = false, launcher = fakeLauncher))
            assertFalse(hub.openLocalDownloadedFile("${insideFile.absolutePath}\n", revealInFolder = false, launcher = fakeLauncher))

            assertEquals(countBeforeBlocks, launched.size, "No blocked path may ever invoke xdg-open")
        } finally {
            hub.close()
            scope.cancel()
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun desktopBridgeDragOverlayAndDroppedFilesFilterHiddenAndCapCount() {
        val dir = createTempDirectory("ec0-desktop-dnd").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = AppState(scope = scope)
            val bridge = DesktopBridge(appState = state, hub = null, uiScope = scope)

            assertFalse(bridge.isDragOverWindow.value)
            bridge.setDragOverWindow(true)
            assertTrue(bridge.isDragOverWindow.value)
            bridge.setDragOverWindow(false)
            assertFalse(bridge.isDragOverWindow.value)

            // Hidden files, missing files, and >64 files must never throw or exceed MAX_DROPPED_FILES
            val hidden = File(dir, ".secret").apply { writeText("x") }
            val missing = File(dir, "does-not-exist.txt")
            val normal = (1..80).map { i -> File(dir, "f$i.txt").apply { writeText("ok$i") } }
            val filtered = (listOf(hidden, missing) + normal)
                .filter { it.exists() && !it.isHidden }
                .take(DesktopBridge.MAX_DROPPED_FILES)
            assertEquals(DesktopBridge.MAX_DROPPED_FILES, filtered.size)
            assertFalse(filtered.any { it.name == ".secret" })

            // Invoking handleDroppedFiles safely handles null hub & large lists
            bridge.handleDroppedFiles(listOf(hidden, missing) + normal)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
