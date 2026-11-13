/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine.transport

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.KeyMismatchException
import ec0.core.engine.crypto.PairingCoordinator
import ec0.core.engine.discovery.EndpointResolver
import ec0.core.kdeconnect.Sha256
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Ec0TransportIntegrationTest {

    @Test
    fun loopbackServerAndClientComplete4MessageQrHandshakeAndMultiplexEncryptedTraffic() {
        val phoneIdentity = Ed25519Identity.generateKeyPair()
        val desktopIdentity = Ed25519Identity.generateKeyPair()

        val phoneCoordinator = PairingCoordinator(
            identity = phoneIdentity,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
        )
        val desktopCoordinator = PairingCoordinator(
            identity = desktopIdentity,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
        )

        val serverSessions = LinkedBlockingQueue<Ec0Session>()
        val server = Ec0Server(
            localIdentity = phoneIdentity,
            localAlias = "OnePlus 12",
            pairingCoordinator = phoneCoordinator,
            bindPort = 0, // Ephemeral localhost port
            pingIntervalMs = 0L,
            onSessionAuthenticated = { serverSessions.offer(it) },
        )
        server.start()

        try {
            val port = server.boundPort
            assertTrue(port > 0)

            // Phone generates QR URI; Desktop scans it
            val qrPayload = phoneCoordinator.generateQrPayload(
                alias = "OnePlus 12",
                port = port,
                ips = listOf("127.0.0.1"),
            )
            val scannedQr = desktopCoordinator.acceptScannedQrUri(qrPayload.toUri())

            val resolver = EndpointResolver()
            val client = Ec0Client(
                localIdentity = desktopIdentity,
                localAlias = "Aryan Arch Linux",
                pairingCoordinator = desktopCoordinator,
                endpointResolver = resolver,
                pingIntervalMs = 0L,
            )

            val clientSession = client.connectToPeer(
                peerDeviceId = scannedQr.deviceId,
                qrPayload = scannedQr,
                includeGatewayFallback = false,
            )
            val serverSession = serverSessions.poll(5, TimeUnit.SECONDS)
            assertNotNull(serverSession)

            assertEquals(phoneIdentity.deviceId, clientSession.peerDeviceId)
            assertEquals(desktopIdentity.deviceId, serverSession.peerDeviceId)
            assertEquals(clientSession.sessionIdHex, serverSession.sessionIdHex)

            // Verify EndpointResolver recorded last-known endpoint
            val recorded = resolver.lastKnownFor(phoneIdentity.deviceId)
            assertNotNull(recorded)
            assertEquals("127.0.0.1", recorded.host)
            assertEquals(port, recorded.port)

            // Desktop sends AEAD-encrypted signed control envelope (0x02) -> Phone receives
            clientSession.sendCmd(
                cmdType = "clipboard_sync",
                cmdBody = buildJsonObject {
                    put("clip_sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                    put("mime", "text/plain")
                    put("size_bytes", 12)
                },
                category = "clipboard",
            )

            val phoneEvent1 = serverSession.receiveEvent() as? Ec0SessionEvent.ControlEnvelope
            assertNotNull(phoneEvent1)
            assertEquals("clipboard_sync", phoneEvent1.envelope.bodyType)
            assertEquals(1L, phoneEvent1.envelope.seq)

            // Phone sends AEAD-encrypted BulkStreamFrame (0x03) -> Desktop receives
            val chunkBytes = "resumable-file-chunk-0".encodeToByteArray()
            val chunkSha = Sha256.digest(chunkBytes)
            serverSession.sendBulkChunk(
                BulkStreamFrame(
                    streamId = 99,
                    chunkIndex = 0,
                    flags = BulkStreamFrame.FLAG_FINAL_CHUNK,
                    sha256 = chunkSha,
                    payload = chunkBytes,
                ),
            )

            val desktopEvent1 = clientSession.receiveEvent() as? Ec0SessionEvent.BulkChunk
            assertNotNull(desktopEvent1)
            assertEquals(99, desktopEvent1.frame.streamId)
            assertEquals(0, desktopEvent1.frame.chunkIndex)
            assertContentEquals(chunkBytes, desktopEvent1.frame.payload)

            clientSession.close()
            serverSession.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun clientRejectsImpostorServerPresentingDifferentPublicKeyThanPinned() {
        val realPhoneIdentity = Ed25519Identity.generateKeyPair()
        val impostorIdentity = Ed25519Identity.generateKeyPair()
        val desktopIdentity = Ed25519Identity.generateKeyPair()

        val impostorCoordinator = PairingCoordinator(
            identity = impostorIdentity,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
        )
        val desktopCoordinator = PairingCoordinator(
            identity = desktopIdentity,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
        )

        val server = Ec0Server(
            localIdentity = impostorIdentity,
            localAlias = "Impostor Phone",
            pairingCoordinator = impostorCoordinator,
            bindPort = 0,
            pingIntervalMs = 0L,
        )
        server.start()

        try {
            val qrFromRealPhone = PairingCoordinator(realPhoneIdentity).generateQrPayload(
                alias = "Real Phone",
                port = server.boundPort,
                ips = listOf("127.0.0.1"),
            )
            val scanned = desktopCoordinator.acceptScannedQrUri(qrFromRealPhone.toUri())

            val client = Ec0Client(
                localIdentity = desktopIdentity,
                localAlias = "Desktop",
                pairingCoordinator = desktopCoordinator,
                pingIntervalMs = 0L,
            )

            // Connecting to impostor server with realPhone's QR expectation must fail closed
            assertFailsWith<Exception> {
                client.connectToPeer(
                    peerDeviceId = scanned.deviceId,
                    qrPayload = scanned,
                    includeGatewayFallback = false,
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun fullJitterBackoffRespectsCapAndFraction() {
        assertEquals(0L, Ec0Client.computeFullJitterBackoffMs(attempt = 0, baseDelayMs = 500L, maxDelayMs = 30_000L, randomFraction = 0.0))
        assertEquals(250L, Ec0Client.computeFullJitterBackoffMs(attempt = 0, baseDelayMs = 500L, maxDelayMs = 30_000L, randomFraction = 0.5))
        assertEquals(1_000L, Ec0Client.computeFullJitterBackoffMs(attempt = 1, baseDelayMs = 500L, maxDelayMs = 30_000L, randomFraction = 1.0))
        assertEquals(30_000L, Ec0Client.computeFullJitterBackoffMs(attempt = 10, baseDelayMs = 500L, maxDelayMs = 30_000L, randomFraction = 1.0))
    }
}
