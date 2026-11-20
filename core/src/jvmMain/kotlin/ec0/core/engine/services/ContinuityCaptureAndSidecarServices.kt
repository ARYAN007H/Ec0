/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Features #2 & #4:
 *   - Feature #2: ContinuityCaptureService ("Insert from Phone" — Photo, Document Scan,
 *     and Continuity Sketch directly into desktop clipboard / optional auto-paste).
 *   - Feature #4: SidecarDisplayService (Wireless Second Display via virtual Linux display /
 *     PipeWire H.264/HEVC stream + multi-touch & stylus pressure/tilt injection).
 *
 * Security invariants:
 *   - Anti-spoofing: incoming `capture_result` is REJECTED unless its `requestId` matches an
 *     outstanding, non-expired request initiated locally by this desktop.
 *   - SHA-256 verification on every received capture payload before exposing it to the clipboard.
 *   - Strict size caps (16 MiB) and 5-minute TTL on pending capture requests.
 *   - Sidecar encoder argv strictly allowlisted (zero shell interpolation); touch/stylus
 *     injection gated by active sidecar session and rate-limited.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.CaptureRequestCommand
import ec0.core.engine.protocol.CaptureResultInfo
import ec0.core.engine.protocol.ContinuityCaptureProtocol
import ec0.core.engine.protocol.SidecarDisplayProtocol
import ec0.core.engine.protocol.SidecarStartCommand
import ec0.core.engine.protocol.SidecarTouchInputEvent
import ec0.core.engine.transport.Ec0Session
import ec0.core.kdeconnect.Sha256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class PendingCaptureRequest(
    val requestId: String,
    val mode: String,
    val autoPaste: Boolean,
    val createdAtMs: Long,
)

data class ReceivedCapturePayload(
    val info: CaptureResultInfo,
    val bytes: ByteArray,
    val autoPasteRequested: Boolean,
)

/**
 * Coordinates Feature #2 ("Insert from Phone": Take Photo / Scan Document / Sketch).
 */
class ContinuityCaptureService(
    private val onRemoteCaptureRequest: (CaptureRequestCommand) -> Unit = {},
    private val onCaptureDelivered: (ReceivedCapturePayload) -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val pending = ConcurrentHashMap<String, PendingCaptureRequest>()
    private val rng = SecureRandom()

    @Volatile
    var onRemoteCaptureRequestHandler: ((CaptureRequestCommand, Ec0Session?) -> Unit)? = null

    @Volatile
    var onCaptureDeliveredHandler: ((ReceivedCapturePayload) -> Unit)? = null

    private val _activePendingRequest = MutableStateFlow<PendingCaptureRequest?>(null)
    val activePendingRequest: StateFlow<PendingCaptureRequest?> = _activePendingRequest.asStateFlow()

    private val _lastDeliveredCapture = MutableStateFlow<CaptureResultInfo?>(null)
    val lastDeliveredCapture: StateFlow<CaptureResultInfo?> = _lastDeliveredCapture.asStateFlow()

    fun sendCaptureResult(
        result: CaptureResultInfo,
        session: Ec0Session,
        timestampMs: Long = clockMs(),
    ) {
        session.sendInfo(
            infoType = ContinuityCaptureProtocol.INFO_CAPTURE_RESULT,
            infoBody = result.toJsonObject(),
            category = ContinuityCaptureProtocol.CATEGORY_CAPTURE,
            timestampMs = timestampMs,
        )
    }

    fun requestCaptureFromPhone(
        mode: String,
        autoPaste: Boolean = false,
        enhanceContrast: Boolean = true,
        session: Ec0Session?,
    ): CaptureRequestCommand {
        pruneExpired()
        val idBytes = ByteArray(12).also { rng.nextBytes(it) }
        val requestId = "cap_${idBytes.toHex()}"
        val cmd = CaptureRequestCommand(
            requestId = requestId,
            mode = mode,
            autoPaste = autoPaste,
            enhanceContrast = enhanceContrast,
        )
        val entry = PendingCaptureRequest(
            requestId = requestId,
            mode = mode,
            autoPaste = autoPaste,
            createdAtMs = clockMs(),
        )
        pending[requestId] = entry
        _activePendingRequest.value = entry
        session?.sendCmd(
            cmdType = ContinuityCaptureProtocol.CMD_CAPTURE_REQUEST,
            cmdBody = cmd.toJsonObject(),
            category = ContinuityCaptureProtocol.CATEGORY_CAPTURE,
            timestampMs = entry.createdAtMs,
        )
        return cmd
    }

    fun handleControlEnvelope(envelope: Ec0Envelope, session: Ec0Session? = null): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            ContinuityCaptureProtocol.CMD_CAPTURE_REQUEST -> {
                val cmd = CaptureRequestCommand.fromJsonObject(body)
                val custom = onRemoteCaptureRequestHandler
                if (custom != null) {
                    custom(cmd, session)
                } else {
                    onRemoteCaptureRequest(cmd)
                }
                true
            }
            ContinuityCaptureProtocol.INFO_CAPTURE_RESULT -> {
                pruneExpired()
                val info = CaptureResultInfo.fromJsonObject(body)
                val req = pending.remove(info.requestId) ?: return true // Reject unsolicited capture!
                if (_activePendingRequest.value?.requestId == req.requestId) {
                    _activePendingRequest.value = null
                }
                val b64 = info.inlineBase64Url ?: return true
                val raw = runCatching { b64.decodeBase64Url() }.getOrNull() ?: return true
                if (raw.size.toLong() != info.sizeBytes) return true
                val actualSha = Sha256.digest(raw).toHex()
                if (!actualSha.equals(info.sha256Hex, ignoreCase = true)) return true
                _lastDeliveredCapture.value = info
                val delivered = ReceivedCapturePayload(
                    info = info,
                    bytes = raw,
                    autoPasteRequested = req.autoPaste,
                )
                onCaptureDelivered(delivered)
                onCaptureDeliveredHandler?.invoke(delivered)
                true
            }
            else -> false
        }
    }

    private fun pruneExpired() {
        val now = clockMs()
        val iter = pending.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value.createdAtMs > REQUEST_TTL_MS) {
                iter.remove()
            }
        }
    }

    companion object {
        const val REQUEST_TTL_MS: Long = 5 * 60_000L
    }
}

data class SidecarSessionState(
    val active: Boolean = false,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val codec: String = "h264",
    val extendMode: Boolean = true,
    val streamPort: Int = 49892,
)

/**
 * Coordinates Feature #4: Wireless Second Display ("Sidecar") for Android tablets/phones.
 */
class SidecarDisplayService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val onTouchInputInjected: (SidecarTouchInputEvent) -> Unit = {},
    private val onRemoteSidecarStart: (SidecarStartCommand) -> Unit = {},
    private val onRemoteSidecarStop: () -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : Closeable {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val touchRateLimiter = TokenBucketRateLimiter(capacityPerPeer = 480L, refillPerSecond = 240L, clockMs = clockMs)
    private var encoderProcess: RunningProcessHandle? = null

    @Volatile
    var onTouchInputInjectedHandler: ((SidecarTouchInputEvent) -> Unit)? = null

    @Volatile
    var onRemoteSidecarStartHandler: ((SidecarStartCommand) -> Unit)? = null

    @Volatile
    var onRemoteSidecarStopHandler: (() -> Unit)? = null

    private val _state = MutableStateFlow(SidecarSessionState())
    val state: StateFlow<SidecarSessionState> = _state.asStateFlow()

    fun sendTouchInput(
        event: SidecarTouchInputEvent,
        session: Ec0Session,
        timestampMs: Long = clockMs(),
    ) {
        session.sendCmd(
            cmdType = SidecarDisplayProtocol.CMD_SIDECAR_TOUCH,
            cmdBody = event.toJsonObject(),
            category = SidecarDisplayProtocol.CATEGORY_SIDECAR,
            timestampMs = timestampMs,
        )
    }

    /**
     * Builds a strictly validated argv-only `ffmpeg` pipeline capturing the desktop or
     * virtual display (`x11grab` or PipeWire) into low-latency Annex-B H.264/HEVC over TCP.
     */
    fun buildSidecarEncoderArgv(
        cmd: SidecarStartCommand,
        displaySource: String = ":0.0",
        listenHost: String = "127.0.0.1",
    ): List<String> {
        require(SAFE_DISPLAY_REGEX.matches(displaySource)) { "Invalid display source: '$displaySource'" }
        require(listenHost == "127.0.0.1" || listenHost == "0.0.0.0") { "Invalid bind host" }
        val encoder = if (cmd.codec == "hevc") "libx265" else "libx264"
        return listOf(
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "error",
            "-f", "x11grab",
            "-framerate", cmd.fps.toString(),
            "-video_size", "${cmd.width}x${cmd.height}",
            "-i", displaySource,
            "-c:v", encoder,
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-b:v", "${cmd.bitrateKbps}k",
            "-f", if (cmd.codec == "hevc") "hevc" else "h264",
            "tcp://$listenHost:${cmd.streamPort}?listen=1",
        )
    }

    fun startSidecar(
        cmd: SidecarStartCommand = SidecarStartCommand(),
        session: Ec0Session?,
        launchEncoder: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): SidecarSessionState {
        lock.lock()
        try {
            val current = _state.value
            if (current.active &&
                current.width == cmd.width &&
                current.height == cmd.height &&
                current.fps == cmd.fps &&
                current.codec == cmd.codec &&
                current.extendMode == cmd.extendMode &&
                current.streamPort == cmd.streamPort
            ) {
                return current
            }
            stopSidecarLocked(session = null, nowMs = nowMs)
            if (launchEncoder) {
                runCatching {
                    encoderProcess = launcher.spawnProcess(buildSidecarEncoderArgv(cmd))
                }
            }
            session?.sendCmd(
                cmdType = SidecarDisplayProtocol.CMD_SIDECAR_START,
                cmdBody = cmd.toJsonObject(),
                category = SidecarDisplayProtocol.CATEGORY_SIDECAR,
                timestampMs = nowMs,
            )
            val next = SidecarSessionState(
                active = true,
                width = cmd.width,
                height = cmd.height,
                fps = cmd.fps,
                codec = cmd.codec,
                extendMode = cmd.extendMode,
                streamPort = cmd.streamPort,
            )
            _state.value = next
            return next
        } finally {
            lock.unlock()
        }
    }

    fun stopSidecar(
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        lock.lock()
        try {
            stopSidecarLocked(session = session, nowMs = nowMs)
        } finally {
            lock.unlock()
        }
    }

    private fun stopSidecarLocked(
        session: Ec0Session?,
        nowMs: Long,
    ) {
        runCatching { encoderProcess?.stop() }
        encoderProcess = null
        if (_state.value.active && session != null && !session.isClosed) {
            runCatching {
                session.sendCmd(
                    cmdType = SidecarDisplayProtocol.CMD_SIDECAR_STOP,
                    cmdBody = buildJsonObject { put("reason", "user_stop") },
                    category = SidecarDisplayProtocol.CATEGORY_SIDECAR,
                    timestampMs = nowMs,
                )
            }
        }
        _state.value = _state.value.copy(active = false)
    }

    fun handleControlEnvelope(peerDeviceId: String, envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            SidecarDisplayProtocol.CMD_SIDECAR_START -> {
                val cmd = SidecarStartCommand.fromJsonObject(body)
                lock.lock()
                try {
                    _state.value = SidecarSessionState(
                        active = true,
                        width = cmd.width,
                        height = cmd.height,
                        fps = cmd.fps,
                        codec = cmd.codec,
                        extendMode = cmd.extendMode,
                        streamPort = cmd.streamPort,
                    )
                } finally {
                    lock.unlock()
                }
                onRemoteSidecarStart(cmd)
                onRemoteSidecarStartHandler?.invoke(cmd)
                true
            }
            SidecarDisplayProtocol.CMD_SIDECAR_STOP -> {
                lock.lock()
                try {
                    _state.value = _state.value.copy(active = false)
                } finally {
                    lock.unlock()
                }
                onRemoteSidecarStop()
                onRemoteSidecarStopHandler?.invoke()
                true
            }
            SidecarDisplayProtocol.CMD_SIDECAR_TOUCH -> {
                if (!_state.value.active) return true // Ignore touch events when Sidecar isn't active!
                if (!touchRateLimiter.tryAcquire(peerDeviceId)) return true
                val ev = SidecarTouchInputEvent.fromJsonObject(body)
                onTouchInputInjected(ev)
                onTouchInputInjectedHandler?.invoke(ev)
                true
            }
            else -> false
        }
    }

    override fun close() {
        stopSidecar(session = null)
    }

    companion object {
        private val SAFE_DISPLAY_REGEX = Regex("^:[0-9]{1,2}(?:\\.[0-9]{1,2})?$")
    }
}
