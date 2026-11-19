/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0's phone-side Continuity Camera streamer: turns any CameraX-capable Android
 * phone into a 1080p30/60 token-gated virtual webcam for the paired desktop.
 *
 * Key properties:
 *   - Honors desktop-requested profile (`720p`, `1080p`, `1440p`/`2k`, `4k` @ 30/60 fps)
 *     via [parseCameraProfile], defaulting to crisp 1080p30 MJPEG (quality 80).
 *   - Row-slice bulk `ByteBuffer.get` copies + reusable frame buffers on the
 *     single-threaded analyzer executor to keep GC pressure minimal at 1080p.
 *   - Two-stage latest-wins frame drop policy (analyzer throttle + single-slot
 *     server) so latency stays low even over congested Wi-Fi.
 *   - Token-gated arming ONLY via signed `cam.control.cmd` Start over the
 *     AEAD-encrypted engine session.
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
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.services.CamDroidArmResult
import ec0.core.engine.services.CamDroidFrameSource
import ec0.core.engine.services.CamDroidStreamServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Parsed camera capture profile (pure Kotlin, unit-testable without android.jar). */
data class CameraCaptureProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val label: String,
)

/**
 * Resolves a wire resolution string (`"720p"`, `"1080p"`, `"1440p"`, `"2k"`, `"4k"`,
 * `"1920x1080"`, etc.) and requested FPS into concrete dimensions clamped by [maxFpsCeiling].
 */
fun parseCameraProfile(
    resolution: String,
    requestedFps: Int,
    maxFpsCeiling: Int = 60,
): CameraCaptureProfile {
    val norm = resolution.trim().lowercase()
    val (w, h, label) = when (norm) {
        "480p", "640x480" -> Triple(640, 480, "480p")
        "720p", "1280x720" -> Triple(1280, 720, "720p")
        "1080p", "1920x1080" -> Triple(1920, 1080, "1080p")
        "1440p", "2k", "2560x1440" -> Triple(2560, 1440, "1440p")
        "4k", "2160p", "3840x2160" -> Triple(3840, 2160, "4k")
        else -> {
            val match = Regex("^(\\d{3,4})x(\\d{3,4})$").matchEntire(norm)
            if (match != null) {
                val pw = match.groupValues[1].toInt().coerceIn(320, 3840)
                val ph = match.groupValues[2].toInt().coerceIn(240, 2160)
                Triple(pw, ph, "${pw}x${ph}")
            } else {
                Triple(1920, 1080, "1080p")
            }
        }
    }
    val fps = requestedFps.coerceIn(15, maxFpsCeiling.coerceIn(15, 60))
    return CameraCaptureProfile(width = w, height = h, fps = fps, label = label)
}

/**
 * Owns the phone-side camera stream lifecycle. One [CamDroidStreamServer] at a
 * time; the ec0 companion callbacks delegate every `cam.control.cmd` here.
 */
class Ec0CameraStreamer(
    private val appContext: Context,
    private val streamPort: Int = CamDroidConstants.DEFAULT_TCP_PORT,
    private val captureWidth: Int = 1920,
    private val captureHeight: Int = 1080,
    /** Capture profile ceiling; the desktop's Start command selects 30 or 60 fps. */
    private val maxFps: Int = 60,
    private val jpegQuality: Int = 80,
    private val hasCameraPermission: () -> Boolean = {
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    },
) {
    private val lock = Any()
    private var server: CamDroidStreamServer? = null
    private var activeProfile: CameraCaptureProfile = CameraCaptureProfile(
        width = captureWidth,
        height = captureHeight,
        fps = maxFps.coerceAtMost(30),
        label = "${captureWidth}x${captureHeight}",
    )

    @Volatile
    private var frontCameraRequested: Boolean = false

    @Volatile
    private var liveSource: CameraXFrameSource? = null

    /** Re-promote hook: Ec0CompanionService upgrades its FGS type on arming. */
    @Volatile
    var onArming: (() -> Unit)? = null

    val isStreaming: Boolean
        get() = synchronized(lock) {
            server?.snapshot?.state == ec0.core.engine.services.CamDroidServerState.STREAMING
        }

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
                    resolution = "${activeProfile.width}x${activeProfile.height}",
                    fps = activeProfile.fps,
                    audioEnabled = false,
                    reason = "Stopped by desktop",
                )
            }
            is CamDroidControlCommand.SwitchCamera -> {
                frontCameraRequested = !frontCameraRequested
                liveSource?.setFacing(back = !frontCameraRequested)
                currentStatusLocked(reason = null)
            }
            is CamDroidControlCommand.SetZoom -> {
                liveSource?.setZoom(command.zoomX100 / 100.0)
                currentStatusLocked(reason = null).copy(zoomX100 = command.zoomX100)
            }
            is CamDroidControlCommand.SetFlash -> {
                liveSource?.setTorch(command.enabled)
                currentStatusLocked(reason = null).copy(flashEnabled = command.enabled)
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
        resolution = "${activeProfile.width}x${activeProfile.height}",
        fps = activeProfile.fps,
        audioEnabled = false,
        frontCamera = frontCameraRequested,
        reason = reason,
    )

    private fun armForStart(command: CamDroidControlCommand.Start): CameraStatusInfo {
        val token = command.token
        if (token.isNullOrBlank()) {
            return currentStatusLocked(
                "Start without a stream token — desktop must arm camera over encrypted session first",
            ).copy(state = CameraStreamState.ERROR)
        }
        if (!hasCameraPermission()) {
            return currentStatusLocked("CAMERA permission not granted on the phone")
                .copy(state = CameraStreamState.ERROR)
        }
        runCatching { onArming?.invoke() }

        disarmLocked("re-armed by a new start command")
        val profile = parseCameraProfile(
            resolution = command.resolution,
            requestedFps = command.fps,
            maxFpsCeiling = maxFps,
        )
        activeProfile = profile
        android.util.Log.i("ec0.camera", "arming stream server (${profile.width}x${profile.height}@${profile.fps})")

        val source = CameraXFrameSource(
            appContext = appContext,
            width = profile.width,
            height = profile.height,
            fps = profile.fps,
            jpegQuality = jpegQuality,
            startBackCamera = !frontCameraRequested,
        )
        val newServer = CamDroidStreamServer(
            frameSourceFactory = { source },
            port = streamPort,
            width = profile.width,
            height = profile.height,
            targetFps = profile.fps,
            codec = "mjpeg",
        )
        newServer.onControlCommand = { cmd ->
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
                    codec = "mjpeg",
                    resolution = "${profile.width}x${profile.height}",
                    fps = profile.fps,
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

    fun shutdown() = synchronized(lock) {
        disarmLocked("service shutdown")
    }
}

/**
 * CameraX `ImageAnalysis` MJPEG frame source with bulk row-slice YUV→NV21
 * conversion, reusable frame buffers, explicit FPS throttling, and upright
 * sensor-rotation correction.
 */
class CameraXFrameSource(
    private val appContext: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val jpegQuality: Int = 80,
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

    // Reusable scratch buffers accessed exclusively on analyzerExecutor.
    private var nv21Scratch = ByteArray(0)
    private var rotatedScratch = ByteArray(0)
    private var chromaRowV = ByteArray(0)
    private var chromaRowU = ByteArray(0)
    private val jpegOut = ByteArrayOutputStream(256 * 1024)

    private val lifecycleOwner = ServiceLifecycleOwner()
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var analysisUseCase: ImageAnalysis? = null

    override fun start(sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (closed.get()) {
            onError("frame source already closed — stream cannot start")
            return
        }
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

    private fun pumpFrame(proxy: ImageProxy, sink: (ByteArray) -> Unit, onError: (String) -> Unit) {
        try {
            if (closed.get()) return
            val now = System.currentTimeMillis()
            val last = lastFrameMs.get()
            if (now - last < minIntervalMs) return
            if (!lastFrameMs.compareAndSet(last, now)) return

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
        val nv21 = imageProxyToNv21Fast(proxy)
        val rotated = rotateNv21Fast(nv21, proxy.width, proxy.height, rotationDegrees)
        val outW = if (rotationDegrees == 90 || rotationDegrees == 270) proxy.height else proxy.width
        val outH = if (rotationDegrees == 90 || rotationDegrees == 270) proxy.width else proxy.height
        val yuv = YuvImage(rotated, ImageFormat.NV21, outW, outH, null)
        jpegOut.reset()
        val ok = yuv.compressToJpeg(Rect(0, 0, outW, outH), jpegQuality, jpegOut)
        return if (ok) jpegOut.toByteArray() else null
    }

    /**
     * Stride-correct, bulk-copy YUV_420_888 → NV21 converter using reusable buffers.
     * Avoids per-pixel ByteBuffer JNI calls for full-speed 1080p30/60 capture.
     */
    private fun imageProxyToNv21Fast(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val required = ySize + (ySize / 2)
        if (nv21Scratch.size != required) {
            nv21Scratch = ByteArray(required)
        }
        val nv21 = nv21Scratch

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        if (yPixelStride == 1 && yRowStride == w) {
            yBuf.position(0)
            yBuf.get(nv21, 0, ySize)
        } else if (yPixelStride == 1) {
            var dst = 0
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, dst, w)
                dst += w
            }
        } else {
            var dst = 0
            for (row in 0 until h) {
                val rowBase = row * yRowStride
                for (col in 0 until w) {
                    nv21[dst++] = yBuf.get(rowBase + col * yPixelStride)
                }
            }
        }

        val chromaH = h / 2
        val chromaW = w / 2
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        // Fast path: standard Android semi-planar NV21 layout (vPixelStride == 2, uPixelStride == 2).
        var dst = ySize
        val vRowLen = (chromaW - 1) * vPixelStride + 1
        val uRowLen = (chromaW - 1) * uPixelStride + 1
        if (chromaRowV.size < vRowLen) chromaRowV = ByteArray(vRowLen)
        if (chromaRowU.size < uRowLen) chromaRowU = ByteArray(uRowLen)

        for (row in 0 until chromaH) {
            val vStart = row * vRowStride
            val uStart = row * uRowStride
            val vRemaining = (vBuf.limit() - vStart).coerceAtMost(vRowLen)
            val uRemaining = (uBuf.limit() - uStart).coerceAtMost(uRowLen)
            if (vRemaining > 0) {
                vBuf.position(vStart)
                vBuf.get(chromaRowV, 0, vRemaining)
            }
            if (uRemaining > 0) {
                uBuf.position(uStart)
                uBuf.get(chromaRowU, 0, uRemaining)
            }
            var vOff = 0
            var uOff = 0
            for (col in 0 until chromaW) {
                nv21[dst++] = chromaRowV[vOff]
                nv21[dst++] = chromaRowU[uOff]
                vOff += vPixelStride
                uOff += uPixelStride
            }
        }
        return nv21
    }

    /** Rotates an NV21 buffer by 0/90/180/270 degrees clockwise into reusable scratch storage. */
    private fun rotateNv21Fast(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return src
        if (rotatedScratch.size != src.size) {
            rotatedScratch = ByteArray(src.size)
        }
        val dst = rotatedScratch
        val ySize = width * height
        val chromaW = width / 2
        val chromaH = height / 2

        when (rotation) {
            90 -> {
                val outW = height
                for (y in 0 until height) {
                    val srcRow = y * width
                    val dstCol = outW - 1 - y
                    for (x in 0 until width) {
                        dst[x * outW + dstCol] = src[srcRow + x]
                    }
                }
                val outChromaW = chromaH
                for (cy in 0 until chromaH) {
                    val srcRow = ySize + cy * chromaW * 2
                    val dstCol = (outChromaW - 1 - cy) * 2
                    for (cx in 0 until chromaW) {
                        val dstIdx = ySize + cx * outChromaW * 2 + dstCol
                        val srcIdx = srcRow + cx * 2
                        dst[dstIdx] = src[srcIdx]
                        dst[dstIdx + 1] = src[srcIdx + 1]
                    }
                }
            }
            180 -> {
                for (i in 0 until ySize) {
                    dst[ySize - 1 - i] = src[i]
                }
                val totalPairs = chromaW * chromaH
                for (i in 0 until totalPairs) {
                    val srcIdx = ySize + i * 2
                    val dstIdx = ySize + (totalPairs - 1 - i) * 2
                    dst[dstIdx] = src[srcIdx]
                    dst[dstIdx + 1] = src[srcIdx + 1]
                }
            }
            else -> { // 270
                val outW = height
                val outH = width
                for (y in 0 until height) {
                    val srcRow = y * width
                    for (x in 0 until width) {
                        dst[(outH - 1 - x) * outW + y] = src[srcRow + x]
                    }
                }
                val outChromaW = chromaH
                val outChromaH = chromaW
                for (cy in 0 until chromaH) {
                    val srcRow = ySize + cy * chromaW * 2
                    for (cx in 0 until chromaW) {
                        val dstIdx = ySize + (outChromaH - 1 - cx) * outChromaW * 2 + cy * 2
                        val srcIdx = srcRow + cx * 2
                        dst[dstIdx] = src[srcIdx]
                        dst[dstIdx + 1] = src[srcIdx + 1]
                    }
                }
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

        fun destroy() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                runCatching { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
                    .onFailure { android.util.Log.w("ec0.camera", "lifecycle destroy: ${it.message}") }
            }
        }
    }
}
