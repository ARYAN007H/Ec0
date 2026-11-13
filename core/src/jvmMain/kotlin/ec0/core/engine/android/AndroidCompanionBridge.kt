/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Android Companion Bridge (`ec0.core.engine.android`):
 *   - Platform-agnostic runtime controller for the `:android-app` companion
 *     service (`Ec0ForegroundService`, `Ec0NotificationListenerService`,
 *     `ShareTargetActivity`, and `CamDroid` `StreamingController` bridge).
 *   - Lives in `:core` so its entire state machine, ColorOS wake recovery,
 *     Android Share Sheet fan-out, RemoteInput inline reply execution, and
 *     CamDroid camera command dispatch are verified by CI unit tests without
 *     requiring the Android SDK on the CI runner.
 */
package ec0.core.engine.android

import ec0.core.engine.Ec0EngineNode
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConstants
import ec0.core.engine.PairingQrPayload
import ec0.core.engine.crypto.Ed25519KeyPair
import ec0.core.engine.crypto.InMemoryPinStore
import ec0.core.engine.crypto.PinStore
import ec0.core.engine.discovery.MdnsDatagramTransport
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.services.ClipboardPushOutcome
import ec0.core.engine.services.InputEventSink
import java.io.Closeable
import java.io.File

/**
 * Callbacks implemented by Android OS services in `:android-app`:
 * - `Ec0NotificationListenerService` (for [onExecuteInlineReply] and [onDismissSystemNotification])
 * - `ClipboardManager` wrapper (for [onApplyRemoteClipboard])
 * - `AccessibilityService` / `InputMethodService` (for [onInjectKeyEvent] and [onInjectPointerEvent])
 * - `CamDroid` `StreamingController` / `CameraStreamingService` (for [onCameraControlCommand])
 * - `BatteryManager` + `PowerManager` (for [queryPhonePresence])
 */
interface AndroidPlatformCallbacks {
    fun onApplyRemoteClipboard(item: ClipboardItem): Boolean = true
    fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean = true
    fun onDismissSystemNotification(notifId: String): Boolean = true
    fun onInjectKeyEvent(event: InputKeyEvent): Boolean = true
    fun onInjectPointerEvent(event: InputPointerEvent): Boolean = true
    fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo? = null
    fun queryPhonePresence(): PresenceBeaconInfo = PresenceBeaconInfo(
        batteryPct = 100,
        isCharging = false,
        screenOn = true,
        capabilities = EngineCapability.entries.toSet(),
    )
}

/**
 * Runtime coordinator for the Android phone side of `ec0-engine`.
 *
 * Bridges Android system events (notifications posted, clipboard copied,
 * Share Sheet files selected, screen wake / network validated after ColorOS
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
        clockMs = clockMs,
    )

    fun start(startMdns: Boolean = false) {
        node.startServer(startMdns = startMdns)
    }

    /**
     * Called by Android's `Ec0NotificationListenerService.onNotificationPosted(sbn)`
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
            if (state == ec0.core.engine.EngineConnectionState.CONNECTED && node.activeSessionFor(peerId) != null) {
                node.sendPresenceBeacon(peerId, beacon)
                count++
            }
        }
        return count
    }

    /**
     * Connects to a Linux desktop by scanning its `ec0://pair?v=1...` QR code.
     */
    fun pairWithDesktopQr(qrUri: String): Boolean {
        return try {
            node.pairAndConnectWithQrUri(qrUri)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun defaultTargetDesktopIds(): Set<String> {
        val pinned = node.pinnedPeers.value.map { it.deviceId.lowercase() }
        val connected = node.connectionStates.value
            .filterValues { it == ec0.core.engine.EngineConnectionState.CONNECTED }
            .keys
        return (pinned + connected).toSet()
    }

    override fun close() {
        node.close()
    }
}
