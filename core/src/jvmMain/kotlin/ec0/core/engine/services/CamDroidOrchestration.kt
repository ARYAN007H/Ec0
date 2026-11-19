/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Continuity Camera orchestration: ec0 prefers the owner's proven CamDroid
 * stack (hardware-encoded H.264/H.265 up to 4K60, ARYAN007H/CamDroid, MIT)
 * over ec0's in-app MJPEG streamer. When the phone's CamDroid server is
 * reachable on :4747, ec0 ORCHESTRATES — it launches the CamDroid desktop
 * consumer (camdroid-client CLI, then the Studio AppImage) and gets out of
 * the way. The in-app streamer (token-armed over the engine session, with
 * the dial-retry + heartbeat fixes) stays the fallback when CamDroid is not
 * installed or its service is not running.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidConstants

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object CamDroidOrchestration {

    /** True when something listens on [host]:[port] within [timeoutMs]. */
    fun serverReachable(host: String, port: Int, timeoutMs: Long = 600L): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            true
        }
    }.getOrDefault(false)

    /** The connect argument the camdroid-client CLI expects: `IP:PORT`. */
    fun connectArg(host: String, port: Int): String = "$host:$port"

    /**
     * The desktop consumer command for a reachable CamDroid server, or null
     * when none is installed (the caller then honestly reports the gap — it
     * must NOT fall back to the in-app streamer, which would fight CamDroid
     * for the camera device).
     *
     * Preference order:
     *   1. `camdroid-client` on PATH — headless CLI with v4l2loopback output,
     *      the exact pipeline ec0 wants (hardware H.264 -> /dev/video10).
     *   2. The installed CamDroid Studio AppImage (appimagelauncher layout in
     *      ~/Applications) — GUI control room; launched without connect args
     *      (the Tauri shell owns its own connect flow).
     */
    fun resolveDesktopConsumer(
        host: String,
        port: Int,
        homeDir: String = System.getProperty("user.home"),
        pathEnv: String = System.getenv("PATH") ?: "",
    ): List<String>? {
        for (dir in pathEnv.split(":")) {
            if (dir.isBlank()) continue
            val candidate = File(dir, "camdroid-client")
            if (candidate.canExecute()) {
                return listOf(
                    candidate.absolutePath,
                    "--connect", connectArg(host, port),
                    "--device", CamDroidConstants.DEFAULT_V4L2_DEVICE,
                    "--codec", "h264",
                    "--resolution", "1080p",
                    "--fps", "30",
                    "--no-audio",
                )
            }
        }
        val appImage = File(homeDir, "Applications")
            .listFiles { f -> f.name.startsWith("CamDroid") && f.name.endsWith(".appimage") }
            ?.maxByOrNull { it.name } ?: return null
        return listOf(appImage.absolutePath)
    }
}
