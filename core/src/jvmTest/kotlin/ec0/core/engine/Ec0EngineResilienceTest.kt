/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.core.engine

import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.PresenceBeaconInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Ec0EngineResilienceTest {

    private class NoopMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): MdnsDatagram? = null
        override fun close() = Unit
    }

    @Test
    fun endToEndNodePairingClipboardNotificationsPresenceAndFileTransfer() {
        val desktopDir = Files.createTempDirectory("ec0-node-desktop").toFile()
        val phoneDir = Files.createTempDirectory("ec0-node-phone").toFile()

        val desktopClips = mutableListOf<ClipboardItem>()
        val clipLatch = CountDownLatch(1)

        val phoneNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generateKeyPair(),
            displayName = "OnePlus 12",
            role = EngineConstants.ROLE_PHONE,
            listenPort = 0,
            downloadDir = phoneDir,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            mdnsTransportFactory = { NoopMdnsTransport() },
        )

        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generateKeyPair(),
            displayName = "Aryan Arch Linux",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = desktopDir,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            mdnsTransportFactory = { NoopMdnsTransport() },
            onRemoteClipboardApplied = {
                desktopClips.add(it)
                clipLatch.countDown()
            },
        )

        phoneNode.startServer(startMdns = false)
        desktopNode.startServer(startMdns = false)

        try {
            val qr = phoneNode.generatePairingQr(ips = listOf("127.0.0.1"))
            desktopNode.pairAndConnectWithQrUri(qr.toUri())

            // Wait briefly for phoneNode server callback to register the session
            var attempts = 0
            while (phoneNode.activeSessionFor(desktopNode.localIdentity.deviceId) == null && attempts < 50) {
                Thread.sleep(20)
                attempts++
            }
            assertEquals(
                EngineConnectionState.CONNECTED,
                desktopNode.connectionStates.value[phoneNode.localIdentity.deviceId],
            )
            assertEquals(
                EngineConnectionState.CONNECTED,
                phoneNode.connectionStates.value[desktopNode.localIdentity.deviceId],
            )

            // 1. Phone pushes clipboard text -> Desktop receives
            phoneNode.sendOrQueueClipboard(
                peerDeviceId = desktopNode.localIdentity.deviceId,
                item = ClipboardItem.text("seamless-linux-android-sync"),
            )
            assertTrue(clipLatch.await(5, TimeUnit.SECONDS))
            assertEquals("seamless-linux-android-sync", desktopClips[0].asUtf8Text())

            // 2. Phone broadcasts presence beacon -> Desktop updates peerPresence
            phoneNode.sendPresenceBeacon(
                peerDeviceId = desktopNode.localIdentity.deviceId,
                beacon = PresenceBeaconInfo(batteryPct = 94, isCharging = true, screenOn = true),
            )
            var beaconAttempts = 0
            while (desktopNode.peerPresence.value[phoneNode.localIdentity.deviceId] == null && beaconAttempts < 50) {
                Thread.sleep(20)
                beaconAttempts++
            }
            val presence = desktopNode.peerPresence.value[phoneNode.localIdentity.deviceId]
            assertNotNull(presence)
            assertEquals(94, presence.batteryPct)

            // 3. Desktop transfers a file to Phone
            val srcFile = File(desktopDir, "notes.txt").apply {
                writeBytes("Continuity file payload 12345".encodeToByteArray())
            }
            desktopNode.sendFileOrDirectory(
                peerDeviceId = phoneNode.localIdentity.deviceId,
                fileOrDir = srcFile,
                transferId = "node-tx-1",
            )

            var txAttempts = 0
            while (phoneNode.transfers.value["node-tx-1"]?.state != FileTransferState.COMPLETED && txAttempts < 100) {
                Thread.sleep(20)
                txAttempts++
            }
            assertEquals(FileTransferState.COMPLETED, phoneNode.transfers.value["node-tx-1"]?.state)
            val receivedFile = File(phoneDir, "notes.txt")
            assertTrue(receivedFile.isFile)
            assertContentEquals(srcFile.readBytes(), receivedFile.readBytes())
        } finally {
            desktopNode.close()
            phoneNode.close()
            desktopDir.deleteRecursively()
            phoneDir.deleteRecursively()
        }
    }

    @Test
    fun offlineOutboxQueuesWhileDisconnectedFlushesOnReconnectAndDeduplicatesAcrossSessions() {
        var now = 1_762_905_600_000L
        val desktopDir = Files.createTempDirectory("ec0-resilience-desktop").toFile()
        val phoneDir = Files.createTempDirectory("ec0-resilience-phone").toFile()

        val desktopClips = mutableListOf<ClipboardItem>()

        val phoneNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generateKeyPair(),
            displayName = "OnePlus 12",
            role = EngineConstants.ROLE_PHONE,
            listenPort = 0,
            downloadDir = phoneDir,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoopMdnsTransport() },
            clockMs = { now },
        )

        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generateKeyPair(),
            displayName = "Aryan Arch Linux",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = desktopDir,
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoopMdnsTransport() },
            onRemoteClipboardApplied = { desktopClips.add(it) },
            clockMs = { now },
        )

        phoneNode.startServer(startMdns = false)
        desktopNode.startServer(startMdns = false)

        try {
            val phoneId = phoneNode.localIdentity.deviceId
            val desktopId = desktopNode.localIdentity.deviceId

            // Pair initially via QR
            val qr = phoneNode.generatePairingQr(ips = listOf("127.0.0.1"))
            desktopNode.pairAndConnectWithQrUri(qr.toUri())

            var wait = 0
            while (phoneNode.activeSessionFor(desktopId) == null && wait < 50) {
                Thread.sleep(20)
                wait++
            }

            // Simulate socket drop (e.g. ColorOS Wi-Fi handoff / laptop sleep)
            desktopNode.disconnectPeer(phoneId)
            phoneNode.disconnectPeer(desktopId)
            assertEquals(EngineConnectionState.DISCONNECTED, phoneNode.connectionStates.value[desktopId])

            // While disconnected:
            // 1. Queue an expired notification (2 hours old -> must be pruned by 1-hour TTL)
            phoneNode.sendOrQueueNotification(
                peerDeviceId = desktopId,
                notif = NotificationPostInfo(
                    notifId = "expired:0",
                    packageName = "com.example",
                    appName = "OldApp",
                    title = "Stale",
                    body = "Should be dropped",
                    timestampMs = now - 7_200_000L,
                ),
            )
            // 2. Queue a fresh clipboard item and a fresh notification
            phoneNode.sendOrQueueClipboard(
                peerDeviceId = desktopId,
                item = ClipboardItem.text("copied-while-offline"),
                timestampMs = now,
            )
            val freshNotif = NotificationPostInfo(
                notifId = "fresh:1",
                packageName = "org.telegram.messenger",
                appName = "Telegram",
                title = "Continuity",
                body = "Delivered on reconnect!",
                timestampMs = now + 100L,
            )
            phoneNode.sendOrQueueNotification(
                peerDeviceId = desktopId,
                notif = freshNotif,
            )

            // Only the 2 fresh items remain in the outbox (expired item pruned)
            assertEquals(2, phoneNode.outboxQueue.pendingCount(desktopId, now + 200L))

            // Reconnect Desktop -> Phone: Phone automatically flushes its outbox on attachAuthenticatedSession!
            now += 500L
            desktopNode.connectToPeer(
                peerDeviceId = phoneId,
                explicitHost = "127.0.0.1",
                explicitPort = phoneNode.boundServerPort,
            )

            var flushWait = 0
            while ((desktopClips.isEmpty() || desktopNode.notifications.value.isEmpty()) && flushWait < 100) {
                Thread.sleep(20)
                flushWait++
            }

            assertEquals(1, desktopClips.size)
            assertEquals("copied-while-offline", desktopClips[0].asUtf8Text())
            assertEquals(listOf("fresh:1"), desktopNode.notifications.value.map { it.notifId })
            assertEquals(0, phoneNode.outboxQueue.pendingCount(desktopId, now))

            // Amendment 2 Cross-Session Replay Check:
            // Even if a fresh session is established and the same inner notification/clipboard
            // content ID is flushed again, OfflineFlushDeduplicator drops duplicates across sessions!
            desktopNode.disconnectPeer(phoneId)
            phoneNode.disconnectPeer(desktopId)

            phoneNode.sendOrQueueNotification(peerDeviceId = desktopId, notif = freshNotif)
            now += 500L
            desktopNode.connectToPeer(
                peerDeviceId = phoneId,
                explicitHost = "127.0.0.1",
                explicitPort = phoneNode.boundServerPort,
            )
            Thread.sleep(150)
            // Still exactly 1 notification, not duplicated
            assertEquals(1, desktopNode.notifications.value.size)
        } finally {
            desktopNode.close()
            phoneNode.close()
            desktopDir.deleteRecursively()
            phoneDir.deleteRecursively()
        }
    }
}
