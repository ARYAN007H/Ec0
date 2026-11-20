/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Real AWT SystemTray attach for [ContinuityTrayController] — the controller
 * owns the state (window visibility, minimize-to-tray policy, menu model);
 * this class owns the java.awt plumbing. All AWT mutations run through
 * EventQueue (XWayland-legal). When SystemTray is unsupported (headless,
 * exotic WMs) the attach fails silently with a log line — the app stays a
 * normal window.
 */
package ec0.desktop.continuity

import java.awt.EventQueue
import java.awt.Image
import java.awt.PopupMenu
import java.awt.MenuItem
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.logging.Logger
import javax.imageio.ImageIO

class ContinuitySystemTray(
    private val controller: ContinuityTrayController,
    private val onQuit: () -> Unit,
    private val onSendClipboard: () -> Unit,
    private val iconResourcePath: String = "/ec0.png",
    private val logger: Logger = Logger.getLogger("ContinuitySystemTray"),
) {
    private var trayIcon: TrayIcon? = null

    /**
     * Adds the tray icon. Returns `true` when attached, `false` when the
     * platform has no tray or the icon resource is missing (never throws).
     */
    fun attach(): Boolean {
        if (!SystemTray.isSupported()) {
            logger.info("SystemTray not supported on this platform — tray skipped")
            return false
        }
        val image = loadIcon() ?: run {
            logger.warning("Tray icon resource $iconResourcePath missing — tray skipped")
            return false
        }
        try {
            val popup = PopupMenu().apply {
                add(MenuItem("Show dashboard").apply {
                    addActionListener { controller.setWindowVisible(true) }
                })
                add(MenuItem("Send clipboard").apply {
                    addActionListener { onSendClipboard() }
                })
                addSeparator()
                add(MenuItem("Quit").apply {
                    addActionListener { onQuit() }
                })
            }
            val icon = TrayIcon(image, "ec0 — unified ecosystem", popup).apply {
                isImageAutoSize = true
                // Left-click toggles the dashboard; the popup menu is the right-click.
                addActionListener {
                    controller.setWindowVisible(!controller.windowVisible.value)
                }
            }
            EventQueue.invokeLater {
                runCatching {
                    SystemTray.getSystemTray().add(icon)
                    trayIcon = icon
                    val sni = ec0.core.engine.linux.LinuxAutostartManager().isStatusNotifierWatcherAvailable()
                    logger.info("System tray icon attached (Wayland StatusNotifierWatcher=$sni)")
                }.onFailure { logger.warning("Tray attach failed: ${it.message}") }
            }
            trayIcon = icon // set optimistically so remove() works even before the EDT runs
            return true
        } catch (e: Exception) {
            logger.warning("Tray attach failed: ${e.message}")
            return false
        }
    }

    /** Dynamically refreshes the tray tooltip (e.g. live peer name & battery %). */
    fun updateTooltip(text: String) {
        val icon = trayIcon ?: return
        val safe = text.take(128)
        EventQueue.invokeLater {
            runCatching { icon.toolTip = safe }
        }
    }

    fun remove() {
        val icon = trayIcon ?: return
        EventQueue.invokeLater {
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
        trayIcon = null
    }

    private fun loadIcon(): Image? = runCatching {
        val stream = javaClass.getResourceAsStream(iconResourcePath) ?: return null
        ImageIO.read(stream)?.getScaledInstance(24, 24, Image.SCALE_SMOOTH)
    }.getOrNull()
}
