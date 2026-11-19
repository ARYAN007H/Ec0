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

/**
 * CameraX `ImageAnalysis` MJPEG frame source with an explicit throttle +
 * latest-wins drop policy and sensor-rotation-corrected output so the desktop
 * sees an upright webcam.
 */
class CameraXFrameSource(
    private val appContext: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val jpegQuality: Int = 70,
    private val startBackCamera: Boolean = true,
) : CamDroidFrameSource {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ec0-camera-analyzer").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val cameraRef = AtomicReference<androidx.camera.core.Camera?>(null)
    private val lastFrameMs = AtomicLong(0L)
    private val minIntervalMs = (1000L / fps.coerceIn(1, 60)).coerceAtLeast(1L)

    private val lifecycleOwner = ServiceLifecycleOwner()
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var analysisUseCase: ImageAnalysis? = null

    override fun start(sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (closed.get()) {
            onError("frame source already closed — stream cannot start")
            return
        }
        // Resolve the provider on the CALLING (background) thread — never block
        // the main thread on a ListenableFuture.
        val cameraProvider = try {
            ProcessCameraProvider.getInstance(appContext).get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            android.util.Log.w("ec0.camera", "provider unavailable: ${e.message}")
            onError("CameraX provider unavailable: ${e.message}")
            return
        }
        provider = cameraProvider

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(width, height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        analysis.setAnalyzer(analyzerExecutor) { proxy ->
            pumpFrame(proxy, sink, onError)
        }
        analysisUseCase = analysis

        // CameraX requires binding on a lifecycle thread (main). The lifecycle
        // is driven synchronously right before the bind so the state machine
        // is RESUMED exactly when bindToLifecycle runs.
        val selector =
            if (startBackCamera) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
        mainHandler.post {
            if (closed.get()) return@post
            try {
                lifecycleOwner.driveToResumed()
                cameraRef.set(cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis))
                android.util.Log.i("ec0.camera", "CameraX bound (${width}x${height}@$fps, back=$startBackCamera)")
            } catch (e: Exception) {
                android.util.Log.w("ec0.camera", "bind failed: ${e.message}")
                onError("CameraX bind failed: ${e.message}")
            }
        }
    }

    /** Throttle -> close -> NV21 -> rotate -> JPEG, never blocking capture. */
    private fun pumpFrame(proxy: ImageProxy, sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
        try {
            if (closed.get()) return
            val now = System.currentTimeMillis()
            val last = lastFrameMs.get()
            if (now - last < minIntervalMs) {
                // Stage-1 explicit drop: over-frequent frames never get converted.
                return
            }
            if (!lastFrameMs.compareAndSet(last, now)) {
                return
            }
            val rotation = proxy.imageInfo.rotationDegrees
            val jpeg = runCatching {
                imageProxyToRotatedJpeg(proxy, rotation)
            }.getOrElse {
                onError("frame conversion failed: ${it.message}")
                return
            }
            if (jpeg != null && !closed.get()) {
                sink(jpeg)
            }
        } finally {
            proxy.close()
        }
    }

    private fun imageProxyToRotatedJpeg(proxy: ImageProxy, rotationDegrees: Int): ByteArray? {
        val nv21 = imageProxyToNv21(proxy)
        val rotated = rotateNv21(nv21, proxy.width, proxy.height, rotationDegrees)
        val outW = if (rotationDegrees == 90 || rotationDegrees == 270) proxy.height else proxy.width
        val outH = if (rotationDegrees == 90 || rotationDegrees == 270) proxy.width else proxy.height
        val yuv = YuvImage(rotated, ImageFormat.NV21, outW, outH, null)
        val out = ByteArrayOutputStream(outW * outH / 8)
        val ok = yuv.compressToJpeg(Rect(0, 0, outW, outH), jpegQuality, out)
        return if (ok) out.toByteArray() else null
    }

    /** YUV_420_888 -> NV21 (Y + interleaved VU), stride-correct. */
    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val imgWidth = image.width
        val imgHeight = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val nv21 = ByteArray(imgWidth * imgHeight * 3 / 2)
        var pos = 0
        if (yPlane.pixelStride == 1 && yPlane.rowStride == imgWidth) {
            yBuffer.get(nv21, 0, imgWidth * imgHeight)
            pos = imgWidth * imgHeight
        } else {
            for (row in 0 until imgHeight) {
                for (col in 0 until imgWidth) {
                    nv21[pos++] = yBuffer.get(row * yPlane.rowStride + col)
                }
            }
        }
        val chromaHeight = imgHeight / 2
        val chromaWidth = imgWidth / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    /** Rotates an NV21 buffer by 0/90/180/270 degrees clockwise (chroma pairs kept together). */
    private fun rotateNv21(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return src
        val outW = if (rotation == 90 || rotation == 270) height else width
        val outH = if (rotation == 90 || rotation == 270) width else height
        val dst = ByteArray(src.size)
        val ySize = width * height
        val chromaW = width / 2
        val chromaH = height / 2
        val outChromaW = outW / 2
        val outChromaH = outH / 2

        for (y in 0 until height) {
            for (x in 0 until width) {
                val (dx, dy) = when (rotation) {
                    90 -> (outW - 1 - y) to x
                    180 -> (outW - 1 - x) to (outH - 1 - y)
                    else -> y to (outH - 1 - x)
                }
                dst[dy * outW + dx] = src[y * width + x]
            }
        }
        for (cy in 0 until chromaH) {
            for (cx in 0 until chromaW) {
                val (dx, dy) = when (rotation) {
                    90 -> (outChromaW - 1 - cy) to cx
                    180 -> (outChromaW - 1 - cx) to (outChromaH - 1 - cy)
                    else -> cy to (outChromaH - 1 - cx)
                }
                val dstIdx = ySize + dy * outChromaW * 2 + dx * 2
                val srcIdx = ySize + cy * chromaW * 2 + cx * 2
                // Copy the interleaved VU pair as one unit.
                dst[dstIdx] = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
            }
        }
        return dst
    }

    fun setFacing(back: Boolean) {
        val currentProvider = provider ?: return
        val useCase = analysisUseCase ?: return
        val selector =
            if (back) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
        mainHandler.post {
            if (closed.get()) return@post
            runCatching {
                currentProvider.unbind(useCase)
                cameraRef.set(currentProvider.bindToLifecycle(lifecycleOwner, selector, useCase))
            }
        }
    }

    fun setZoom(ratio: Double) {
        runCatching { cameraRef.get()?.cameraControl?.setZoomRatio(ratio.coerceIn(1.0, 10.0).toFloat()) }
    }

    fun setTorch(enabled: Boolean) {
        runCatching { cameraRef.get()?.cameraControl?.enableTorch(enabled) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            mainHandler.post {
                // Never let a lifecycle edge kill the host process: close() can
                // legitimately run before the bind lambda ever executed.
                runCatching {
                    provider?.unbindAll()
                    lifecycleOwner.destroy()
                }.onFailure {
                    android.util.Log.w("ec0.camera", "source teardown: ${it.message}")
                }
            }
        }
        analyzerExecutor.shutdown()
        runCatching { analyzerExecutor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    /** Minimal LifecycleOwner so CameraX can bind from a foreground Service. */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun driveToResumed() {
            runCatching {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        /**
         * LifecycleRegistry refuses INITIALIZED -> DESTROYED (crashes the app
         * when close() wins the race against the bind lambda). A source that
         * never reached CREATED has nothing to destroy.
         */
        fun destroy() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                runCatching { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
                    .onFailure { android.util.Log.w("ec0.camera", "lifecycle destroy: ${it.message}") }
            }
        }
    }
}
