/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Synergy WebSocket client — per-profile port walk (SynergyPortWalk), pinned
 * TLS trust (SynergyTrust), RFC 6455 handshake (WsFrames), and the Synergy
 * envelope codec (SynergyEnvelopeCodec). Modeled on upstream OkHttp usage in
 * WebSocketConnectManager: connect timeout 3 s, read/write 50 s, ping 5 s,
 * retryOnConnectionFailure = true (WSCM:466-470).
 *
 * KNOWN UNKNOWNS (T4 capture targets — all configurable here on purpose):
 *  - The WS URL path upstream requests (we default to "/" — never observed).
 *  - The first-message handshake after the upgrade (upstream sends identity
 *    via the socket per profile — kdeconnect's handshake does NOT apply).
 *    SynergyHandshake makes the first frame configurable; default None.
 *  - Non-base signature schemes for specific message classes.
 */
package ec0.core.synergy

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.logging.Logger
import javax.net.ssl.SSLSocket

class SynergyWsClient(
    private val host: String,
    private val profile: Profile = Profile.COMPUTER,
    private val path: String = "/", // T4 UNKNOWN — upstream path never observed statically
    private val handshake: SynergyHandshake = SynergyHandshake.None,
    private val connectTimeoutMs: Int = 3_000,
    private val ioTimeoutMs: Int = 50_000,
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
        val factory = SynergyTrust.sslSocketFactory()
        var lastError: IOException? = null
        for (port in walk.attempts()) {
            var socket: SSLSocket? = null
            try {
                logger.fine("Synergy WS attempt $host:$port (profile=$profile)")
                socket = (factory.createSocket() as SSLSocket).also { SynergyTrust.configure(it) }
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = ioTimeoutMs
                val session = upgrade(socket, port)
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

        return SynergyWsSession(socket, input, output)
    }
}

class SynergyWsSession internal constructor(
    private val socket: SSLSocket,
    private val input: DataInputStream,
    private val output: java.io.OutputStream,
    private val logger: Logger = Logger.getLogger("SynergyWsSession"),
) {
    fun send(envelope: SynergyEnvelope) = sendText(SynergyEnvelopeCodec.encode(envelope))

    fun sendText(text: String) {
        output.write(WsFrames.encodeClientFrame(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8)))
        output.flush()
    }

    /** Blocks for the next envelope; null on clean close. Auto-pongs pings. */
    fun receive(): SynergyEnvelope? {
        while (true) {
            val frame = WsFrames.readFrame(input)
            when (frame.opcode) {
                WsOpcode.TEXT -> return SynergyEnvelopeCodec.decode(String(frame.payload, Charsets.UTF_8))
                WsOpcode.PING -> {
                    output.write(WsFrames.encodeClientFrame(WsOpcode.PONG, frame.payload))
                    output.flush()
                }
                WsOpcode.PONG -> Unit
                WsOpcode.CLOSE -> {
                    logger.fine("Peer closed Synergy WS")
                    return null
                }
            }
        }
    }

    fun close() {
        try {
            output.write(WsFrames.encodeClientFrame(WsOpcode.CLOSE, ByteArray(0)))
            output.flush()
        } catch (_: IOException) {
        }
        runCatching { socket.close() }
    }
}
