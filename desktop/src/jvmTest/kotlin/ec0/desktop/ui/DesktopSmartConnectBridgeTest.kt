/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Tests for Slice S-A.1 desktop wiring: ContinuityHubController default alias,
 * Smart Connect server startup, and DesktopBridge.smartConnectPairingQr seam.
 */
package ec0.desktop.ui

import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.smartconnect.SmartConnectIdentity
import ec0.desktop.continuity.ContinuityHubController
import ec0.desktop.state.AppState
import ec0.ui.models.SettingsState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DesktopSmartConnectBridgeTest {

    @Test
    fun continuityHubDefaultAliasAndDesktopBridgeSmartConnectQrSeam() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tempDir = Files.createTempDirectory("ec0-downloads-desktop-test").toFile()
        val hub = ContinuityHubController(
            scope = scope,
            localIdentity = EncryptedIdentityStore(tempDir).loadOrCreateIdentity(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            downloadDir = tempDir,
        )
        try {
            // Verify default desktopAlias comes from shared SettingsState().alias
            assertEquals(SettingsState().alias, hub.desktopAlias)
            assertEquals(SettingsState().alias, hub.node.displayName)

            hub.node.startServer(startMdns = false)
            val scPort = hub.node.startSmartConnectServer(announceMdns = false)
            assertTrue(scPort > 0, "Expected Smart Connect TLS WebSocket server to bind a valid port")

            val appState = AppState(scope)
            val bridge = DesktopBridge(
                appState = appState,
                hub = hub,
                smartConnectWsPort = scPort,
            )

            val qr = bridge.smartConnectPairingQr.value
            assertNotNull(qr, "Expected DesktopBridge.smartConnectPairingQr to be populated from hub")
            assertTrue(qr.payload.startsWith("motorolardpconnection{"))
            assertEquals(scPort, qr.wsPort)
            assertEquals(hub.desktopAlias, qr.deviceName)
            assertTrue(qr.size >= 45, "Expected QR matrix size >= 45 for motorolardpconnection payload")
            assertEquals(qr.size * qr.size, qr.modules.size)
            assertTrue(qr.modules.any { it }, "Expected non-empty dark modules in QR matrix")

            val parsed = SmartConnectIdentity.parseQrPayloadString(qr.payload)
            assertEquals("3.0.0", parsed.version)
            assertEquals(scPort, parsed.wsData.wsPort)
            assertEquals(hub.desktopAlias, parsed.wsData.deviceName)
            assertEquals(6, parsed.wsData.os)
            assertEquals(4, parsed.wsData.type)
            assertEquals(2, parsed.wsData.scType)
        } finally {
            runCatching { hub.close() }
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
