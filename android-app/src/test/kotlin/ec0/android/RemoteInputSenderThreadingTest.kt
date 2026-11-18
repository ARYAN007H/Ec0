/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The remote-input send path MUST NOT run its socket writes on the Android
 * main thread: every touchpad burst crashed the process with
 * NetworkOnMainThreadException (the same crash class the notification relay
 * hit before its daemon executor). The flush tick and the direct keyboard/
 * volume sends now run on an injected IO dispatcher; these tests pin that
 * contract end-to-end over a real authenticated node pair, plus the batcher's
 * cross-thread coalescing.
 */
package ec0.android

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineConstants
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.discovery.MdnsDatagram
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.services.InputEventSink
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

class RemoteInputSenderThreadingTest {

    private class NoOpMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): MdnsDatagram? = null
        override fun close() = Unit
    }

    /** Records every injected event WITH the thread that delivered it. */
    private class RecordingSink : InputEventSink {
        val pointers = Collections.synchronizedList(mutableListOf<Pair<InputPointerEvent, String>>())
        val keys = Collections.synchronizedList(mutableListOf<Pair<InputKeyEvent, String>>())
        override fun onKeyEvent(event: InputKeyEvent) {
            keys.add(event to Thread.currentThread().name)
        }
        override fun onPointerEvent(event: InputPointerEvent) {
            pointers.add(event to Thread.currentThread().name)
        }
    }

    private val openNodes = mutableListOf<Ec0EngineNode>()
    private val openScopes = mutableListOf<CoroutineScope>()
    private val openDispatchers = mutableListOf<java.util.concurrent.ExecutorService>()
    private val sink = RecordingSink()

    @AfterTest
    fun tearDown() {
        openNodes.forEach { runCatching { it.close() } }
        openNodes.clear()
        openScopes.forEach { it.cancel() }
        openScopes.clear()
        openDispatchers.forEach { runCatching { it.shutdownNow() } }
        openDispatchers.clear()
    }

    @Test
    fun pointerBurstAndKeysArriveOnTheInjectedIoDispatcher() {
        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generate(),
            displayName = "Aryan Arch Linux",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = Files.createTempDirectory("ec0-input-desktop").toFile(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoOpMdnsTransport() },
            inputEventSink = sink,
        ).also { openNodes.add(it) }

        val phoneNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generate(),
            displayName = "SM-A035F",
            role = EngineConstants.ROLE_PHONE,
            listenPort = 0,
            downloadDir = Files.createTempDirectory("ec0-input-phone").toFile(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoOpMdnsTransport() },
        ).also { openNodes.add(it) }

        desktopNode.startServer(startMdns = false)
        phoneNode.startServer(startMdns = false)
        val desktopId = desktopNode.localIdentity.deviceId
        val pairing = runCatching {
            phoneNode.pairAndConnectWithQrUri(desktopNode.generatePairingQr(ips = listOf("127.0.0.1")).toUri())
        }
        assertTrue(
            pairing.isSuccess,
            "pairing must succeed before the sender can resolve a session",
        )

        val namedIo = Executors.newSingleThreadExecutor { r -> Thread(r, "ec0-remote-input-test") }
            .also { openDispatchers.add(it) }
            .asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { openScopes.add(it) }
        val sender = RemoteInputSender(
            node = phoneNode,
            scope = scope,
            peerDeviceId = { desktopId },
            ioDispatcher = namedIo,
        )

        sender.sendPointerMove(3, -2)
        sender.sendKey(PcKeys.VOLUME_UP)

        val deadline = System.currentTimeMillis() + 5_000L
        while ((sink.pointers.isEmpty() || sink.keys.size < 2) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }

        // 1. The coalesced MOVE burst reached the desktop's sink end-to-end.
        val move = sink.pointers.firstOrNull()
        assertTrue(move != null, "the flush tick must deliver the pointer burst over the live session")
        assertEquals(3, move?.first?.dx)
        assertEquals(-2, move?.first?.dy)

        // 2. Volume rocker produced the down+up key pair on the same path.
        assertEquals(2, sink.keys.size, "sendKey must emit KEY_ACTION_DOWN and KEY_ACTION_UP")

        // 3. THE REGRESSION: the send dispatch ran on the injected IO
        //    dispatcher — never on the caller (Android main) thread. Asserted
        //    through the sender's test seam because StrictMode is Android-only.
        val sendDeadline = System.currentTimeMillis() + 5_000L
        while (sender.lastSendThreadName == null && System.currentTimeMillis() < sendDeadline) {
            Thread.sleep(20L)
        }
        assertEquals(
            "ec0-remote-input-test",
            sender.lastSendThreadName,
            "sends must dispatch on the injected IO dispatcher, not the UI thread",
        )
    }

    @Test
    fun concurrentAddMovesCoalesceWithoutLoss() {
        val batcher = PointerEventBatcher()
        val threads = 8
        val perThread = 1_000
        val runners = (1..threads).map {
            Thread {
                repeat(perThread) { batcher.addMove(1, 1) }
            }
        }
        runners.forEach { it.start() }
        runners.forEach { it.join() }
        val burst = batcher.flush()
        assertEquals(1, burst.size, "concurrent moves must coalesce into one MOVE per flush")
        assertEquals(threads * perThread, burst[0].dx, "no delta may be lost across threads")
        assertEquals(threads * perThread, burst[0].dy)
        assertTrue(batcher.flush().isEmpty(), "the drained batcher must be empty")
    }
}
