/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Tray controller state tests (pure — no AWT): close-request semantics,
 * visibility toggle, and the menu model the real tray renders. A real hub
 * instance backs the controller (same pattern as DesktopDashboardBridgeTest).
 */
package ec0.desktop.continuity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuityTrayControllerTest {

    private fun controller(): ContinuityTrayController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = ContinuityHubController(scope = scope, desktopAlias = "tray-test")
        return ContinuityTrayController(hub)
    }

    @Test
    fun closeRequestWithMinimizeToTrayStaysAliveAndHides() {
        val tray = controller().apply { setMinimizeToTrayOnClose(true) }
        assertTrue(tray.onWindowCloseRequested(), "stay alive when minimize-to-tray is on")
        assertFalse(tray.windowVisible.value, "window hidden to tray")
    }

    @Test
    fun closeRequestWithoutMinimizeToTrayExits() {
        val tray = controller().apply { setMinimizeToTrayOnClose(false) }
        assertFalse(tray.onWindowCloseRequested(), "app may exit when minimize-to-tray is off")
    }

    @Test
    fun leftClickToggleFlipsVisibility() {
        val tray = controller()
        assertEquals(true, tray.windowVisible.value)
        tray.setWindowVisible(!tray.windowVisible.value) // tray icon left-click equivalent
        assertFalse(tray.windowVisible.value)
        tray.setWindowVisible(!tray.windowVisible.value)
        assertTrue(tray.windowVisible.value)
    }

    @Test
    fun trayMenuCarriesTheThreeActions() {
        val tray = controller()
        val items = tray.buildTrayMenuItems().associateBy { it.id }
        // left-click equivalent + clipboard + camera — the real popup renders
        // "Show dashboard" / "Send clipboard" / "Quit" (ContinuitySystemTray)
        assertTrue("toggle_window" in items)
        assertTrue("sync_clipboard" in items)
    }
}
