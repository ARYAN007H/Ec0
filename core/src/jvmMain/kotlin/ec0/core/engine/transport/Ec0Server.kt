/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Inbound ec0-engine TCP + RFC 6455 WebSocket server that upgrades connections
 * on `/ec0/v1` and runs the 4-message Responder mutual authentication handshake.
 */
package ec0.core.engine.transport

import ec0.core.engine.EngineConstants
import ec0.core.engine.OfflineFlushDeduplicator
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.PairingCoordinator
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Ec0Server(
    private val localIdentity: Ed25519KeyPair,
    private val localAlias: String,
    private val pairingCoordinator: PairingCoordinator,
    private val bindPort: Int = EngineConstants.PRIMARY_PORT,
    private val offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
    private val ioTimeoutMs: Int = 50_000,
    private val pingIntervalMs: Long = 5_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onSessionAuthenticated: (Ec0Session) -> Unit = {},
    private val onHandshakeError: ( Throwable) -> Unit = {},
) : Closeable {

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val activeSockets = CopyOnWriteArrayList<Socket>()

    val boundPort: Int
        get() = serverSocket?.localPort ?: bindPort

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindPort))
        serverSocket = ss

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ec0-server-${ss.localPort}").apply { isDaemon = true }
        }
        executor = pool

        pool.execute {
            while (running.get() && !ss.isClosed) {
                val clientSocket = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                activeSockets.add(clientSocket)
                pool.execute {
                    handleConnection(clientSocket)
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = ioTimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val rawHeaders = Ec0WsCodec.readHttpHeaderBlock(input)
            val upgradeReq = Ec0WsCodec.parseClientUpgradeRequest(rawHeaders)
            val response = Ec0WsCodec.buildServerUpgradeResponse(upgradeReq.clientKey)
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            val session = Ec0Session.performResponderHandshake(
                socket = socket,
                input = input,
                output = output,
                localIdentity = localIdentity,
                localAlias = localAlias,
                pairingCoordinator = pairingCoordinator,
                offlineDeduplicator = offlineDeduplicator,
                nowMs = nowMs,
            )
            session.startPingLoop(pingIntervalMs)
            onSessionAuthenticated(session)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            activeSockets.remove(socket)
            onHandshakeError(t)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        for (s in activeSockets) {
            runCatching { s.close() }
        }
        activeSockets.clear()
        executor?.shutdownNow()
    }
}
