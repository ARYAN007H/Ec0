/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 Android client:
 *   - Starts and binds to [Ec0CompanionService] (`connectedDevice` foreground
 *     service hosting [AndroidCompanionRuntime], the engine server with mDNS,
 *     the Ktor CIO LocalSend receiver, and the UDP discovery ticker + MulticastLock
 *     so all background services survive Activity destruction).
 *   - When `trustedPeerCount == 0`, renders the M3 Expressive [AndroidOnboardingScreen]
 *     ("Connect to PC" -> paste `ec0pair://…` URI -> one-time ColorOS battery &
 *     notification listener setup cards); once trusted, enters the full-suite [Ec0App].
 */
package ec0.android

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.PairingStepState
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.beaconCapabilityWireNames
import ec0.core.localsend.DeviceType
import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.client.LocalsendHttp
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.ui.Ec0App
import ec0.ui.models.Capabilities
import ec0.ui.models.CapabilityTile
import ec0.ui.models.IncomingRequest
import ec0.ui.models.PairedPeerInfo
import ec0.ui.models.PeerLinkState
import ec0.ui.models.PeerManagementBridge
import ec0.ui.models.PhoneCameraBridge
import ec0.ui.models.PhoneCameraGrant
import ec0.ui.models.RemoteInputBridge
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.models.computeTileRegistry
import ec0.ui.models.UiDevice
import ec0.ui.theme.Ec0Seed
import ec0.ui.theme.Ec0Theme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pure peerLink mapping (same-commit tested, mirrors DesktopBridge): "Wi-Fi TLS"
 * while an engine session is CONNECTED (or the pairing pipeline has committed),
 * "searching" otherwise. The Android side has no USB tunnel leg (scrcpy runs
 * from the desktop), so the chip is two-state. [hasPinnedPeers] is carried for
 * the dashboard's honesty card (peers exist, ladder reconnecting) — both
 * non-connected outcomes render "searching" today; a distinct "reconnecting"
 * label would be a UI-only change.
 */
fun mapPeerLinkState(
    pairing: PairingStepState,
    hasPinnedPeers: Boolean,
    connectionStates: Map<String, EngineConnectionState>,
): PeerLinkState = when {
    connectionStates.values.any { it == EngineConnectionState.CONNECTED } ||
        pairing is PairingStepState.Connected ->
        PeerLinkState(connected = true, transport = "Wi-Fi TLS")
    else -> PeerLinkState(connected = false, transport = "searching")
}

/**
 * The phone's honest support set for the B3 tile registry — what a paired
 * DESKTOP can do through this phone's executors/senders: clipboard sync,
 * file AirDrop, notification forwarding, remote input senders (A1), media-key
 * dispatch (A2), Find-My ring (A2). Mirror/cam intentionally absent — the
 * phone CONSUMES those from the desktop (desktop support set), not the
 * reverse.
 */
val phoneLocalTileSupport: Set<EngineCapability> = setOf(
    EngineCapability.CLIPBOARD,
    EngineCapability.FILE,
    EngineCapability.NOTIFICATION,
    EngineCapability.INPUT,
    EngineCapability.MEDIA_CONTROL,
    EngineCapability.FIND_MY,
)

/**
 * Pure tile-registry mapping (same-commit tested, mirrors
 * `DesktopBridge.tiles`): the phone's dashboard tiles = the DESKTOP's beacon
 * capabilities ∩ [phoneLocalSupport], one tile per (capability, deviceId),
 * disabled while the peer's engine session is not CONNECTED. Consumes the
 * desktop's presence beacons through `node.peerPresence` — those exist only
 * since the hub's presence-beacon leg (`ContinuityHubController`), without
 * which this registry rendered permanently empty.
 */
fun mapPhoneTileRegistry(
    peerPresence: Map<String, PresenceBeaconInfo>,
    connectionStates: Map<String, EngineConnectionState>,
    phoneLocalSupport: Set<EngineCapability> = phoneLocalTileSupport,
): List<CapabilityTile> = computeTileRegistry(
    peerCapabilities = peerPresence.mapValues { (_, beacon) ->
        beaconCapabilityWireNames(beacon.capabilities)
    },
    localSupport = beaconCapabilityWireNames(phoneLocalSupport),
    onlineByDevice = connectionStates.mapValues { (_, state) ->
        state == EngineConnectionState.CONNECTED
    },
)

/** Android-side app state — implements the shared [UiBridge] seam over [Ec0CompanionService]. */
class AndroidAppState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: AndroidSettingsStore = AndroidSettingsStore(context.applicationContext),
    private val ec0StoreDir: File = File(context.filesDir, "ec0"),
    serviceHostOverride: CompanionServiceHost? = null,
) : UiBridge {

    private val defaultSettings = SettingsState(
        alias = Build.MODEL.ifBlank { "Android" },
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        deviceType = DeviceType.MOBILE,
        protocol = ProtocolType.HTTP,
    )

    private val _settings = MutableStateFlow(
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            runCatching { settingsStore.load(defaultSettings) }.getOrDefault(defaultSettings)
        },
    )
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    /**
     * Foreground-service-hosted coordinator owning [AndroidCompanionRuntime], the
     * engine server (`startServer(startMdns = true)`), the Ktor LocalSend receiver,
     * and the continuous `MulticastLock` + discovery ticker.
     */
    val serviceHost: CompanionServiceHost = serviceHostOverride
        ?: Ec0CompanionService.startServiceAndGetHost(
            context = context.applicationContext,
            initialSettings = _settings.value,
            ec0StoreDir = ec0StoreDir,
        )

    val companionRuntime: AndroidCompanionRuntime
        get() = serviceHost.runtime

    val pinnedPeers: StateFlow<List<PinnedPeer>>
        get() = serviceHost.pinnedPeers

    override val trustedPeerCount: StateFlow<Int>
        get() = serviceHost.trustedPeerCount

    val pairingState: StateFlow<PairingStepState>
        get() = serviceHost.pairingState

    private val http = LocalsendHttp()

    override val devices: StateFlow<List<UiDevice>>
        get() = serviceHost.devices

    private val _outgoingTransfers = MutableStateFlow<List<TransferItem>>(emptyList())

    override val scanning: StateFlow<Boolean>
        get() = serviceHost.scanning

    /**
     * Notification-listener grant state, polled — the grant can flip outside the
     * app (system settings, QS) and the SyncPanel switch must reflect reality,
     * not the value seen at process start. A Settings.Secure read per 2 s is
     * noise; the activity scope cancels the ticker on destroy.
     */
    private val _notificationRelayGranted = MutableStateFlow(false)

    /**
     * Strict session-live predicate (any engine session CONNECTED) — feeds the
     * SyncPanel dots ("LIVE" must mean an actual peer session, not a hardcoded
     * `true`) and the remote-input bridge's availability.
     */
    private val sessionLive: StateFlow<Boolean> =
        serviceHost.runtime.node.connectionStates
            .map { states -> states.values.any { it == EngineConnectionState.CONNECTED } }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val capabilities: Capabilities
        get() = Capabilities(
            canReceive = true,
            // LIVE only means something real: a live peer session AND the sync
            // toggle armed (the hardcoded `true` made the dot lie while paused
            // or disconnected).
            clipboardSync = clipboardSyncEnabled.value && sessionLive.value,
            notifications = _notificationRelayGranted.value,
            photoSync = false, // photo-sync slice has not shipped — stays honest
            remoteInput = false,
        )

    /**
     * Phone clipboard-sync toggle — ONE boolean behind the SyncPanel card, the
     * HOME Auto-sync tile and the QS tile, persisted in DataStore and applied to
     * the running watcher/apply gate via [Ec0TileState.setClipboardSyncEnabled]
     * (assigned by the service host, same instance the QS tile flips).
     */
    override val clipboardSyncEnabled: StateFlow<Boolean> =
        settingsStore.clipboardSyncEnabledFlow().stateIn(scope, SharingStarted.Eagerly, true)

    override fun setClipboardSyncEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.setClipboardSyncEnabled(enabled) }
        }
        Ec0TileState.setClipboardSyncEnabled(enabled)
    }

    /** HOME Auto-sync switch = the same phone clipboard-sync boolean. */
    override val autoSyncClipboard: StateFlow<Boolean> get() = clipboardSyncEnabled
    override fun setAutoSyncClipboard(enabled: Boolean) = setClipboardSyncEnabled(enabled)

    /**
     * Notification relay toggle — REAL state is the system notification-listener
     * grant, which Android cannot grant or revoke programmatically. Both toggle
     * directions therefore route to the system grant screen; the flow updates
     * when the grant actually changes (ticker + resume refresh), never before.
     */
    override val notificationRelayEnabled: StateFlow<Boolean> = _notificationRelayGranted.asStateFlow()

    override fun setNotificationRelayEnabled(enabled: Boolean) {
        val granted = isNotificationListenerAccessGranted()
        when {
            enabled && !granted -> requestNotificationListenerAccess()
            !enabled && granted -> requestNotificationListenerAccess()
        }
    }

    private fun refreshNotificationRelayState() {
        _notificationRelayGranted.value = isNotificationListenerAccessGranted()
    }

    /**
     * Dashboard header chip — mirrors [ec0.desktop.ui.DesktopBridge.peerLink]:
     * "Wi-Fi TLS" while an engine session is CONNECTED (or the pairing pipeline
     * reached Connected), "searching" otherwise. Pure mapping is
     * [mapPeerLinkState] (same-commit tested).
     */
    override val peerLink: StateFlow<PeerLinkState?> = combine(
        serviceHost.pairingState,
        serviceHost.pinnedPeers,
        serviceHost.runtime.node.connectionStates,
    ) { pairing, pinned, states ->
        mapPeerLinkState(pairing, pinned.isNotEmpty(), states)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val showRemoteInput = MutableStateFlow(false)
    val showDeviceManagement = MutableStateFlow(false)

    /**
     * Desktop the chip/tiles target: the peer with a LIVE engine session (multi-
     * device honest targeting — never firstOrNull over pins).
     */
    val connectedDesktopDeviceId: StateFlow<String?> =
        combine(serviceHost.runtime.node.connectionStates, serviceHost.runtime.pinnedPeers) { states, pinned ->
            val live = states.entries.firstOrNull { it.value == EngineConnectionState.CONNECTED }?.key
            live ?: pinned.firstOrNull()?.deviceId.takeIf { states.isNotEmpty() }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The live desktop's negotiated capabilities (dynamic-tile contract, plan §P1). */
    val desktopCapabilities: StateFlow<Set<EngineCapability>> =
        combine(connectedDesktopDeviceId, serviceHost.runtime.node.peerPresence) { id, presence ->
            id?.let { presence[it]?.capabilities } ?: emptySet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * B3 dynamic tile registry, phone side (dynamic-tile contract, plan §P1):
     * tiles = the desktop's beacon capabilities ∩ [phoneLocalTileSupport],
     * consuming the desktop's beacons through `node.peerPresence` and disabling
     * tiles for peers whose engine session is not CONNECTED. The UiBridge
     * default (empty) rendered nothing — the registry never had data before
     * the desktop broadcast its own presence beacon.
     */
    override val tiles: StateFlow<List<CapabilityTile>> = combine(
        serviceHost.runtime.node.peerPresence,
        serviceHost.runtime.node.connectionStates,
    ) { presence, states ->
        mapPhoneTileRegistry(presence, states)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val grantsFlow = settingsStore.deviceCapabilityGrantsFlow()

    /** A5: paired-device surface over the engine pin store + LocalSend trust. */
    private inner class PeerManagementBridgeImpl : PeerManagementBridge {
        override val peers: StateFlow<List<PairedPeerInfo>> = combine(
            serviceHost.pinnedPeers,
            serviceHost.runtime.node.connectionStates,
            grantsFlow,
        ) { pinned, states, grants ->
            pinned.map { peer ->
                PairedPeerInfo(
                    deviceId = peer.deviceId,
                    alias = peer.alias,
                    // Human-verification fingerprint: SHA-256 of the peer's public key.
                    fingerprintPrefix = ec0.core.kdeconnect.Sha256.digest(peer.publicKeyRaw)
                        .joinToString("") { "%02x".format(it) }.take(16),
                    pinnedAtMs = peer.pinnedAtMs,
                    trustLevel = peer.trustLevel.name,
                    connected = states[peer.deviceId] == EngineConnectionState.CONNECTED,
                    grantedCapabilities = grants[peer.deviceId] ?: setOf("remote_input"),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        override fun unpair(deviceId: String) {
            // Engine pin revoke (unpairPeer also drops the engine session) —
            // core-owned call, unchanged.
            serviceHost.runtime.node.unpairPeer(deviceId)
            // Best-effort wire notice so the desktop unpins us too (hub coordination:
            // cmd_type "unpair", category "system" — agent-2's receiver consumes it).
            serviceHost.runtime.node.activeSessionFor(deviceId)?.let { session ->
                runCatching {
                    session.sendCmd(
                        cmdType = EngineConstants.CMD_UNPAIR,
                        cmdBody = kotlinx.serialization.json.buildJsonObject {
                            put("reason", kotlinx.serialization.json.JsonPrimitive("user_unpair"))
                        },
                        category = EngineConstants.CATEGORY_SYSTEM,
                        timestampMs = System.currentTimeMillis(),
                    )
                }
            }
            // LocalSend trust: the trusted fingerprints set is keyed by LocalSend
            // fingerprints; a per-peer mapping is not stored yet — the full removal
            // is a handoff note for argon (receiver trust registry). v1 clears the
            // pairing state so the tile stops showing the removed peer.
            serviceHost.resetPairingState()
        }

        override fun setCapability(deviceId: String, wireName: String, granted: Boolean) {
            scope.launch(Dispatchers.IO) {
                runCatching { settingsStore.setDeviceCapability(deviceId, wireName, granted) }
            }
        }
    }

    /** A1: remote-input senders — available while a peer engine session is live. */
    private inner class RemoteInputBridgeImpl : RemoteInputBridge {
        override val available: StateFlow<Boolean> = sessionLive

        override fun openTouchpad() {
            showRemoteInput.value = true
        }
    }

    override val peerManagement: PeerManagementBridge = PeerManagementBridgeImpl()

    override val remoteInput: RemoteInputBridge = RemoteInputBridgeImpl()

    // ---- Phone-camera runtime grant (Settings "Phone camera" row) ----

    /** Observable CAMERA-grant facts; mapped to honest UI state by [mapPhoneCameraGrant]. */
    private val cameraGrantInputs = MutableStateFlow(
        CameraGrantInputs(granted = hasCameraPermission(), shouldShowRationale = false, deniedAtLeastOnce = false),
    )

    /**
     * Set by [MainActivity] — the permission dialog can only be launched from an
     * Activity; the bridge (applicationContext-backed) delegates through here.
     */
    var cameraRequestHandler: (() -> Unit)? = null

    private fun hasCameraPermission(): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun openAppPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)

    private inner class PhoneCameraBridgeImpl : PhoneCameraBridge {
        override val grant: StateFlow<PhoneCameraGrant> =
            cameraGrantInputs.map { mapPhoneCameraGrant(it) }.stateIn(
                scope,
                SharingStarted.Eagerly,
                mapPhoneCameraGrant(cameraGrantInputs.value),
            )

        override fun requestGrant() {
            // Decide on the MAPPED state (same source the row renders): the raw
            // inputs have no needsSystemSettings — that verdict is computed by
            // [mapPhoneCameraGrant] (denied + no rationale = Android will
            // silently auto-deny, so bounce to app settings).
            val current = grant.value
            when {
                current.granted -> Unit
                current.needsSystemSettings -> openAppPermissionSettings()
                else -> cameraRequestHandler?.invoke()
            }
        }
    }

    override val phoneCamera: PhoneCameraBridge = PhoneCameraBridgeImpl()

    /**
     * [MainActivity] feeds the launcher result here. The denial flag flips on
     * the RESULT (never on launching the dialog — dismissing a dialog by
     * navigating away is not a denial) and is persisted, because Android's own
     * rationale signal does not survive process death: without this, a blocked
     * grant would look like a fresh install after a restart and Enable would
     * launch a dialog that silently auto-denies.
     */
    fun onCameraPermissionResult(granted: Boolean, shouldShowRationale: Boolean) {
        cameraGrantInputs.value = CameraGrantInputs(
            granted = granted,
            shouldShowRationale = shouldShowRationale,
            deniedAtLeastOnce = !granted,
        )
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.setCameraDeniedOnce(!granted) }
        }
    }

    /** [MainActivity] re-checks on resume — grants flip in system settings too. */
    fun refreshCameraGrant(shouldShowRationale: Boolean = false) {
        cameraGrantInputs.value = cameraGrantInputs.value.copy(
            granted = hasCameraPermission(),
            shouldShowRationale = shouldShowRationale,
        )
    }

    fun isNotificationListenerAccessGranted(): Boolean =
        NotificationAccessHelper.isNotificationListenerEnabled(context)

    fun requestNotificationListenerAccess(): Boolean =
        NotificationAccessHelper.openNotificationAccessSettings(context)

    fun isBatteryOptimizationIgnored(): Boolean =
        ColorOsBatteryHelper.isIgnoringBatteryOptimizations(context)

    fun requestBatteryOptimizationExemption(): Boolean =
        ColorOsBatteryHelper.openBatteryExemptionSettings(context)

    fun openColorOsAutoLaunchSettings(): Boolean =
        ColorOsBatteryHelper.openAutoLaunchSettings(context)

    /**
     * Parses a pasted `ec0pair://…` or `ec0://pair?…` URI, pins the desktop peer as
     * `TRUSTED_QR`, and walks its candidate endpoints on a background coroutine.
     */
    fun pairWithUri(
        rawUri: String,
        onStep: (PairingStepState) -> Unit = {},
    ): Job = serviceHost.pairWithUriAsync(rawUri, onStep = onStep)

    fun resetPairingState() {
        serviceHost.resetPairingState()
    }

    override fun isDarkTheme(): Boolean = _settings.value.darkTheme ?: false

    private val cancelled = mutableSetOf<String>()

    override fun acceptIncoming(trust: Boolean) {
        Ec0CompanionService.acceptIncomingTransfer(trust) { trustedFingerprint ->
            updateSettings { s ->
                s.copy(trustedFingerprints = s.trustedFingerprints + trustedFingerprint)
            }
        }
    }

    override fun declineIncoming() {
        Ec0CompanionService.declineIncomingTransfer()
    }

    /** Combined queue: outgoing sends + service-hosted incoming Ktor receiver transfers. */
    override val transfers: StateFlow<List<TransferItem>> =
        combine(_outgoingTransfers, serviceHost.receiverTransfers) { outgoing, incoming ->
            outgoing + incoming
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val incomingRequest: StateFlow<IncomingRequest?>
        get() = Ec0CompanionService.incomingRequest

    init {
        Ec0CompanionService.updateCachedSettings(_settings.value)
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { settingsStore.load(defaultSettings) }.getOrDefault(defaultSettings)
            _settings.value = loaded
            Ec0CompanionService.updateCachedSettings(loaded)
        }
        // Notification-listener grant can flip while the app sits in the system
        // settings screen — poll gently so the SyncPanel switch never lies. IO
        // dispatcher: the check is a Settings.Secure binder read. The CAMERA
        // grant rides the same loop (the Settings row's "Open settings" routing
        // and user revokes/re-grants flip it outside the app too); rationale is
        // only observable from the Activity, so it stays with onResume/results.
        refreshNotificationRelayState()
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(2_000)
                refreshNotificationRelayState()
                val granted = hasCameraPermission()
                if (granted != cameraGrantInputs.value.granted) {
                    cameraGrantInputs.value = cameraGrantInputs.value.copy(granted = granted)
                }
            }
        }
        // Seed the persisted camera-denial flag (upgrade-only: a fresh process
        // starts at false, and nothing here may downgrade a denial set this
        // session). Rationale itself is re-observed in MainActivity.onResume.
        scope.launch(Dispatchers.IO) {
            if (runCatching { settingsStore.cameraDeniedOnce() }.getOrDefault(false)) {
                cameraGrantInputs.value = cameraGrantInputs.value.copy(deniedAtLeastOnce = true)
            }
        }
    }

    override fun startScan() {
        serviceHost.startScan()
    }

    override fun cancelTransfer(id: String) {
        synchronized(cancelled) { cancelled.add(id) }
        updateItem(id) { it.copy(state = TransferState.CANCELLED) }
    }

    override fun updateSettings(transform: (SettingsState) -> SettingsState) {
        var updatedSnapshot = _settings.value
        _settings.update { current ->
            val next = transform(current)
            updatedSnapshot = next
            next
        }
        Ec0CompanionService.updateCachedSettings(updatedSnapshot)
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.save(updatedSnapshot) }
        }
    }

    override fun sendFile(fileRef: Any, device: UiDevice) {
        val uri = fileRef as? Uri ?: return
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: "file-${System.currentTimeMillis()}"

            val item = TransferItem(
                id = "out-${System.nanoTime()}",
                fileName = displayName,
                direction = TransferDirection.OUTGOING,
                peerAlias = device.alias,
                sizeBytes = 0,
                state = TransferState.RUNNING,
            )
            _outgoingTransfers.update { it + item }
            try {
                val staged = File(context.cacheDir, "ec0-out-${System.nanoTime()}-$displayName")
                val size = resolver.openInputStream(uri)!!.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                    staged.length()
                }
                if (size == 0L) throw IllegalStateException("Empty file or unreadable URI")

                val s = _settings.value
                val response = http.prepareUpload(
                    peer = device,
                    info = RegisterDtoV2(
                        alias = s.alias,
                        version = LocalsendV2.PROTOCOL_VERSION,
                        deviceModel = s.deviceModel,
                        deviceType = s.deviceType,
                        fingerprint = OWN_FINGERPRINT,
                        port = s.port,
                        protocol = ProtocolType.HTTP,
                        download = false,
                    ),
                    files = mapOf(
                        item.id to FileDto(
                            id = item.id,
                            fileName = displayName,
                            size = size,
                            fileType = resolver.getType(uri) ?: "application/octet-stream",
                            sha256 = null,
                        ),
                    ),
                    pin = s.pin.takeIf { it.isNotEmpty() },
                )
                val token = response.files[item.id] ?: throw IllegalStateException("Recipient accepted no files (204)")
                staged.inputStream().use { input ->
                    http.uploadFile(
                        peer = device,
                        sessionId = response.sessionId,
                        fileId = item.id,
                        token = token,
                        fileName = displayName,
                        bytes = input,
                        size = size,
                        cancelSignal = { synchronized(cancelled) { item.id in cancelled } },
                    ) { pct -> updateItem(item.id) { it.copy(progressPct = pct) } }
                }
                updateItem(item.id) { it.copy(progressPct = 100, state = TransferState.DONE) }
                staged.delete()
            } catch (t: Throwable) {
                updateItem(item.id) {
                    if (it.state == TransferState.CANCELLED) it
                    else it.copy(state = TransferState.FAILED, error = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun updateItem(id: String, transform: (TransferItem) -> TransferItem) {
        _outgoingTransfers.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * Releases Activity-scoped state while keeping [Ec0CompanionService] (and its
     * engine server, Ktor receiver, and discovery ticker) alive in the foreground service.
     */
    fun release() {
        // Service lifecycle owns CompanionServiceHost; Activity destruction must not kill it.
    }

    companion object {
        val OWN_FINGERPRINT: String get() = Ec0CompanionService.OWN_FINGERPRINT
    }
}

class MainActivity : ComponentActivity() {
    private var appState: AndroidAppState? = null
    private var scope: CoroutineScope? = null
    private var pendingPick: ((Any?) -> Unit)? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pendingPick?.invoke(uri)
        pendingPick = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    /**
     * Phone-camera runtime grant — the normal one-shot contract, launched ONLY
     * from the Settings row's Enable action (never in onResume; a declined grant
     * stays calm: the row just says "Declined" / "Blocked" and offers the honest
     * next step).
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appState?.onCameraPermissionResult(
            granted = granted,
            shouldShowRationale = runCatching {
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            }.getOrDefault(false),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPostNotificationsIfNeeded()

        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activityScope
        val settingsStore = AndroidSettingsStore(applicationContext.ec0SettingsDataStore)
        val ec0StoreDir = File(applicationContext.filesDir, "ec0")
        val state = AndroidAppState(
            context = applicationContext,
            scope = activityScope,
            settingsStore = settingsStore,
            ec0StoreDir = ec0StoreDir,
        )
        appState = state
        state.cameraRequestHandler = {
            runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
        }
        setContent {
            val settings by state.settings.collectAsState()
            val trustedPeerCount by state.trustedPeerCount.collectAsState()
            val systemDark = isSystemInDarkTheme()
            var onboardingCompleted by rememberSaveable { mutableStateOf(false) }
            val showRemoteInput by state.showRemoteInput.collectAsState()
            val showDeviceManagement by state.showDeviceManagement.collectAsState()

            val showPhoneOnboarding = trustedPeerCount == 0 && !onboardingCompleted

            if (showRemoteInput) {
                Ec0Theme(dark = settings.darkTheme ?: systemDark, seed = Ec0Seed.fromId(settings.themeSeed)) {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { state.showRemoteInput.value = false },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        ) { Text("Close remote input") }
                        RemoteInputScreen(
                            node = state.companionRuntime.node,
                            connectedPeerId = state.connectedDesktopDeviceId.collectAsState().value,
                            peerCapabilities = state.desktopCapabilities.collectAsState().value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                return@setContent
            }
            if (showDeviceManagement) {
                Ec0Theme(dark = settings.darkTheme ?: systemDark, seed = Ec0Seed.fromId(settings.themeSeed)) {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { state.showDeviceManagement.value = false },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        ) { Text("Close") }
                        PairedDevicesScreen(
                            peers = state.peerManagement?.peers?.collectAsState()?.value ?: emptyList(),
                            onUnpair = { state.peerManagement?.unpair(it) },
                            onSetCapability = { deviceId, wireName, granted ->
                                state.peerManagement?.setCapability(deviceId, wireName, granted)
                            },
                            onClose = { state.showDeviceManagement.value = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                return@setContent
            }

            if (showPhoneOnboarding) {
                Ec0Theme(
                    dark = settings.darkTheme ?: systemDark,
                    seed = Ec0Seed.fromId(settings.themeSeed),
                ) {
                    AndroidOnboardingScreen(
                        state = state,
                        onContinueToApp = { onboardingCompleted = true },
                    )
                }
            } else {
                Ec0App(
                    bridge = state,
                    pickFile = { onPicked ->
                        pendingPick = onPicked
                        pickFileLauncher.launch(arrayOf("*/*"))
                    },
                    compact = true,
                )
            }
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = runCatching {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(true)
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Android 10+ grants clipboard read to the focused app's UID — phone→PC
        // clipboard pushes (and the focus-gain re-check) only run while resumed.
        Ec0CompanionService.onMainActivityFocusChanged(focused = true)
        // Grants (camera, notification-listener) can flip while the app sits in
        // system settings — re-check on every resume so the UI never lies.
        appState?.refreshCameraGrant(
            shouldShowRationale = runCatching {
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            }.getOrDefault(false),
        )
        // NOTE: the clipboard READ itself waits for WINDOW focus — onResume fires
        // before the window is focusable, and ClipboardService denies the read
        // ("application is not in focus") if it happens here. See onWindowFocusChanged.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readClipboardFromActivityAndPush()
    }

    /**
     * ColorOS reality (A5x): the foreground service's clipboard READ is denied even
     * while ec0 is focused — only the focused Activity's own read succeeds (the
     * PC→phone SET direction works fine from the service). So on focus, read here
     * with the Activity context and hand text to the watcher's shared policy tail.
     * The service-side listener stays registered for live change events.
     */
    private fun readClipboardFromActivityAndPush() {
        val clip = runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val text = runCatching {
            clip.getItemAt(0).coerceToText(this)?.toString()
        }.getOrNull().orEmpty()
        if (text.isNotBlank()) {
            Ec0CompanionService.pushClipboardFromActivity(text, sensitive)
        }
    }

    override fun onPause() {
        Ec0CompanionService.onMainActivityFocusChanged(focused = false)
        super.onPause()
    }

    override fun onDestroy() {
        scope?.cancel()
        appState?.release()
        appState = null
        super.onDestroy()
    }
}
