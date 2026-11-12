/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Synergy WebSocket client — per-profile port walk (SynergyPortWalk),
 * per-profile pinned TLS trust (SynergyTrust), RFC 6455 handshake (WsFrames),
 * and the Synergy envelope codec (SynergyEnvelopeCodec). Modeled on upstream
 * OkHttp usage in WebSocketConnectManager: connect timeout 3 s, read/write
 * 50 s, pingInterval 5 s, retryOnConnectionFailure = true (WSCM:466-470).
 *
 * KNOWN UNKNOWNS (T4 capture targets — configurable here on purpose):
 *  - The WS URL path: PCSynergyDevice.java:90 dials wss://<host>:<port> with
 *    NO path — "/" confirmed statically; capture still verifies end-to-end.
 *  - The first-message handshake after the upgrade (upstream sends identity
 *    via the socket per profile — kdeconnect's handshake does NOT apply).
 *    SynergyHandshake makes the first frame configurable; default None.
 *  - Non-base signature schemes for specific message classes.
 */
package ec0.core.synergy

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.net.ssl.SSLSocket

class SynergyWsClient(
    private val host: String,
    private val profile: Profile = Profile.COMPUTER,
    private val path: String = "/", // upstream dials wss://host:port with no path (PCSynergyDevice.java:90)
    private val handshake: SynergyHandshake = SynergyHandshake.None,
    private val connectTimeoutMs: Int = 3_000,
    private val ioTimeoutMs: Int = 50_000,
    private val pingIntervalMs: Long = 5_000, // WSCM:470 pingInterval(5, SECONDS)
    private val logger: Logger = Logger.getLogger("SynergyWsClient"),
) {
    enum class Profile { COMPUTER, PAD }

    /** Post-upgrade first-frame policy — the real Synergy handshake is a T4 unknown. */
    sealed interface SynergyHandshake {
        /** Send nothing; wait for the peer to speak first. Default. */
        data object None : SynergyHandshake

        /** Send a raw text frame first (fill in from T4 capture). */
        data class Raw(val payload: String) : SynergyHandshake
    }

    private val walk = when (profile) {
        Profile.COMPUTER -> SynergyPortWalk.computer()
        Profile.PAD -> SynergyPortWalk.pad()
    }

    /**
     * Walks the profile's ports and performs the TLS + WS upgrade on the
     * first reachable one. Throws IOException after exhausting the walk.
     */
    fun connect(): SynergyWsSession {
        val factory = SynergyTrust.sslSocketFactory(profile)
        var lastError: IOException? = null
        for (port in walk.attempts()) {
            var socket: SSLSocket? = null
            try {
                logger.fine("Synergy WS attempt $host:$port (profile=$profile)")
                socket = (factory.createSocket() as SSLSocket).also { SynergyTrust.configure(it) }
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = ioTimeoutMs
                val session = upgrade(socket, port)
                session.startPingLoop(pingIntervalMs)
                when (val hs = handshake) {
                    is SynergyHandshake.Raw -> session.sendText(hs.payload)
                    SynergyHandshake.None -> Unit
                }
                logger.info("Synergy WS established $host:$port (profile=$profile)")
                return session
            } catch (e: IOException) {
                logger.fine("Synergy WS attempt $host:$port failed: $e")
                lastError = e
                runCatching { socket?.close() }
            }
        }
        throw IOException("Synergy WS port walk exhausted for $host (profile=$profile)", lastError)
    }

    private fun upgrade(socket: SSLSocket, port: Int): SynergyWsSession {
        socket.startHandshake()
        val key = WsFrames.newClientKey()

        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        output.write(WsFrames.buildUpgradeRequest(host, port, path, key).toByteArray())
        output.flush()

        // Read the HTTP response headers (terminated by CRLFCRLF); subsequent
        // bytes on this stream are WS frames.
        val headerBytes = StringBuilder()
        var seen = 0
        while (seen < 4) {
            val b = input.read()
            if (b == -1) throw IOException("Connection closed during WS upgrade")
            val c = b.toChar()
            headerBytes.append(c)
            seen = when {
                c == '\r' && seen % 2 == 0 -> seen + 1
                c == '\n' && seen % 2 == 1 -> seen + 1
                else -> 0
            }
        }
        val (code, headers) = WsFrames.parseUpgradeResponse(headerBytes.toString())
        if (code != 101) throw IOException("WS upgrade refused: HTTP $code")
        val expectedAccept = WsFrames.acceptKey(key)
        if (headers["sec-websocket-accept"] != expectedAccept) {
            throw IOException("WS upgrade accept-key mismatch")
        }

        return SynergyWsSession(socket, input, output, logger)
    }
}

class SynergyWsSession internal constructor(
    private val socket: SSLSocket,
    private val input: DataInputStream,
    private val output: java.io.OutputStream,
    private val logger: Logger = Logger.getLogger("SynergyWsSession"),
) {
    private val closed = AtomicBoolean(false)
    private var pingExecutor: java.util.concurrent.ScheduledExecutorService? = null

    /** Upstream OkHttp pingInterval(5, SECONDS) — daemon pings while open. */
    internal fun startPingLoop(intervalMs: Long) {
        if (pingExecutor != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "synergy-ws-ping").apply { isDaemon = true }
        }
        pingExecutor = executor
        executor.scheduleAtFixedRate({
            if (!closed.get()) {
                runCatching { sendFrame(WsOpcode.PING, ByteArray(0)) }
                    .onFailure { logger.fine("Synergy WS ping failed: $it") }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun send(envelope: SynergyEnvelope) = sendText(SynergyEnvelopeCodec.encode(envelope))

    fun sendText(text: String) = sendFrame(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        output.write(WsFrames.encodeClientFrame(opcode, payload))
        output.flush()
    }

    /**
     * Blocks for the next envelope; null on clean close (close frame echoed
     * per RFC 6455 §5.5.1). Fragmentation reassembled; BINARY frames are a
     * control-plane protocol violation (Synergy envelopes are JSON text).
     */
    fun receive(): SynergyEnvelope? {
        var pending: ByteArray? = null
        while (true) {
            val frame = WsFrames.readFrame(input)
            when (frame.opcode) {
                WsOpcode.TEXT ->
                    if (frame.fin) {
                        return SynergyEnvelopeCodec.decode(String(frame.payload, Charsets.UTF_8))
                    } else {
                        pending = frame.payload
                    }
                WsOpcode.CONTINUATION -> {
                    val start = pending
                        ?: throw IOException("CONTINUATION frame without a preceding TEXT frame")
                    pending = start + frame.payload
                    if (frame.fin) {
                        val message = pending!!
                        pending = null
                        return SynergyEnvelopeCodec.decode(String(message, Charsets.UTF_8))
                    }
                }
                WsOpcode.BINARY -> throw IOException(
                    "Unexpected BINARY frame on the Synergy control plane (envelopes are JSON text)",
                )
                WsOpcode.PING -> sendFrame(WsOpcode.PONG, frame.payload)
                WsOpcode.PONG -> Unit
                WsOpcode.CLOSE -> {
                    // RFC 6455 §5.5.1: echo the received close payload back
                    sendFrame(WsOpcode.CLOSE, frame.payload)
                    logger.fine("Peer closed Synergy WS (echoed close)")
                    return null
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pingExecutor?.shutdownNow()
        try {
            sendFrame(WsOpcode.CLOSE, ByteArray(0))
        } catch (_: IOException) {
        }
        runCatching { socket.close() }
    }
}
