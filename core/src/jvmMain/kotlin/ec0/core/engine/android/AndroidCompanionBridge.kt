/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Android Companion Bridge (`ec0.core.engine.android`):
 *   - Platform-agnostic runtime controller for the `:android-app` companion
 *     service (`Ec0CompanionService`, `Ec0NotificationListener`,
 *     `ShareTargetActivity`, `CamDroid` `StreamingController`, `WifiManager`
 *     Instant Hotspot, `TelecomManager` Call Relay, and `MediaSessionManager`).
 *   - Lives in `:core` so its entire state machine, ColorOS wake recovery,
 *     Android Share Sheet fan-out, RemoteInput inline reply execution,
 *     CamDroid camera command dispatch, Instant Hotspot, Call Relay, and
 *     MediaSession sync are verified by CI unit tests without requiring the
 *     Android SDK on the CI runner.
 */
package ec0.core.engine.android

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.EngineConstants
import ec0.core.engine.PairingQrPayload
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.HotspotAndTelephonyProtocol
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.FindMyRingCommand
import ec0.core.engine.protocol.NotificationActionCommand
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.services.ClipboardPushOutcome
import ec0.core.engine.services.InputEventSink
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow

/**
 * Callbacks implemented by Android OS services in `:android-app`:
 * - `Ec0NotificationListener` (for [onExecuteInlineReply], [onNotificationActionButton], and [onDismissSystemNotification])
 * - `ClipboardManager` wrapper (for [onApplyRemoteClipboard])
 * - `AccessibilityService` / `InputMethodService` (for [onInjectKeyEvent], [onInjectPointerEvent], [onCursorHandoff])
 * - `CamDroid` `StreamingController` / `CameraStreamingService` (for [onCameraControlCommand])
 * - `WifiManager` Tethering / LocalOnlyHotspot (for [onHotspotControlCommand])
 * - `TelecomManager` / `SmsManager` (for [onCallActionCommand])
 * - `MediaSessionManager` (for [onMediaControlCommand])
 * - `BatteryManager` + `PowerManager` (for [queryPhonePresence])
 */
interface AndroidPlatformCallbacks {
    fun onApplyRemoteClipboard(item: ClipboardItem): Boolean = true
    fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean = true
    fun onNotificationActionButton(command: NotificationActionCommand): Boolean = false
    fun onDismissSystemNotification(notifId: String): Boolean = true
    fun onInjectKeyEvent(event: InputKeyEvent): Boolean = true
    fun onInjectPointerEvent(event: InputPointerEvent): Boolean = true
    fun onCursorHandoff(command: CursorHandoffCommand): Boolean = true
    fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo? = null
    fun onHotspotControlCommand(command: HotspotControlCommand): HotspotStatusInfo? = null
    fun onCallActionCommand(command: CallActionCommand): Boolean = true
    fun onMediaControlCommand(command: MediaControlCommand): MediaSessionInfo? = null
    /** Received CMD_FIND_MY_RING (desktop "Find my phone" siren) — ring executor. */
    fun onFindMyRingCommand(command: FindMyRingCommand) = Unit
    fun queryPhonePresence(): PresenceBeaconInfo = PresenceBeaconInfo(
        batteryPct = 100,
        isCharging = false,
        screenOn = true,
        capabilities = EngineCapability.entries.toSet(),
    )
}

/**
 * Step-by-step state trace emitted during phone-side QR/URI pairing
 * (`URI_PARSE -> PIN (TRUSTED_QR) -> CONNECT (DISCOVERING -> CONNECTING -> AUTHENTICATING -> CONNECTED)`).
 */
sealed interface PairingStepState {
    data object Idle : PairingStepState
    data class Parsing(val rawUri: String) : PairingStepState
    data class Pinned(val payload: PairingQrPayload) : PairingStepState
    data class Connecting(
        val payload: PairingQrPayload,
        val connectionState: EngineConnectionState,
    ) : PairingStepState
    data class Connected(val payload: PairingQrPayload) : PairingStepState
    data class Failed(
        val stage: String,
        val message: String,
        val pinnedPayload: PairingQrPayload? = null,
    ) : PairingStepState
}

/**
 * Runtime coordinator for the Android phone side of `ec0-engine`.
 *
 * Bridges Android system events (notifications posted, clipboard copied,
 * Share Sheet files selected, incoming phone calls, media session updates,
 * Instant Hotspot status, and screen wake / network validation after ColorOS
 * Hans freeze) into [Ec0EngineNode].
 */
class AndroidCompanionRuntime(
    val phoneIdentity: Ed25519KeyPair,
    val phoneModelName: String,
    val callbacks: AndroidPlatformCallbacks,
    val listenPort: Int = EngineConstants.PRIMARY_PORT,
    val downloadDir: File,
    val pinStore: PinStore = InMemoryPinStore(allowAutoTofuOnUnknownPeer = false),
    mdnsTransportFactory: (() -> MdnsDatagramTransport)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : Closeable {

    val node: Ec0EngineNode = Ec0EngineNode(
        localIdentity = phoneIdentity,
        displayName = phoneModelName,
        role = EngineConstants.ROLE_PHONE,
        listenPort = listenPort,
        downloadDir = downloadDir,
        pinStore = pinStore,
        mdnsTransportFactory = mdnsTransportFactory,
        onRemoteClipboardApplied = { item ->
            callbacks.onApplyRemoteClipboard(item)
        },
        onInlineNotificationReply = { reply ->
            callbacks.onExecuteInlineReply(reply)
        },
        onNotificationActionButton = { cmd ->
            callbacks.onNotificationActionButton(cmd)
        },
        onRemoteNotificationDismiss = { notifId ->
            callbacks.onDismissSystemNotification(notifId)
        },
        inputEventSink = object : InputEventSink {
            override fun onKeyEvent(event: InputKeyEvent) {
                callbacks.onInjectKeyEvent(event)
            }

            override fun onPointerEvent(event: InputPointerEvent) {
                callbacks.onInjectPointerEvent(event)
            }
        },
        onExecuteCameraCommand = { cmd ->
            callbacks.onCameraControlCommand(cmd)
        },
        onRemoteCursorHandoff = { cmd ->
            callbacks.onCursorHandoff(cmd)
        },
        onRemoteHotspotCommand = { cmd ->
            callbacks.onHotspotControlCommand(cmd)
        },
        onRemoteCallAction = { cmd ->
            callbacks.onCallActionCommand(cmd)
        },
        onRemoteMediaControl = { cmd ->
            callbacks.onMediaControlCommand(cmd)
        },
        clockMs = clockMs,
    )

    private val runtimeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var reconnectJob: kotlinx.coroutines.Job? = null

    private val _pinnedPeers = MutableStateFlow<List<PinnedPeer>>(pinStore.all())
    val pinnedPeers: StateFlow<List<PinnedPeer>> = _pinnedPeers.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingStepState>(PairingStepState.Idle)
    val pairingState: StateFlow<PairingStepState> = _pairingState.asStateFlow()

    fun refreshPinnedPeers(): List<PinnedPeer> {
        val current = pinStore.all()
        _pinnedPeers.value = current
        return current
    }

    fun start(startMdns: Boolean = false) {
        refreshPinnedPeers()
        node.startServer(startMdns = startMdns)
        // Deep-integration slice A2: route the engine's received CMD_FIND_MY_RING
        // envelopes (desktop "Find my phone" siren toggle) into the platform
        // callbacks — the ring executor lives in the app layer (AudioManager +
        // wake activity). Assigned post-construction; the desktop keeps its own
        // initiator default.
        node.handoffAndProximityService.onFindMyRingRequested = { command ->
            callbacks.onFindMyRingCommand(command)
        }
        scheduleReconnectToTrustedPeers()
    }

    /**
     * One-shot reconnect sweep shortly after engine start: the phone's resolver has the
     * desktop's last-known endpoint persisted in the pin store (Tier 2, port-walked
     * across the standard tier), so a restarted app re-dials the desktop even when
     * mDNS multicast is filtered by the network. Failures are silent — the desktop's
     * reconnect supervisor is the other half of the ladder.
     */
    private fun scheduleReconnectToTrustedPeers() {
        reconnectJob?.cancel()
        reconnectJob = runtimeScope.launch {
            kotlinx.coroutines.delay(RECONNECT_SETTLE_DELAY_MS)
            for (peer in pinStore.all()) {
                if (node.activeSessionFor(peer.deviceId) == null) {
                    runCatching { node.reconnectToPeer(peer.deviceId) }
                }
            }
        }
    }

    /**
     * Called by Android's `Ec0NotificationListener.onNotificationPosted(sbn)`
     * to relay a phone notification to all paired Linux desktops (or queue it in
     * the ColorOS-resilient [Ec0EngineNode.outboxQueue] if the desktop is offline).
     */
    fun onAndroidNotificationPosted(
        notif: NotificationPostInfo,
        targetDesktopDeviceIds: Set<String> = defaultTargetDesktopIds(),
    ): Int {
        var deliveredOrQueued = 0
        for (peerId in targetDesktopDeviceIds) {
            node.sendOrQueueNotification(peerId, notif)
            deliveredOrQueued++
        }
        return deliveredOrQueued
    }

    /**
     * Called when the Android clipboard changes. Honors Android 13+
     * `ClipDescription.EXTRA_IS_SENSITIVE` via [ClipboardItem.isSensitive] so
     * password-manager clips are never transmitted over the network.
     */
    fun onAndroidClipboardChanged(
        item: ClipboardItem,
        targetDesktopDeviceIds: Set<String> = defaultTargetDesktopIds(),
    ): Map<String, ClipboardPushOutcome> {
        val outcomes = LinkedHashMap<String, ClipboardPushOutcome>()
        for (peerId in targetDesktopDeviceIds) {
            outcomes[peerId] = node.sendOrQueueClipboard(peerId, item, clockMs())
        }
        return outcomes
    }

    /**
     * Broadcasts an Android telephony call state change (`RINGING`, `ACTIVE`, `ENDED`)
     * to all connected Linux desktops so the desktop can display the incoming call banner
     * and automatically pause local media playback.
     */
    fun publishPhoneCallState(info: CallStateInfo): Int {
        var sent = 0
        for (peerId in defaultTargetDesktopIds()) {
            val session = node.activeSessionFor(peerId)
            if (session != null) {
                session.sendInfo(
                    infoType = HotspotAndTelephonyProtocol.INFO_CALL_STATE,
                    infoBody = info.toJsonObject(),
                    category = HotspotAndTelephonyProtocol.CATEGORY_TELEPHONY,
                    timestampMs = clockMs(),
                )
                sent++
            }
        }
        return sent
    }

    /**
     * Broadcasts the phone's Wi-Fi Hotspot status (`SSID`, passphrase, band MHz)
     * to all connected Linux desktops so the desktop can join via `nmcli`.
     */
    fun publishHotspotStatus(status: HotspotStatusInfo): Int {
        var sent = 0
        for (peerId in defaultTargetDesktopIds()) {
            val session = node.activeSessionFor(peerId)
            if (session != null) {
                session.sendInfo(
                    infoType = HotspotAndTelephonyProtocol.INFO_HOTSPOT_STATUS,
                    infoBody = status.toJsonObject(),
                    category = HotspotAndTelephonyProtocol.CATEGORY_NET,
                    timestampMs = clockMs(),
                )
                sent++
            }
        }
        return sent
    }

    /**
     * Broadcasts the phone's active `MediaSession` metadata (`Spotify`, `YouTube Music`, etc.)
     * to all connected Linux desktops.
     */
    fun publishMediaSession(info: MediaSessionInfo): Int {
        var sent = 0
        for (peerId in defaultTargetDesktopIds()) {
            val session = node.activeSessionFor(peerId)
            if (session != null) {
                node.mediaSessionBridgeService.broadcastMediaSession(info, session, clockMs())
                sent++
            }
        }
        return sent
    }

    /**
     * Called by Android's `ShareTargetActivity` (`Intent.ACTION_SEND` /
     * `Intent.ACTION_SEND_MULTIPLE`) to AirDrop files or directories to a paired
     * Linux desktop.
     */
    fun shareFileToDesktop(
        peerDeviceId: String,
        file: File,
        transferId: String = "share-${clockMs()}",
    ): Boolean {
        return try {
            node.sendFileOrDirectory(
                peerDeviceId = peerDeviceId,
                fileOrDir = file,
                transferId = transferId,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Triggered when ColorOS unfreezes the app (`ConnectivityManager.NetworkCallback.onValidated`
     * or `Intent.ACTION_USER_PRESENT`): broadcasts fresh battery/screen presence
     * to all currently connected Linux desktops.
     */
    fun onDeviceWakeOrNetworkValidated(): Int {
        val beacon = callbacks.queryPhonePresence()
        var count = 0
        for ((peerId, state) in node.connectionStates.value) {
            if (state == EngineConnectionState.CONNECTED && node.activeSessionFor(peerId) != null) {
                node.sendPresenceBeacon(peerId, beacon)
                count++
            }
        }
        return count
    }

    /**
     * Connects to a Linux desktop by scanning or pasting its `ec0://pair?v=1...`
     * or `ec0pair://...` QR URI.
     */
    fun pairWithDesktopQr(qrUri: String): Boolean {
        val outcome = pairWithDesktopUriDetailed(qrUri)
        return outcome is PairingStepState.Connected
    }

    /**
     * Full step-reporting pairing pipeline (`URI_PARSE -> PIN (TRUSTED_QR) -> CONNECT`):
     *   1. Normalizes `ec0pair://...` or `ec0://pair?...` and parses/validates via
     *      `node.pairingCoordinator.acceptScannedQrUri(normalizedUri, clockMs())`, pinning
     *      the desktop peer in [pinStore] with `TrustLevel.TRUSTED_QR`.
     *   2. Updates [pinnedPeers] immediately so `trustedPeerCount` reflects the newly
     *      pinned desktop.
     *   3. Walks the desktop's advertised `(host, port)` endpoints via `node.connectToPeer`.
     */
    fun pairWithDesktopUriDetailed(
        rawUri: String,
        onStep: (PairingStepState) -> Unit = {},
    ): PairingStepState {
        fun emit(step: PairingStepState): PairingStepState {
            _pairingState.value = step
            onStep(step)
            return step
        }

        emit(PairingStepState.Parsing(rawUri))
        val normalizedUri = normalizePairingUri(rawUri)
        val payload = try {
            node.pairingCoordinator.acceptScannedQrUri(normalizedUri, nowMs = clockMs())
        } catch (e: Exception) {
            return emit(
                PairingStepState.Failed(
                    stage = "URI_PARSE",
                    message = e.message ?: "Invalid pairing code",
                    pinnedPayload = null,
                ),
            )
        }

        // Refresh both node and runtime pinnedPeers immediately after TRUSTED_QR pin
        refreshPinnedPeers()
        emit(PairingStepState.Pinned(payload))
        emit(PairingStepState.Connecting(payload, EngineConnectionState.DISCOVERING))

        return try {
            node.pairAndConnectWithQrUri(normalizedUri)
            refreshPinnedPeers()
            emit(PairingStepState.Connected(payload))
        } catch (e: Exception) {
            refreshPinnedPeers()
            emit(
                PairingStepState.Failed(
                    stage = "CONNECT",
                    message = e.message ?: "Could not reach ${payload.alias}",
                    pinnedPayload = payload,
                ),
            )
        }
    }

    fun resetPairingState() {
        _pairingState.value = PairingStepState.Idle
    }

    private fun defaultTargetDesktopIds(): Set<String> {
        val pinnedFromNode = node.pinnedPeers.value.map { it.deviceId.lowercase() }
        val pinnedFromStore = pinStore.all().map { it.deviceId.lowercase() }
        val connected = node.connectionStates.value
            .filterValues { it == EngineConnectionState.CONNECTED }
            .keys
        return (pinnedFromNode + pinnedFromStore + connected).toSet()
    }

    override fun close() {
        reconnectJob?.cancel()
        runtimeScope.cancel()
        node.close()
    }

    companion object {
        private const val ALT_SCHEME_PREFIX = "ec0pair://"

        /** Network settle delay before the one-shot reconnect sweep after engine start. */
        private const val RECONNECT_SETTLE_DELAY_MS = 4_000L

        /**
         * Normalizes user-pasted pairing URIs (`ec0pair://...` or `ec0://pair?...`) into
         * canonical `ec0://pair?<query>` form expected by [PairingQrPayload.parseUri].
         */
        fun normalizePairingUri(rawUri: String): String {
            val trimmed = rawUri.trim().removeSurrounding("\"").removeSurrounding("'").trim()
            if (trimmed.startsWith(PairingQrPayload.SCHEME_PREFIX, ignoreCase = true)) {
                val queryPart = trimmed.substring(PairingQrPayload.SCHEME_PREFIX.length)
                return "${PairingQrPayload.SCHEME_PREFIX}$queryPart"
            }
            if (trimmed.startsWith(ALT_SCHEME_PREFIX, ignoreCase = true)) {
                val remainder = trimmed.substring(ALT_SCHEME_PREFIX.length).trimStart('/')
                val query = when {
                    remainder.startsWith("pair?", ignoreCase = true) -> remainder.substring("pair?".length)
                    remainder.startsWith("?") -> remainder.substring(1)
                    else -> remainder
                }
                return "${PairingQrPayload.SCHEME_PREFIX}?$query"
            }
            return trimmed
        }

        /**
         * Creates an [AndroidCompanionRuntime] backed by explicit `<appFilesDir>/ec0`
         * store directories ([ec0.core.engine.crypto.EncryptedIdentityStore] and
         * [ec0.core.engine.crypto.PersistentPinStore]) rather than `user.home/.config`
         * (which is invalid on Android).
         */
        fun createWithPersistentStores(
            storeDir: File,
            phoneModelName: String,
            callbacks: AndroidPlatformCallbacks,
            listenPort: Int = EngineConstants.PRIMARY_PORT,
            downloadDir: File = File(storeDir, "downloads"),
            allowAutoTofuOnUnknownPeer: Boolean = false,
            mdnsTransportFactory: (() -> MdnsDatagramTransport)? = null,
            clockMs: () -> Long = { System.currentTimeMillis() },
        ): AndroidCompanionRuntime {
            storeDir.mkdirs()
            downloadDir.mkdirs()
            val identityStore = ec0.core.engine.crypto.EncryptedIdentityStore(configDir = storeDir)
            val pinStore = ec0.core.engine.crypto.PersistentPinStore(
                storeFile = File(storeDir, "pinned_peers.json"),
                allowAutoTofuOnUnknownPeer = allowAutoTofuOnUnknownPeer,
            )
            return AndroidCompanionRuntime(
                phoneIdentity = identityStore.loadOrCreateIdentity(),
                phoneModelName = phoneModelName,
                callbacks = callbacks,
                listenPort = listenPort,
                downloadDir = downloadDir,
                pinStore = pinStore,
                mdnsTransportFactory = mdnsTransportFactory,
                clockMs = clockMs,
            )
        }
    }
}
