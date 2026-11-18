/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * M2 (docs/security-audit.md): the accept loop previously spawned an unbounded
 * cached-pool thread per connection, so an UNAUTHENTICATED LAN peer could park
 * threads for the whole read timeout with no ceiling. The handshake semaphore
 * must close surplus connections immediately while holders keep theirs until
 * the handshake completes or times out.
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.transport.Ec0Server
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class Ec0ServerHandshakeBoundTest {

    private var server: Ec0Server? = null
    private val openSockets = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        openSockets.forEach { runCatching { it.close() } }
        server?.let { runCatching { it.close() } }
    }

    @Test
    fun surplusUnauthenticatedConnectionsCloseImmediately() {
        val server = Ec0Server(
            localIdentity = Ed25519Identity.generate(),
            localAlias = "m2-bound-test",
            pairingCoordinator = PairingCoordinator(Ed25519Identity.generateKeyPair()),
            bindPort = 0,
            ioTimeoutMs = 5_000,
            pingIntervalMs = 5_000,
        ).also { this.server = it }
        server.start()
        val port = server.boundPort

        // Fill every handshake permit with connections that send nothing.
        val holders = (1..32).map {
            Socket("127.0.0.1", port).also { openSockets.add(it) }
        }
        Thread.sleep(500L) // let the server process the accepts and take the permits

        // The next connection hits the bound: closed immediately, EOF on read.
        val surplus = Socket("127.0.0.1", port).also { openSockets.add(it) }
        surplus.soTimeout = 2_500
        val sawEof = try {
            surplus.getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false // no EOF within the window — server kept the connection open
        } catch (_: java.net.SocketException) {
            true // reset/close by the server = the bound fired
        }
        assertTrue(sawEof, "the 33rd unauthenticated connection must be closed at the handshake bound")

        // Sanity: a holder is NOT dropped while it stays silent — the bound
        // limits concurrency, it does not evict in-flight handshakes.
        val holder = holders.first()
        holder.soTimeout = 1_500
        val holderSawEof = try {
            holder.getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        }
        assertTrue(!holderSawEof, "an in-flight silent handshake must keep its permit (no early eviction)")
    }
}
