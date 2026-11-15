/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Dashboard seams: DesktopBridge must expose real hub state (camera status,
 * clipboard summary + toggle, mirror poll, peer link) with honest defaults —
 * and the tile actions must be safe no-ops when nothing is paired.
 */
package ec0.desktop.ui

import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopDashboardBridgeTest {

    @Test
    fun dashboardSeamsExposeHubStateAndTileActionsStaySafeUnpaired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tempDir = Files.createTempDirectory("ec0-dash-test").toFile()
        val hub = ContinuityHubController(
            scope = scope,
            localIdentity = EncryptedIdentityStore(tempDir).loadOrCreateIdentity(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            downloadDir = tempDir,
        )
        try {
            val appState = AppState(scope)
            val bridge = DesktopBridge(appState, hub, null, scope)

            // trustedPeerCount flows from the (empty) pin store.
            assertEquals(0, withTimeout(2_000) { bridge.trustedPeerCount.first { it >= 0 } })

            // Clipboard seam mirrors the hub and round-trips the toggle.
            bridge.setAutoSyncClipboard(false)
            assertEquals(false, withTimeout(2_000) { bridge.autoSyncClipboard.first { !it } })
            bridge.setAutoSyncClipboard(true)
            assertEquals(true, withTimeout(2_000) { bridge.autoSyncClipboard.first { it } })

            // Camera status line materializes from the coordinator (IDLE → "idle").
            val camera = withTimeout(2_000) { bridge.cameraStateText.first { it != null } }
            assertNotNull(camera)
            assertTrue(camera.startsWith("idle"), "expected idle camera state, got: $camera")

            // Mirror poll settles false (nothing mirroring).
            assertEquals(false, withTimeout(2_000) { bridge.mirrorRunning.first { !it } })

            // Peer link materializes even with nothing paired (searching state).
            val link = withTimeout(2_000) { bridge.peerLink.first { it != null } }
            assertNotNull(link)
            assertEquals("searching", link.transport)

            // Tile actions are safe no-ops with nothing paired — no crash, no throw.
            bridge.sendNotificationReply("nonexistent", "hello")
            bridge.startScreenMirror()
            bridge.stopScreenMirror()
            bridge.startCamera()
            bridge.stopCamera()
            bridge.setupV4l2Loopback()
        } finally {
            runCatching { hub.close() }
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
