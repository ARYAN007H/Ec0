/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Authenticated ec0-engine WebSocket session: runs the 4-message X25519+Ed25519
 * handshake over RFC 6455, then multiplexes AEAD-encrypted signed Ec0Envelope
 * control messages (`frameType = 0x02`) and bulk binary stream chunks
 * (`frameType = 0x03`) with a 5-second PING heartbeat.
 */
package ec0.core.engine.transport

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.EngineConstants
import ec0.core.engine.OfflineFlushDeduplicator
import ec0.core.engine.ReplayCheckResult
import ec0.core.engine.ReplayWindow
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.EstablishedHandshakeSession
import ec0.core.engine.crypto.HandshakeSecurityException
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.crypto.SessionEnvelopeProcessor
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.crypto.X25519Handshake
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface Ec0SessionEvent {
    data class ControlEnvelope(val envelope: Ec0Envelope) : Ec0SessionEvent
    data class BulkChunk(val frame: BulkStreamFrame) : Ec0SessionEvent
}

class Ec0Session internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    val isClient: Boolean,
    val handshakeSession: EstablishedHandshakeSession,
    val envelopeProcessor: SessionEnvelopeProcessor,
) : Closeable {

    val peerDeviceId: String get() = handshakeSession.peerDeviceId
    val peerPublicKeyRaw: ByteArray get() = handshakeSession.peerPublicKeyRaw
    val peerAlias: String get() = handshakeSession.peerAlias
    val sessionIdHex: String get() = handshakeSession.sessionIdHex

    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private var pingExecutor: ScheduledExecutorService? = null

    val isClosed: Boolean get() = closed.get()

    /**
     * Starts a daemon 5-second RFC 6455 `PING` loop (modeled on WSCM `pingInterval(5, SECONDS)`).
     */
    fun startPingLoop(intervalMs: Long = 5_000L) {
        if (intervalMs <= 0L || pingExecutor != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ec0-session-ping-${sessionIdHex.take(8)}").apply { isDaemon = true }
        }
        pingExecutor = executor
        executor.scheduleAtFixedRate(
            {
                if (!closed.get()) {
                    runCatching { sendRawWsFrame(Ec0WsOpcode.PING, ByteArray(0)) }
                }
            },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Signs and sends a `"cmd"` [Ec0Envelope] inside an AEAD `0x02` frame.
     */
    fun sendCmd(
        cmdType: String,
        cmdBody: JsonElement? = null,
        cmdValue: Int = 0,
        reason: String = "",
        category: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope {
        val envelope = envelopeProcessor.nextCmdEnvelope(
            cmdType = cmdType,
            cmdBody = cmdBody,
            cmdValue = cmdValue,
            reason = reason,
            category = category,
            timestampMs = timestampMs,
        )
        sendSignedEnvelope(envelope)
        return envelope
    }

    /**
     * Signs and sends an `"info"` [Ec0Envelope] inside an AEAD `0x02` frame.
     */
    fun sendInfo(
        infoType: String,
        infoBody: JsonElement? = null,
        infoValue: Int = 0,
        category: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope {
        val envelope = envelopeProcessor.nextInfoEnvelope(
            infoType = infoType,
            infoBody = infoBody,
            infoValue = infoValue,
            category = category,
            timestampMs = timestampMs,
        )
        sendSignedEnvelope(envelope)
        return envelope
    }

    /**
     * Encrypts and transmits an already-signed [Ec0Envelope] inside an AEAD `0x02` frame.
     */
    fun sendSignedEnvelope(envelope: Ec0Envelope) {
        val rawJsonBytes = Ec0EnvelopeCodec.encode(envelope).encodeToByteArray()
        writeLock.withLock {
            val aeadPacket = handshakeSession.aeadChannel.sealApplicationFrame(
                frameType = EngineConstants.FRAME_ENVELOPE_JSON,
                plaintext = rawJsonBytes,
            )
            sendRawWsFrameLocked(Ec0WsOpcode.BINARY, aeadPacket)
        }
    }

    /**
     * Encrypts and transmits a [BulkStreamFrame] inside an AEAD `0x03` frame.
     */
    fun sendBulkChunk(frame: BulkStreamFrame) {
        val plainBytes = frame.toBytes()
        writeLock.withLock {
            val aeadPacket = handshakeSession.aeadChannel.sealApplicationFrame(
                frameType = EngineConstants.FRAME_BULK_CHUNK,
                plaintext = plainBytes,
            )
            sendRawWsFrameLocked(Ec0WsOpcode.BINARY, aeadPacket)
        }
    }

    /**
     * Blocks for the next authenticated [Ec0SessionEvent] (`ControlEnvelope` or `BulkChunk`),
     * automatically replying to `PING` frames and echoing `CLOSE` frames per RFC 6455 §5.5.1.
     * Returns `null` on clean WebSocket close.
     */
    fun receiveEvent(nowMs: () -> Long = { System.currentTimeMillis() }): Ec0SessionEvent? {
        while (!closed.get()) {
            val wsMsg = Ec0WsCodec.readMessage(
                input = input,
                onPing = { payload -> runCatching { sendRawWsFrame(Ec0WsOpcode.PONG, payload) } },
                onClose = { payload ->
                    runCatching { sendRawWsFrame(Ec0WsOpcode.CLOSE, payload) }
                    closed.set(true)
                },
            ) ?: return null

            if (wsMsg.opcode != Ec0WsOpcode.BINARY) {
                throw IOException(
                    "Post-handshake ec0-engine frames must be BINARY AEAD packets (got opcode ${wsMsg.opcode})",
                )
            }

            val decrypted = handshakeSession.aeadChannel.openApplicationFrame(wsMsg.payload)
            when (decrypted.frameType) {
                EngineConstants.FRAME_ENVELOPE_JSON -> {
                    val jsonStr = decrypted.plaintext.decodeToString()
                    val envelope = Ec0EnvelopeCodec.decode(jsonStr)
                    val replayCheck = envelopeProcessor.verifyLiveEnvelope(envelope, nowMs())
                    if (replayCheck != ReplayCheckResult.ACCEPTED) {
                        throw HandshakeSecurityException(
                            "Rejected incoming Ec0Envelope seq=${envelope.seq}: $replayCheck",
                        )
                    }
                    return Ec0SessionEvent.ControlEnvelope(envelope)
                }
                EngineConstants.FRAME_BULK_CHUNK -> {
                    val chunk = BulkStreamFrame.fromBytes(decrypted.plaintext)
                    return Ec0SessionEvent.BulkChunk(chunk)
                }
                else -> {
                    throw HandshakeSecurityException(
                        "Unexpected post-handshake AEAD frameType: ${decrypted.frameType}",
                    )
                }
            }
        }
        return null
    }

    internal fun sendRawWsFrame(opcode: Int, payload: ByteArray) {
        writeLock.withLock {
            sendRawWsFrameLocked(opcode, payload)
        }
    }

    private fun sendRawWsFrameLocked(opcode: Int, payload: ByteArray) {
        val wire = if (isClient) {
            Ec0WsCodec.encodeClientFrame(opcode, payload)
        } else {
            Ec0WsCodec.encodeServerFrame(opcode, payload)
        }
        output.write(wire)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pingExecutor?.shutdownNow()
        runCatching { sendRawWsFrame(Ec0WsOpcode.CLOSE, ByteArray(0)) }
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Runs the 4-message Initiator handshake (`CLIENT_HELLO` -> `SERVER_HELLO` ->
         * `CLIENT_AUTH` -> `SERVER_AUTH_CONFIRM`) over an upgraded WebSocket connection.
         */
        fun performInitiatorHandshake(
            socket: Socket,
            input: DataInputStream,
            output: OutputStream,
            localIdentity: Ed25519KeyPair,
            localAlias: String,
            pairingCoordinator: PairingCoordinator,
            qrPairingTarget: Pair<ByteArray, ByteArray>? = null,
            offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): Ec0Session {
            val initiator = X25519Handshake.InitiatorSession(
                identity = localIdentity,
                alias = localAlias,
                qrPairingTarget = qrPairingTarget,
            )

            // Message 1: Send CLIENT_HELLO (WS TEXT)
            val clientHelloJson = initiator.createClientHelloJson()
            output.write(
                Ec0WsCodec.encodeClientFrame(
                    Ec0WsOpcode.TEXT,
                    clientHelloJson.encodeToByteArray(),
                ),
            )
            output.flush()

            // Message 2: Read SERVER_HELLO (WS TEXT)
            val serverHelloMsg = Ec0WsCodec.readMessage(input)
                ?: throw IOException("Connection closed while waiting for SERVER_HELLO")
            if (serverHelloMsg.opcode != Ec0WsOpcode.TEXT) {
                throw HandshakeSecurityException("Expected TEXT frame for SERVER_HELLO (got ${serverHelloMsg.opcode})")
            }

            // Message 3: Verify SERVER_HELLO & send CLIENT_AUTH (WS BINARY AEAD 0x01 counter=0)
            val trustLevel = if (qrPairingTarget != null) TrustLevel.TRUSTED_QR else TrustLevel.TOFU_PINNED
            val clientAuthFrame = initiator.processServerHelloAndCreateClientAuth(
                serverHelloJson = serverHelloMsg.asUtf8String(),
                peerPinVerifier = { deviceId, rkPubRaw32, alias ->
                    pairingCoordinator.pinStore.verifyOrPin(
                        deviceId = deviceId,
                        publicKeyRaw = rkPubRaw32,
                        alias = alias,
                        trustLevel = trustLevel,
                        nowMs = nowMs(),
                    )
                },
            )
            output.write(Ec0WsCodec.encodeClientFrame(Ec0WsOpcode.BINARY, clientAuthFrame))
            output.flush()

            // Message 4: Read & verify SERVER_AUTH_CONFIRM (WS BINARY AEAD 0x01 counter=0)
            val serverConfirmMsg = Ec0WsCodec.readMessage(input)
                ?: throw IOException("Connection closed while waiting for SERVER_AUTH_CONFIRM")
            if (serverConfirmMsg.opcode != Ec0WsOpcode.BINARY) {
                throw HandshakeSecurityException(
                    "Expected BINARY frame for SERVER_AUTH_CONFIRM (got ${serverConfirmMsg.opcode})",
                )
            }
            val established = initiator.verifyServerAuthConfirm(serverConfirmMsg.payload)
            offlineDeduplicator.onNewSession()

            val processor = SessionEnvelopeProcessor(
                localIdentity = localIdentity,
                peerDeviceId = established.peerDeviceId,
                peerPublicKeyRaw32 = established.peerPublicKeyRaw,
                replayWindow = ReplayWindow(),
                offlineDeduplicator = offlineDeduplicator,
            )
            return Ec0Session(
                socket = socket,
                input = input,
                output = output,
                isClient = true,
                handshakeSession = established,
                envelopeProcessor = processor,
            )
        }

        /**
         * Runs the 4-message Responder handshake (`CLIENT_HELLO` -> `SERVER_HELLO` ->
         * `CLIENT_AUTH` -> `SERVER_AUTH_CONFIRM`) over an upgraded WebSocket connection.
         */
        fun performResponderHandshake(
            socket: Socket,
            input: DataInputStream,
            output: OutputStream,
            localIdentity: Ed25519KeyPair,
            localAlias: String,
            pairingCoordinator: PairingCoordinator,
            offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): Ec0Session {
            val responder = X25519Handshake.ResponderSession(
                identity = localIdentity,
                alias = localAlias,
            )

            // Message 1: Read CLIENT_HELLO (WS TEXT)
            val clientHelloMsg = Ec0WsCodec.readMessage(input)
                ?: throw IOException("Connection closed while waiting for CLIENT_HELLO")
            if (clientHelloMsg.opcode != Ec0WsOpcode.TEXT) {
                throw HandshakeSecurityException("Expected TEXT frame for CLIENT_HELLO (got ${clientHelloMsg.opcode})")
            }

            // Message 2: Authorize client & send SERVER_HELLO (WS TEXT)
            val serverHelloJson = responder.processClientHelloAndCreateServerHello(
                clientHelloJson = clientHelloMsg.asUtf8String(),
                peerAuthorizer = { deviceId, ikPubRaw32, epkIRaw32, alias, pairingProofHex ->
                    pairingCoordinator.authorizeIncomingClientHello(
                        clientDeviceId = deviceId,
                        ikPubRaw32 = ikPubRaw32,
                        epkIRaw32 = epkIRaw32,
                        clientAlias = alias,
                        pairingProofHex = pairingProofHex,
                        nowMs = nowMs(),
                    )
                },
            )
            output.write(
                Ec0WsCodec.encodeServerFrame(
                    Ec0WsOpcode.TEXT,
                    serverHelloJson.encodeToByteArray(),
                ),
            )
            output.flush()

            // Message 3: Read CLIENT_AUTH (WS BINARY AEAD 0x01 counter=0)
            val clientAuthMsg = Ec0WsCodec.readMessage(input)
                ?: throw IOException("Connection closed while waiting for CLIENT_AUTH")
            if (clientAuthMsg.opcode != Ec0WsOpcode.BINARY) {
                throw HandshakeSecurityException("Expected BINARY frame for CLIENT_AUTH (got ${clientAuthMsg.opcode})")
            }

            // Message 4: Verify CLIENT_AUTH & send SERVER_AUTH_CONFIRM (WS BINARY AEAD 0x01 counter=0)
            val (confirmPacket, established) = responder.verifyClientAuthAndCreateServerConfirm(clientAuthMsg.payload)
            output.write(Ec0WsCodec.encodeServerFrame(Ec0WsOpcode.BINARY, confirmPacket))
            output.flush()

            offlineDeduplicator.onNewSession()
            val processor = SessionEnvelopeProcessor(
                localIdentity = localIdentity,
                peerDeviceId = established.peerDeviceId,
                peerPublicKeyRaw32 = established.peerPublicKeyRaw,
                replayWindow = ReplayWindow(),
                offlineDeduplicator = offlineDeduplicator,
            )
            return Ec0Session(
                socket = socket,
                input = input,
                output = output,
                isClient = false,
                handshakeSession = established,
                envelopeProcessor = processor,
            )
        }
    }
}
