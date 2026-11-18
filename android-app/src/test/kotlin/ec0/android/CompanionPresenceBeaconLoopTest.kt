/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The phone-side presence-beacon leg: the desktop's tile registry renders from
 * the beacons IT RECEIVES, so the phone must broadcast its own once a session
 * reaches CONNECTED. Without the host's beacon loop the desktop's registry
 * rendered permanently empty even with a live, authenticated session.
 *
 * The host under test runs with `beaconPeriodMs = 60_000` — the ticker's first
 * iteration fires before pairing completes (no connected peers yet) and cannot
 * fire again inside the assertion window, so any beacon the desktop receives
 * here MUST have come from the reactive `connectionStates → CONNECTED` leg.
 */
package ec0.android

import ec0.core.engine.EngineCapability
import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineConstants
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.beaconCapabilityWireNames
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ec0.ui.models.TransferItem

class CompanionPresenceBeaconLoopTest {

    private class NoOpMdnsTransport : MdnsDatagramTransport {
        override fun send(payload: ByteArray) = Unit
        override fun receive(): ec0.core.engine.discovery.MdnsDatagram? = null
        override fun close() = Unit
    }

    private class FakeMulticastLock : MulticastLockHandle {
        override var isHeld: Boolean = false
        override fun acquire() { isHeld = true }
        override fun release() { isHeld = false }
    }

    private class FakeCompanionReceiver : CompanionReceiverHandle {
        var started = false
        var stopped = false
        private val _queue = MutableStateFlow<List<TransferItem>>(emptyList())
        override val transferQueue: StateFlow<List<TransferItem>> = _queue
        override fun start() { started = true }
        override fun stop() { stopped = true }
    }

    private val openNodes = mutableListOf<Ec0EngineNode>()
    private val openScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        Ec0NotificationListener.attachRuntime(null)
        openNodes.forEach { runCatching { it.close() } }
        openNodes.clear()
        openScopes.forEach { it.cancel() }
        openScopes.clear()
    }

    @Test
    fun reactiveLegBroadcastsPhoneBeaconWhenSessionConnects() {
        val desktopNode = Ec0EngineNode(
            localIdentity = Ed25519Identity.generate(),
            displayName = "Aryan Arch Linux",
            role = EngineConstants.ROLE_DESKTOP,
            listenPort = 0,
            downloadDir = Files.createTempDirectory("ec0-beacon-desktop").toFile(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoOpMdnsTransport() },
        ).also { openNodes.add(it) }

        val phoneCallbacks = object : AndroidPlatformCallbacks {
            override fun queryPhonePresence(): PresenceBeaconInfo = PresenceBeaconInfo(
                batteryPct = 77,
                isCharging = false,
                screenOn = true,
                capabilities = setOf(
                    EngineCapability.MIRROR,
                    EngineCapability.CLIPBOARD,
                    EngineCapability.REMOTE_INPUT,
                ),
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { openScopes.add(it) }
        val phoneRuntime = AndroidCompanionRuntime(
            phoneIdentity = Ed25519Identity.generate(),
            phoneModelName = "SM-A035F",
            callbacks = phoneCallbacks,
            listenPort = 0,
            downloadDir = Files.createTempDirectory("ec0-beacon-phone").toFile(),
            pinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = true),
            mdnsTransportFactory = { NoOpMdnsTransport() },
        )
        val host = CompanionServiceHost(
            runtime = phoneRuntime,
            multicastLock = FakeMulticastLock(),
            receiver = FakeCompanionReceiver(),
            discoverSweep = { emptyList() },
            scope = scope,
            discoveryPeriodMs = 0L, // no discovery noise in this test
            startMdnsOnServerStart = false,
            beaconPeriodMs = 60_000L, // ticker cannot deliver inside the window
        )

        desktopNode.startServer(startMdns = false)
        host.start()
        try {
            val phoneId = phoneRuntime.phoneIdentity.deviceId
            assertTrue(phoneRuntime.pairWithDesktopQr(desktopNode.generatePairingQr(ips = listOf("127.0.0.1")).toUri()))

            // The reactive leg must deliver the phone's beacon to the desktop the
            // moment the session authenticates — the desktop's tile registry
            // consumes exactly this (peerPresence ∩ localSupport).
            val deadline = System.currentTimeMillis() + 5_000L
            while (desktopNode.peerPresence.value[phoneId] == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            val beacon = desktopNode.peerPresence.value[phoneId]
            assertTrue(beacon != null, "desktop must receive the phone's presence beacon via the host beacon leg")
            assertEquals(77, beacon?.batteryPct)
            assertTrue(
                "mirror" in beaconCapabilityWireNames(beacon?.capabilities ?: emptySet()),
                "beacon capabilities must carry the mirror wire name the desktop's tile registry negotiates",
            )
        } finally {
            runCatching { host.stop() }
        }
    }
}
