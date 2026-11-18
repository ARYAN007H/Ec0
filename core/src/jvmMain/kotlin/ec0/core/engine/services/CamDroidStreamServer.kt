/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0's phone-side Continuity Camera stream server (CamDroid ProtocolHandler v1
 * wire-compatible, TCP :4747): a *transient, token-gated* server that only
 * exists while an explicit `cam.control.cmd` Start — signed and AEAD-encrypted
 * over the paired ec0-engine session — has armed it.
 *
 * Security posture (the phone camera is its most sensitive device):
 *   - NOT permanently listening: the socket opens on `arm(token)` and closes on
 *     session end, on arm TTL expiry, or on `disarm()`. Nothing to scan for
 *     between streams.
 *   - The desktop's TCP METADATA handshake must carry the SAME token the arm
 *     command delivered over the encrypted session (constant-time compare).
 *   - Client silence (no inbound packet for [clientSilenceTimeoutMs]) ends the
 *     session and disarms — a vanished peer can never leave the camera hot.
 *   - `stop` control command and socket teardown always stop capture, even
 *     when the client side is already gone.
 *
 * Media: bounded single-slot MJPEG handoff from the platform frame source
 * (newest frame wins, stale frames are explicitly dropped), paced at
 * [frameIntervalMs], with HEARTBEAT keepalive in both directions.
 */
package ec0.core.engine.services

import ec0.core.engine.EngineEncoding
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CamDroidMediaKind
import ec0.core.engine.protocol.CamDroidV1Codec
import ec0.core.engine.protocol.CamDroidV1Packet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** JPEG payload a platform capture source hands to [CamDroidStreamServer]. */
interface CamDroidFrameSource : Closeable {
    /**
     * Starts capture. The server calls [sink] from the capture thread whenever a
     * fresh JPEG frame is available; implementations MUST be non-blocking and
     * apply their own latest-wins drop policy (the server slot is bounded, so a
     * slow consumer never grows a queue). [onError] ends the session honestly.
     */
    fun start(sink: (ByteArray) -> Unit, onError: (String) -> Unit)

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
 * Token-gated CamDroid v1 stream server. One armed session at a time; the
 * phone's ec0 companion owns a single instance per process.
 */
class CamDroidStreamServer(
    private val frameSourceFactory: () -> CamDroidFrameSource,
    private val port: Int = CamDroidConstants.DEFAULT_TCP_PORT,
    private val width: Int = 640,
    private val height: Int = 480,
    private val targetFps: Int = 30,
    private val codec: String = "mjpeg",
    /** Auto-disarm when no client completed the handshake within this window. */
    private val armTtlMs: Long = 10_000L,
    /** End the session when the client sends nothing for this long. */
    private val clientSilenceTimeoutMs: Long = 5_000L,
    /** Handshake must complete within this window after TCP accept. */
    private val handshakeTimeoutMs: Long = 3_000L,
    private val heartbeatIntervalMs: Long = 2_000L,
    /** Defensive cap on one frame's payload before it goes on the wire. */
    private val maxFrameBytes: Int = 2 * 1024 * 1024,
) : Closeable {

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var armedToken: ByteArray? = null

    private val sessionEnded = AtomicBoolean(true)
    private var sessionSocket: Socket? = null
    private var frameSource: CamDroidFrameSource? = null

    /** Bounded latest-wins slot: the capture thread overwrites, the writer drains. */
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val framesSent = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val writeLock = Any()

    /** Live control commands from the desktop (zoom/switch/...) for the owner. */
    @Volatile
    var onControlCommand: ((CamDroidControlCommand) -> Unit)? = null

    private val _snapshot = AtomicReference(
        CamDroidServerSnapshot(state = CamDroidServerState.IDLE),
    )
    val snapshot: CamDroidServerSnapshot get() = _snapshot.get()

    private fun update(update: (CamDroidServerSnapshot) -> CamDroidServerSnapshot) {
        _snapshot.updateAndGet(update)
    }

    /**
     * Arms the server with [token] (hex, delivered by the desktop over the
     * AEAD-encrypted engine session). Re-arming closes any previous window.
     */
    fun arm(token: String): CamDroidArmResult = synchronized(lock) {
        if (token.isBlank()) return CamDroidArmResult.Failed("Empty stream token — refusing to arm")
        disarmLocked()
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 1)
            }
        } catch (e: Exception) {
            return CamDroidArmResult.Failed("Cannot bind camera stream server on :$port — ${e.message}")
        }
        serverSocket = socket
        armedToken = token.encodeToByteArray()
        update {
            CamDroidServerSnapshot(
                state = CamDroidServerState.ARMED,
                port = socket.localPort,
            )
        }
        val thread = Thread(
            {
                runCatching { acceptLoop(socket) }
            },
            "ec0-camdroid-server-accept",
        )
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        CamDroidArmResult.Armed(socket.localPort)
    }

    /**
     * Closes the listening socket and any live session. Always succeeds and
     * always lands the server in IDLE — a disarmed server holds nothing.
     */
    fun disarm() = synchronized(lock) {
        disarmLocked()
        update { snap ->
            CamDroidServerSnapshot(
                state = CamDroidServerState.IDLE,
                port = null,
                framesSent = snap.framesSent,
                framesDropped = snap.framesDropped,
                reason = snap.reason,
            )
        }
    }

    private fun disarmLocked() {
        armedToken = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        if (!sessionEnded.get()) {
            endSessionLocked("server disarmed")
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        val deadline = System.currentTimeMillis() + armTtlMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val client = try {
                server.soTimeout = remaining
                server.accept()
            } catch (_: SocketTimeoutException) {
                break // armed window expired with no client
            } catch (_: Exception) {
                return // disarmed / closed
            }
            // Exactly one client: anything arriving while a session is live is
            // rejected outright (walled garden, no second consumer of the camera).
            if (!sessionEnded.get()) {
                runCatching { client.close() }
                continue
            }
            client.soTimeout = handshakeTimeoutMs.coerceIn(100, 30_000).toInt()
            // One buffered stream for the WHOLE session: the desktop sends its
            // handshake and Start command back-to-back, often in a single TCP
            // segment — a fresh stream after the handshake would swallow them.
            val sessionInput = DataInputStream(BufferedInputStream(client.getInputStream()))
            val handshake = try {
                readPacketFrom(sessionInput)
            } catch (_: SocketTimeoutException) {
                runCatching { client.close() }
                synchronized(lock) { disarmLocked() }
                update { it.copy(state = CamDroidServerState.IDLE, reason = "Handshake never arrived — disarmed") }
                return
            } catch (_: Exception) {
                runCatching { client.close() }
                synchronized(lock) { disarmLocked() }
                update { it.copy(state = CamDroidServerState.IDLE, reason = "Handshake failed — disarmed") }
                return
            }
            if (!handshakeValid(handshake)) {
                runCatching { client.close() }
                synchronized(lock) { disarmLocked() }
                update { it.copy(state = CamDroidServerState.IDLE, reason = "Rejected camera stream handshake: bad token") }
                return
            }
            beginSession(client, sessionInput)
            return
        }
        // TTL expired: nothing connected, close the door again.
        synchronized(lock) {
            runCatching { server.close() }
            if (serverSocket === server) {
                serverSocket = null
                armedToken = null
                acceptThread = null
                update { it.copy(state = CamDroidServerState.IDLE, reason = "Arm window expired — nobody connected") }
            }
        }
    }

    private fun handshakeValid(packet: CamDroidV1Packet): Boolean {
        if (packet.kind != CamDroidMediaKind.METADATA) return false
        val token = armedToken ?: return false
        val provided = runCatching {
            parseHandshakeToken(packet.payload.decodeToString())
        }.getOrNull() ?: return false
        return EngineEncoding.constantTimeEquals(token, provided.encodeToByteArray())
    }

    private fun beginSession(client: Socket, sessionInput: DataInputStream) {
        synchronized(lock) {
            sessionSocket = client
            sessionEnded.set(false)
            framesSent.set(0)
            framesDropped.set(0)
            latestFrame.set(null)
            update { it.copy(state = CamDroidServerState.STREAMING, port = client.localPort) }

            val source = frameSourceFactory()
            frameSource = source
            val started = runCatching {
                source.start(
                    sink = { frame -> offerFrame(frame) },
                    onError = { msg -> endSession("capture failed: $msg", error = true) },
                )
            }
            if (started.isFailure) {
                // Capture unavailable (permission revoked, camera busy, ...):
                // end honestly instead of streaming heartbeats forever.
                endSessionLocked(
                    "capture could not start: ${started.exceptionOrNull()?.message}",
                    error = true,
                )
            }

            val writer = Thread({ writerLoop(client) }, "ec0-camdroid-server-writer").apply {
                isDaemon = true
            }
            writer.start()

            val reader = Thread({ readerLoop(client, sessionInput) }, "ec0-camdroid-server-reader").apply {
                isDaemon = true
            }
            reader.start()
        }
    }

    /**
     * Latest-wins bounded handoff: overwrite any unconsumed frame. The
     * overwritten bytes are counted as explicitly dropped — capture NEVER
     * queues behind a slow consumer.
     */
    private fun offerFrame(frame: ByteArray) {
        if (frame.size > maxFrameBytes) {
            framesDropped.incrementAndGet()
            return
        }
        val previous = latestFrame.getAndSet(frame)
        if (previous != null) {
            framesDropped.incrementAndGet()
        }
    }

    private fun writerLoop(client: Socket) {
        try {
            val out = client.getOutputStream()
            // Send the actual capture config FIRST so the desktop sink launches
            // with the real dimensions/framerate (honest telemetry, no guessing).
            val config = CamDroidStreamConfig(
                width = width,
                height = height,
                fps = targetFps,
                codec = codec,
            ).toJsonObject().toString()
            synchronized(writeLock) {
                out.write(CamDroidV1Codec.encode(CamDroidV1Packet(CamDroidMediaKind.METADATA, config.encodeToByteArray())))
                out.flush()
            }

            val frameIntervalMs = (1000L / targetFps.coerceIn(1, 60)).coerceAtLeast(1L)
            var nextFrameDeadline = System.currentTimeMillis() + frameIntervalMs
            var lastHeartbeatSent = System.currentTimeMillis()
            while (!sessionEnded.get()) {
                val now = System.currentTimeMillis()
                if (now >= nextFrameDeadline) {
                    nextFrameDeadline = now + frameIntervalMs
                    val frame = latestFrame.getAndSet(null)
                    if (frame != null) {
                        synchronized(writeLock) {
                            out.write(CamDroidV1Codec.encode(CamDroidV1Packet(CamDroidMediaKind.VIDEO, frame)))
                            out.flush()
                        }
                        framesSent.incrementAndGet()
                        // Keep the observable snapshot's counters in lockstep.
                        update { it.copy(framesSent = framesSent.get(), framesDropped = framesDropped.get()) }
                    }
                }
                if (now - lastHeartbeatSent >= heartbeatIntervalMs) {
                    synchronized(writeLock) {
                        out.write(CamDroidV1Codec.encode(CamDroidV1Codec.heartbeatPacket()))
                        out.flush()
                    }
                    lastHeartbeatSent = now
                }
                val sleepMs = minOf(
                    nextFrameDeadline - System.currentTimeMillis(),
                    heartbeatIntervalMs,
                ).coerceAtLeast(1L)
                Thread.sleep(sleepMs)
            }
        } catch (_: Exception) {
            if (!sessionEnded.get()) {
                endSession("client connection lost", error = true)
            }
        }
    }

    private fun readerLoop(client: Socket, input: DataInputStream) {
        try {
            // A silent client ends the session: the read timeout IS the watchdog.
            client.soTimeout = clientSilenceTimeoutMs.coerceIn(100, 60_000).toInt()
            while (!sessionEnded.get()) {
                val packet = readPacketFrom(input)
                when (packet.kind) {
                    CamDroidMediaKind.HEARTBEAT -> Unit
                    CamDroidMediaKind.CONTROL -> handleControl(packet)
                    else -> Unit // video/audio/metadata are upstream-only: ignore
                }
            }
        } catch (_: SocketTimeoutException) {
            if (!sessionEnded.get()) {
                endSession("client silent for ${clientSilenceTimeoutMs}ms — auto-stopped", error = true)
            }
        } catch (_: EOFException) {
            if (!sessionEnded.get()) {
                endSession("desktop closed the stream socket", error = true)
            }
        } catch (_: SocketException) {
            if (!sessionEnded.get()) {
                endSession("client connection lost", error = true)
            }
        } catch (_: Exception) {
            if (!sessionEnded.get()) {
                endSession("client stream error", error = true)
            }
        }
    }

    private fun handleControl(packet: CamDroidV1Packet) {
        // TCP control plane carries the raw CamDroid wire JSON, not the
        // engine-envelope representation — parse accordingly.
        val command = CamDroidControlCommand.fromCamDroidWireJson(packet.payload.decodeToString())
            ?: return
        if (command is CamDroidControlCommand.Stop) {
            endSession("stopped by desktop")
            return
        }
        onControlCommand?.invoke(command)
    }

    /**
     * Ends the live session and disarms. Synchronous, network-independent —
     * safe to call from any thread, safe when the peer already vanished.
     */
    fun endSession(reason: String, error: Boolean = false) = synchronized(lock) {
        endSessionLocked(reason, error)
    }

    private fun endSessionLocked(reason: String, error: Boolean = false) {
        if (!sessionEnded.compareAndSet(false, true)) {
            // Already ended — keep the FIRST reason (e.g. "stopped by desktop"),
            // not whichever teardown thread got here last.
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
        disarmLocked()
        update { it.copy(state = CamDroidServerState.IDLE) }
    }

    /** Telemetry hook: frames dropped by the bounded slot or the size guard. */
    fun droppedFrameCount(): Long = framesDropped.get()

    private companion object {
        fun parseHandshakeToken(json: String): String? {
            val obj = Json.decodeFromString(JsonObject.serializer(), json)
            val client = obj["client"]?.jsonPrimitive?.contentOrNull ?: return null
            if (client.isBlank()) return null
            return obj["token"]?.jsonPrimitive?.contentOrNull
        }

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

/** Actual capture config the phone reports right after the handshake (`METADATA`). */
data class CamDroidStreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("type", "config")
        put("width", width.toLong())
        put("height", height.toLong())
        put("fps", fps.toLong())
        put("codec", codec)
    }

    companion object {
        /** Defensive bound on the config metadata payload. */
        const val MAX_JSON_BYTES: Int = 4096

        fun fromJson(json: String): CamDroidStreamConfig? = runCatching {
            val obj = Json.decodeFromString(JsonObject.serializer(), json)
            CamDroidStreamConfig(
                width = obj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null,
                height = obj["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null,
                fps = obj["fps"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null,
                codec = obj["codec"]?.jsonPrimitive?.contentOrNull ?: "mjpeg",
            )
        }.getOrNull()?.takeIf {
            it.width in 1..4096 && it.height in 1..4096 && it.fps in 1..60 &&
                it.width * it.height <= 4096L * 2160L
        }
    }
}
