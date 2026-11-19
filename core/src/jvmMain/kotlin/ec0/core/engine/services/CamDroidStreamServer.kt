/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0's phone-side Continuity Camera stream server — CamDroid wire protocol
 * v2 ("CMD2"), ported from ARYAN007H/CamDroid's proven engine with ec0's
 * walled-garden posture layered on top:
 *   - TRANSIENT: the socket opens only after an arm(token) — the token was
 *     delivered by the desktop over the AEAD-encrypted engine session. Between
 *     streams there is nothing to scan for.
 *   - TOKEN-GATED: the client's v2 Hello must carry `client: "ec0|<token>"`;
 *     the comparison is constant-time. CamDroid's own clients (which send no
 *     token) are rejected honestly — only a paired ec0 desktop can stream.
 *   - SILENCE ENDS IT: no inbound packet (heartbeat included) for
 *     clientSilenceTimeoutMs ends the session and disarms — a vanished peer
 *     can never leave the camera hot. The server feeds the same watchdog
 *     downstream so the desktop's side never starves either.
 *   - CAPABILITY-CLAMPED: the advertised modes come from the frame source's
 *     REAL encoder limits and the client's Start is resolved against them.
 *     Requesting 60fps from a 30fps encoder used to crash MediaCodec.configure
 *     (the A03) — an un-advertised mode is now rejected, never configured.
 *
 * Media: hardware H.264 NAL units from the frame source, bounded single-slot
 * handoff (newest frame wins), paced at the negotiated fps.
 */
package ec0.core.engine.services

import ec0.core.engine.EngineEncoding
import kotlinx.serialization.json.JsonObject
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.services.camdroid.CamDroidCapabilities
import ec0.core.engine.services.camdroid.CamDroidModeResolver
import ec0.core.engine.services.camdroid.CamDroidSessionMessages
import ec0.core.engine.services.camdroid.CamDroidV2
import ec0.core.engine.services.camdroid.CamDroidVideoMode
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One encoded H.264 unit from the capture engine. */
interface CamDroidH264Sink {
    /**
     * Called from the capture thread for every encoder output unit:
     * [data] is a NAL unit (or the SPS/PPS [isConfig] bundle), [isKeyFrame]
     * marks an IDR, [ptsNs] is the capture timestamp in nanoseconds.
     * Implementations MUST be non-blocking (bounded handoff downstream).
     */
    fun onEncoded(data: ByteArray, isConfig: Boolean, isKeyFrame: Boolean, ptsNs: Long)
}

/**
 * The capture engine behind the server: queries its own honest limits, then
 * produces hardware-encoded H.264 NAL units when started with a negotiated,
 * clamped mode.
 */
interface CamDroidH264FrameSource : Closeable {
    /** The modes this device ACTUALLY supports — advertised verbatim. */
    fun supportedModes(): List<CamDroidVideoMode>

    /** Preferred codec label from the device ("h264" expected, "h265" legal). */
    fun preferredCodec(): String = "h264"

    /**
     * Starts capture with a mode ALREADY resolved against [supportedModes].
     * [sink] receives NAL units from the capture thread; [onError] ends the
     * session honestly.
     */
    fun start(mode: CamDroidVideoMode, bitrateBps: Long, sink: CamDroidH264Sink, onError: (String) -> Unit)

    override fun close()
}

/** Lifecycle of the phone-side camera stream server. */
enum class CamDroidServerState(val wireName: String) {
    IDLE("idle"),
    ARMED("armed"),
    STREAMING("streaming"),
    ERROR("error"),
    STOPPED("stopped"),
}

/** Result of arming the stream server for one desktop-initiated session. */
sealed interface CamDroidArmResult {
    /** Server listening on [port]; awaiting the token-carrying handshake. */
    data class Armed(val port: Int) : CamDroidArmResult

    /** Bind failed (port taken, permission, etc.) — [reason] is honest. */
    data class Failed(val reason: String) : CamDroidArmResult
}

/** Observable server snapshot for status reporting and tests. */
data class CamDroidServerSnapshot(
    val state: CamDroidServerState,
    val port: Int? = null,
    val framesSent: Long = 0,
    val framesDropped: Long = 0,
    val reason: String? = null,
)

/**
 * Token-gated CamDroid v2 stream server. One armed session at a time; the
 * phone's ec0 companion owns a single instance per process.
 */
class CamDroidStreamServer(
    private val frameSourceFactory: () -> CamDroidH264FrameSource,
    private val port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
    /** Auto-disarm when no client completed the handshake within this window. */
    private val armTtlMs: Long = 10_000L,
    /** End the session when the client sends nothing for this long. */
    private val clientSilenceTimeoutMs: Long = 5_000L,
    /** Session setup (hello + start) must complete within this window. */
    private val handshakeTimeoutMs: Long = 3_000L,
    private val heartbeatIntervalMs: Long = 2_000L,
    /** Defensive cap on one frame's payload before it goes on the wire. */
    private val maxFrameBytes: Int = 4 * 1024 * 1024,
) : Closeable {

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var sessionSocket: Socket? = null
    private var frameSource: CamDroidH264FrameSource? = null
    private var armedToken: String? = null
    private var acceptThread: Thread? = null
    private val sessionEnded = AtomicBoolean(true)
    private val writeLock = Any()

    private val _snapshot = AtomicReference(
        CamDroidServerSnapshot(state = CamDroidServerState.IDLE),
    )
    val snapshot: CamDroidServerSnapshot get() = _snapshot.get()

    /** Live camera controls (zoom/switch/flash) from the stream client. */
    var onControlCommand: ((CamDroidControlCommand) -> Unit)? = null

    private val framesSent = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val latestFrameIsKey = AtomicBoolean(false)

    private fun update(update: (CamDroidServerSnapshot) -> CamDroidServerSnapshot) {
        _snapshot.updateAndGet(update)
    }

    /**
     * Opens the listening socket and stores the one-shot token. [token] MUST
     * be non-blank — the callers deliver it to the desktop over the encrypted
     * engine session, and the handshake is worthless without it.
     */
    fun arm(token: String): CamDroidArmResult = synchronized(lock) {
        if (token.isBlank()) return CamDroidArmResult.Failed("arm refused: blank token")
        disarmLocked()
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 1)
            }
        } catch (e: Exception) {
            return CamDroidArmResult.Failed("Cannot bind camera stream server on :$port — ${e.message}")
        }
        serverSocket = server
        armedToken = token
        sessionEnded.set(false)
        update { CamDroidServerSnapshot(state = CamDroidServerState.ARMED, port = port) }
        acceptThread = thread(name = "ec0-camdroid-accept", isDaemon = true) { acceptLoop(server) }
        CamDroidArmResult.Armed(port)
    }

    fun disarm() = synchronized(lock) { disarmLocked() }

    private fun disarmLocked() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        armedToken = null
        acceptThread = null
    }

    private fun acceptLoop(server: ServerSocket) {
        val deadline = System.currentTimeMillis() + armTtlMs
        try {
            while (System.currentTimeMillis() < deadline && !sessionEnded.get()) {
                server.soTimeout = 500
                val client = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (sessionSocket != null) {
                    // One session at a time: refuse a second client honestly.
                    runCatching { client.close() }
                    continue
                }
                beginSession(client)
            }
            if (!sessionEnded.get()) {
                // TTL expired: nothing connected, close the door again.
                synchronized(lock) { disarmLocked() }
                update { CamDroidServerSnapshot(state = CamDroidServerState.IDLE, reason = "arm TTL expired") }
            }
        } catch (_: Exception) {
            if (!sessionEnded.get()) endSession("accept loop failed", error = true)
        }
    }

    /**
     * v2 session setup on the accepted socket:
     *   1. client Hello — `client` field MUST be `ec0|<armed token>`.
     *   2. server Capabilities — the frame source's real modes.
     *   3. client Start — resolved against the modes; unsupported = rejected.
     *   4. server StreamAccepted — then media flows.
     */
    private fun beginSession(client: Socket) {
        sessionSocket = client
        client.tcpNoDelay = true
        try {
            client.soTimeout = handshakeTimeoutMs.coerceIn(100, 60_000).toInt()
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))

            val hello = CamDroidV2.readPacket(input)
            require(hello.kind == CamDroidV2.KIND_METADATA) { "expected a METADATA hello" }
            val helloBody = CamDroidSessionMessages.parseType(hello.payload)
                ?: throw IllegalArgumentException("hello is not a session message")
            require(helloBody.first == "hello") { "expected a hello, got ${helloBody.first}" }
            val clientField = helloBody.second["client"]?.toString()?.trim('"')
                ?: throw IllegalArgumentException("hello carries no client identity")
            val presentedToken = clientField.removePrefix("ec0|")
            if (clientField == presentedToken) {
                throw IllegalArgumentException("hello client identity lacks the ec0 token prefix")
            }
            val expected = armedToken ?: throw IllegalArgumentException("server is not armed")
            if (!EngineEncoding.constantTimeEquals(presentedToken.encodeToByteArray(), expected.encodeToByteArray())) {
                throw IllegalArgumentException("stream token rejected")
            }

            val source = frameSourceFactory().also { frameSource = it }
            val capabilities = CamDroidCapabilities(
                codecs = listOf(source.preferredCodec()),
                videoModes = source.supportedModes(),
            )
            CamDroidV2.Packet(
                kind = CamDroidV2.KIND_METADATA,
                flags = 0,
                sessionId = 0,
                sequence = 0,
                captureTimestampNs = 0,
                payload = CamDroidSessionMessages.capabilities(capabilities).toString().encodeToByteArray(),
            ).writeTo(client.getOutputStream())

            // Start: mode resolution happens HERE — the client's desired shape
            // is clamped to the advertised modes (the A03 60fps crash lesson).
            val start = CamDroidV2.readPacket(input)
            require(start.kind == CamDroidV2.KIND_METADATA) { "expected a METADATA start" }
            val startBody = CamDroidSessionMessages.parseType(start.payload)
                ?: throw IllegalArgumentException("start is not a session message")
            require(startBody.first == "start") { "expected a start, got ${startBody.first}" }
            val config = startBody.second["config"] as? JsonObject
                ?: throw IllegalArgumentException("start carries no config")
            val desiredCodec = config["codec"]?.toString()?.trim('"') ?: source.preferredCodec()
            val desiredW = config["width"]?.toString()?.trim('"')?.toIntOrNull() ?: 1920
            val desiredH = config["height"]?.toString()?.trim('"')?.toIntOrNull() ?: 1080
            val desiredFps = config["fps"]?.toString()?.trim('"')?.toIntOrNull() ?: 30

            val codec = capabilities.codecs.firstOrNull { it.equals(desiredCodec, ignoreCase = true) }
                ?: capabilities.codecs.firstOrNull()
                ?: throw IllegalArgumentException("the device advertises no video codecs")
            val mode = CamDroidModeResolver.resolve(
                capabilities, codec, desiredW, desiredH, desiredFps,
            ) ?: throw IllegalArgumentException("the device advertises no video modes")
            val bitrate = CamDroidModeResolver.recommendedBitrate(mode.width, mode.height, mode.fps, codec)
                .coerceIn(mode.minBitrateBps, mode.maxBitrateBps)
            val sessionId = System.currentTimeMillis()

            CamDroidV2.Packet(
                kind = CamDroidV2.KIND_METADATA,
                flags = 0,
                sessionId = sessionId,
                sequence = 0,
                captureTimestampNs = 0,
                payload = CamDroidSessionMessages.streamAccepted(sessionId, codec, mode, bitrate)
                    .toString().encodeToByteArray(),
            ).writeTo(client.getOutputStream())

            update { it.copy(state = CamDroidServerState.STREAMING, port = port) }

            // Media session is live: watchdogs hand over to the session cadence.
            client.soTimeout = clientSilenceTimeoutMs.coerceIn(100, 60_000).toInt()
            startMedia(client, source, sessionId, mode, bitrate)
        } catch (t: Throwable) {
            runCatching { client.close() }
            sessionSocket = null
            runCatching { frameSource?.close() }
            frameSource = null
            endSession("handshake failed — ${t.message}", error = true)
        }
    }

    private fun startMedia(
        client: Socket,
        source: CamDroidH264FrameSource,
        sessionId: Long,
        mode: CamDroidVideoMode,
        bitrate: Long,
    ) {
        val out = client.getOutputStream()
        val writer = thread(name = "ec0-camdroid-writer", isDaemon = true) {
            try {
                val frameIntervalMs = (1000L / mode.fps.coerceIn(1, 60)).coerceAtLeast(1L)
                var nextFrameDeadline = System.currentTimeMillis() + frameIntervalMs
                var lastHeartbeatSent = System.currentTimeMillis()
                var sequence = 1L
                while (!sessionEnded.get()) {
                    val now = System.currentTimeMillis()
                    if (now >= nextFrameDeadline) {
                        nextFrameDeadline = now + frameIntervalMs
                        val frame = latestFrame.getAndSet(null)
                        if (frame != null && frame.size <= maxFrameBytes) {
                            val isKey = latestFrameIsKey.getAndSet(false)
                            val flags = if (isKey) CamDroidV2.FLAG_KEY_FRAME else 0
                            synchronized(writeLock) {
                                CamDroidV2.Packet(
                                    kind = CamDroidV2.KIND_VIDEO,
                                    flags = flags,
                                    sessionId = sessionId,
                                    sequence = sequence++,
                                    captureTimestampNs = System.nanoTime(),
                                    payload = frame,
                                ).writeTo(out)
                            }
                            framesSent.incrementAndGet()
                            update { it.copy(framesSent = framesSent.get(), framesDropped = framesDropped.get()) }
                        }
                    }
                    if (now - lastHeartbeatSent >= heartbeatIntervalMs) {
                        synchronized(writeLock) {
                            CamDroidV2.Packet.heartbeat(sessionId, sequence++).writeTo(out)
                        }
                        lastHeartbeatSent = now
                    }
                    Thread.sleep(
                        minOf(
                            nextFrameDeadline - System.currentTimeMillis(),
                            heartbeatIntervalMs,
                        ).coerceAtLeast(1L),
                    )
                }
            } catch (_: Exception) {
                if (!sessionEnded.get()) endSession("media writer failed", error = true)
            }
        }

        source.start(
            mode = mode,
            bitrateBps = bitrate,
            sink = object : CamDroidH264Sink {
                override fun onEncoded(data: ByteArray, isConfig: Boolean, isKeyFrame: Boolean, ptsNs: Long) {
                    if (sessionEnded.get()) return
                    if (isConfig) {
                        // SPS/PPS: send immediately, marked CONFIGURATION.
                        synchronized(writeLock) {
                            runCatching {
                                CamDroidV2.Packet(
                                    kind = CamDroidV2.KIND_VIDEO_CONFIG,
                                    flags = CamDroidV2.FLAG_CONFIGURATION,
                                    sessionId = sessionId,
                                    sequence = 0,
                                    captureTimestampNs = ptsNs,
                                    payload = data,
                                ).writeTo(out)
                            }
                        }
                        return
                    }
                    // Capture thread: park the newest NAL in the bounded slot.
                    if (data.size > maxFrameBytes) {
                        framesDropped.incrementAndGet()
                        return
                    }
                    latestFrame.set(data)
                    latestFrameIsKey.set(isKeyFrame)
                }
            },
            onError = { reason -> if (!sessionEnded.get()) endSession("capture engine: $reason", error = true) },
        )

        // Reader: consumes the client's heartbeats/controls; the read timeout
        // IS the silence watchdog — a vanished desktop ends the session.
        thread(name = "ec0-camdroid-reader", isDaemon = true) {
            try {
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                while (!sessionEnded.get()) {
                    val packet = CamDroidV2.readPacket(input)
                    when (packet.kind) {
                        CamDroidV2.KIND_HEARTBEAT -> Unit
                        else -> Unit // media kinds are upstream-only inbound
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (!sessionEnded.get()) endSession("client silent for ${clientSilenceTimeoutMs}ms — auto-stopped", error = true)
            } catch (_: EOFException) {
                if (!sessionEnded.get()) endSession("desktop closed the stream socket", error = true)
            } catch (_: SocketException) {
                if (!sessionEnded.get()) endSession("client connection lost", error = true)
            } catch (_: Exception) {
                if (!sessionEnded.get()) endSession("client reader failed", error = true)
            } finally {
                runCatching { writer.join(1_000) }
                if (!sessionEnded.get()) endSession("session reader ended", error = false)
            }
        }
    }

    fun endSession(reason: String, error: Boolean = false) = synchronized(lock) {
        endSessionLocked(reason, error)
    }

    private fun endSessionLocked(reason: String, error: Boolean = false) {
        if (!sessionEnded.compareAndSet(false, true)) {
            // Already ended — keep the FIRST reason, not the last teardown's.
            return
        }
        runCatching { sessionSocket?.close() }
        sessionSocket = null
        runCatching { frameSource?.close() }
        frameSource = null
        // disarm as well: a stopped session never leaves a listening socket.
        runCatching { serverSocket?.close() }
        serverSocket = null
        armedToken = null
        acceptThread = null
        latestFrame.set(null)
        update { snap ->
            CamDroidServerSnapshot(
                state = if (error) CamDroidServerState.ERROR else CamDroidServerState.STOPPED,
                framesSent = snap.framesSent,
                framesDropped = snap.framesDropped,
                reason = reason,
            )
        }
    }

    override fun close() = synchronized(lock) {
        endSessionLocked("server closed")
        disarmLocked()
    }

    fun droppedFrameCount(): Long = framesDropped.get()
}
