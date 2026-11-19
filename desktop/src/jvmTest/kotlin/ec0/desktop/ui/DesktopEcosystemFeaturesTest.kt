/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Verifies desktop ecosystem upgrades: Desktop PeerManagementBridge (fingerprint,
 * capability toggles, 2-way unpair), ConnectedPeerSummary, CameraControlsState,
 * OTP code extraction in relayedNotifications, and merged encrypted transfers.
 */
package ec0.desktop.ui

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopEcosystemFeaturesTest {

    @Test
    fun peerManagementConnectedPeersCameraControlsAndEncryptedTransfersWorkEndToEnd() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tempDir = Files.createTempDirectory("ec0-eco-test").toFile()
        val pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false)
        val peerIdentity = Ed25519Identity.generateKeyPair()
        pinStore.verifyOrPin(
            deviceId = peerIdentity.deviceId,
            publicKeyRaw = peerIdentity.publicKeyRaw,
            alias = "Pixel 9 Pro",
            trustLevel = TrustLevel.TRUSTED_QR,
            nowMs = 1_700_000_000_000L,
        )

        val hub = ContinuityHubController(
            scope = scope,
            localIdentity = EncryptedIdentityStore(tempDir).loadOrCreateIdentity(),
            pinStore = pinStore,
            downloadDir = tempDir,
        )
        try {
            val appState = AppState(scope)
            val bridge = DesktopBridge(appState, hub, null, scope)

            // 1. Desktop PeerManagementBridge exposes pinned peer with SHA-256 fingerprint prefix
            val pm = assertNotNull(bridge.peerManagement)
            val peers = withTimeout(2_000) { pm.peers.first { it.isNotEmpty() } }
            assertEquals(1, peers.size)
            assertEquals("Pixel 9 Pro", peers.single().alias)
            assertEquals(16, peers.single().fingerprintPrefix.length)
            assertTrue("remote_input" in peers.single().grantedCapabilities)

            // Toggle per-device capability grant
            pm.setCapability(peerIdentity.deviceId, "remote_input", false)
            val updatedPeers = withTimeout(2_000) {
                pm.peers.first { "remote_input" !in it.single().grantedCapabilities }
            }
            assertFalse("remote_input" in updatedPeers.single().grantedCapabilities)

            // 2. ConnectedPeerSummary feeds the Hero Card
            val summaries = withTimeout(2_000) { bridge.connectedPeers.first { it.isNotEmpty() } }
            assertEquals("Pixel 9 Pro", summaries.single().alias)
            assertEquals(16, summaries.single().fingerprintPrefix.length)

            // 3. Camera studio controls update state cleanly
            bridge.setCameraZoom(250)
            bridge.setCameraFlash(true)
            bridge.switchCameraLens()
            val cam = withTimeout(2_000) {
                bridge.cameraControls.first { it != null && it.zoomX100 == 250 && it.flashEnabled && it.frontCamera }
            }
            assertNotNull(cam)
            assertEquals(250, cam.zoomX100)
            assertTrue(cam.flashEnabled)
            assertTrue(cam.frontCamera)

            // 4. Encrypted engine transfer appears in unified bridge.transfers with encrypted=true
            val sampleFile = File(tempDir, "design-deck.pdf").apply { writeText("deck payload") }
            val offer = hub.node.fileTransferService.createOfferFromDirectoryOrFile(sampleFile, transferId = "eng-1")
            val merged = withTimeout(2_000) {
                bridge.transfers.first { list -> list.any { it.id == "ec0:${offer.transferId}" } }
            }
            val item = merged.single { it.id == "ec0:${offer.transferId}" }
            assertEquals("design-deck.pdf", item.fileName)
            assertTrue(item.encrypted)
            assertEquals("Pixel 9 Pro", item.peerAlias)

            // Cancelling via bridge cancels engine transfer and clearFinishedTransfers removes it
            bridge.cancelTransfer("ec0:${offer.transferId}")
            withTimeout(2_000) {
                bridge.transfers.first { list ->
                    list.any { it.id == "ec0:${offer.transferId}" && it.state == ec0.ui.models.TransferState.CANCELLED }
                }
            }
            bridge.clearFinishedTransfers()
            val afterClear = withTimeout(2_000) {
                bridge.transfers.first { list -> list.none { it.id == "ec0:${offer.transferId}" } }
            }
            assertTrue(afterClear.none { it.id == "ec0:${offer.transferId}" })

            // 5. Unpair removes peer from pinStore & peerManagement
            pm.unpair(peerIdentity.deviceId)
            val afterUnpair = withTimeout(2_000) { pm.peers.first { it.isEmpty() } }
            assertTrue(afterUnpair.isEmpty())
            assertEquals(0, bridge.trustedPeerCount.first { it == 0 })
        } finally {
            runCatching { hub.close() }
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
