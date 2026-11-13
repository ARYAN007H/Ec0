/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Linux AirDrop CLI argument parser (`ec0 --send <path>`, `ec0 --mirror`,
 * `ec0 --camera`, `ec0 --doctor`, `ec0 --install-desktop-integration`) and
 * Freedesktop / Dolphin / Nautilus / Nemo context-menu generator.
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CameraBackend
import java.io.File

sealed class Ec0CliCommand {
    /** Launch the default Compose Desktop GUI window. */
    data object LaunchGui : Ec0CliCommand()

    /** AirDrop-style file/folder send from Linux CLI or file manager context menu. */
    data class SendFiles(
        val paths: List<String>,
        val targetPeer: String? = null,
    ) : Ec0CliCommand()

    /** 1-click Phone Screen Mirroring (`scrcpy`). */
    data class StartMirror(
        val serialOrHost: String,
        val adbPort: Int = 5555,
        val maxFps: Int = 60,
        val bitrateKbps: Int = 8_000,
    ) : Ec0CliCommand()

    /** 1-click Continuity Camera (`CamDroid` native `:4747` or `scrcpy` V4L2 fallback). */
    data class StartCamera(
        val host: String,
        val port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        val backend: CameraBackend = CameraBackend.CAMDROID_NATIVE,
        val v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        val resolution: String = "1080p",
        val fps: Int = 30,
        val codec: String = "h264",
        val frontCamera: Boolean = false,
        val audio: Boolean = true,
    ) : Ec0CliCommand()

    /** Install Freedesktop `.desktop`, KDE Dolphin ServiceMenu, and GNOME Nautilus scripts. */
    data class InstallDesktopIntegration(
        val dataHomeDir: String? = null,
        val binaryExecPath: String = "ec0",
    ) : Ec0CliCommand()

    /** Run Linux system integration readiness diagnostics. */
    data object Doctor : Ec0CliCommand()

    companion object {
        fun parse(args: Array<String>): Ec0CliCommand = parse(args.toList())

        fun parse(args: List<String>): Ec0CliCommand {
            if (args.isEmpty()) return LaunchGui
            return when (val first = args[0].trim()) {
                "--send", "-s" -> {
                    val paths = mutableListOf<String>()
                    var peer: String? = null
                    var i = 1
                    while (i < args.size) {
                        when (args[i]) {
                            "--peer", "-p" -> {
                                require(i + 1 < args.size) { "Missing value after ${args[i]}" }
                                peer = args[i + 1]
                                i += 2
                            }
                            else -> {
                                paths.add(args[i])
                                i += 1
                            }
                        }
                    }
                    require(paths.isNotEmpty()) { "Usage: ec0 --send <file-or-dir...> [--peer <deviceId>]" }
                    SendFiles(paths = paths, targetPeer = peer)
                }

                "--mirror", "-m" -> {
                    var host: String? = null
                    var adbPort = 5555
                    var fps = 60
                    var bitrateKbps = 8_000
                    var i = 1
                    while (i < args.size) {
                        when (args[i]) {
                            "--host", "--serial" -> {
                                require(i + 1 < args.size) { "Missing value after ${args[i]}" }
                                val raw = args[i + 1]
                                if (':' in raw) {
                                    host = raw.substringBeforeLast(':')
                                    adbPort = raw.substringAfterLast(':').toIntOrNull() ?: 5555
                                } else {
                                    host = raw
                                }
                                i += 2
                            }
                            "--port" -> {
                                require(i + 1 < args.size) { "Missing value after --port" }
                                adbPort = args[i + 1].toInt()
                                i += 2
                            }
                            "--fps" -> {
                                require(i + 1 < args.size) { "Missing value after --fps" }
                                fps = args[i + 1].toInt()
                                i += 2
                            }
                            "--bitrate-kbps" -> {
                                require(i + 1 < args.size) { "Missing value after --bitrate-kbps" }
                                bitrateKbps = args[i + 1].toInt()
                                i += 2
                            }
                            else -> throw IllegalArgumentException("Unknown --mirror option: ${args[i]}")
                        }
                    }
                    val resolvedHost = requireNotNull(host) { "Usage: ec0 --mirror --host <phone-ip> [--port 5555]" }
                    StartMirror(
                        serialOrHost = resolvedHost,
                        adbPort = adbPort,
                        maxFps = fps,
                        bitrateKbps = bitrateKbps,
                    )
                }

                "--camera", "-c" -> {
                    var host: String? = null
                    var port = CamDroidConstants.DEFAULT_TCP_PORT
                    var backend = CameraBackend.CAMDROID_NATIVE
                    var v4l2Device = CamDroidConstants.DEFAULT_V4L2_DEVICE
                    var resolution = "1080p"
                    var fps = 30
                    var codec = "h264"
                    var front = false
                    var audio = true
                    var i = 1
                    while (i < args.size) {
                        when (args[i]) {
                            "--host" -> {
                                require(i + 1 < args.size) { "Missing value after --host" }
                                host = args[i + 1]
                                i += 2
                            }
                            "--port" -> {
                                require(i + 1 < args.size) { "Missing value after --port" }
                                port = args[i + 1].toInt()
                                i += 2
                            }
                            "--backend" -> {
                                require(i + 1 < args.size) { "Missing value after --backend" }
                                backend = when (args[i + 1].lowercase()) {
                                    "camdroid", "camdroid_native" -> CameraBackend.CAMDROID_NATIVE
                                    "scrcpy", "scrcpy_v4l2" -> CameraBackend.SCRCPY_V4L2_FALLBACK
                                    else -> throw IllegalArgumentException("Unsupported camera backend: ${args[i + 1]}")
                                }
                                i += 2
                            }
                            "--v4l2-device" -> {
                                require(i + 1 < args.size) { "Missing value after --v4l2-device" }
                                v4l2Device = args[i + 1]
                                i += 2
                            }
                            "--res", "--resolution" -> {
                                require(i + 1 < args.size) { "Missing value after ${args[i]}" }
                                resolution = args[i + 1]
                                i += 2
                            }
                            "--fps" -> {
                                require(i + 1 < args.size) { "Missing value after --fps" }
                                fps = args[i + 1].toInt()
                                i += 2
                            }
                            "--codec" -> {
                                require(i + 1 < args.size) { "Missing value after --codec" }
                                codec = args[i + 1]
                                i += 2
                            }
                            "--front" -> {
                                front = true
                                i += 1
                            }
                            "--no-audio" -> {
                                audio = false
                                i += 1
                            }
                            else -> throw IllegalArgumentException("Unknown --camera option: ${args[i]}")
                        }
                    }
                    val resolvedHost = requireNotNull(host) { "Usage: ec0 --camera --host <phone-ip>" }
                    StartCamera(
                        host = resolvedHost,
                        port = port,
                        backend = backend,
                        v4l2DevicePath = v4l2Device,
                        resolution = resolution,
                        fps = fps,
                        codec = codec,
                        frontCamera = front,
                        audio = audio,
                    )
                }

                "--install-desktop-integration" -> {
                    var targetDir: String? = null
                    var binPath = "ec0"
                    var i = 1
                    while (i < args.size) {
                        when (args[i]) {
                            "--target-dir" -> {
                                require(i + 1 < args.size) { "Missing value after --target-dir" }
                                targetDir = args[i + 1]
                                i += 2
                            }
                            "--exec" -> {
                                require(i + 1 < args.size) { "Missing value after --exec" }
                                binPath = args[i + 1]
                                i += 2
                            }
                            else -> throw IllegalArgumentException("Unknown option: ${args[i]}")
                        }
                    }
                    InstallDesktopIntegration(dataHomeDir = targetDir, binaryExecPath = binPath)
                }

                "--doctor" -> Doctor
                else -> throw IllegalArgumentException("Unrecognized CLI flag: $first")
            }
        }
    }
}

data class InstalledDesktopFiles(
    val freedesktopEntry: File,
    val dolphinServiceMenu: File,
    val nautilusScript: File,
)

/**
 * Generates and installs native Linux file-manager "AirDrop" context-menu
 * integrations for Freedesktop (`~/.local/share/applications`), KDE Dolphin
 * (`~/.local/share/kio/servicemenus`), and GNOME Files / Nautilus
 * (`~/.local/share/nautilus/scripts`).
 */
object LinuxDesktopIntegrationGenerator {
    fun buildFreedesktopEntry(binaryExecPath: String = "ec0"): String =
        """
        [Desktop Entry]
        Type=Application
        Version=1.0
        Name=ec0 Continuity Hub
        GenericName=Cross-Device Continuity & AirDrop
        Comment=Send files, mirror screen, sync clipboard, and stream CamDroid Continuity Camera
        Exec=$binaryExecPath --send %F
        Icon=phone
        Terminal=false
        Categories=Network;FileTransfer;Utility;
        MimeType=inode/directory;application/octet-stream;image/png;image/jpeg;video/mp4;text/plain;
        StartupNotify=true
        """.trimIndent() + "\n"

    fun buildDolphinServiceMenu(binaryExecPath: String = "ec0"): String =
        """
        [Desktop Entry]
        Type=Service
        X-KDE-ServiceTypes=KonqPopupMenu/Plugin
        MimeType=all/all;
        Actions=Ec0AirDropSend;
        X-KDE-Priority=TopLevel

        [Desktop Action Ec0AirDropSend]
        Name=Send to Android via ec0 AirDrop
        Icon=document-send
        Exec=$binaryExecPath --send %F
        """.trimIndent() + "\n"

    fun buildNautilusScript(binaryExecPath: String = "ec0"): String =
        """
        #!/usr/bin/env sh
        # GNOME Files (Nautilus) AirDrop integration for ec0
        set -eu
        if [ -n "${'$'}{NAUTILUS_SCRIPT_SELECTED_FILE_PATHS:-}" ]; then
            IFS='
        '
            set -- ${'$'}NAUTILUS_SCRIPT_SELECTED_FILE_PATHS
            exec "$binaryExecPath" --send "${'$'}@"
        else
            exec "$binaryExecPath" --send "${'$'}@"
        fi
        """.trimIndent() + "\n"

    /**
     * Writes all three desktop integration files under [xdgDataHome] (defaults
     * to `$XDG_DATA_HOME` or `~/.local/share`) and marks the Nautilus script executable.
     */
    fun install(
        xdgDataHome: File = defaultXdgDataHome(),
        binaryExecPath: String = "ec0",
    ): InstalledDesktopFiles {
        val appsDir = File(xdgDataHome, "applications").apply { mkdirs() }
        val dolphinDir = File(xdgDataHome, "kio/servicemenus").apply { mkdirs() }
        val nautilusDir = File(xdgDataHome, "nautilus/scripts").apply { mkdirs() }

        val desktopFile = File(appsDir, "ec0-airdrop.desktop")
        desktopFile.writeText(buildFreedesktopEntry(binaryExecPath))

        val dolphinFile = File(dolphinDir, "ec0-airdrop.desktop")
        dolphinFile.writeText(buildDolphinServiceMenu(binaryExecPath))

        val nautilusFile = File(nautilusDir, "Send via ec0 AirDrop")
        nautilusFile.writeText(buildNautilusScript(binaryExecPath))
        nautilusFile.setExecutable(true)

        return InstalledDesktopFiles(
            freedesktopEntry = desktopFile,
            dolphinServiceMenu = dolphinFile,
            nautilusScript = nautilusFile,
        )
    }

    fun defaultXdgDataHome(): File {
        val xdg = System.getenv("XDG_DATA_HOME")?.trim().orEmpty()
        return if (xdg.isNotEmpty()) {
            File(xdg)
        } else {
            File(System.getProperty("user.home", "."), ".local/share")
        }
    }
}
