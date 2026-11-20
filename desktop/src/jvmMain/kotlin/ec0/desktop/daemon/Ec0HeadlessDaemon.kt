/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #10 — Headless Split Architecture (`ec0d` / `ec0 --daemon`):
 *   Runs the full encrypted Continuity Engine, clipboard/notification sync,
 *   PipeWire audio & meeting focus monitors, WebDAV VFS mount, and loopback
 *   authenticated IPC server with ZERO AWT/Compose/Skiko overhead.
 *   Protected by `ServiceCrashLoopWatchdog` against infinite restart storms.
 */
package ec0.desktop.daemon

import ec0.core.engine.services.DiagnosticSeverity
import ec0.core.engine.services.LocalDaemonIpcClient
import ec0.core.engine.services.ServiceCrashLoopWatchdog
import ec0.core.engine.services.SystemHealthDiagnosticsRunner
import ec0.desktop.continuity.ContinuityHubController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch

object Ec0HeadlessDaemon {
    fun runHeadless() {
        val watchdog = ServiceCrashLoopWatchdog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shutdownLatch = CountDownLatch(1)
        var hub: ContinuityHubController? = null

        Runtime.getRuntime().addShutdownHook(
            Thread({
                runCatching { hub?.close() }
                scope.cancel()
                shutdownLatch.countDown()
            }, "ec0-daemon-shutdown"),
        )

        try {
            hub = ContinuityHubController(scope = scope)
            hub.start(startMdns = true)
            watchdog.recordHealthyStart()
            println("ec0d headless continuity daemon started (port ${hub.node.boundServerPort})")
            shutdownLatch.await()
        } catch (t: Throwable) {
            val backoff = watchdog.recordFailure()
            System.err.println("ec0d fatal error (backoff=${backoff}ms): ${t.message}")
            runCatching { hub?.close() }
            scope.cancel()
            throw t
        }
    }

    fun runDoctorCli() {
        val runner = SystemHealthDiagnosticsRunner()
        val checks = runner.runAllChecks()
        println("ec0 Continuity System Health Diagnostics:")
        for (c in checks) {
            val badge = when (c.severity) {
                DiagnosticSeverity.OK -> "[OK]"
                DiagnosticSeverity.WARN -> "[WARN]"
                DiagnosticSeverity.ERROR -> "[ERR]"
            }
            println("  $badge ${c.title}: ${c.summary}")
            if (c.fixHint.isNotBlank()) {
                println("       Fix: ${c.fixHint}")
            }
        }
    }

    fun sendIpcCommandAndPrint(command: String, payload: kotlinx.serialization.json.JsonObject = buildJsonObject {}): Int {
        val reply = LocalDaemonIpcClient.sendCommandToRunningDaemon(command, payload)
        return if (reply == null) {
            System.err.println("ec0 daemon is not running. Start it with `ec0 --daemon` or launch the ec0 desktop app.")
            1
        } else {
            println(reply)
            0
        }
    }

    fun sendHandoffTabViaIpc(url: String, title: String, mediaPositionSec: Long): Int =
        sendIpcCommandAndPrint(
            command = "handoff_tab",
            payload = buildJsonObject {
                put("url", url)
                put("title", title)
                put("media_position_sec", mediaPositionSec)
            },
        )
}
