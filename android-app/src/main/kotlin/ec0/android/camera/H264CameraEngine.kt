/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0's Continuity Camera capture engine — the hardware H.264 pipeline ported
 * from the owner's CamDroid project (ARYAN007H/CamDroid, MIT): Camera2 feeds
 * a Surface-input MediaCodec encoder, and the encoder's NAL units flow into
 * the CamDroid v2 stream server.
 *
 * THE A03 LESSON IS BAKED IN: CamDroid's original encoder configured
 * MediaCodec with whatever the desktop asked for and crashed with
 * IllegalArgumentException on phones whose encoder rejects that mode
 * (1080p@60 requested, Exynos 850 offers 1080p@30). Here the advertised
 * [supportedModes] are queried from the REAL MediaCodecInfo + Camera2
 * capabilities, and [start] refuses any mode outside them — negotiation
 * happens on the wire, never inside a configure() call.
 */
package ec0.android.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import ec0.core.engine.services.CamDroidH264FrameSource
import ec0.core.engine.services.CamDroidH264Sink
import ec0.core.engine.services.camdroid.CamDroidVideoMode

/** ec0's camera engine: hardware H.264 NAL units for the CamDroid v2 wire. */
class H264CameraEngine(
    private val appContext: Context,
    /** Facing-lens preference; `switch_camera` flips it live when streaming. */
    private var preferFrontCamera: Boolean = false,
) : CamDroidH264FrameSource {

    companion object {
        private const val TAG = "ec0.camera"
        private const val MIME = "video/avc"
    }

    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Capture stack (created per session, torn down in close()).
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var sinkRef: CamDroidH264Sink? = null
    private val lock = Any()

    private fun lensCameraId(): String {
        val facing = if (preferFrontCamera) {
            CameraMetadata.LENS_FACING_FRONT
        } else {
            CameraMetadata.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: cameraManager.cameraIdList.firstOrNull()
        ?: throw IllegalStateException("the device has no camera")
    }

    /**
     * The honest, capability-clamped mode set: every candidate size MUST be
     * supported by BOTH the hardware encoder (MediaCodecInfo) and the chosen
     * lens (Camera2 StreamConfigurationMap). Frame rates come from the
     * encoder's supported range, clamped to a sane 30 ceiling on low-tier
     * lenses. This is what killed the 60fps crash — nothing here can return a
     * mode MediaCodec will refuse to configure.
     */
    override fun supportedModes(): List<CamDroidVideoMode> {
        val modes = mutableListOf<CamDroidVideoMode>()
        runCatching {
            val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            } ?: return emptyList()
            val caps = encoderInfo.getCapabilitiesForType(MIME)
            val videoCaps = caps.videoCapabilities ?: return emptyList()

            val cameraId = lensCameraId()
            val lensChars = cameraManager.getCameraCharacteristics(cameraId)
            val lensFpsCeiling = lensChars
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.maxOfOrNull { it.upper } ?: 30

            // Canonical shapes, highest first. Each must clear the encoder's
            // own supported range AND the lens's fps range — isSizeSupported
            // and supportedFrameRatesFor are the honest oracles.
            for (candidate in listOf(1920 to 1080, 1280 to 720, 640 to 480)) {
                val (w, h) = candidate
                if (!videoCaps.isSizeSupported(w, h)) continue
                // getSupportedFrameRatesFor(w, h): the per-size fps Range —
                // a flat supportedFrameRatesFor property does not exist. Plain
                // lower/upper comparisons avoid the Range.contains boxing
                // overload ambiguity.
                val fpsRange = videoCaps.getSupportedFrameRatesFor(w, h)
                val fps = (1..minOf(30, lensFpsCeiling))
                    .lastOrNull { it >= fpsRange.lower && it <= fpsRange.upper } ?: continue
                modes.add(
                    CamDroidVideoMode(
                        width = w,
                        height = h,
                        fps = fps,
                        minBitrateBps = 1_000_000L,
                        maxBitrateBps = 50_000_000L,
                    ),
                )
            }
        }
        return modes
    }

    override fun preferredCodec(): String = "h264"

    private fun openEncoder(mode: CamDroidVideoMode, bitrateBps: Long) {
        val handlerThread = HandlerThread("ec0-camera-encoder").also { it.start() }
        encoderThread = handlerThread
        val handler = Handler(handlerThread.looper).also { encoderHandler = it }

        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        } ?: throw IllegalStateException("no hardware H.264 encoder on this device")
        val codecCaps = codecInfo.getCapabilitiesForType(MIME)

        val format = MediaFormat.createVideoFormat(MIME, mode.width, mode.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            val baseline = codecCaps.profileLevels.firstOrNull {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
            if (baseline != null) {
                setInteger(MediaFormat.KEY_PROFILE, baseline.profile)
                setInteger(MediaFormat.KEY_LEVEL, baseline.level)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.setCallback(encoderCallback, handler)
        // The negotiated mode cleared supportedModes() — this configure CANNOT
        // throw IllegalArgumentException the way the un-clamped original did.
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val sink = sinkRef ?: run { codec.releaseOutputBuffer(index, false); return }
            try {
                val encoded = codec.getOutputBuffer(index) ?: return
                encoded.position(info.offset)
                encoded.limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val configData = ByteArray(info.size)
                    encoded.get(configData)
                    // SPS/PPS: the decoder needs these before the first frame.
                    sink.onEncoded(configData, isConfig = true, isKeyFrame = false, ptsNs = info.presentationTimeUs * 1000L)
                } else if (info.size > 0) {
                    val frame = ByteArray(info.size)
                    encoded.get(frame)
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    sink.onEncoded(frame, isConfig = false, isKeyFrame = isKey, ptsNs = info.presentationTimeUs * 1000L)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
        }
    }

    private fun openCameraOnSurface(surface: Surface, onError: (String) -> Unit) {
        cameraThread = HandlerThread("ec0-camera-device").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val cameraId = lensCameraId()
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        device.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    session = configured
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                        .apply { addTarget(surface) }
                                    runCatching {
                                        configured.setRepeatingRequest(request.build(), null, cameraHandler)
                                    }.onFailure { onError("capture session failed: ${it.message}") }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    onError("capture session configuration failed")
                                }
                            },
                            cameraHandler,
                        )
                    } catch (e: Exception) {
                        onError("capture session setup failed: ${e.message}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) = device.close()

                override fun onError(device: CameraDevice, error: Int) {
                    onError("camera device error $error")
                }
            },
            cameraHandler,
        )
    }

    override fun start(mode: CamDroidVideoMode, bitrateBps: Long, sink: CamDroidH264Sink, onError: (String) -> Unit) {
        synchronized(lock) {
            sinkRef = sink
            try {
                openEncoder(mode, bitrateBps)
                val surface = inputSurface
                    ?: throw IllegalStateException("encoder produced no input surface")
                openCameraOnSurface(surface, onError)
                Log.i(TAG, "H.264 engine streaming ${mode.width}x${mode.height}@${mode.fps} @${bitrateBps / 1_000_000}Mbps")
            } catch (t: Throwable) {
                Log.e(TAG, "engine start failed", t)
                runCatching { closeLocked() }
                onError("camera engine start failed — ${t.message}")
            }
        }
    }

    private fun closeLocked() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { encoder?.signalEndOfInputStream() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        sinkRef = null
    }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }
}
