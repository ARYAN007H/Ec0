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
    private val v4l2DeviceCheck: (String) -> Boolean = { path ->
        val file = File(path)
        file.exists() && file.canWrite()
    },
    private val onExecuteCameraCommand: ((CamDroidControlCommand) -> CameraStatusInfo?)? = null,
    private val camdroidBinaryPath: String = "camdroid",
    private val adbBinaryPath: String = "adb",
    private val scrcpyBinaryPath: String = "scrcpy",
) : Closeable {
    private val lock = Any()
    private var activeProcess: RunningProcessHandle? = null
    private var activeTransport: CamDroidControlTransport? = null

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
            add("--camera-facing=${if (frontCamera) "front" else "back"}")
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
            CameraBackend.CAMDROID_NATIVE -> startCamDroidNativeLocked(
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
    }
}
