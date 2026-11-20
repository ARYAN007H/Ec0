/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 Android client:
 *   - Starts and binds to [Ec0CompanionService] (`connectedDevice|camera` foreground
 *     service hosting [AndroidCompanionRuntime], engine server + mDNS, VFS responder,
 *     encrypted file transfers, and direct LAN receiver).
 *   - When `trustedPeerCount == 0`, renders [AndroidOnboardingScreen]; once paired,
 *     enters the shared M3 Expressive [Ec0App].
 *   - Handles Android Share Sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) with strict
 *     `content://` URI validation so external apps can never trick ec0 into leaking
 *     private app-data files.
 */
package ec0.android

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConnectionState
import ec0.core.engine.EngineConstants
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.PairingStepState
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaPlaybackState
import ec0.core.engine.protocol.OtpCodeExtractor
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.ProximitySecurityProtocol
import ec0.core.engine.protocol.WorkstationLockCommand
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
import ec0.ui.models.ConnectedPeerSummary
import ec0.ui.models.IncomingRequest
import ec0.ui.models.LinkTelemetryUi
import ec0.ui.models.MediaSessionUi
import ec0.ui.models.NotificationItem
import ec0.ui.models.PairedPeerInfo
import ec0.ui.models.PeerLinkState
import ec0.ui.models.PeerManagementBridge
import ec0.ui.models.PhoneCameraBridge
import ec0.ui.models.PhoneCameraGrant
import ec0.ui.models.RemoteInputBridge
import ec0.ui.models.SettingsState
import ec0.ui.models.SetupCheckItem
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import ec0.ui.models.computeTileRegistry
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure defensive validator for incoming Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
 * share intents (unit-tested on JVM without android.jar):
 *   - Accepts ONLY `content://` URIs (blocks `file://` path exfiltration attacks!).
 *   - Rejects any URI whose authority belongs to ec0 itself (prevents self-provider loops).
 *   - Sanitizes display filenames to strip path separators, `..`, and control characters.
 */
object ShareIntentSanitizer {
    /** Hard upper bound on files accepted from a single external share intent to prevent cache-exhaustion DoS. */
    const val MAX_SHARE_BATCH_FILES: Int = 64

    fun isAllowedShareUri(uriString: String?, appPackageName: String = "ec0.android"): Boolean {
        val raw = uriString?.trim() ?: return false
        if (raw.length > 2048) return false
        if (raw.any { it.code < 32 || it.code == 127 || it.isWhitespace() }) return false
        if (!raw.startsWith("content://", ignoreCase = true)) return false
        val afterScheme = raw.substring("content://".length)
        val rawAuthority = afterScheme.substringBefore('/').lowercase()
        val hostAuthority = rawAuthority.substringAfterLast('@').substringBefore(':')
        if (hostAuthority.isBlank()) return false
        val pathPart = afterScheme.substringAfter('/', missingDelimiterValue = "")
        if (pathPart.split('/').any { it == ".." || it.equals("%2e%2e", ignoreCase = true) }) {
            return false
        }
        val ownPkg = appPackageName.trim().lowercase()
        if (ownPkg.isNotEmpty() && (hostAuthority == ownPkg || hostAuthority.startsWith("$ownPkg."))) {
            return false
        }
        return true
    }

    fun sanitizeShareFileName(raw: String?, fallback: String = "shared-file"): String {
        val cleaned = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "_")
            .filter { ch -> ch >= ' ' && ch != '\u007f' && ch != '/' && ch != '\\' && ch != '\u0000' }
            .trim()
            .take(160)
        return cleaned.ifBlank { fallback }
    }
}

/**
 * Pure peerLink mapping: "Wi-Fi TLS" while an engine session is CONNECTED
 * (or the pairing pipeline has committed), "searching" otherwise.
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
 * The phone's honest support set for the dynamic tile registry.
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
 * Pure tile-registry mapping: the phone's dashboard tiles = the DESKTOP's beacon
 * capabilities ∩ [phoneLocalSupport].
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

    private val _statusBanner = MutableStateFlow<String?>(null)
    override val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    override val scanning: StateFlow<Boolean>
        get() = serviceHost.scanning

    override val canRingPhone: Boolean get() = false
    override val canLockPc: Boolean get() = true
    override val hasUsbTunnelControl: Boolean get() = false
    override val hasDesktopIntegration: Boolean get() = false
    override val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val _notificationRelayGranted = MutableStateFlow(false)

    private val sessionLive: StateFlow<Boolean> =
        serviceHost.runtime.node.connectionStates
            .map { states -> states.values.any { it == EngineConnectionState.CONNECTED } }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val capabilities: Capabilities
        get() = Capabilities(
            canReceive = true,
            clipboardSync = clipboardSyncEnabled.value && sessionLive.value,
            notifications = _notificationRelayGranted.value,
            photoSync = false,
            remoteInput = sessionLive.value,
        )

    override val clipboardSyncEnabled: StateFlow<Boolean> =
        settingsStore.clipboardSyncEnabledFlow().stateIn(scope, SharingStarted.Eagerly, true)

    override fun setClipboardSyncEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.setClipboardSyncEnabled(enabled) }
        }
        Ec0TileState.setClipboardSyncEnabled(enabled)
    }

    override val autoSyncClipboard: StateFlow<Boolean> get() = clipboardSyncEnabled
    override fun setAutoSyncClipboard(enabled: Boolean) = setClipboardSyncEnabled(enabled)

    override fun pushClipboardNow() {
        val clip = runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val text = runCatching { clip.getItemAt(0).coerceToText(context)?.toString() }.getOrNull().orEmpty()
        if (text.isNotBlank()) {
            Ec0CompanionService.pushClipboardFromActivity(text, sensitive)
            _statusBanner.value = "Pushed clipboard to paired PC"
        }
    }

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

    override val peerLink: StateFlow<PeerLinkState?> = combine(
        serviceHost.pairingState,
        serviceHost.pinnedPeers,
        serviceHost.runtime.node.connectionStates,
    ) { pairing, pinned, states ->
        mapPeerLinkState(pairing, pinned.isNotEmpty(), states)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val connectedPeers: StateFlow<List<ConnectedPeerSummary>> = combine(
        serviceHost.pinnedPeers,
        serviceHost.runtime.node.connectionStates,
        serviceHost.runtime.node.peerPresence,
    ) { pinned, states, presence ->
        pinned.map { peer ->
            val id = peer.deviceId.lowercase()
            val connected = states[id] == EngineConnectionState.CONNECTED ||
                states[peer.deviceId] == EngineConnectionState.CONNECTED
            val beacon = presence[id] ?: presence[peer.deviceId]
            val fp = ec0.core.kdeconnect.Sha256.digest(peer.publicKeyRaw)
                .joinToString("") { "%02x".format(it) }.take(16)
            ConnectedPeerSummary(
                deviceId = peer.deviceId,
                alias = peer.alias,
                transport = if (connected) "Wi-Fi Encrypted" else "Offline",
                connected = connected,
                batteryPct = beacon?.batteryPct,
                isCharging = beacon?.isCharging == true,
                screenOn = beacon?.screenOn ?: true,
                fingerprintPrefix = fp,
                usbActive = false,
            )
        }.sortedByDescending { it.connected }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val mediaSession: StateFlow<MediaSessionUi?> =
        serviceHost.runtime.node.remoteMediaSession.map { info ->
            info?.let {
                MediaSessionUi(
                    playerName = it.playerName,
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    playing = it.state == MediaPlaybackState.PLAYING,
                    volumePct = it.volumePct,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override val relayedNotifications: StateFlow<List<NotificationItem>> =
        serviceHost.runtime.node.notifications.map { list ->
            list.map {
                NotificationItem(
                    id = it.notifId,
                    appName = it.appName,
                    title = it.title,
                    body = it.body,
                    canReply = it.canReply,
                    otpCode = OtpCodeExtractor.extractOtpCode(it.title, it.body),
                    timestampMs = it.timestampMs,
                    appIconB64 = it.appIconB64,
                    actions = it.actions,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _linkTelemetry = MutableStateFlow(LinkTelemetryUi())
    override val linkTelemetry: StateFlow<LinkTelemetryUi> = _linkTelemetry.asStateFlow()

    private val _setupChecks = MutableStateFlow<List<SetupCheckItem>>(emptyList())
    override val setupChecks: StateFlow<List<SetupCheckItem>> = _setupChecks.asStateFlow()

    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        }
        return runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun buildAndroidSetupChecks(): List<SetupCheckItem> {
        val cameraOk = hasCameraPermission()
        val notifOk = isNotificationListenerAccessGranted()
        val batteryOk = isBatteryOptimizationIgnored()
        val storageOk = hasStorageAccess()
        val accessibilityOk = Ec0UniversalControlAccessibilityService.isAccessibilityServiceEnabled(context)
        return listOf(
            SetupCheckItem(
                id = "camera",
                title = "Continuity Camera",
                subtitle = if (cameraOk) "Camera permission granted (1080p/4K ready)" else "Grant camera permission to stream as PC webcam",
                ready = cameraOk,
                actionLabel = if (cameraOk) null else "Grant",
            ),
            SetupCheckItem(
                id = "notifications",
                title = "Notification Relay",
                subtitle = if (notifOk) "Live notification sync & inline reply enabled" else "Enable Notification Access to mirror phone alerts to PC",
                ready = notifOk,
                actionLabel = if (notifOk) null else "Enable",
            ),
            SetupCheckItem(
                id = "battery",
                title = "Background Resilience",
                subtitle = if (batteryOk) "Exempt from battery optimization / Doze kills" else "Allow unrestricted background running for instant wake",
                ready = batteryOk,
                actionLabel = if (batteryOk) null else "Exempt",
            ),
            SetupCheckItem(
                id = "storage",
                title = "Phone Files (VFS)",
                subtitle = if (storageOk) "Photos & Downloads accessible from paired PC" else "Grant All Files Access so your PC can browse phone folders",
                ready = storageOk,
                actionLabel = if (storageOk) null else "Allow",
            ),
            SetupCheckItem(
                id = "accessibility",
                title = "Universal Control Cursor",
                subtitle = if (accessibilityOk) "Gesture receiver active for PC mouse handoff" else "Optional: enable gesture-only service to slide PC mouse onto phone",
                ready = accessibilityOk,
                actionLabel = if (accessibilityOk) null else "Configure",
            ),
        )
    }

    override fun runSetupCheckAction(checkId: String) {
        when (checkId) {
            "camera" -> phoneCamera.requestGrant()
            "notifications" -> requestNotificationListenerAccess()
            "battery" -> requestBatteryOptimizationExemption()
            "storage" -> openAllFilesAccessSettings()
            "accessibility" -> Ec0UniversalControlAccessibilityService.openAccessibilitySettings(context)
        }
    }

    private fun openAllFilesAccessSettings() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                openAppPermissionSettings()
            }
        }.onFailure { openAppPermissionSettings() }
    }

    override val remoteHandoffUrl: StateFlow<String?> =
        serviceHost.runtime.node.remoteHandoffActivity.map { it?.url }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override fun sendMediaControl(action: String, volumePct: Int?) {
        scope.launch(Dispatchers.IO) {
            val session = activeDesktopSessionOrNull() ?: return@launch
            runCatching {
                serviceHost.runtime.node.mediaSessionBridgeService.sendMediaControl(
                    MediaControlCommand(action = action, volumePct = volumePct),
                    session,
                )
            }
        }
    }

    override fun lockRemoteWorkstation() {
        scope.launch(Dispatchers.IO) {
            val session = activeDesktopSessionOrNull()
            if (session == null) {
                _statusBanner.value = "Connect to your paired PC to lock it"
                return@launch
            }
            val ok = runCatching {
                session.sendCmd(
                    cmdType = ProximitySecurityProtocol.CMD_WORKSTATION_LOCK,
                    cmdBody = WorkstationLockCommand(
                        action = WorkstationLockCommand.ACTION_LOCK,
                        reason = "phone_dashboard",
                    ).toJsonObject(),
                    category = ProximitySecurityProtocol.CATEGORY_SECURITY,
                    timestampMs = System.currentTimeMillis(),
                )
                true
            }.getOrDefault(false)
            _statusBanner.value = if (ok) "Locked paired workstation" else "Failed to send lock command"
        }
    }

    override fun openRemoteHandoff() {
        val url = remoteHandoffUrl.value?.trim() ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun sendHandoffUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val session = activeDesktopSessionOrNull()
            if (session == null) {
                _statusBanner.value = "Connect to your paired PC to hand off links"
                return@launch
            }
            try {
                serviceHost.runtime.node.handoffAndProximityService.sendHandoffOpen(
                    url = trimmed,
                    title = "Phone Link",
                    session = session,
                )
                _statusBanner.value = "Opened link on paired PC"
            } catch (e: Exception) {
                _statusBanner.value = "Rejected unsafe link: ${e.message}"
            }
        }
    }

    override fun copyOtpCode(code: String) {
        if (code.isBlank()) return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = android.content.ClipData.newPlainText("ec0-otp", code.trim())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            _statusBanner.value = "Copied 2FA code '$code'"
        }
    }

    override fun dismissNotification(id: String) {
        scope.launch(Dispatchers.IO) {
            val session = activeDesktopSessionOrNull() ?: return@launch
            runCatching {
                serviceHost.runtime.node.notificationRelayService.dismissNotification(id, session)
            }
        }
    }

    val showRemoteInput = MutableStateFlow(false)
    val showDeviceManagement = MutableStateFlow(false)

    val connectedDesktopDeviceId: StateFlow<String?> =
        combine(serviceHost.runtime.node.connectionStates, serviceHost.runtime.pinnedPeers) { states, pinned ->
            val live = states.entries.firstOrNull { it.value == EngineConnectionState.CONNECTED }?.key
            live ?: pinned.firstOrNull()?.deviceId.takeIf { states.isNotEmpty() }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val desktopCapabilities: StateFlow<Set<EngineCapability>> =
        combine(connectedDesktopDeviceId, serviceHost.runtime.node.peerPresence) { id, presence ->
            id?.let { presence[it]?.capabilities } ?: emptySet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val tiles: StateFlow<List<CapabilityTile>> = combine(
        serviceHost.runtime.node.peerPresence,
        serviceHost.runtime.node.connectionStates,
    ) { presence, states ->
        mapPhoneTileRegistry(presence, states)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val grantsFlow = settingsStore.deviceCapabilityGrantsFlow()

    /** Paired-device management with correct 2-way unpair ordering. */
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
                    fingerprintPrefix = ec0.core.kdeconnect.Sha256.digest(peer.publicKeyRaw)
                        .joinToString("") { "%02x".format(it) }.take(16),
                    pinnedAtMs = peer.pinnedAtMs,
                    trustLevel = peer.trustLevel.name,
                    connected = states[peer.deviceId] == EngineConnectionState.CONNECTED ||
                        states[peer.deviceId.lowercase()] == EngineConnectionState.CONNECTED,
                    grantedCapabilities = grants[peer.deviceId] ?: setOf("remote_input"),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        override fun unpair(deviceId: String) {
            // CRITICAL ORDERING: send wire CMD_UNPAIR FIRST while the authenticated
            // session is still open so the desktop immediately revokes our pin too,
            // THEN drop the local pin & close the session.
            val normalized = deviceId.lowercase()
            val liveSession = serviceHost.runtime.node.activeSessionFor(normalized)
                ?: serviceHost.runtime.node.activeSessionFor(deviceId)
            liveSession?.let { session ->
                runCatching {
                    session.sendCmd(
                        cmdType = EngineConstants.CMD_UNPAIR,
                        cmdBody = kotlinx.serialization.json.buildJsonObject {
                            put("reason", kotlinx.serialization.json.JsonPrimitive("user_unpair"))
                            put(
                                "device_id",
                                kotlinx.serialization.json.JsonPrimitive(
                                    serviceHost.runtime.node.localIdentity.deviceId,
                                ),
                            )
                        },
                        category = EngineConstants.CATEGORY_SYSTEM,
                        timestampMs = System.currentTimeMillis(),
                    )
                }
            }
            serviceHost.runtime.node.unpairPeer(normalized)
            serviceHost.resetPairingState()
        }

        override fun setCapability(deviceId: String, wireName: String, granted: Boolean) {
            scope.launch(Dispatchers.IO) {
                runCatching { settingsStore.setDeviceCapability(deviceId, wireName, granted) }
            }
        }
    }

    private inner class RemoteInputBridgeImpl : RemoteInputBridge {
        override val available: StateFlow<Boolean> = sessionLive

        override fun openTouchpad() {
            showRemoteInput.value = true
        }
    }

    override val peerManagement: PeerManagementBridge = PeerManagementBridgeImpl()
    override val remoteInput: RemoteInputBridge = RemoteInputBridgeImpl()

    private val cameraGrantInputs = MutableStateFlow(
        CameraGrantInputs(granted = hasCameraPermission(), shouldShowRationale = false, deniedAtLeastOnce = false),
    )

    var cameraRequestHandler: (() -> Unit)? = null

    private fun hasCameraPermission(): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun openAppPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            val current = grant.value
            when {
                current.granted -> Unit
                current.needsSystemSettings -> openAppPermissionSettings()
                else -> cameraRequestHandler?.invoke()
            }
        }
    }

    override val phoneCamera: PhoneCameraBridge = PhoneCameraBridgeImpl()

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

    /**
     * Unified transfer queue: merges direct LAN outgoing + incoming transfers with
     * encrypted ec0-engine transfers (`encrypted = true`).
     */
    override val transfers: StateFlow<List<TransferItem>> = combine(
        _outgoingTransfers,
        serviceHost.receiverTransfers,
        serviceHost.runtime.node.transfers,
        serviceHost.pinnedPeers,
    ) { outgoing, incoming, engineMap, pinned ->
        val defaultPeerAlias = pinned.firstOrNull()?.alias ?: "Paired PC"
        val engineItems = engineMap.values.map { snap ->
            val pct = if (snap.totalBytes > 0L) {
                ((snap.transferredBytes * 100L) / snap.totalBytes).toInt().coerceIn(0, 100)
            } else if (snap.state == FileTransferState.COMPLETED) {
                100
            } else {
                0
            }
            TransferItem(
                id = "ec0:${snap.transferId}",
                fileName = snap.displayName.ifBlank {
                    if (snap.totalFiles > 1) "${snap.totalFiles} files" else snap.transferId.take(8)
                },
                direction = if (snap.isOutgoing) TransferDirection.OUTGOING else TransferDirection.INCOMING,
                peerAlias = defaultPeerAlias,
                sizeBytes = snap.totalBytes,
                progressPct = pct,
                state = when (snap.state) {
                    FileTransferState.OFFERED -> TransferState.PENDING
                    FileTransferState.TRANSFERRING -> TransferState.RUNNING
                    FileTransferState.COMPLETED -> TransferState.DONE
                    FileTransferState.CANCELLED -> TransferState.CANCELLED
                    FileTransferState.FAILED -> TransferState.FAILED
                },
                error = snap.failureReason,
                encrypted = true,
                localPath = snap.savedLocalPath,
            )
        }
        outgoing + incoming + engineItems
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
        refreshNotificationRelayState()
        _setupChecks.value = buildAndroidSetupChecks()
        scope.launch(Dispatchers.IO) {
            val rttHistory = ArrayDeque<Int>(24)
            while (true) {
                delay(2_000)
                refreshNotificationRelayState()
                val granted = hasCameraPermission()
                if (granted != cameraGrantInputs.value.granted) {
                    cameraGrantInputs.value = cameraGrantInputs.value.copy(granted = granted)
                }
                _setupChecks.value = buildAndroidSetupChecks()
                val session = activeDesktopSessionOrNull()
                if (session != null) {
                    val sampleMs = session.lastMeasuredRttMs.toInt().coerceIn(1, 999)
                    if (rttHistory.size >= 24) rttHistory.removeFirst()
                    rttHistory.addLast(sampleMs)
                    _linkTelemetry.value = LinkTelemetryUi(
                        transportLabel = "Wi-Fi TLS",
                        rttSamplesMs = rttHistory.toList(),
                        autoFailoverActive = false,
                    )
                } else {
                    _linkTelemetry.value = LinkTelemetryUi(
                        transportLabel = "Offline",
                        rttSamplesMs = rttHistory.toList(),
                        autoFailoverActive = false,
                    )
                }
            }
        }
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
        if (id.startsWith("ec0:")) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    serviceHost.runtime.node.fileTransferService.cancelTransfer(id.removePrefix("ec0:"))
                }
            }
            return
        }
        synchronized(cancelled) { cancelled.add(id) }
        updateItem(id) { it.copy(state = TransferState.CANCELLED) }
    }

    override fun clearFinishedTransfers() {
        val finished = setOf(TransferState.DONE, TransferState.FAILED, TransferState.CANCELLED)
        _outgoingTransfers.update { list -> list.filterNot { it.state in finished } }
        serviceHost.runtime.node.fileTransferService.clearFinishedTransfers()
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
        val uris = extractUris(fileRef)
        if (uris.isEmpty()) return

        val pairedId = resolveLivePairedDeviceId(device)
        if (pairedId != null) {
            sendUrisOverEncryptedEngine(uris, pairedId)
            return
        }
        for (uri in uris) {
            sendSingleUriOverLan(uri, device)
        }
    }

    override fun sendFileToPairedPeer(fileRef: Any, deviceId: String) {
        val uris = extractUris(fileRef)
        if (uris.isEmpty()) return
        sendUrisOverEncryptedEngine(uris, deviceId)
    }

    /** Handles validated incoming Android Share Sheet payloads (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`). */
    fun handleSharedIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val pkg = runCatching { context.packageName }.getOrDefault("ec0.android")
        val desktopId = connectedDesktopDeviceId.value

        val rawUris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.filterNotNull().orEmpty()
            }
            else -> emptyList()
        }

        if (rawUris.isNotEmpty()) {
            val safeUris = rawUris
                .filter { ShareIntentSanitizer.isAllowedShareUri(it.toString(), pkg) }
                .take(ShareIntentSanitizer.MAX_SHARE_BATCH_FILES)
            if (safeUris.isEmpty()) {
                _statusBanner.value = "Blocked unsafe share URI (only content:// streams are accepted)"
                return
            }
            if (desktopId != null && serviceHost.runtime.node.activeSessionFor(desktopId) != null) {
                sendUrisOverEncryptedEngine(safeUris, desktopId)
            } else {
                _statusBanner.value = "Connect to your paired PC to send ${safeUris.size} shared file(s)"
            }
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!sharedText.isNullOrBlank()) {
            if (sharedText.startsWith("http://") || sharedText.startsWith("https://")) {
                sendHandoffUrl(sharedText)
            } else {
                Ec0CompanionService.pushClipboardFromActivity(sharedText, isSensitive = false)
                _statusBanner.value = "Synced shared text to paired PC clipboard"
            }
        }
    }

    private fun sendUrisOverEncryptedEngine(uris: List<Uri>, deviceId: String) {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val node = serviceHost.runtime.node
            if (node.activeSessionFor(deviceId) == null) {
                _statusBanner.value = "Paired PC is offline — reconnect to send files"
                return@launch
            }
            val terminalStates = setOf(
                FileTransferState.COMPLETED,
                FileTransferState.CANCELLED,
                FileTransferState.FAILED,
            )
            var started = 0
            for (uri in uris.take(ShareIntentSanitizer.MAX_SHARE_BATCH_FILES)) {
                if (!ShareIntentSanitizer.isAllowedShareUri(uri.toString(), context.packageName)) continue
                val displayName = resolveDisplayName(uri)
                val stageDir = File(context.cacheDir, "ec0-stage-${System.nanoTime()}").apply { mkdirs() }
                val staged = File(stageDir, displayName)
                var transferId: String? = null
                try {
                    val input = resolver.openInputStream(uri) ?: continue
                    input.use { src -> staged.outputStream().use { dst -> src.copyTo(dst) } }
                    if (staged.length() <= 0L) continue
                    val offer = node.sendFileOrDirectory(deviceId, staged)
                    transferId = offer.transferId
                    started++
                } catch (e: Throwable) {
                    _statusBanner.value = "Send failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    val activeId = transferId
                    if (activeId == null) {
                        runCatching { stageDir.deleteRecursively() }
                    } else {
                        scope.launch(Dispatchers.IO) {
                            // Keep the staged file intact for the entire transfer (even multi-GB
                            // transfers), then immediately reap once terminal or on 30m cap.
                            withTimeoutOrNull(30 * 60_000L) {
                                node.transfers.first { map ->
                                    val snap = map[activeId]
                                    snap == null || snap.state in terminalStates
                                }
                            }
                            runCatching { stageDir.deleteRecursively() }
                        }
                    }
                }
            }
            if (started > 0) {
                _statusBanner.value = "Sending $started file(s) over encrypted ec0 link"
            }
        }
    }

    private fun sendSingleUriOverLan(uri: Uri, device: UiDevice) {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = resolveDisplayName(uri)

            val item = TransferItem(
                id = "out-${System.nanoTime()}",
                fileName = displayName,
                direction = TransferDirection.OUTGOING,
                peerAlias = device.alias,
                sizeBytes = 0,
                state = TransferState.RUNNING,
            )
            _outgoingTransfers.update { it + item }
            val staged = File(context.cacheDir, "ec0-out-${System.nanoTime()}-$displayName")
            try {
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
                val token = response.files[item.id]
                    ?: throw IllegalStateException("Recipient accepted no files (204)")
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
            } catch (t: Throwable) {
                updateItem(item.id) {
                    if (it.state == TransferState.CANCELLED) it
                    else it.copy(state = TransferState.FAILED, error = t.message ?: t.javaClass.simpleName)
                }
            } finally {
                // Always clean up staged cache file on BOTH success and failure.
                runCatching { staged.delete() }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()
        return ShareIntentSanitizer.sanitizeShareFileName(raw, fallback = "file-${System.currentTimeMillis()}")
    }

    private fun extractUris(fileRef: Any): List<Uri> = when (fileRef) {
        is Uri -> listOf(fileRef)
        is List<*> -> fileRef.filterIsInstance<Uri>()
        else -> emptyList()
    }

    private fun activeDesktopSessionOrNull() =
        connectedDesktopDeviceId.value?.let { id ->
            serviceHost.runtime.node.activeSessionFor(id)
                ?: serviceHost.runtime.node.activeSessionFor(id.lowercase())
        } ?: serviceHost.pinnedPeers.value.firstNotNullOfOrNull { p ->
            serviceHost.runtime.node.activeSessionFor(p.deviceId)
        }

    private fun resolveLivePairedDeviceId(device: UiDevice): String? {
        val node = serviceHost.runtime.node
        val pinned = serviceHost.pinnedPeers.value
        val byId = pinned.firstOrNull {
            it.deviceId.equals(device.id, ignoreCase = true) && node.activeSessionFor(it.deviceId) != null
        }
        if (byId != null) return byId.deviceId

        val byIp = device.hostAddress.takeIf { it.isNotBlank() }?.let { host ->
            pinned.firstOrNull { peer ->
                val session = node.activeSessionFor(peer.deviceId)
                session != null && session.peerRemoteIp == host
            }
        }
        if (byIp != null) return byIp.deviceId

        val byAlias = pinned.firstOrNull {
            it.alias.equals(device.alias, ignoreCase = true) && node.activeSessionFor(it.deviceId) != null
        }
        return byAlias?.deviceId
    }

    private fun updateItem(id: String, transform: (TransferItem) -> TransferItem) {
        _outgoingTransfers.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

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
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val payload: Any? = when (uris.size) {
            0 -> null
            1 -> uris.first()
            else -> uris
        }
        pendingPick?.invoke(payload)
        pendingPick = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

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
        state.handleSharedIntent(intent)

        setContent {
            val settings by state.settings.collectAsState()
            val trustedPeerCount by state.trustedPeerCount.collectAsState()
            val systemDark = isSystemInDarkTheme()
            var onboardingCompleted by rememberSaveable { mutableStateOf(false) }
            val showRemoteInput by state.showRemoteInput.collectAsState()
            val showDeviceManagement by state.showDeviceManagement.collectAsState()
            val mediaSession by state.mediaSession.collectAsState()

            val showPhoneOnboarding = trustedPeerCount == 0 && !onboardingCompleted
            val effectiveDark = settings.darkTheme ?: systemDark
            val dynamicScheme = remember(effectiveDark, settings.dynamicColor) {
                if (settings.dynamicColor) {
                    buildAndroidDynamicColorScheme(this@MainActivity, effectiveDark)
                } else {
                    null
                }
            }

            if (showRemoteInput) {
                Ec0Theme(
                    dark = effectiveDark,
                    seed = Ec0Seed.fromId(settings.themeSeed),
                    dynamicColorScheme = dynamicScheme,
                ) {
                    RemoteInputScreen(
                        node = state.companionRuntime.node,
                        connectedPeerId = state.connectedDesktopDeviceId.collectAsState().value,
                        peerCapabilities = state.desktopCapabilities.collectAsState().value,
                        onClose = { state.showRemoteInput.value = false },
                        mediaSession = mediaSession,
                        onMediaControl = { action, vol -> state.sendMediaControl(action, vol) },
                    )
                }
                return@setContent
            }
            if (showDeviceManagement) {
                Ec0Theme(
                    dark = effectiveDark,
                    seed = Ec0Seed.fromId(settings.themeSeed),
                    dynamicColorScheme = dynamicScheme,
                ) {
                    PairedDevicesScreen(
                        peers = state.peerManagement.peers.collectAsState().value,
                        onUnpair = { state.peerManagement.unpair(it) },
                        onSetCapability = { deviceId, wireName, granted ->
                            state.peerManagement.setCapability(deviceId, wireName, granted)
                        },
                        onClose = { state.showDeviceManagement.value = false },
                    )
                }
                return@setContent
            }

            if (showPhoneOnboarding) {
                Ec0Theme(
                    dark = effectiveDark,
                    seed = Ec0Seed.fromId(settings.themeSeed),
                    dynamicColorScheme = dynamicScheme,
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
                    dynamicColorScheme = dynamicScheme,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appState?.handleSharedIntent(intent)
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
        Ec0CompanionService.onMainActivityFocusChanged(focused = true)
        appState?.refreshCameraGrant(
            shouldShowRationale = runCatching {
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            }.getOrDefault(false),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readClipboardFromActivityAndPush()
    }

    private fun readClipboardFromActivityAndPush() {
        val clip = runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val description = clip.description
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && appState?.settings?.value?.autoRelayScreenshots != false) {
            val mime = (0 until description.mimeTypeCount)
                .map { description.getMimeType(it) }
                .firstNotNullOfOrNull { ClipboardImagePolicy.normalizeSupportedImageMime(it) }
                ?: runCatching { contentResolver.getType(uri) }
                    .getOrNull()
                    ?.let { ClipboardImagePolicy.normalizeSupportedImageMime(it) }
            if (mime != null) {
                Ec0CompanionService.pushClipboardImageFromActivity(uri, mime, sensitive)
                return
            }
        }
        val text = runCatching {
            item.coerceToText(this)?.toString()
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

/**
 * Builds an authentic Android 12+ (API 31+) Material You dynamic [ColorScheme]
 * directly from Android's `android.R.color.system_accent*` / `system_neutral*`
 * wallpaper tonal palettes (zero extra AndroidX BOM dependency conflicts).
 */
private fun buildAndroidDynamicColorScheme(context: Context, dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        fun c(resId: Int) = Color(context.getColor(resId))
        if (dark) {
            darkColorScheme(
                primary = c(android.R.color.system_accent1_200),
                onPrimary = c(android.R.color.system_accent1_800),
                primaryContainer = c(android.R.color.system_accent1_700),
                onPrimaryContainer = c(android.R.color.system_accent1_100),
                secondary = c(android.R.color.system_accent2_200),
                onSecondary = c(android.R.color.system_accent2_800),
                secondaryContainer = c(android.R.color.system_accent2_700),
                onSecondaryContainer = c(android.R.color.system_accent2_100),
                tertiary = c(android.R.color.system_accent3_200),
                onTertiary = c(android.R.color.system_accent3_800),
                tertiaryContainer = c(android.R.color.system_accent3_700),
                onTertiaryContainer = c(android.R.color.system_accent3_100),
                background = c(android.R.color.system_neutral1_900),
                onBackground = c(android.R.color.system_neutral1_100),
                surface = c(android.R.color.system_neutral1_900),
                onSurface = c(android.R.color.system_neutral1_100),
                surfaceVariant = c(android.R.color.system_neutral2_700),
                onSurfaceVariant = c(android.R.color.system_neutral2_200),
                surfaceContainerLowest = c(android.R.color.system_neutral1_1000),
                surfaceContainerLow = c(android.R.color.system_neutral1_900),
                surfaceContainer = c(android.R.color.system_neutral1_800),
                surfaceContainerHigh = c(android.R.color.system_neutral1_800),
                surfaceContainerHighest = c(android.R.color.system_neutral1_700),
                outline = c(android.R.color.system_neutral2_400),
                outlineVariant = c(android.R.color.system_neutral2_700),
            )
        } else {
            lightColorScheme(
                primary = c(android.R.color.system_accent1_600),
                onPrimary = c(android.R.color.system_accent1_0),
                primaryContainer = c(android.R.color.system_accent1_100),
                onPrimaryContainer = c(android.R.color.system_accent1_900),
                secondary = c(android.R.color.system_accent2_600),
                onSecondary = c(android.R.color.system_accent2_0),
                secondaryContainer = c(android.R.color.system_accent2_100),
                onSecondaryContainer = c(android.R.color.system_accent2_900),
                tertiary = c(android.R.color.system_accent3_600),
                onTertiary = c(android.R.color.system_accent3_0),
                tertiaryContainer = c(android.R.color.system_accent3_100),
                onTertiaryContainer = c(android.R.color.system_accent3_900),
                background = c(android.R.color.system_neutral1_10),
                onBackground = c(android.R.color.system_neutral1_900),
                surface = c(android.R.color.system_neutral1_10),
                onSurface = c(android.R.color.system_neutral1_900),
                surfaceVariant = c(android.R.color.system_neutral2_100),
                onSurfaceVariant = c(android.R.color.system_neutral2_700),
                surfaceContainerLowest = c(android.R.color.system_neutral1_0),
                surfaceContainerLow = c(android.R.color.system_neutral1_50),
                surfaceContainer = c(android.R.color.system_neutral1_100),
                surfaceContainerHigh = c(android.R.color.system_neutral1_100),
                surfaceContainerHighest = c(android.R.color.system_neutral1_200),
                outline = c(android.R.color.system_neutral2_500),
                outlineVariant = c(android.R.color.system_neutral2_200),
            )
        }
    }.getOrNull()
}
