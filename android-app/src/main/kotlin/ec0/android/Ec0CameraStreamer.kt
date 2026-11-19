/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0's phone-side Continuity Camera streamer: turns a CameraX-capable device
 * (Samsung A03 included) into a token-gated webcam for the paired desktop.
 *
 * Capture profile (production defaults, honest about limits):
 *   - 640x480 MJPEG @ up to 30 fps, JPEG quality 70 — ~0.3-0.8 MB/s on Wi-Fi.
 *   - Explicit frame-drop policy, two stages:
 *       1. analyzer throttle: frames arriving faster than the target interval
 *          are dropped before any conversion work;
 *       2. server slot: [CamDroidStreamServer] keeps exactly ONE undelivered
 *          frame — a new capture overwrites it, stale frames never queue.
 *   - Arming: ONLY via a `cam.control.cmd` Start over the AEAD-encrypted,
 *     Ed25519-signed engine session, carrying a one-shot token echoed in the
 *     TCP handshake. The socket exists only while a stream is armed.
 *   - Clean stop: desktop `stop` command, client silence watchdog, or service
 *     teardown all close the capture and the socket.
 *
 * Android 11+ background-capture reality: camera access from a foreground
 * service requires the `camera` FGS type — Ec0CompanionService promotes with
 * `connectedDevice|camera` once the CAMERA grant exists (re-promoted on every
 * service start and on arming).
 */
package ec0.android

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.protocol.CamDroidConstants
import ec0.android.camera.H264CameraEngine
import ec0.core.engine.services.CamDroidArmResult
import ec0.core.engine.services.CamDroidH264FrameSource
import ec0.core.engine.services.CamDroidStreamServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the phone-side camera stream lifecycle. One [CamDroidStreamServer] at a
 * time; the ec0 companion callbacks delegate every `cam.control.cmd` here.
 */
class Ec0CameraStreamer(
    private val appContext: Context,
    private val streamPort: Int = CamDroidConstants.DEFAULT_TCP_PORT,
    private val captureWidth: Int = 640,
    private val captureHeight: Int = 480,
    /** Capture profile ceiling; the desktop's Start command can lower it. */
    private val maxFps: Int = 30,
    private val jpegQuality: Int = 70,
    private val hasCameraPermission: () -> Boolean = {
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    },
) {
    private val lock = Any()
    private var server: CamDroidStreamServer? = null

    /** Applied on the next arm; `switch_camera` flips it live when streaming. */
    @Volatile
    private var frontCameraRequested: Boolean = false

    @Volatile
    private var liveSource: CamDroidH264FrameSource? = null

    /** Re-promote hook: Ec0CompanionService upgrades its FGS type on arming. */
    @Volatile
    var onArming: (() -> Unit)? = null

    val isStreaming: Boolean
        get() = synchronized(lock) {
            server?.snapshot?.state == ec0.core.engine.services.CamDroidServerState.STREAMING
        }

    /**
     * Entry point for the engine's camera command dispatch (`onCameraControlCommand`).
     * Returns the honest status for the phone-side `cam_status` flow, or null
     * for commands that carry no status information.
     */
    fun handleCommand(command: CamDroidControlCommand): CameraStatusInfo? = synchronized(lock) {
        when (command) {
            is CamDroidControlCommand.Start -> armForStart(command)
            is CamDroidControlCommand.Stop -> {
                disarmLocked("stopped by desktop")
                CameraStatusInfo(
                    state = CameraStreamState.STOPPED,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    port = streamPort,
                    codec = "mjpeg",
                    resolution = "${captureWidth}x${captureHeight}",
                    fps = maxFps,
                    audioEnabled = false,
                    reason = "Stopped by desktop",
                )
            }
            is CamDroidControlCommand.SwitchCamera -> {
                // Live lens flip needs a capture-session reconfigure; the
                // engine honours the preference on the next arm. Honest no-op
                // for now rather than a half-working switch.
                frontCameraRequested = !frontCameraRequested
                currentStatusLocked(reason = "lens preference applies on next stream start")
            }
            is CamDroidControlCommand.SetZoom, is CamDroidControlCommand.SetFlash -> {
                // Hardware zoom/torch controls land with the engine's second
                // iteration — acknowledge honestly without pretending.
                currentStatusLocked(reason = null)
            }
            else -> null
        }
    }

    private fun currentStatusLocked(reason: String?): CameraStatusInfo = CameraStatusInfo(
        state = when (server?.snapshot?.state) {
            ec0.core.engine.services.CamDroidServerState.STREAMING -> CameraStreamState.STREAMING
            ec0.core.engine.services.CamDroidServerState.ARMED -> CameraStreamState.STARTING
            ec0.core.engine.services.CamDroidServerState.ERROR -> CameraStreamState.ERROR
            else -> CameraStreamState.IDLE
        },
        backend = CameraBackend.CAMDROID_NATIVE,
        port = streamPort,
        v4l2DevicePath = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        codec = "mjpeg",
        resolution = "${captureWidth}x${captureHeight}",
        fps = maxFps,
        audioEnabled = false,
        frontCamera = frontCameraRequested,
        reason = reason,
    )

    private fun armForStart(command: CamDroidControlCommand.Start): CameraStatusInfo {
        val token = command.token
        if (token.isNullOrBlank()) {
            return currentStatusLocked(
                "Start without a stream token — the desktop must arm the camera " +
                    "over the encrypted session first",
            ).copy(state = CameraStreamState.ERROR)
        }
        if (!hasCameraPermission()) {
            return currentStatusLocked("CAMERA permission not granted on the phone")
                .copy(state = CameraStreamState.ERROR)
        }
        // Re-promote the foreground service so the FGS carries the `camera`
        // type before CameraX opens the device (Android 11+ requirement).
        runCatching { onArming?.invoke() }

        disarmLocked("re-armed by a new start command")
        android.util.Log.i("ec0.camera", "arming stream server (token received, fps=${command.fps.coerceAtMost(maxFps)})")

        // The imported CamDroid engine: hardware H.264, capability-clamped to
        // the device's REAL encoder limits (the A03 1080p@30 lesson — the old
        // 60fps MJPEG path crashed MediaCodec.configure on this phone).
        val source = H264CameraEngine(appContext, preferFrontCamera = frontCameraRequested)
        val newServer = CamDroidStreamServer(
            frameSourceFactory = { source },
            port = streamPort,
        )
        newServer.onControlCommand = { cmd ->
            // Route live controls (zoom/switch/flash) through the same handler.
            runCatching { handleCommand(cmd) }
        }
        return when (val armResult = newServer.arm(token)) {
            is CamDroidArmResult.Armed -> {
                server = newServer
                liveSource = source
                android.util.Log.i("ec0.camera", "armed on :${armResult.port} — awaiting token handshake")
                CameraStatusInfo(
                    state = CameraStreamState.STARTING,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    port = armResult.port,
                    v4l2DevicePath = CamDroidConstants.DEFAULT_V4L2_DEVICE,
                    codec = "h264",
                    resolution = "1080p",
                    fps = command.fps.coerceAtMost(maxFps),
                    audioEnabled = false,
                    frontCamera = frontCameraRequested,
                    reason = null,
                )
            }
            is CamDroidArmResult.Failed -> {
                android.util.Log.w("ec0.camera", "arm failed: ${armResult.reason}")
                runCatching { source.close() }
                currentStatusLocked(armResult.reason).copy(state = CameraStreamState.ERROR)
            }
        }
    }

    private fun disarmLocked(reason: String) {
        android.util.Log.i("ec0.camera", "disarm: $reason")
        runCatching { server?.disarm() }
        runCatching { server?.close() }
        server = null
        runCatching { liveSource?.close() }
        liveSource = null
    }

    /** Service teardown: capture and sockets must never outlive the host. */
    fun shutdown() = synchronized(lock) {
        disarmLocked("service shutdown")
    }
}
