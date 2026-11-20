/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Linux XDG Login Autostart manager (`~/.config/autostart/ec0.desktop`) and
 * Wayland StatusNotifierItem (SNI) D-Bus watcher probe.
 *
 * Security invariants:
 *   - Strictly validates `execPath` before writing `.desktop`: rejects NUL,
 *     newlines, quotes, backticks, `$`, `;`, `|`, `&`, `<`, `>`, and `%` so
 *     freedesk Exec-field expansion or shell metacharacter injection is impossible.
 *   - Writes atomically with POSIX `0600` owner-only permissions via
 *     [EncryptedIdentityStore.atomicWriteText].
 *   - Wayland SNI probe uses argv-only `busctl --user list` (zero shell).
 */
package ec0.core.engine.linux

import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.SystemProcessLauncher
import java.io.File

class LinuxAutostartManager(
    private val autostartDir: File = defaultAutostartDir(),
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
) {
    val desktopFile: File
        get() = File(autostartDir, DESKTOP_FILE_NAME)

    fun isAutostartEnabled(): Boolean = desktopFile.isFile

    /**
     * Enables or disables automatic background launch at desktop login via
     * `$XDG_CONFIG_HOME/autostart/ec0.desktop`.
     */
    fun setAutostartEnabled(
        enabled: Boolean,
        execPath: String = detectCurrentExecPath(),
    ): Boolean {
        if (!enabled) {
            return !desktopFile.exists() || desktopFile.delete()
        }
        val cleanExec = validateExecPath(execPath)
        val content = buildDesktopEntry(cleanExec)
        EncryptedIdentityStore.ensureDirectoryPermissions(autostartDir)
        EncryptedIdentityStore.atomicWriteText(desktopFile, content)
        return desktopFile.isFile
    }

    /**
     * Probes the user's session D-Bus for `org.kde.StatusNotifierWatcher`
     * (supported natively by KDE Plasma, GNOME AppIndicator, Sway/Waybar,
     * Hyprland, and COSMIC on Wayland).
     */
    fun isStatusNotifierWatcherAvailable(): Boolean {
        val result = runCatching {
            launcher.runBlockingCommand(
                listOf("busctl", "--user", "list", "--no-pager"),
                timeoutMs = 2_000L,
            )
        }.getOrNull() ?: return false
        return result.exitCode == 0 && result.stdout.contains(SNI_WATCHER_BUS_NAME)
    }

    companion object {
        const val DESKTOP_FILE_NAME: String = "ec0.desktop"
        const val SNI_WATCHER_BUS_NAME: String = "org.kde.StatusNotifierWatcher"

        private val FORBIDDEN_EXEC_CHARS = setOf('\n', '\r', '\u0000', '"', '\'', '`', '$', ';', '|', '&', '<', '>', '%')

        fun defaultAutostartDir(): File {
            val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdgConfig != null) {
                File(xdgConfig)
            } else {
                File(System.getProperty("user.home", "."), ".config")
            }
            return File(base, "autostart")
        }

        fun detectCurrentExecPath(): String {
            val appImage = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
            if (appImage != null && runCatching { validateExecPath(appImage) }.isSuccess) {
                return appImage
            }
            val installed = File("/usr/bin/ec0")
            if (installed.isFile) return installed.absolutePath
            val localBin = File(System.getProperty("user.home", "."), ".local/bin/ec0")
            if (localBin.isFile) return localBin.absolutePath
            return "ec0"
        }

        fun validateExecPath(raw: String): String {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= 512) {
                "Autostart execPath must be 1..512 characters"
            }
            require(trimmed.none { it.code < 32 || it.code == 127 || it in FORBIDDEN_EXEC_CHARS }) {
                "Autostart execPath contains forbidden characters"
            }
            return trimmed
        }

        fun buildDesktopEntry(execPath: String): String {
            val safeExec = validateExecPath(execPath)
            return buildString {
                appendLine("[Desktop Entry]")
                appendLine("Type=Application")
                appendLine("Version=1.0")
                appendLine("Name=ec0 Continuity")
                appendLine("Comment=Encrypted Android-Linux continuity hub")
                appendLine("Exec=$safeExec --tray")
                appendLine("Icon=ec0")
                appendLine("Terminal=false")
                appendLine("Categories=Utility;Network;")
                appendLine("X-GNOME-Autostart-enabled=true")
            }
        }
    }
}
