/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * UDP multicast announcer for the desktop LocalSend receiver — mirrors the
 * reference discovery (packages/core/src/multicast/mod.rs): multicast group
 * 224.0.0.167 (DEFAULT_MULTICAST_GROUP, chosen inside 224.0.0.0/24 because
 * some Android devices reject other ranges), port 53317, v2.2 JSON payload,
 * download=true while the receiver server is up.
 */
package ec0.desktop.localsend

import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.dto.MulticastMessageV2
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class LocalsendAnnouncer(
    private val messageProvider: () -> MulticastMessageV2,
    private val intervalMs: Long = 3_000,
    private val multicastGroup: String = "224.0.0.167",
    private val port: Int = LocalsendV2.DISCOVERY_PORT,
    private val logger: Logger = Logger.getLogger("LocalsendAnnouncer"),
) {
    private var socket: MulticastSocket? = null
    private var executor: ScheduledExecutorService? = null

    fun start() {
        val socket = MulticastSocket(port).also { this.socket = it }
        runCatching { socket.joinGroup(InetAddress.getByName(multicastGroup)) }
            .onFailure { logger.warning("Multicast join failed ($multicastGroup): $it — announcements will still be sent") }
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "localsend-announce").apply { isDaemon = true }
        }.also { this.executor = it }
        announce() // immediate announce on start ("download=true once server is up")
        executor.scheduleAtFixedRate(this::announce, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun announce() {
        runCatching {
            val payload = LocalsendV2.json.encodeToString(MulticastMessageV2.serializer(), messageProvider())
                .toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(multicastGroup)
            // Announce on all non-loopback interfaces (reference sends per-interface)
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .forEach { nic ->
                    runCatching {
                        socket?.networkInterface = nic
                        socket?.send(DatagramPacket(payload, payload.size, group, port))
                    }
                }
        }.onFailure { logger.fine("Announce failed: $it") }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        runCatching { socket?.leaveGroup(InetAddress.getByName(multicastGroup)) }
        runCatching { socket?.close() }
        socket = null
    }
}
