/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unit tests for [CompanionServiceHost] service lifecycle wiring and the
 * phone-side `ec0pair://` / `ec0://pair` URI pairing pipeline
 * (`URI_PARSE -> PIN (TRUSTED_QR) -> CONNECT (DISCOVERING -> CONNECTING -> AUTHENTICATING -> CONNECTED)`),
 * without requiring the Android framework in JVM tests.
 */
package ec0.android

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.EngineConstants
import ec0.core.engine.PairingQrPayload
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.android.PairingStepState
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.TrustLevel
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.ui.models.ProtocolFamily
import ec0.ui.models.TransferItem
import ec0.ui.models.UiDevice
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

class Ec0CompanionServiceAndPairingTest {

    private class FakeMulticastLock : MulticastLockHandle {
        var acquireCount: Int = 0
        var releaseCount: Int = 0
        override var isHeld: Boolean = false

        override fun acquire() {
            acquireCount++
            isHeld = true
        }

        override fun release() {
            releaseCount++
            isHeld = false
        }
    }

    private class FakeCompanionReceiver : CompanionReceiverHandle {
        var started: Boolean = false
        var stopped: Boolean = false
        private val _queue = MutableStateFlow<List<TransferItem>>(emptyList())
        override val transferQueue: StateFlow<List<TransferItem>> = _queue

        override fun start() {
            started = true
        }

        override fun stop() {
            started = false
            stopped = true
        }
    }

    private class NoOpMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): ec0.core.engine.discovery.MdnsDatagram? = null
        override fun close() = Unit
    }

    @AfterTest
    fun tearDown() {
        Ec0NotificationListener.attachRuntime(null)
        Ec0NotificationListener.clearReplyActionsForTest()
    }

    @Test
    fun `CompanionServiceHost start and stop wire engine server receiver multicast lock and discovery`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ec0-companion-host-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val storeDir = File(tempRoot, "files/ec0")
            val runtime = AndroidCompanionRuntime.createWithPersistentStores(
                storeDir = storeDir,
                phoneModelName = "OPPO-A5x",
                callbacks = object : AndroidPlatformCallbacks {},
                listenPort = 0,
                mdnsTransportFactory = { NoOpMdnsTransport() },
            )
            val fakeLock = FakeMulticastLock()
            val fakeReceiver = FakeCompanionReceiver()
            val discoveredDevice = UiDevice(
                id = "arch-workstation-id",
                alias = "Arch-Workstation",
                deviceModel = "Linux",
                deviceType = DeviceType.DESKTOP,
                protocol = ProtocolFamily.LOCALSEND,
                port = 53317,
                fingerprint = "fp-1",
                hostAddress = "192.168.1.42",
                wireProtocol = "http",
            )

            val host = CompanionServiceHost(
                runtime = runtime,
                multicastLock = fakeLock,
                receiver = fakeReceiver,
                discoverSweep = { listOf(discoveredDevice) },
                scope = scope,
                discoveryPeriodMs = 0L, // manual scan in test
                startMdnsOnServerStart = true,
            )

            assertFalse(host.isRunning.value)
            assertEquals(0, host.trustedPeerCount.value)

            host.start()

            assertTrue(host.isRunning.value)
            assertTrue(fakeLock.isHeld)
            assertEquals(1, fakeLock.acquireCount)
            assertTrue(fakeReceiver.started)
            assertTrue(runtime.node.boundServerPort > 0, "Engine server should bind an active TCP port")
            assertEquals(runtime, Ec0NotificationListener.currentRuntime())

            // Trigger manual scan and wait for completion
            host.startScan()
            val deadline = System.currentTimeMillis() + 3_000L
            while (host.devices.value.isEmpty() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20L)
            }
            assertEquals(1, host.devices.value.size)
            assertEquals("Arch-Workstation", host.devices.value.first().alias)

            host.stop()

            assertFalse(host.isRunning.value)
            assertFalse(fakeLock.isHeld)
            assertEquals(1, fakeLock.releaseCount)
            assertTrue(fakeReceiver.stopped)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `normalizePairingUri supports both ec0pair and ec0 pair URI formats`() {
        val id = Ed25519Identity.generate()
        val canonical = PairingQrPayload(
            deviceId = id.deviceId,
            publicKeyRaw = id.publicKeyRaw,
            alias = "Aryan-Desktop",
            nonce = ByteArray(16) { (it + 1).toByte() },
            port = 49880,
            ips = listOf("192.168.1.10"),
        ).toUri()

        val queryOnly = canonical.substringAfter("ec0://pair?")
        val variants = listOf(
            canonical,
            "  $canonical  ",
            "\"$canonical\"",
            "ec0pair://$queryOnly",
            "ec0pair://?$queryOnly",
            "ec0pair://pair?$queryOnly",
            "EC0PAIR://pair?$queryOnly",
        )

        for (variant in variants) {
            val normalized = AndroidCompanionRuntime.normalizePairingUri(variant)
            assertEquals(canonical, normalized, "Failed to normalize variant: $variant")
            val parsed = PairingQrPayload.parseUri(normalized)
            assertEquals(id.deviceId, parsed.deviceId)
            assertEquals("Aryan-Desktop", parsed.alias)
            assertEquals(49880, parsed.port)
            assertEquals(listOf("192.168.1.10"), parsed.ips)
        }
    }

    @Test
    fun `pairing URI parse to pin TRUSTED_QR to connect transitions through full state machine`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ec0-pairing-flow-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val nowMs = 1_731_710_000_000L

        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generate(),
            displayName = "Aryan-Arch-PC",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = File(tempRoot, "desktop-downloads").apply { mkdirs() },
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
            mdnsTransportFactory = { NoOpMdnsTransport() },
            clockMs = { nowMs },
        )

        try {
            desktopNode.startServer(startMdns = false)
            val qrPayload = desktopNode.generatePairingQr(ips = listOf("127.0.0.1"))
            val pastedEc0PairUri = qrPayload.toUri().replaceFirst("ec0://pair?", "ec0pair://")

            val phoneStoreDir = File(tempRoot, "phone-files/ec0")
            val phoneRuntime = AndroidCompanionRuntime.createWithPersistentStores(
                storeDir = phoneStoreDir,
                phoneModelName = "OPPO-A5x",
                callbacks = object : AndroidPlatformCallbacks {},
                listenPort = 0,
                mdnsTransportFactory = { NoOpMdnsTransport() },
                clockMs = { nowMs },
            )
            val host = CompanionServiceHost(
                runtime = phoneRuntime,
                multicastLock = FakeMulticastLock(),
                receiver = FakeCompanionReceiver(),
                discoverSweep = { emptyList() },
                scope = scope,
                discoveryPeriodMs = 0L,
                startMdnsOnServerStart = false,
            )

            try {
                host.start()
                assertEquals(0, host.trustedPeerCount.value)

                val recordedSteps = mutableListOf<PairingStepState>()
                val finalState = host.pairWithUriBlocking(pastedEc0PairUri) { step ->
                    recordedSteps.add(step)
                }

                // 1. Verify step sequence: Parsing -> Pinned(TRUSTED_QR) -> Connecting(DISCOVERING) -> Connected
                assertEquals(4, recordedSteps.size, "Expected 4 steps: $recordedSteps")
                assertIs<PairingStepState.Parsing>(recordedSteps[0])
                val pinnedStep = assertIs<PairingStepState.Pinned>(recordedSteps[1])
                assertEquals(desktopNode.localIdentity.deviceId, pinnedStep.payload.deviceId)
                assertEquals("Aryan-Arch-PC", pinnedStep.payload.alias)
                val connectingStep = assertIs<PairingStepState.Connecting>(recordedSteps[2])
                assertEquals(EngineConnectionState.DISCOVERING, connectingStep.connectionState)
                val connectedStep = assertIs<PairingStepState.Connected>(recordedSteps[3])
                assertEquals( connectedStep, finalState)

                // 2. Verify both sides pinned each other as TRUSTED_QR
                val phonePin = phoneRuntime.pinStore.get(desktopNode.localIdentity.deviceId)
                assertNotNull(phonePin)
                assertEquals(TrustLevel.TRUSTED_QR, phonePin.trustLevel)
                assertEquals("Aryan-Arch-PC", phonePin.alias)

                val desktopPin = desktopNode.pinStore.get(phoneRuntime.phoneIdentity.deviceId)
                assertNotNull(desktopPin)
                assertEquals(TrustLevel.TRUSTED_QR, desktopPin.trustLevel)
                assertEquals("OPPO-A5x", desktopPin.alias)

                // 3. Verify node connection state is CONNECTED and trustedPeerCount updated to 1
                assertEquals(
                    EngineConnectionState.CONNECTED,
                    phoneRuntime.node.connectionStates.value[desktopNode.localIdentity.deviceId.lowercase()],
                )
                val deadline = System.currentTimeMillis() + 2_000L
                while (host.trustedPeerCount.value < 1 && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(15L)
                }
                assertEquals(1, host.trustedPeerCount.value)
            } finally {
                host.stop()
            }
        } finally {
            desktopNode.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `pairing URI surfaces URI_PARSE error on malformed input and CONNECT error while retaining pin on unreachable endpoint`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ec0-pairing-failure-test").toFile()
        try {
            val phoneRuntime = AndroidCompanionRuntime.createWithPersistentStores(
                storeDir = File(tempRoot, "phone-files/ec0"),
                phoneModelName = "OPPO-A5x",
                callbacks = object : AndroidPlatformCallbacks {},
                listenPort = 0,
                mdnsTransportFactory = { NoOpMdnsTransport() },
            )
            try {
                // Case 1: Malformed URI fails at URI_PARSE and pins nothing
                val parseFailure = phoneRuntime.pairWithDesktopUriDetailed("ec0pair://v=1&id=not-a-valid-uri")
                val failedParse = assertIs<PairingStepState.Failed>(parseFailure)
                assertEquals("URI_PARSE", failedParse.stage)
                assertEquals(0, phoneRuntime.pinnedPeers.value.size)

                // Case 2: Valid URI with unreachable port pins TRUSTED_QR and reports CONNECT failure + BACKOFF
                val offlineDesktopId = Ed25519Identity.generate()
                val unreachableUri = PairingQrPayload(
                    deviceId = offlineDesktopId.deviceId,
                    publicKeyRaw = offlineDesktopId.publicKeyRaw,
                    alias = "Offline-Desktop",
                    nonce = ByteArray(16) { 0x42 },
                    port = 1, // Unassigned/closed port on 127.0.0.1
                    ips = listOf("127.0.0.1"),
                ).toUri().replaceFirst("ec0://pair?", "ec0pair://")

                val connectFailure = phoneRuntime.pairWithDesktopUriDetailed(unreachableUri)
                val failedConnect = assertIs<PairingStepState.Failed>(connectFailure)
                assertEquals("CONNECT", failedConnect.stage)
                assertNotNull(failedConnect.pinnedPayload)
                assertEquals("Offline-Desktop", failedConnect.pinnedPayload?.alias)

                // Even though the immediate TCP dial failed, the peer is pinned as TRUSTED_QR
                assertEquals(1, phoneRuntime.pinnedPeers.value.size)
                assertEquals(
                    TrustLevel.TRUSTED_QR,
                    phoneRuntime.pinStore.get(offlineDesktopId.deviceId)?.trustLevel,
                )
                assertEquals(
                    EngineConnectionState.BACKOFF,
                    phoneRuntime.node.connectionStates.value[offlineDesktopId.deviceId.lowercase()],
                )
            } finally {
                phoneRuntime.close()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
