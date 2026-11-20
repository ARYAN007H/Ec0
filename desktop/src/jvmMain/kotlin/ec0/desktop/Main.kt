/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.desktop

import ec0.core.engine.linux.Ec0CliCommand
import ec0.core.engine.linux.LinuxDesktopIntegrationGenerator
import ec0.core.engine.services.LocalDaemonIpcClient
import ec0.desktop.daemon.Ec0HeadlessDaemon
import ec0.desktop.ui.runApp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val cmd = try {
        Ec0CliCommand.parse(args)
    } catch (e: Exception) {
        System.err.println("ec0: ${e.message}")
        exitProcess(2)
    }

    when (cmd) {
        Ec0CliCommand.LaunchGui -> runApp()
        Ec0CliCommand.RunDaemon -> Ec0HeadlessDaemon.runHeadless()
        Ec0CliCommand.Doctor -> Ec0HeadlessDaemon.runDoctorCli()
        Ec0CliCommand.DaemonStatus -> exitProcess(Ec0HeadlessDaemon.sendIpcCommandAndPrint("status"))
        Ec0CliCommand.AutoTypeOtp -> exitProcess(Ec0HeadlessDaemon.sendIpcCommandAndPrint("auto_type_otp"))
        is Ec0CliCommand.MountVfs -> exitProcess(
            Ec0HeadlessDaemon.sendIpcCommandAndPrint(
                command = if (cmd.unmount) "unmount_vfs" else "mount_vfs",
            ),
        )
        is Ec0CliCommand.SetFocus -> exitProcess(
            Ec0HeadlessDaemon.sendIpcCommandAndPrint(
                command = "set_focus",
                payload = buildJsonObject { put("mode", cmd.mode) },
            ),
        )
        is Ec0CliCommand.HandoffTab -> exitProcess(
            Ec0HeadlessDaemon.sendHandoffTabViaIpc(
                url = cmd.url,
                title = cmd.title,
                mediaPositionSec = cmd.mediaPositionSec,
            ),
        )
        is Ec0CliCommand.SendFiles -> {
            val files = cmd.paths.map(::File).filter { it.exists() }
            if (files.isEmpty()) {
                System.err.println("ec0: no existing files provided to --send")
                exitProcess(1)
            }
            val handedOff = LocalDaemonIpcClient.trySendFilesToRunningDaemon(files.map { it.absolutePath })
            if (handedOff) {
                println("Sent ${files.size} item(s) to running ec0 daemon.")
                exitProcess(0)
            } else {
                // Launch GUI if daemon isn't running yet
                runApp()
            }
        }
        is Ec0CliCommand.InstallDesktopIntegration -> {
            val target = cmd.dataHomeDir?.let(::File) ?: LinuxDesktopIntegrationGenerator.defaultXdgDataHome()
            val installed = LinuxDesktopIntegrationGenerator.install(
                xdgDataHome = target,
                binaryExecPath = cmd.binaryExecPath,
            )
            println("Installed: ${installed.freedesktopEntry.path}, ${installed.dolphinServiceMenu.path}, ${installed.nautilusScript.path}")
        }
        is Ec0CliCommand.StartMirror,
        is Ec0CliCommand.StartCamera -> {
            // Route into interactive GUI session
            runApp()
        }
    }
}
