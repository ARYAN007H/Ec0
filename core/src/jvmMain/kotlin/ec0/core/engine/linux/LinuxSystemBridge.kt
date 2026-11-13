/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Native Linux OS integration bridges for ec0-engine:
 *   1. LinuxEnvironmentProbe — detects Wayland vs X11, wl-clipboard / xclip,
 *      notify-send / gdbus, adb, scrcpy, camdroid, and /dev/video10 (v4l2loopback).
 *   2. LinuxClipboardBridge — bidirectional text/plain & image/png clipboard sync
 *      with Wayland (wl-copy / wl-paste) and X11 (xclip) + SHA-256 echo suppression.
 *   3. LinuxNotificationBridge — Freedesktop desktop notification dispatch via
 *      notify-send with strict argv separation (zero shell interpolation).
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.ClipboardMime
import ec0.core.engine.protocol.NotificationPostInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class LinuxDisplayServer {
    WAYLAND,
    X11,
    HEADLESS,
}

data class BinaryCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String = "",
) {
    val stdoutUtf8: String
        get() = stdout.decodeToString()
}

/**
 * Injectable binary-safe process executor used by [LinuxClipboardBridge],
 * [LinuxNotificationBridge], and [LinuxEnvironmentProbe].
 *
 * Never invokes `/bin/sh -c`; commands are always passed as discrete argv arrays
 * to `ProcessBuilder` to eliminate command injection surfaces.
 */
interface BinaryCommandExecutor {
    fun execute(
        command: List<String>,
        stdin: ByteArray = ByteArray(0),
        timeoutMs: Long = 3_000L,
    ): BinaryCommandResult
}

object SystemBinaryCommandExecutor : BinaryCommandExecutor {
    override fun execute(
        command: List<String>,
        stdin: ByteArray,
        timeoutMs: Long,
    ): BinaryCommandResult {
        require(command.isNotEmpty()) { "Command argv must not be empty" }
        val proc = ProcessBuilder(command).start()
        if (stdin.isNotEmpty()) {
            proc.outputStream.use { out ->
                out.write(stdin)
                out.flush()
            }
        } else {
            runCatching { proc.outputStream.close() }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("Command timed out after ${timeoutMs}ms: ${command.first()}")
        }
        val stdout = proc.inputStream.readBytes()
        val stderr = proc.errorStream.bufferedReader().readText()
        return BinaryCommandResult(
            exitCode = proc.exitValue(),
            stdout = stdout,
            stderr = stderr,
        )
    }
}

/**
 * Snapshot of Linux host capabilities for Apple-Continuity parity features.
 */
data class LinuxSystemCapabilities(
    val displayServer: LinuxDisplayServer,
    val hasWlClipboard: Boolean,
    val hasXclip: Boolean,
    val hasNotifySend: Boolean,
    val hasGdbus: Boolean,
    val hasAdb: Boolean,
    val hasScrcpy: Boolean,
    val hasCamDroidCli: Boolean,
    val v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
    val v4l2DeviceReady: Boolean = false,
) {
    val clipboardReady: Boolean
        get() = when (displayServer) {
            LinuxDisplayServer.WAYLAND -> hasWlClipboard || hasXclip
            LinuxDisplayServer.X11 -> hasXclip || hasWlClipboard
            LinuxDisplayServer.HEADLESS -> false
        }

    val notificationsReady: Boolean
        get() = hasNotifySend || hasGdbus

    val screenMirrorReady: Boolean
        get() = hasAdb && hasScrcpy

    val continuityCameraReady: Boolean
        get() = v4l2DeviceReady && (hasCamDroidCli || (hasAdb && hasScrcpy))

    fun remediationHints(): List<String> = buildList {
        if (!clipboardReady) {
            when (displayServer) {
                LinuxDisplayServer.WAYLAND ->
                    add("Install wl-clipboard (`sudo pacman -S wl-clipboard` or `sudo apt install wl-clipboard`) for Wayland clipboard sync.")
                LinuxDisplayServer.X11 ->
                    add("Install xclip (`sudo pacman -S xclip` or `sudo apt install xclip`) for X11 clipboard sync.")
                LinuxDisplayServer.HEADLESS ->
                    add("No active Wayland/X11 display server detected in environment (`WAYLAND_DISPLAY` / `DISPLAY`).")
            }
        }
        if (!notificationsReady) {
            add("Install libnotify (`notify-send`) to display mirrored Android notifications on Linux desktop.")
        }
        if (!screenMirrorReady) {
            add("Install `android-tools` (`adb`) and `scrcpy` (>= 2.4) for low-latency phone screen mirroring.")
        }
        if (!v4l2DeviceReady) {
            val videoNr = v4l2DevicePath.removePrefix("/dev/video").toIntOrNull() ?: 10
            add(
                "Load v4l2loopback for Continuity Camera: " +
                    "`sudo modprobe v4l2loopback exclusive_caps=1 video_nr=$videoNr card_label=\"${CamDroidConstants.DEFAULT_V4L2_CARD_LABEL}\"`",
            )
        }
    }
}

class LinuxEnvironmentProbe(
    private val envProvider: (String) -> String? = { System.getenv(it) },
    private val binaryLocator: (String) -> Boolean = { bin ->
        try {
            val res = SystemBinaryCommandExecutor.execute(listOf("which", bin), timeoutMs = 1_500L)
            res.exitCode == 0 && res.stdoutUtf8.isNotBlank()
        } catch (_: Exception) {
            false
        }
    },
    private val v4l2Inspector: (String) -> Boolean = { path ->
        val f = File(path)
        f.exists() && f.canWrite()
    },
) {
    fun detectDisplayServer(): LinuxDisplayServer {
        val waylandDisplay = envProvider("WAYLAND_DISPLAY")?.trim().orEmpty()
        val xdgSession = envProvider("XDG_SESSION_TYPE")?.trim()?.lowercase().orEmpty()
        val x11Display = envProvider("DISPLAY")?.trim().orEmpty()
        return when {
            waylandDisplay.isNotEmpty() || xdgSession == "wayland" -> LinuxDisplayServer.WAYLAND
            x11Display.isNotEmpty() || xdgSession == "x11" -> LinuxDisplayServer.X11
            else -> LinuxDisplayServer.HEADLESS
        }
    }

    fun probe(v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE): LinuxSystemCapabilities {
        val display = detectDisplayServer()
        val hasWlCopy = binaryLocator("wl-copy") && binaryLocator("wl-paste")
        val hasXclip = binaryLocator("xclip")
        return LinuxSystemCapabilities(
            displayServer = display,
            hasWlClipboard = hasWlCopy,
            hasXclip = hasXclip,
            hasNotifySend = binaryLocator("notify-send"),
            hasGdbus = binaryLocator("gdbus"),
            hasAdb = binaryLocator("adb"),
            hasScrcpy = binaryLocator("scrcpy"),
            hasCamDroidCli = binaryLocator("camdroid"),
            v4l2DevicePath = v4l2DevicePath,
            v4l2DeviceReady = v4l2Inspector(v4l2DevicePath),
        )
    }
}

/**
 * Bidirectional Linux system clipboard bridge supporting Wayland (`wl-copy` /
 * `wl-paste`) and X11 (`xclip`) for both `text/plain` and `image/png`.
 *
 * Tracks the SHA-256 of the most recently applied remote clipboard item so
 * polling the local Linux clipboard never re-echoes a remote paste back to the phone.
 */
class LinuxClipboardBridge(
    private val executor: BinaryCommandExecutor = SystemBinaryCommandExecutor,
    private val displayServerProvider: () -> LinuxDisplayServer = { LinuxEnvironmentProbe().detectDisplayServer() },
) {
    private val lock = Any()
    private var lastObservedSha256Hex: String? = null

    val lastSyncedSha256Hex: String?
        get() = synchronized(lock) { lastObservedSha256Hex }

    /**
     * Writes an incoming remote [ClipboardItem] into the Linux system clipboard
     * (`wl-copy` on Wayland, `xclip` on X11) and records its SHA-256 digest to
     * suppress loopback echo on subsequent polls.
     */
    fun applyRemoteClipboardToSystem(item: ClipboardItem): Boolean = synchronized(lock) {
        if (item.isSensitive) return false
        val display = displayServerProvider()
        val command = buildWriteCommand(display, item.mime) ?: return false
        return try {
            val result = executor.execute(command = command, stdin = item.bytes)
            if (result.exitCode == 0) {
                lastObservedSha256Hex = item.sha256Hex.lowercase()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads the current Linux system clipboard. If its SHA-256 differs from the
     * last observed or remotely applied clip, returns a fresh [ClipboardItem]
     * ready to push to connected peers; otherwise returns `null`.
     */
    fun pollLocalClipboardForChange(preferImagePng: Boolean = false): ClipboardItem? = synchronized(lock) {
        val display = displayServerProvider()
        if (display == LinuxDisplayServer.HEADLESS) return null

        val candidate = if (preferImagePng) {
            readSystemClipboard(display, ClipboardMime.IMAGE_PNG)
                ?: readSystemClipboard(display, ClipboardMime.TEXT_PLAIN)
        } else {
            readSystemClipboard(display, ClipboardMime.TEXT_PLAIN)
                ?: readSystemClipboard(display, ClipboardMime.IMAGE_PNG)
        } ?: return null

        val digest = candidate.sha256Hex.lowercase()
        if (digest == lastObservedSha256Hex) {
            return null
        }
        lastObservedSha256Hex = digest
        return candidate
    }

    internal fun buildWriteCommand(display: LinuxDisplayServer, mimeType: String): List<String>? =
        when (display) {
            LinuxDisplayServer.WAYLAND -> listOf("wl-copy", "--type", mimeType)
            LinuxDisplayServer.X11 -> listOf("xclip", "-selection", "clipboard", "-t", mimeType, "-i")
            LinuxDisplayServer.HEADLESS -> null
        }

    internal fun buildReadCommand(display: LinuxDisplayServer, mimeType: String): List<String>? =
        when (display) {
            LinuxDisplayServer.WAYLAND -> if (mimeType == ClipboardMime.TEXT_PLAIN) {
                listOf("wl-paste", "--no-newline", "--type", mimeType)
            } else {
                listOf("wl-paste", "--type", mimeType)
            }
            LinuxDisplayServer.X11 -> listOf("xclip", "-selection", "clipboard", "-t", mimeType, "-o")
            LinuxDisplayServer.HEADLESS -> null
        }

    /**
     * Builds the event-driven Wayland clipboard watcher argv (`wl-paste --type <mime> --watch ...`)
     * for zero-CPU-idle clipboard change notification.
     */
    fun buildWaylandWatchCommand(
        mimeType: String = ClipboardMime.TEXT_PLAIN,
        onChangeCommand: List<String> = listOf("true"),
    ): List<String> {
        require(onChangeCommand.isNotEmpty()) { "onChangeCommand must not be empty" }
        return listOf("wl-paste", "--type", mimeType, "--watch") + onChangeCommand
    }

    /**
     * Parses an RFC 2483 `text/uri-list` or GNOME/GTK `x-special/gnome-copied-files`
     * clipboard payload (produced when a user presses `Ctrl+C` on files in KDE Dolphin,
     * GNOME Files/Nautilus, or Thunar) into existing local [File]s.
     */
    fun extractCopiedFilesFromUriList(rawPayload: String): List<File> {
        val result = mutableListOf<File>()
        for (rawLine in rawPayload.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.equals("copy", ignoreCase = true) || line.equals("cut", ignoreCase = true)) continue
            if (line.startsWith("file://")) {
                val file = runCatching { File(java.net.URI(line)) }.getOrNull()
                if (file != null && file.exists()) {
                    result.add(file)
                }
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    /**
     * Polls the Linux clipboard for copied file URIs (`text/uri-list` or
     * `x-special/gnome-copied-files`).
     */
    fun pollCopiedFilesFromClipboard(): List<File> = synchronized(lock) {
        val display = displayServerProvider()
        if (display == LinuxDisplayServer.HEADLESS) return emptyList()
        val uriClip = readSystemClipboard(display, ClipboardMime.URI_LIST)
            ?: readSystemClipboard(display, "x-special/gnome-copied-files")
            ?: return emptyList()
        return extractCopiedFilesFromUriList(uriClip.bytes.decodeToString())
    }

    /**
     * Builds the discrete argv command to clear a sensitive clipboard entry (`wl-copy --clear`
     * on Wayland or `xclip -selection clipboard -i /dev/null` on X11).
     */
    fun buildClearClipboardCommand(display: LinuxDisplayServer = displayServerProvider()): List<String>? =
        when (display) {
            LinuxDisplayServer.WAYLAND -> listOf("wl-copy", "--clear")
            LinuxDisplayServer.X11 -> listOf("xclip", "-selection", "clipboard", "-i", "/dev/null")
            LinuxDisplayServer.HEADLESS -> null
        }

    /**
     * Scrubs the system clipboard if and only if its current `text/plain` content still
     * hashes to [expectedSha256Hex], preventing accidental erasure if the user has
     * copied something new in the meantime.
     */
    fun scrubIfUnchanged(expectedSha256Hex: String): Boolean = synchronized(lock) {
        val display = displayServerProvider()
        if (display == LinuxDisplayServer.HEADLESS) return false
        val current = readSystemClipboard(display, ClipboardMime.TEXT_PLAIN) ?: return false
        if (!current.sha256Hex.equals(expectedSha256Hex, ignoreCase = true)) {
            return false
        }
        val clearCmd = buildClearClipboardCommand(display) ?: return false
        return try {
            val res = executor.execute(clearCmd)
            res.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun readSystemClipboard(display: LinuxDisplayServer, mimeType: String): ClipboardItem? {
        val cmd = buildReadCommand(display, mimeType) ?: return null
        return try {
            val res = executor.execute(command = cmd)
            if (res.exitCode != 0 || res.stdout.isEmpty()) {
                null
            } else {
                ClipboardItem(
                    mime = mimeType,
                    bytes = res.stdout,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Dispatches incoming Android notifications ([NotificationPostInfo]) to the
 * Linux Freedesktop notification daemon via `notify-send` or `gdbus`
 * (`org.freedesktop.Notifications.Notify` with interactive action buttons).
 */
class LinuxNotificationBridge(
    private val executor: BinaryCommandExecutor = SystemBinaryCommandExecutor,
    private val notifySendBinary: String = "notify-send",
    private val gdbusBinary: String = "gdbus",
    private val defaultExpireMs: Int = 8_000,
) {
    /**
     * Builds the exact `notify-send` argv list using `--` before positional
     * summary/body arguments so notification titles starting with `-` can never
     * be interpreted as CLI flags.
     */
    fun buildNotifySendCommand(
        notif: NotificationPostInfo,
        urgency: String = "normal",
    ): List<String> {
        val safeAppName = notif.appName.ifBlank { notif.packageName }.take(64)
        val safeTitle = notif.title.ifBlank { safeAppName }.take(200)
        val safeBody = notif.body.take(1_000)
        return listOf(
            notifySendBinary,
            "--app-name=ec0 ($safeAppName)",
            "--urgency=$urgency",
            "--expire-time=$defaultExpireMs",
            "--category=device",
            "--",
            safeTitle,
            safeBody,
        )
    }

    /**
     * Builds a `gdbus call --session` argv list invoking `org.freedesktop.Notifications.Notify`
     * with interactive action buttons (e.g. `["dismiss", "Dismiss", "reply", "Reply"]`).
     */
    fun buildGdbusNotifyCommand(
        notif: NotificationPostInfo,
        replacesId: Int = 0,
    ): List<String> {
        val safeAppName = sanitizeGVariantText(notif.appName.ifBlank { notif.packageName }.take(64))
        val safeTitle = sanitizeGVariantText(notif.title.ifBlank { safeAppName }.take(200))
        val safeBody = sanitizeGVariantText(notif.body.take(1_000))
        val actionPairs = mutableListOf<String>()
        actionPairs.add("'dismiss'")
        actionPairs.add("'Dismiss'")
        if (notif.canReply) {
            actionPairs.add("'reply'")
            actionPairs.add("'Reply'")
        }
        for (act in notif.actions) {
            val clean = sanitizeGVariantText(act.take(32))
            if (clean.isNotBlank() && clean != "dismiss" && clean != "reply") {
                actionPairs.add("'$clean'")
                actionPairs.add("'$clean'")
            }
        }
        val actionsVariant = "[${actionPairs.joinToString(", ")}]"
        return listOf(
            gdbusBinary,
            "call",
            "--session",
            "--dest",
            "org.freedesktop.Notifications",
            "--object-path",
            "/org/freedesktop/Notifications",
            "--method",
            "org.freedesktop.Notifications.Notify",
            "ec0 ($safeAppName)",
            replacesId.coerceAtLeast(0).toString(),
            "phone",
            safeTitle,
            safeBody,
            actionsVariant,
            "{}",
            defaultExpireMs.toString(),
        )
    }

    /**
     * Parses a `gdbus monitor` line for `org.freedesktop.Notifications.ActionInvoked (uint32 id, string action_key)`.
     */
    fun parseDbusActionInvokedSignal(line: String): Pair<Int, String>? {
        if (!line.contains("ActionInvoked")) return null
        val regex = Regex("""\(uint32\s+(\d+),\s*'([^']+)'\)""")
        val match = regex.find(line) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null
        val actionKey = match.groupValues[2]
        return id to actionKey
    }

    fun showDesktopNotification(
        notif: NotificationPostInfo,
        urgency: String = "normal",
    ): Boolean {
        val cmd = buildNotifySendCommand(notif, urgency)
        return try {
            val res = executor.execute(cmd)
            res.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeGVariantText(input: String): String =
        input.replace("\\", " ").replace("'", " ").replace("\n", " ").trim()
}
