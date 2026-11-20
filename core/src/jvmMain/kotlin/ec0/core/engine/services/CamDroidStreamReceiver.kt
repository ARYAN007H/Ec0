/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop-side Continuity Camera receiver: speaks CamDroid ProtocolHandler v1
 * framing over TCP :4747 to the phone's token-gated stream server, then pipes
 * the inbound MJPEG frames into `ffmpeg -f mjpeg -i pipe:0 -pix_fmt yuyv422
 * -f v4l2 /dev/video10` so any Linux app sees an "ec0 camera" webcam.
 *
 * Resilience contract:
 *   - `stop()` is synchronous and network-independent: it always closes the
 *     socket and kills the ffmpeg sink, even when the phone has vanished.
 *   - A silent peer (no packet — including HEARTBEAT — for
 *     [silenceTimeoutMs]) auto-stops the stream with an honest ERROR reason.
 *   - Frames arriving before the capture config are dropped (bounded, never
 *     queued): the sink is launched with the phone's ACTUAL config metadata.
 */
package ec0.core.engine.services

import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import ec0.core.engine.protocol.CameraEffectsCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle of the desktop-side receiver. */
enum class CamDroidReceiverState(val wireName: String) {
    IDLE("idle"),
    CONNECTING("connecting"),
    STREAMING("streaming"),
    ERROR("error"),
    STOPPED("stopped"),
}

/** Observable receiver snapshot for the coordinator status and tests. */
data class CamDroidReceiverSnapshot(
    val state: CamDroidReceiverState,
    val config: CamDroidStreamConfig? = null,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val reason: String? = null,
)

/** One running ffmpeg(-like) v4l2 sink process. */
interface CamDroidSinkHandle : Closeable {
    val stdin: OutputStream
    val isAlive: Boolean
    fun exitCode(): Int?
    /** Bounded trailing stderr/stdout lines for honest diagnostics if ffmpeg exits. */
    fun lastDiagnosticOutput(): String? = null
    override fun close()
}

/** Spawns the v4l2 sink process. Injectable so tests can fake ffmpeg. */
fun interface CamDroidSinkLauncher {
    fun launch(argv: List<String>): CamDroidSinkHandle
}

/**
 * Default launcher: real `ffmpeg` writing raw YUYV into the loopback device.
 *
 * CRITICAL: Always drains `proc.inputStream` with `redirectErrorStream(true)` on a
 * daemon thread into a bounded 16-line ring buffer. Leaving ffmpeg's stderr pipe
 * unread causes an OS pipe deadlock after 64 KiB of log output (freezing OBS black!).
 */
object FfmpegV4l2SinkLauncher : CamDroidSinkLauncher {
    private const val MAX_LOG_LINES = 16

    override fun launch(argv: List<String>): CamDroidSinkHandle {
        val proc = ProcessBuilder(argv)
            .redirectErrorStream(true)
            .start()
        val logRing = ArrayDeque<String>(MAX_LOG_LINES)
        val drainer = Thread({
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        synchronized(logRing) {
                            if (logRing.size >= MAX_LOG_LINES) logRing.removeFirst()
                            logRing.addLast(trimmed.take(240))
                        }
                    }
                }
            }
        }, "ec0-ffmpeg-sink-log").apply { isDaemon = true }
        drainer.start()

        val stdin = BufferedOutputStream(proc.getOutputStream(), 128 * 1024)
        return object : CamDroidSinkHandle {
            override val stdin: OutputStream = stdin
            override val isAlive: Boolean
                get() = proc.isAlive
            override fun exitCode(): Int? = if (proc.isAlive) null else proc.exitValue()
            override fun lastDiagnosticOutput(): String? = synchronized(logRing) {
                logRing.takeLast(4).joinToString(" | ").ifBlank { null }
            }
            override fun close() {
                runCatching { stdin.flush() }
                runCatching { stdin.close() }
                if (proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
        }
    }
}

/**
 * Consumes the phone's CamDroid v1 media stream and feeds the v4l2 sink.
 * One session per instance; the coordinator owns the lifecycle.
 */
class CamDroidStreamReceiver(
    private val sinkLauncher: CamDroidSinkLauncher = FfmpegV4l2SinkLauncher,
    private val v4l2DevicePath: String = CamDroidConstants.DEFAULT_V4L2_DEVICE,
    private val ffmpegBinary: String = "ffmpeg",
    private val connectTimeoutMs: Int = 3_000,
    /** Auto-stop when the phone sends nothing (even heartbeats) this long. */
    private val silenceTimeoutMs: Long = 5_000L,
    /** The phone's config METADATA must arrive within this window after start. */
    private val configAwaitMs: Long = 4_000L,
    /**
     * Arm→bind race: the phone opens :4747 only AFTER the arm envelope is
     * processed over the engine session, so an immediate single dial can beat
     * the bind by a few milliseconds (observed live: every attempt refused
     * while the phone logged 'armed on :4747 — awaiting token handshake').
     * Dial [bindRetryAttempts] times spaced [bindRetryIntervalMs] apart.
     */
    private val bindRetryAttempts: Int = 6,
    private val bindRetryIntervalMs: Long = 300L,
    /**
     * Upstream HEARTBEAT cadence. The phone's server enforces a 5s CLIENT-silence
     * watchdog — and the desktop only ever READS (frames/heartbeats flow
     * downstream), so without these the phone kills every session ~5s after
     * connect (observed live: stream banner goes live, then the sink dies and
     * OBS shows black). The wire doc says 'HEARTBEAT keepalive in BOTH
     * directions'; the downstream leg existed, this is the upstream one.
     */
    private val heartbeatIntervalMs: Long = 2_000L,
    private val effectsProvider: () -> CameraEffectsCommand = { CameraEffectsCommand() },
) : Closeable {

    private val lock = Any()
    private var socket: Socket? = null
    private var readerThread: Thread? = null

    /**
     * Volatile (never guarded by [lock]): the reader thread reads it per frame
     * while `start()` holds [lock] waiting for the config latch — taking the
     * lock here would deadlock the session before the first frame.
     */
    @Volatile
    private var sink: CamDroidSinkHandle? = null

    private val sessionEnded = AtomicBoolean(true)
    private var heartbeatExecutor: ScheduledExecutorService? = null
    private val receivedConfig = AtomicReference<CamDroidStreamConfig?>(null)
    /** Unblocks `start()` once the phone's config METADATA has been parsed. */
    @Volatile
    private var configLatchField: CountDownLatch? = null
    private val framesReceived = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val writeLock = Any()
    private val sinkWriteLock = Any()
    /**
     * Most recent H.264 Annex-B SPS/PPS/IDR keyframe received on this session.
     * Primed into freshly launched ffmpeg sinks (both initial startup when the first
     * keyframe arrives before ffmpeg finishes spawning, and live Studio Effects reloads)
     * so ffmpeg NEVER attempts to decode a P-slice without SPS/PPS/IDR.
     */
    @Volatile
    private var lastH264Keyframe: ByteArray? = null

    private val _snapshot = MutableStateFlow(
        CamDroidReceiverSnapshot(state = CamDroidReceiverState.IDLE),
    )
    val snapshot: StateFlow<CamDroidReceiverSnapshot> = _snapshot.asStateFlow()

    private fun update(update: (CamDroidReceiverSnapshot) -> CamDroidReceiverSnapshot) {
        _snapshot.update(update)
    }

    /**
     * Connects to the phone, performs the token handshake, requests the stream,
     * waits for the phone's config metadata, and launches the v4l2 sink.
     * Synchronous — returns once the pipeline is live or definitively failed.
     */
    /**
     * Bounded dial retry for the arm→bind race above. 'Connection refused'
     * returns instantly, so the full window costs ~[bindRetryAttempts] ×
     * [bindRetryIntervalMs]; an unreachable HOST still pays per-attempt
     * [connectTimeoutMs] — bounded by the attempt count.
     */
    private fun dialWithBindRetry(host: String, port: Int): Socket {
        var lastError: Exception? = null
        repeat(bindRetryAttempts) { attempt ->
            if (attempt > 0) Thread.sleep(bindRetryIntervalMs)
            try {
                return Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), connectTimeoutMs)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("dial failed without an error")
    }

    fun start(
        host: String,
        port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        streamToken: String,
        requestedFps: Int = 30,
        requestedCodec: String = "mjpeg",
    ): CamDroidReceiverSnapshot = synchronized(lock) {
        stopActiveLocked()

        if (streamToken.isBlank()) {
            return failLocked("Refusing to start camera stream: empty token")
        }
        val normalizedCodec = when (requestedCodec.lowercase()) {
            "h264", "mjpeg" -> requestedCodec.lowercase()
            else -> "mjpeg"
        }
        update { CamDroidReceiverSnapshot(state = CamDroidReceiverState.CONNECTING) }
        val sock = try {
            dialWithBindRetry(host, port)
        } catch (e: Exception) {
            return failLocked("Phone camera stream unreachable at $host:$port — ${e.message}")
        }
        socket = sock
        sessionEnded.set(false)
        framesReceived.set(0)
        framesDropped.set(0)
        receivedConfig.set(null)
        val latch = CountDownLatch(1)
        configLatchField = latch

        try {
            val out = sock.getOutputStream()
            // Handshake MUST carry the arm token the desktop delivered to the
            // phone over the AEAD-encrypted engine session.
            val handshake =
                """{"version":"1.0","client":"ec0-desktop","token":"${streamToken}"}"""
            synchronized(writeLock) {
                out.write(
                    CamDroidV1Codec.encode(
                        CamDroidV1Packet(CamDroidMediaKind.METADATA, handshake.encodeToByteArray()),
                    ),
                )
                out.flush()
                out.write(
                    CamDroidV1Codec.encode(
                        CamDroidV1Codec.controlCommandPacket(
                            CamDroidControlCommand.Start(
                                codec = normalizedCodec,
                                resolution = "1080p",
                                fps = requestedFps.coerceIn(30, 60),
                                audio = false,
                            ),
                        ),
                    ),
                )
                out.flush()
            }
        } catch (e: Exception) {
            return failLocked("Camera stream handshake failed — ${e.message}")
        }

        // Upstream heartbeat: feeds the phone's client-silence watchdog. Must
        // start BEFORE the config await — the watchdog runs from connect.
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ec0-camdroid-hb").apply { isDaemon = true }
        }.also { hb ->
            hb.scheduleAtFixedRate({
                if (sessionEnded.get()) return@scheduleAtFixedRate
                runCatching {
                    val sock = socket ?: return@scheduleAtFixedRate
                    synchronized(writeLock) {
                        val out = sock.getOutputStream()
                        out.write(CamDroidV1Codec.encode(CamDroidV1Codec.heartbeatPacket()))
                        out.flush()
                    }
                }
            }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
        }

        val reader = Thread({ readerLoop(sock) }, "ec0-camdroid-receiver").apply {
            isDaemon = true
        }
        readerThread = reader
        reader.start()

        // The phone reports its ACTUAL capture config; the sink needs it before
        // the first frame. Waiting here keeps startCameraStream synchronous.
        if (!latch.await(configAwaitMs, TimeUnit.MILLISECONDS)) {
            return failLocked(
                "Phone never reported its capture config within ${configAwaitMs}ms " +
                    "(camera permission granted? stream token accepted?)",
            )
        }
        val config = receivedConfig.get()
        if (config == null) {
            return failLocked("Phone reported an invalid capture config")
        }
        try {
            val handle = sinkLauncher.launch(buildFfmpegArgv(config))
            synchronized(lock) {
                synchronized(sinkWriteLock) {
                    if (config.codec.equals("h264", ignoreCase = true)) {
                        lastH264Keyframe?.let { cachedKey ->
                            runCatching {
                                handle.stdin.write(cachedKey)
                                handle.stdin.flush()
                                framesReceived.incrementAndGet()
                            }
                        }
                    }
                    sink = handle
                }
            }
            update {
                CamDroidReceiverSnapshot(
                    state = CamDroidReceiverState.STREAMING,
                    config = config,
                    framesReceived = framesReceived.get(),
                    framesDropped = framesDropped.get(),
                )
            }
        } catch (e: Exception) {
            return failLocked("Failed to launch ffmpeg v4l2 sink — ${e.message}")
        }
        return _snapshot.value
    }

    /**
     * Hot-restarts ONLY the local ffmpeg v4l2 sink (keeping the phone TCP media socket open)
     * so Studio Effects (Center Stage / Portrait Blur / Desk View) apply live in OBS.
     */
    fun reconfigureSinkForEffects(): Boolean = synchronized(lock) {
        if (sessionEnded.get()) return false
        val config = receivedConfig.get() ?: return false
        return try {
            val replacement = sinkLauncher.launch(buildFfmpegArgv(config, effectsProvider()))
            val previous = synchronized(sinkWriteLock) {
                if (config.codec.equals("h264", ignoreCase = true)) {
                    lastH264Keyframe?.let { cachedKey ->
                        runCatching {
                            replacement.stdin.write(cachedKey)
                            replacement.stdin.flush()
                        }
                    }
                }
                val prev = sink
                sink = replacement
                prev
            }
            runCatching { previous?.close() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the strict allowlisted ffmpeg pipeline for either hardware H.264
     * Annex-B (`-f h264`) or MJPEG (`-f mjpeg`) piped into `v4l2loopback`.
     *
     * Always enforces even output dimensions and explicit `format=yuyv422` so
     * OBS / Chrome / Zoom never receive dark/misaligned frames on v4l2loopback.
     */
    fun buildFfmpegArgv(
        config: CamDroidStreamConfig,
        effects: CameraEffectsCommand = effectsProvider(),
    ): List<String> {
        val safeInputFormat = when (config.codec.lowercase()) {
            "h264" -> "h264"
            else -> "mjpeg"
        }
        val safeFps = config.fps.coerceIn(1, 60)
        val studioFilters = CameraStudioEffectsController().buildStudioVideoFilterChain(effects)
        val filterChain = buildList {
            addAll(studioFilters)
            // Guarantee even width/height & clean YUYV 4:2:2 conversion for v4l2loopback
            add("scale=trunc(iw/2)*2:trunc(ih/2)*2:flags=fast_bilinear")
            add("format=yuyv422")
        }.joinToString(",")

        return buildList {
            add(ffmpegBinary)
            add("-hide_banner")
            add("-loglevel"); add("error")
            add("-fflags"); add("nobuffer")
            add("-flags"); add("low_delay")
            add("-use_wallclock_as_timestamps"); add("1")
            if (safeInputFormat == "h264") {
                when (effects.hwDecodePreference) {
                    "vaapi" -> {
                        add("-hwaccel"); add("vaapi")
                        add("-hwaccel_device"); add("/dev/dri/renderD128")
                    }
                    "nvdec" -> {
                        add("-hwaccel"); add("cuda")
                    }
                }
                add("-f"); add("h264")
            } else {
                add("-f"); add("mjpeg")
            }
            add("-i"); add("pipe:0")
            add("-vf"); add(filterChain)
            add("-r"); add(safeFps.toString())
            add("-pix_fmt"); add("yuyv422")
            add("-f"); add("v4l2")
            add(v4l2DevicePath)
        }
    }

    private fun readerLoop(sock: Socket) {
        try {
            // The read timeout IS the silence watchdog: any inbound packet
            // (HEARTBEAT included) resets it; a vanished phone trips it.
            sock.soTimeout = silenceTimeoutMs.coerceIn(100, 60_000).toInt()
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            while (!sessionEnded.get()) {
                val packet = readPacketFrom(input)
                when (packet.kind) {
                    CamDroidMediaKind.METADATA -> handleMetadata(packet)
                    CamDroidMediaKind.VIDEO -> handleVideo(packet)
                    CamDroidMediaKind.HEARTBEAT -> Unit
                    CamDroidMediaKind.CONTROL -> handleControl(packet)
                    else -> Unit
                }
            }
        } catch (_: SocketTimeoutException) {
            if (!sessionEnded.get()) {
                endSession(
                    "Phone stream silent for ${silenceTimeoutMs}ms — auto-stopped (phone asleep or out of range)",
                    error = true,
                )
            }
        } catch (_: EOFException) {
            if (!sessionEnded.get()) {
                endSession("Phone closed the camera stream", error = true)
            }
        } catch (_: Exception) {
            if (!sessionEnded.get()) {
                endSession("Camera stream error", error = true)
            }
        }
    }

    private fun handleMetadata(packet: CamDroidV1Packet) {
        if (packet.payload.size > CamDroidStreamConfig.MAX_JSON_BYTES) {
            endSession("Phone config metadata exceeds ${CamDroidStreamConfig.MAX_JSON_BYTES} bytes", error = true)
            return
        }
        val config = CamDroidStreamConfig.fromJson(packet.payload.decodeToString())
        if (config == null) {
            endSession("Phone sent an unparsable capture config", error = true)
            return
        }
        receivedConfig.set(config)
        update { it.copy(config = config) }
        configLatchField?.countDown()
    }

    private fun handleVideo(packet: CamDroidV1Packet) {
        val cfg = receivedConfig.get()
        val isH264 = cfg?.codec?.equals("h264", ignoreCase = true) == true
        var failureReason: String? = null
        synchronized(sinkWriteLock) {
            if (isH264 && CamDroidStreamServer.isAnnexBKeyOrConfig(packet.payload)) {
                lastH264Keyframe = packet.payload
            }
            val activeSink = sink // volatile read — MUST NOT take [lock] (see field doc)
            if (activeSink == null || cfg == null) {
                // Bounded pre-config/pre-sink policy: drop (keyframe is cached above for H.264), never queue.
                framesDropped.incrementAndGet()
                update { it.copy(framesDropped = it.framesDropped + 1) }
                return
            }
            if (!activeSink.isAlive) {
                val diag = activeSink.lastDiagnosticOutput()?.let { " ($it)" }.orEmpty()
                failureReason =
                    "ffmpeg v4l2 sink exited (code ${activeSink.exitCode()})$diag — is the loopback device writable?"
            } else {
                try {
                    activeSink.stdin.write(packet.payload)
                    activeSink.stdin.flush()
                    framesReceived.incrementAndGet()
                    update { it.copy(framesReceived = it.framesReceived + 1) }
                } catch (e: Exception) {
                    failureReason = "Writing to the v4l2 sink failed — ${e.message}"
                }
            }
        }
        failureReason?.let { endSession(it, error = true) }
    }

    private fun handleControl(packet: CamDroidV1Packet) {
        // TCP control plane carries the raw CamDroid wire JSON.
        val command = CamDroidControlCommand.fromCamDroidWireJson(packet.payload.decodeToString())
            ?: return
        if (command is CamDroidControlCommand.Stop) {
            endSession("Phone stopped the camera stream")
        }
    }

    /**
     * Sends a live control command (zoom/switch/...) on the stream socket.
     * Best-effort: returns false when no session is live.
     */
    fun sendControl(command: CamDroidControlCommand): Boolean {
        val sock = socket ?: return false
        if (sessionEnded.get()) return false
        return try {
            synchronized(writeLock) {
                val out = sock.getOutputStream()
                out.write(CamDroidV1Codec.encode(CamDroidV1Codec.controlCommandPacket(command)))
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ends the session, closes the socket, and stops the sink. Synchronous and
     * network-independent: always completes even when the phone is unreachable.
     */
    fun endSession(reason: String, error: Boolean = false) = synchronized(lock) {
        endSessionLocked(reason, error)
    }

    private fun endSessionLocked(reason: String, error: Boolean = false) {
        if (!sessionEnded.compareAndSet(false, true)) {
            update { it.copy(reason = reason) }
            return
        }
        runCatching { socket?.close() }
        socket = null
        runCatching { sink?.close() }
        sink = null
        lastH264Keyframe = null
        configLatchField?.countDown() // unblock a start() waiting for config
        update { snap ->
            CamDroidReceiverSnapshot(
                state = if (error) CamDroidReceiverState.ERROR else CamDroidReceiverState.STOPPED,
                config = snap.config,
                framesReceived = snap.framesReceived,
                framesDropped = snap.framesDropped,
                reason = reason,
            )
        }
    }

    /**
     * Public stop (user action or teardown). Never touches the network: it
     * cannot fail because the peer vanished. Always ends in STOPPED — a user
     * stop overrides any prior auto-stop error state.
     */
    fun stop(reason: String = "Stopped by user") = synchronized(lock) {
        endSessionLocked(reason)
        update { snap ->
            snap.copy(
                state = CamDroidReceiverState.STOPPED,
                reason = reason,
            )
        }
    }

    private fun stopActiveLocked() {
        if (!sessionEnded.get()) {
            endSessionLocked("superseded by a new stream")
        }
        runCatching { heartbeatExecutor?.shutdownNow() }
        heartbeatExecutor = null
        runCatching { socket?.close() }
        socket = null
        runCatching { sink?.close() }
        sink = null
        readerThread = null
        configLatchField = null
    }

    private fun failLocked(reason: String): CamDroidReceiverSnapshot {
        stopActiveLocked()
        update { CamDroidReceiverSnapshot(state = CamDroidReceiverState.ERROR, reason = reason) }
        return _snapshot.value
    }

    override fun close() {
        stop("receiver closed")
    }

    private companion object {
        /** Incremental v1-framing reader over one session stream. */
        fun readPacketFrom(input: DataInputStream): CamDroidV1Packet {
            val kindByte = input.readByte()
            val length = Integer.reverseBytes(input.readInt()) // LE on-wire
            require(length in 0..CamDroidConstants.MAX_PAYLOAD_BYTES) {
                "Invalid CamDroid v1 payload length: $length"
            }
            val payload = ByteArray(length)
            input.readFully(payload)
            return CamDroidV1Packet(
                kind = CamDroidMediaKind.fromWireValue(kindByte),
                payload = payload,
            )
        }
    }
}
