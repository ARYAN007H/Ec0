/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Continuity Camera coordinator bridging ec0-engine with ARYAN007H/CamDroid
 * (`_camdroid._tcp.`, TCP :4747 ProtocolV2/ProtocolHandler + v4l2loopback
 * `/dev/video10`) and `scrcpy --video-source=camera --v4l2-sink=/dev/video10`
 * fallback.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.EngineEncoding
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import ec0.core.engine.protocol.CameraBackend
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.CameraStreamState
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Injectable TCP control transport for speaking CamDroid's binary control plane
 * (`ProtocolHandler` v1 packets on TCP `:4747`) directly to the phone's
 * `com.camdroid.network.StreamServer`.
 */
interface CamDroidControlTransport : Closeable {
    val isConnected: Boolean
    fun connect(host: String, port: Int = CamDroidConstants.DEFAULT_TCP_PORT, timeoutMs: Int = 3_000): Boolean
    fun sendPacket(packet: CamDroidV1Packet): Boolean
}

/** Real TCP socket implementation of [CamDroidControlTransport]. */
class TcpCamDroidControlTransport : CamDroidControlTransport {
    private val lock = Any()
    private var socket: Socket? = null

    override val isConnected: Boolean
        get() = synchronized(lock) {
            val s = socket
            s != null && s.isConnected && !s.isClosed
        }

    override fun connect(host: String, port: Int, timeoutMs: Int): Boolean = synchronized(lock) {
        close()
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            val handshake = CamDroidV1Codec.clientHandshakePacket()
            s.getOutputStream().write(CamDroidV1Codec.encode(handshake))
            s.getOutputStream().flush()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    override fun sendPacket(packet: CamDroidV1Packet): Boolean = synchronized(lock) {
        val s = socket ?: return false
        if (!s.isConnected || s.isClosed) return false
        return try {
            val out = s.getOutputStream()
            out.write(CamDroidV1Codec.encode(packet))
            out.flush()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    override fun close() = synchronized(lock) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}

/**
 * Coordinates Continuity Camera streaming and live camera controls on Linux
 * desktop and Android peers.
 */
class ContinuityCameraCoordinator(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val camDroidTransportFactory: () -> CamDroidControlTransport = { TcpCamDroidControlTransport() },
    /**
     * Builds the in-process media receiver used by the token-authenticated
     * ec0 phone streamer (`startCameraStream` with a non-null [streamToken]).
     */
    private val camDroidReceiverFactory: () -> CamDroidStreamReceiver = { CamDroidStreamReceiver() },
    private val v4l2DeviceCheck: (String) -> Boolean = { path ->
        val file = File(path)
        file.exists() && file.canWrite()
    },
    private val onExecuteCameraCommand: ((CamDroidControlCommand) -> CameraStatusInfo?)? = null,
    private val camdroidBinaryPath: String = "camdroid",
    private val adbBinaryPath: String = "adb",
    private val scrcpyBinaryPath: String = "scrcpy",
    private val ffmpegBinary: String = "ffmpeg",
) : Closeable {
    private val lock = Any()
    private var activeProcess: RunningProcessHandle? = null
    private var activeTransport: CamDroidControlTransport? = null
    private var activeReceiver: CamDroidStreamReceiver? = null

    private val _status = MutableStateFlow(
        CameraStatusInfo(
            state = CameraStreamState.IDLE,
            backend = CameraBackend.CAMDROID_NATIVE,
        ),
    )
    val status: StateFlow<CameraStatusInfo> = _status.asStateFlow()

    /**
     * Builds the exact Linux CLI command to launch the local `camdroid` desktop
     * receiver piping into a `v4l2loopback` device node.
     */
    fun buildCamDroidDesktopCommand(
        host: String,
        port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        codec: String = "h264",
        resolution: String = "1080p",
        fps: Int = 30,
        audio: Boolean = true,
    ): List<String> = buildList {
        add(camdroidBinaryPath)
        add("--host")
        add(host)
        add("--port")
        add(port.toString())
        add("--v4l2-device")
        add(v4l2DevicePath)
        add("--codec")
        add(codec.lowercase())
        add("--resolution")
        add(resolution.lowercase())
        add("--fps")
        add(fps.toString())
        if (!audio) {
            add("--no-audio")
        }
    }

    /**
     * Builds the `scrcpy --video-source=camera` V4L2 fallback CLI command.
     */
    fun buildScrcpyCameraCommand(
        host: String,
        adbPort: Int = 5555,
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        resolution: String = "1080p",
        fps: Int = 30,
        frontCamera: Boolean = false,
        audio: Boolean = true,
        cameraId: String? = null,
    ): List<String> {
        val sizeArg = when (resolution.lowercase()) {
            "4k" -> "3840x2160"
            "1440p", "2k" -> "2560x1440"
            else -> "1920x1080"
        }
        return buildList {
            add(scrcpyBinaryPath)
            add("--serial")
            add("$host:$adbPort")
            add("--video-source=camera")
            if (cameraId != null) {
                // scrcpy 2.2+: an explicit id wins over the facing heuristic
                add("--camera-id=$cameraId")
            } else {
                add("--camera-facing=${if (frontCamera) "front" else "back"}")
            }
            add("--camera-size=$sizeArg")
            add("--camera-fps=$fps")
            add("--v4l2-sink=$v4l2DevicePath")
            add("--no-playback")
            if (!audio) {
                add("--no-audio")
            }
        }
    }

    /**
     * Returns the exact Linux remediation command if the `v4l2loopback` device
     * node (e.g., `/dev/video10`) is missing or not writable.
     */
    fun v4l2SetupCommand(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = CamDroidConstants.DEFAULT_V4L2_CARD_LABEL,
    ): String {
        val videoNr = v4l2DevicePath.removePrefix("/dev/video").toIntOrNull() ?: 10
        return "sudo modprobe v4l2loopback exclusive_caps=1 video_nr=$videoNr card_label=\"$cardLabel\""
    }

    /**
     * Builds the PolicyKit (`pkexec`) argv list to load `v4l2loopback` without a
     * terminal prompt or shell interpolation.
     */
    fun buildPkexecModprobeCommand(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = CamDroidConstants.DEFAULT_V4L2_CARD_LABEL,
    ): List<String> {
        val videoNr = v4l2DevicePath.removePrefix("/dev/video").toIntOrNull() ?: 10
        return listOf(
            "pkexec",
            "modprobe",
            "v4l2loopback",
            "exclusive_caps=1",
            "video_nr=$videoNr",
            "card_label=$cardLabel",
        )
    }

    /**
     * Executes `pkexec modprobe v4l2loopback ...` to provision `/dev/video10` in
     * 1 click from the Linux desktop UI.
     */
    fun setupV4l2WithPkexec(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = CamDroidConstants.DEFAULT_V4L2_CARD_LABEL,
    ): Boolean {
        val argv = buildPkexecModprobeCommand(v4l2DevicePath, cardLabel)
        return try {
            val res = launcher.runBlockingCommand(argv, timeoutMs = 30_000L)
            res.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates the exact contents for `/etc/modules-load.d/ec0-camdroid.conf` and
     * `/etc/modprobe.d/ec0-camdroid.conf` so `v4l2loopback` persists across Linux reboots.
     */
    fun generatePersistentModprobeConfigs(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = CamDroidConstants.DEFAULT_V4L2_CARD_LABEL,
    ): Pair<String, String> {
        val videoNr = v4l2DevicePath.removePrefix("/dev/video").toIntOrNull() ?: 10
        val modulesLoadConf = "# Auto-generated by ec0 Continuity Camera\nv4l2loopback\n"
        val modprobeOptionsConf =
            "# Auto-generated by ec0 Continuity Camera\n" +
                "options v4l2loopback exclusive_caps=1 video_nr=$videoNr card_label=\"$cardLabel\"\n"
        return modulesLoadConf to modprobeOptionsConf
    }

    /**
     * Starts a Continuity Camera session on Linux desktop using either
     * [CameraBackend.CAMDROID_NATIVE] or [CameraBackend.SCRCPY_V4L2_FALLBACK].
     */
    fun startCameraStream(
        host: String,
        port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        backend: CameraBackend = CameraBackend.CAMDROID_NATIVE,
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        codec: String = "h264",
        resolution: String = "1080p",
        fps: Int = 30,
        audio: Boolean = true,
        frontCamera: Boolean = false,
        requireV4l2Device: Boolean = false,
        spawnDesktopReceiver: Boolean = true,
        /**
         * Non-null switches [CameraBackend.CAMDROID_NATIVE] to ec0's OWN phone
         * streamer: the token-authenticated, in-process [CamDroidStreamReceiver]
         * path (token was armed over the encrypted engine session). Null keeps
         * the legacy external-`camdroid`-binary behavior.
         */
        streamToken: String? = null,
    ): CameraStatusInfo = synchronized(lock) {
        stopActiveLocked()

        if (requireV4l2Device && !v4l2DeviceCheck(v4l2DevicePath)) {
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = backend,
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    codec = codec,
                    resolution = resolution,
                    fps = fps,
                    audioEnabled = audio,
                    frontCamera = frontCamera,
                    reason = "V4L2 device $v4l2DevicePath not ready. Run: ${v4l2SetupCommand(v4l2DevicePath)}",
                ),
            )
        }

        return when (backend) {
            CameraBackend.CAMDROID_NATIVE -> if (streamToken != null) {
                startCamDroidReceiverLocked(
                    host = host,
                    port = port,
                    streamToken = streamToken,
                    v4l2DevicePath = v4l2DevicePath,
                    fps = fps,
                    frontCamera = frontCamera,
                )
            } else {
                startCamDroidNativeLocked(
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    codec = codec,
                    resolution = resolution,
                    fps = fps,
                    audio = audio,
                    frontCamera = frontCamera,
                    spawnDesktopReceiver = spawnDesktopReceiver,
                )
            }
            CameraBackend.SCRCPY_V4L2_FALLBACK -> startScrcpyCameraFallbackLocked(
                host = host,
                adbPort = port,
                v4l2DevicePath = v4l2DevicePath,
                resolution = resolution,
                fps = fps,
                audio = audio,
                frontCamera = frontCamera,
            )
        }
    }

    /**
     * ec0-native phone streamer path: connect the token-authenticated media
     * receiver, honor the phone's ACTUAL capture config in the status, and
     * surface every failure honestly.
     */
    private fun startCamDroidReceiverLocked(
        host: String,
        port: Int,
        streamToken: String,
        v4l2DevicePath: String,
        fps: Int,
        frontCamera: Boolean,
    ): CameraStatusInfo {
        val receiver = camDroidReceiverFactory()
        val snapshot = receiver.start(host = host, port = port, streamToken = streamToken, requestedFps = fps)
        if (snapshot.state != CamDroidReceiverState.STREAMING) {
            runCatching { receiver.close() }
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    reason = snapshot.reason ?: "Camera stream failed before streaming",
                ),
            )
        }
        activeReceiver = receiver
        if (frontCamera) {
            receiver.sendControl(CamDroidControlCommand.SwitchCamera)
        }
        val config = snapshot.config
        return updateStatusLocked(
            CameraStatusInfo(
                state = CameraStreamState.STREAMING,
                backend = CameraBackend.CAMDROID_NATIVE,
                host = host,
                port = port,
                v4l2DevicePath = v4l2DevicePath,
                codec = config?.codec ?: "mjpeg",
                resolution = config?.let { "${it.width}x${it.height}" } ?: "640x480",
                fps = config?.fps ?: fps,
                audioEnabled = false,
                frontCamera = frontCamera,
            ),
        )
    }

    private fun startCamDroidNativeLocked(
        host: String,
        port: Int,
        v4l2DevicePath: String,
        codec: String,
        resolution: String,
        fps: Int,
        audio: Boolean,
        frontCamera: Boolean,
        spawnDesktopReceiver: Boolean,
    ): CameraStatusInfo {
        val transport = camDroidTransportFactory()
        val connected = transport.connect(host, port)
        if (!connected) {
            transport.close()
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    codec = codec,
                    resolution = resolution,
                    fps = fps,
                    audioEnabled = audio,
                    frontCamera = frontCamera,
                    reason = "Failed to connect to CamDroid StreamServer at $host:$port",
                ),
            )
        }
        activeTransport = transport

        val startCmd = CamDroidControlCommand.Start(
            codec = codec,
            resolution = resolution,
            fps = fps,
            audio = audio,
        )
        if (!transport.sendPacket(CamDroidV1Codec.controlCommandPacket(startCmd))) {
            stopActiveLocked()
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.CAMDROID_NATIVE,
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    reason = "Failed to send CamDroid start command to $host:$port",
                ),
            )
        }

        if (frontCamera) {
            transport.sendPacket(CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.SwitchCamera))
        }

        if (spawnDesktopReceiver) {
            try {
                val cmd = buildCamDroidDesktopCommand(
                    host = host,
                    port = port,
                    v4l2DevicePath = v4l2DevicePath,
                    codec = codec,
                    resolution = resolution,
                    fps = fps,
                    audio = audio,
                )
                activeProcess = launcher.spawnProcess(cmd)
            } catch (_: Exception) {
                // Keep TCP control connection alive even if optional desktop viewer binary isn't installed
            }
        }

        return updateStatusLocked(
            CameraStatusInfo(
                state = CameraStreamState.STREAMING,
                backend = CameraBackend.CAMDROID_NATIVE,
                host = host,
                port = port,
                v4l2DevicePath = v4l2DevicePath,
                codec = codec.lowercase(),
                resolution = resolution.lowercase(),
                fps = fps,
                audioEnabled = audio,
                frontCamera = frontCamera,
            ),
        )
    }

    private fun startScrcpyCameraFallbackLocked(
        host: String,
        adbPort: Int,
        v4l2DevicePath: String,
        resolution: String,
        fps: Int,
        audio: Boolean,
        frontCamera: Boolean,
    ): CameraStatusInfo {
        val endpoint = "$host:$adbPort"
        val adbResult = try {
            launcher.runBlockingCommand(listOf(adbBinaryPath, "connect", endpoint), timeoutMs = 5_000L)
        } catch (e: Exception) {
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                    host = host,
                    port = adbPort,
                    v4l2DevicePath = v4l2DevicePath,
                    reason = "adb connect failed: ${e.message}",
                ),
            )
        }
        val combinedOut = "${adbResult.stdout} ${adbResult.stderr}".trim()
        val outLower = combinedOut.lowercase()
        val connected = adbResult.exitCode == 0 &&
            (outLower.contains("connected to") || outLower.contains("already connected")) &&
            !outLower.contains("unable to connect") &&
            !outLower.contains("failed to connect")

        if (!connected) {
            return updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                    host = host,
                    port = adbPort,
                    v4l2DevicePath = v4l2DevicePath,
                    reason = "adb connect $endpoint failed: ${combinedOut.ifBlank { "exit=${adbResult.exitCode}" }}",
                ),
            )
        }

        val scrcpyCmd = buildScrcpyCameraCommand(
            host = host,
            adbPort = adbPort,
            v4l2DevicePath = v4l2DevicePath,
            resolution = resolution,
            fps = fps,
            frontCamera = frontCamera,
            audio = audio,
        )
        return try {
            val handle = launcher.spawnProcess(scrcpyCmd)
            if (!handle.isAlive) {
                updateStatusLocked(
                    CameraStatusInfo(
                        state = CameraStreamState.ERROR,
                        backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                        host = host,
                        port = adbPort,
                        v4l2DevicePath = v4l2DevicePath,
                        reason = "scrcpy camera process exited immediately",
                    ),
                )
            } else {
                activeProcess = handle
                updateStatusLocked(
                    CameraStatusInfo(
                        state = CameraStreamState.STREAMING,
                        backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                        host = host,
                        port = adbPort,
                        v4l2DevicePath = v4l2DevicePath,
                        codec = "h264",
                        resolution = resolution.lowercase(),
                        fps = fps,
                        audioEnabled = audio,
                        frontCamera = frontCamera,
                    ),
                )
            }
        } catch (e: Exception) {
            updateStatusLocked(
                CameraStatusInfo(
                    state = CameraStreamState.ERROR,
                    backend = CameraBackend.SCRCPY_V4L2_FALLBACK,
                    host = host,
                    port = adbPort,
                    v4l2DevicePath = v4l2DevicePath,
                    reason = "Failed to spawn scrcpy camera sink: ${e.message}",
                ),
            )
        }
    }

    /**
     * Applies a [CamDroidControlCommand] locally to the active CamDroid TCP
     * control stream (if connected) AND optionally sends a signed
     * `cam_control` command envelope over [session].
     */
    fun sendControlCommand(
        command: CamDroidControlCommand,
        session: Ec0Session? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? = synchronized(lock) {
        val receiver = activeReceiver
        if (receiver != null && !receiver.sendControl(command)) {
            updateStatusLocked(
                _status.value.copy(
                    state = CameraStreamState.ERROR,
                    reason = "Camera control channel lost — receiver socket closed",
                ),
            )
        }
        activeTransport?.sendPacket(CamDroidV1Codec.controlCommandPacket(command))
        applyCommandToStatusLocked(command)
        return session?.sendCmd(
            cmdType = CMD_CAMERA_CONTROL,
            cmdBody = command.toJsonObject(),
            category = CATEGORY_CAMERA,
            timestampMs = timestampMs,
        )
    }

    /**
     * Broadcasts the current [CameraStatusInfo] as a signed `cam_status` info
     * envelope over [session].
     */
    fun broadcastCameraStatus(
        session: Ec0Session,
        statusOverride: CameraStatusInfo = _status.value,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope = synchronized(lock) {
        updateStatusLocked(statusOverride)
        return session.sendInfo(
            infoType = INFO_CAMERA_STATUS,
            infoBody = statusOverride.toJsonObject(),
            category = CATEGORY_CAMERA,
            timestampMs = timestampMs,
        )
    }

    /**
     * Handles an incoming signed camera control/status envelope from a remote peer.
     */
    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean = synchronized(lock) {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        val bodyType = envelope.bodyType ?: envelope.msgType
        return when (bodyType) {
            CMD_CAMERA_CONTROL, CamDroidConstants.MSG_CAM_CONTROL_CMD -> {
                val cmd = CamDroidControlCommand.fromJsonObject(body)
                applyCommandToStatusLocked(cmd)
                val customStatus = onExecuteCameraCommand?.invoke(cmd)
                if (customStatus != null) {
                    updateStatusLocked(customStatus)
                }
                true
            }
            INFO_CAMERA_STATUS, CamDroidConstants.MSG_CAM_STATUS_INFO -> {
                val remoteStatus = CameraStatusInfo.fromJsonObject(body)
                updateStatusLocked(remoteStatus)
                true
            }
            else -> false
        }
    }

    fun stopCameraStream(session: Ec0Session? = null): CameraStatusInfo = synchronized(lock) {
        // Network-independent stop first: the receiver tears down its socket and
        // the ffmpeg sink synchronously — it MUST work even if the peer vanished.
        runCatching { activeReceiver?.sendControl(CamDroidControlCommand.Stop) }
        runCatching { activeReceiver?.close() }
        activeReceiver = null
        activeTransport?.sendPacket(CamDroidV1Codec.controlCommandPacket(CamDroidControlCommand.Stop))
        session?.sendCmd(
            cmdType = CMD_CAMERA_CONTROL,
            cmdBody = CamDroidControlCommand.Stop.toJsonObject(),
            category = CATEGORY_CAMERA,
        )
        stopActiveLocked()
        updateStatusLocked(
            _status.value.copy(
                state = CameraStreamState.STOPPED,
                reason = "Stopped by user",
            ),
        )
    }

    override fun close() {
        synchronized(lock) {
            stopActiveLocked()
        }
    }

    private fun applyCommandToStatusLocked(command: CamDroidControlCommand) {
        val cur = _status.value
        val next = when (command) {
            is CamDroidControlCommand.Start -> cur.copy(
                state = CameraStreamState.STREAMING,
                codec = command.codec.lowercase(),
                resolution = command.resolution.lowercase(),
                fps = command.fps,
                audioEnabled = command.audio,
            )
            is CamDroidControlCommand.Stop -> cur.copy(state = CameraStreamState.STOPPED)
            is CamDroidControlCommand.SwitchCamera -> cur.copy(frontCamera = !cur.frontCamera)
            is CamDroidControlCommand.SetZoom -> cur.copy(zoomX100 = command.zoomX100)
            is CamDroidControlCommand.SetFocus -> cur
            is CamDroidControlCommand.SetExposure -> cur.copy(exposureEv = command.compensation)
            is CamDroidControlCommand.SetWhiteBalance -> cur.copy(whiteBalance = command.mode.lowercase())
            is CamDroidControlCommand.SetFlash -> cur.copy(flashEnabled = command.enabled)
            is CamDroidControlCommand.SetMirror -> cur.copy(mirrorEnabled = command.enabled)
            is CamDroidControlCommand.SetResolution -> cur.copy(resolution = command.value.lowercase())
            is CamDroidControlCommand.SetFps -> cur.copy(fps = command.value)
            is CamDroidControlCommand.SetCodec -> cur.copy(codec = command.value.lowercase())
            is CamDroidControlCommand.SetBitrate -> cur
            is CamDroidControlCommand.RequestKeyframe -> cur
        }
        _status.value = next
    }

    private fun stopActiveLocked() {
        try {
            activeReceiver?.close()
        } catch (_: Exception) {
        }
        activeReceiver = null
        try {
            activeTransport?.close()
        } catch (_: Exception) {
        }
        activeTransport = null
        try {
            activeProcess?.stop()
        } catch (_: Exception) {
        }
        activeProcess = null
    }

    private fun updateStatusLocked(newStatus: CameraStatusInfo): CameraStatusInfo {
        _status.value = newStatus
        return newStatus
    }

    companion object {
        const val CATEGORY_CAMERA: String = "cam"
        const val CMD_CAMERA_CONTROL: String = "cam_control"
        const val INFO_CAMERA_STATUS: String = "cam_status"

        /** Minimum scrcpy version with `--video-source=camera` support (v2.2+). */
        const val MIN_SCRCPY_MAJOR: Int = 2
        const val MIN_SCRCPY_MINOR: Int = 2

        private val streamTokenRandom = java.security.SecureRandom()

        /**
         * One-shot 128-bit stream arm token (32 lowercase hex chars). The
         * desktop generates it per start attempt, arms the phone's stream
         * server with it via the AEAD-encrypted engine session, and echoes it
         * in the TCP handshake. Lifetime: a single arm window.
         */
        fun newStreamToken(): String {
            val bytes = ByteArray(16)
            streamTokenRandom.nextBytes(bytes)
            return EngineEncoding.encodeHex(bytes)
        }
    }

    /**
     * Builds the passwordless (`sudo -n`) modprobe argv for the "Load v4l2"
     * tile pre-flight. Mirrors [v4l2SetupCommand] but as argv + `-n` so a
     * passwordless-sudo probe can run it without an interactive prompt.
     */
    fun buildSudoModprobeCommand(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = "ec0 camera",
    ): List<String> {
        val videoNr = v4l2DevicePath.removePrefix("/dev/video").toIntOrNull() ?: 10
        return listOf(
            "sudo",
            "-n",
            "modprobe",
            "v4l2loopback",
            "devices=1",
            "video_nr=$videoNr",
            "card_label=$cardLabel",
            "exclusive_caps=1",
        )
    }

    fun buildScrcpyVersionCommand(): List<String> = listOf(scrcpyBinaryPath, "--version")

    /**
     * Pre-flight for the "Load v4l2" tile, fully fake-runner-testable:
     * 1. `sudo -n modprobe …` — needs-password (exit ≠ 0 + password hint in
     *    stderr) → honest remediation text instead of a GUI prompt.
     * 2. `scrcpy --version` — parsed; < 2.2 → unsupported verdict.
     */
    fun v4l2Preflight(
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        cardLabel: String = CamDroidConstants.DEFAULT_V4L2_CARD_LABEL,
        runner: (List<String>) -> CommandExecResult = { argv -> launcher.runBlockingCommand(argv, timeoutMs = 15_000L) },
    ): V4l2PreflightResult {
        val modprobeArgv = buildSudoModprobeCommand(v4l2DevicePath, cardLabel)
        if (v4l2DeviceCheck(v4l2DevicePath)) {
            // Device node already present and writable — only scrcpy matters now.
            return scrcpyCheck(runner)
        }
        val modprobe = runner(modprobeArgv)
        if (modprobe.exitCode != 0) {
            val needsPassword = (modprobe.stderr + modprobe.stdout).let { text ->
                text.contains("password", ignoreCase = true) || text.contains("sudo: a password is required")
            }
            return V4l2PreflightResult(
                outcome = if (needsPassword) V4l2Outcome.NEEDS_PASSWORD else V4l2Outcome.MODPROBE_FAILED,
                scrcpyVersionText = null,
                scrcpySupported = false,
                remediationCommand = v4l2SetupCommand(v4l2DevicePath, cardLabel),
            )
        }
        return scrcpyCheck(runner)
    }

    private fun scrcpyCheck(runner: (List<String>) -> CommandExecResult): V4l2PreflightResult {
        val versionRun = runner(buildScrcpyVersionCommand())
        val version = parseScrcpyVersion(versionRun.stdout + versionRun.stderr)
        val supported = isScrcpyVersionSupported(version)
        return V4l2PreflightResult(
            outcome = if (supported) V4l2Outcome.READY else V4l2Outcome.SCRCPY_UNSUPPORTED,
            scrcpyVersionText = version,
            scrcpySupported = supported,
            remediationCommand = if (supported) "" else "Install scrcpy >= 2.2 (found: ${version ?: "none"})",
        )
    }

    /**
     * Full pre-flight for an ec0-native camera stream start, run BEFORE the
     * phone's camera is armed: live session, phone's `cam` capability, writable
     * v4l2loopback node, and an ffmpeg binary for the v4l2 sink. Nothing here
     * touches the phone's camera — it only checks the desktop-side readiness
     * and the advertised beacon.
     */
    fun streamPreflight(
        sessionConnected: Boolean,
        phoneCameraAdvertised: Boolean,
        v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
        runner: (List<String>) -> CommandExecResult = { argv ->
            launcher.runBlockingCommand(argv, timeoutMs = 10_000L)
        },
    ): CameraStreamPreflight {
        val v4l2Ready = v4l2DeviceCheck(v4l2DevicePath)
        val ffmpegReady = try {
            val res = runner(listOf(ffmpegBinary, "-version"))
            res.exitCode == 0 && res.stdout.contains("ffmpeg", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
        return CameraStreamPreflight(
            sessionConnected = sessionConnected,
            phoneCameraAdvertised = phoneCameraAdvertised,
            v4l2Ready = v4l2Ready,
            v4l2Remediation = v4l2SetupCommand(v4l2DevicePath),
            ffmpegReady = ffmpegReady,
        )
    }
}

/**
 * Parses the scrcpy banner ("scrcpy 5.0.1 …") into a normalized version
 * string ("5.0.1"); tolerates surrounding noise, returns null when absent.
 */
fun parseScrcpyVersion(text: String): String? {
    val m = Regex("scrcpy[ \t]+(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(text) ?: return null
    val patch = m.groupValues[3]
    return if (patch.isEmpty()) {
        "${m.groupValues[1]}.${m.groupValues[2]}"
    } else {
        "${m.groupValues[1]}.${m.groupValues[2]}.$patch"
    }
}

/** True when [version] (from [parseScrcpyVersion]) is >= the --video-source=camera floor (2.2). */
fun isScrcpyVersionSupported(
    version: String?,
    minMajor: Int = ContinuityCameraCoordinator.MIN_SCRCPY_MAJOR,
    minMinor: Int = ContinuityCameraCoordinator.MIN_SCRCPY_MINOR,
): Boolean {
    if (version == null) return false
    val parts = version.split('.').map { it.toIntOrNull() ?: return false }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    return major > minMajor || (major == minMajor && minor >= minMinor)
}

/**
 * Finds a back-facing camera id from `scrcpy --list-cameras` output
 * ("Camera #0, facing=back …") → "0"; null when none is listed.
 */
fun parseBackCameraId(listCamerasOutput: String): String? =
    Regex("Camera #(\\S+),[\\t ]*facing[=: ]*back(?:[\r\n,]|$)").find(listCamerasOutput)?.groupValues?.get(1)

/** Outcome of the "Load v4l2" tile pre-flight ([ContinuityCameraCoordinator.v4l2Preflight]). */
enum class V4l2Outcome { READY, NEEDS_PASSWORD, MODPROBE_FAILED, SCRCPY_UNSUPPORTED }

/**
 * Structured pre-flight for a Continuity Camera stream start: every check the
 * tile needs BEFORE arming the phone's camera, each with an honest remediation.
 */
data class CameraStreamPreflight(
    val sessionConnected: Boolean,
    val phoneCameraAdvertised: Boolean,
    val v4l2Ready: Boolean,
    val v4l2Remediation: String,
    val ffmpegReady: Boolean,
) {
    val allClear: Boolean
        get() = sessionConnected && phoneCameraAdvertised && v4l2Ready && ffmpegReady

    /** Missing preconditions as one honest banner line; empty when all clear. */
    fun bannerText(): String {
        if (allClear) return ""
        val missing = buildList {
            if (!sessionConnected) add("phone session not connected (pair the phone first)")
            if (!phoneCameraAdvertised) add("phone has not advertised `cam` — grant the CAMERA permission on the phone")
            if (!v4l2Ready) add("v4l2loopback not writable — $v4l2Remediation")
            if (!ffmpegReady) add("ffmpeg not found on PATH (scrcpy dependency)")
        }
        return "Continuity Camera cannot start: " + missing.joinToString("; ")
    }
}

data class V4l2PreflightResult(
    val outcome: V4l2Outcome,
    /** Parsed scrcpy version, e.g. "5.0.1" — null when scrcpy is absent/garbled. */
    val scrcpyVersionText: String?,
    val scrcpySupported: Boolean,
    /**
     * Honest remediation text for the tile: the exact `sudo modprobe …` command
     * when a password is required, or an upgrade hint when scrcpy is too old.
     * Empty when nothing is wrong.
     */
    val remediationCommand: String,
)
