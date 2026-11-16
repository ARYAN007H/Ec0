/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Android Companion Foreground Service (`Ec0CompanionService`):
 *   - Runs with `foregroundServiceType="connectedDevice"` and `START_STICKY`
 *     under notification channel `"ec0"` so ColorOS (`OplusQualityProtect` /
 *     `HansManager`) keeps the companion node alive across screen-off and
 *     Activity recreation (`docs/engine/coloros-resilience.md`, `docs/guides/coloros-setup.md`).
 *   - Hosts:
 *       1. [AndroidCompanionRuntime] backed by explicit `File(context.filesDir, "ec0")` stores.
 *       2. Engine server + `_ec0._tcp.local.` mDNS responder (`node.startServer(startMdns = true)`).
 *       3. Ktor CIO LocalSend receiver ([LocalsendReceiverKtor]) so incoming file transfers
 *          survive `MainActivity` destruction.
 *       4. Continuous `WifiManager.MulticastLock` + periodic UDP discovery ticker.
 *   - Exposes [CompanionServiceHost] as a pure-Kotlin, framework-decoupled coordinator
 *     so service lifecycle wiring and `ec0pair://` / `ec0://pair` URI pairing state
 *     transitions are 100% unit-testable on the JVM without Robolectric.
 */
package ec0.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import ec0.core.engine.EngineCapability
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.android.PairingStepState
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CamDroidControlCommand
import ec0.core.engine.protocol.CameraStatusInfo
import ec0.core.engine.protocol.ClipboardItem
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.InputKeyEvent
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.NotificationReplyCommand
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.client.LocalsendUdpDiscovery
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.server.AcceptPolicy
import ec0.core.localsend.server.LocalsendReceiverConfig
import ec0.core.localsend.server.LocalsendReceiverKtor
import ec0.core.localsend.server.ReceiverAcceptance
import ec0.ui.models.IncomingFileInfo
import ec0.ui.models.IncomingRequest
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferItem
import ec0.ui.models.UiDevice
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Abstraction over Android's `WifiManager.MulticastLock` so [CompanionServiceHost]
 * can be verified in pure JVM unit tests.
 */
interface MulticastLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Abstraction over the Ktor LocalSend receiver lifecycle for [CompanionServiceHost].
 */
interface CompanionReceiverHandle {
    val transferQueue: StateFlow<List<TransferItem>>
    fun start()
    fun stop()
}

/**
 * Honest implementation of [AndroidPlatformCallbacks]:
 *   - Clipboard apply (`onApplyRemoteClipboard`) writes to Android's [ClipboardManager]
 *     (propagating `EXTRA_IS_SENSITIVE` on Android 13+).
 *   - Notification inline reply (`onExecuteInlineReply`) & dismiss (`onDismissSystemNotification`)
 *     route through [Ec0NotificationListener].
 *   - Input injection, camera, hotspot, telephony, and media controls return honest
 *     no-op values (`false` / `null`) until their respective slices land.
 */
class AndroidHonestPlatformCallbacks(
    private val appContext: Context,
) : AndroidPlatformCallbacks {

    override fun onApplyRemoteClipboard(item: ClipboardItem): Boolean {
        if (!item.mime.startsWith("text/", ignoreCase = true)) return false
        val text = runCatching { item.asUtf8Text() }.getOrNull() ?: return false
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText("ec0", text)
            if (item.isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean =
        Ec0NotificationListener.sendInlineReply(reply, appContext)

    override fun onDismissSystemNotification(notifId: String): Boolean =
        Ec0NotificationListener.dismissSystemNotification(notifId)

    // Honest no-ops for slices not yet active on Android
    override fun onInjectKeyEvent(event: InputKeyEvent): Boolean = false
    override fun onInjectPointerEvent(event: InputPointerEvent): Boolean = false
    override fun onCursorHandoff(command: CursorHandoffCommand): Boolean = false
    override fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo? = null
    override fun onHotspotControlCommand(command: HotspotControlCommand): HotspotStatusInfo? = null
    override fun onCallActionCommand(command: CallActionCommand): Boolean = false
    override fun onMediaControlCommand(command: MediaControlCommand): MediaSessionInfo? = null

    override fun queryPhonePresence(): PresenceBeaconInfo {
        val batteryPct = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100)
        }.getOrNull() ?: 100

        val isCharging = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.isCharging
        }.getOrNull() ?: false

        val screenOn = runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive
        }.getOrNull() ?: true

        val liveCapabilities = buildSet {
            add(EngineCapability.CLIPBOARD)
            add(EngineCapability.FILE)
            add(EngineCapability.BATTERY)
            if (NotificationAccessHelper.isNotificationListenerEnabled(appContext)) {
                add(EngineCapability.NOTIFICATION)
            }
        }

        return PresenceBeaconInfo(
            batteryPct = batteryPct,
            isCharging = isCharging,
            screenOn = screenOn,
            capabilities = liveCapabilities,
        )
    }
}

/**
 * Helper for ColorOS / OEM background battery exemption and Auto-launch settings
 * (`docs/guides/coloros-setup.md` §1–§2).
 */
object ColorOsBatteryHelper {

    private val COLOROS_AUTO_LAUNCH_COMPONENTS = listOf(
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    )

    /**
     * Returns `true` if the app is currently on Android's Doze / battery-optimization whitelist.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
    }

    /**
     * Builds the direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent for [packageName].
     */
    fun buildRequestIgnoreBatteryOptimizationsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${packageName.trim()}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Opens the system battery-optimization exemption prompt (or falls back to the
     * battery optimization list / app details screen on restricted OEM builds).
     */
    fun openBatteryExemptionSettings(context: Context): Boolean {
        val pkg = runCatching { context.packageName }.getOrNull().orEmpty().ifBlank { "ec0.android" }
        val directIntent = buildRequestIgnoreBatteryOptimizationsIntent(pkg)
        if (runCatching { context.startActivity(directIntent); true }.getOrDefault(false)) {
            return true
        }
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(listIntent); true }.getOrDefault(false)) {
            return true
        }
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(appDetailsIntent); true }.getOrDefault(false)
    }

    /**
     * Opens ColorOS Auto-launch / Associate-start management (`OplusQualityProtect`),
     * falling back to the app's system details page on non-ColorOS devices.
     */
    fun openAutoLaunchSettings(context: Context): Boolean {
        for ((pkg, cls) in COLOROS_AUTO_LAUNCH_COMPONENTS) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                return true
            }
        }
        val appPkg = runCatching { context.packageName }.getOrNull().orEmpty().ifBlank { "ec0.android" }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$appPkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }
}

/**
 * Pure-Kotlin coordinator that owns the lifecycle of:
 *   - [AndroidCompanionRuntime] + engine server (`node.startServer(startMdns = true)`)
 *   - [CompanionReceiverHandle] (Ktor LocalSend receiver)
 *   - [MulticastLockHandle] + periodic discovery sweep
 *   - Non-blocking `ec0pair://` / `ec0://pair` URI pairing flow and `trustedPeerCount` state.
 */
class CompanionServiceHost(
    val runtime: AndroidCompanionRuntime,
    private val multicastLock: MulticastLockHandle,
    private val receiver: CompanionReceiverHandle,
    private val discoverSweep: suspend () -> List<UiDevice>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val discoveryPeriodMs: Long = 5_000L,
    private val startMdnsOnServerStart: Boolean = true,
) : Closeable {

    private val started = AtomicBoolean(false)
    private var discoveryTickerJob: Job? = null
    private var manualScanJob: Job? = null
    private var pairingJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val receiverTransfers: StateFlow<List<TransferItem>>
        get() = receiver.transferQueue

    val pairingState: StateFlow<PairingStepState>
        get() = runtime.pairingState

    /**
     * Reactive list of pinned (trusted) engine peers, combining the runtime's
     * persisted [AndroidCompanionRuntime.pinnedPeers] and [ec0.core.engine.Ec0EngineNode.pinnedPeers].
     */
    val pinnedPeers: StateFlow<List<PinnedPeer>> = combine(
        runtime.pinnedPeers,
        runtime.node.pinnedPeers,
    ) { fromRuntime, fromNode ->
        val byId = LinkedHashMap<String, PinnedPeer>()
        for (p in fromRuntime) byId[p.deviceId.lowercase()] = p
        for (p in fromNode) byId[p.deviceId.lowercase()] = p
        byId.values.toList()
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = runtime.refreshPinnedPeers(),
    )

    /**
     * Reactive count of trusted peers (`0` on first run prior to pairing).
     */
    val trustedPeerCount: StateFlow<Int> = combine(
        runtime.pinnedPeers,
        runtime.node.pinnedPeers,
    ) { fromRuntime, fromNode ->
        val ids = HashSet<String>()
        for (p in fromRuntime) ids.add(p.deviceId.lowercase())
        for (p in fromNode) ids.add(p.deviceId.lowercase())
        ids.size
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = runtime.refreshPinnedPeers().size,
    )

    /**
     * Starts the companion runtime engine server (`startMdns = true`), acquires the
     * Wi-Fi `MulticastLock`, starts the Ktor LocalSend receiver, and begins the
     * periodic discovery sweep. Idempotent if already running.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        _isRunning.value = true

        Ec0NotificationListener.attachRuntime(runtime)
        runCatching { multicastLock.acquire() }
        runCatching { runtime.start(startMdns = startMdnsOnServerStart) }
        runCatching { receiver.start() }

        if (discoveryPeriodMs > 0L) {
            discoveryTickerJob = scope.launch(ioDispatcher) {
                while (isActive) {
                    runCatching { _devices.value = discoverSweep() }
                    delay(discoveryPeriodMs)
                }
            }
        }
    }

    /**
     * Triggers an immediate on-demand UDP discovery sweep on [ioDispatcher].
     */
    fun startScan() {
        if (_scanning.value) return
        if (!multicastLock.isHeld) {
            runCatching { multicastLock.acquire() }
        }
        manualScanJob = scope.launch(ioDispatcher) {
            _scanning.value = true
            try {
                _devices.value = discoverSweep()
            } catch (_: Throwable) {
                // Keep existing devices list on transient socket errors
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Launches the `ec0pair://...` / `ec0://pair?...` pairing pipeline asynchronously on
     * [ioDispatcher] so the endpoint walk never blocks the main UI thread.
     */
    fun pairWithUriAsync(
        rawUri: String,
        onStep: (PairingStepState) -> Unit = {},
    ): Job {
        pairingJob?.cancel()
        val job = scope.launch(ioDispatcher) {
            runtime.pairWithDesktopUriDetailed(rawUri, onStep = onStep)
        }
        pairingJob = job
        return job
    }

    /**
     * Synchronous entry point for background callers and deterministic unit tests.
     */
    fun pairWithUriBlocking(
        rawUri: String,
        onStep: (PairingStepState) -> Unit = {},
    ): PairingStepState = runtime.pairWithDesktopUriDetailed(rawUri, onStep = onStep)

    fun resetPairingState() {
        runtime.resetPairingState()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        _isRunning.value = false
        discoveryTickerJob?.cancel()
        discoveryTickerJob = null
        manualScanJob?.cancel()
        manualScanJob = null
        pairingJob?.cancel()
        pairingJob = null

        if (Ec0NotificationListener.currentRuntime() === runtime) {
            Ec0NotificationListener.attachRuntime(null)
        }
        runCatching { receiver.stop() }
        runCatching { multicastLock.release() }
        runCatching { runtime.close() }
    }

    override fun close() {
        stop()
    }
}

/**
 * Foreground Android [Service] (`foregroundServiceType="connectedDevice"`, `START_STICKY`,
 * notification channel `"ec0"`) that keeps [CompanionServiceHost] alive across Activity
 * lifecycle transitions and ColorOS background freezes.
 */
class Ec0CompanionService : Service() {

    inner class LocalBinder : Binder() {
        val service: Ec0CompanionService get() = this@Ec0CompanionService
        val host: CompanionServiceHost? get() = activeHost
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        val host = ensureHostStarted(applicationContext)
        host.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val host = ensureHostStarted(applicationContext)
        host.start()

        val pairUri = intent?.getStringExtra(EXTRA_PAIR_URI)?.takeIf { it.isNotBlank() }
        if (pairUri != null) {
            host.pairWithUriAsync(pairUri)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        shutdownActiveHost()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "ec0 Companion Service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps your phone connected to your paired PC for file transfer, clipboard, and notification sync."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("ec0 companion active")
                .setContentText("Listening for paired PC connections on local network")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ec0"
        const val NOTIFICATION_ID = 49880
        const val EXTRA_PAIR_URI = "ec0.android.extra.PAIR_URI"
        val OWN_FINGERPRINT: String = java.util.UUID.randomUUID().toString()

        private val hostLock = Any()

        @Volatile
        private var activeHost: CompanionServiceHost? = null

        @Volatile
        private var activeServiceScope: CoroutineScope? = null

        @Volatile
        private var pendingRequestRef: Pair<PrepareUploadRequestDtoV2, CompletableDeferred<ReceiverAcceptance>>? = null

        private val _incomingRequest = MutableStateFlow<IncomingRequest?>(null)
        val incomingRequest: StateFlow<IncomingRequest?> = _incomingRequest.asStateFlow()

        private val _latestSettings = MutableStateFlow(
            SettingsState(
                alias = Build.MODEL.ifBlank { "Android" },
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                deviceType = DeviceType.MOBILE,
                protocol = ProtocolType.HTTP,
            ),
        )

        fun updateCachedSettings(settings: SettingsState) {
            _latestSettings.value = settings
        }

        fun acceptIncomingTransfer(
            trust: Boolean,
            onTrustFingerprint: (String) -> Unit,
        ) {
            pendingRequestRef?.let { (request, decision) ->
                if (trust) {
                    onTrustFingerprint(request.info.fingerprint)
                }
                decision.complete(ReceiverAcceptance.ACCEPT(request.files.keys))
            }
        }

        fun declineIncomingTransfer() {
            pendingRequestRef?.second?.complete(ReceiverAcceptance.DECLINE)
        }

        fun currentHost(): CompanionServiceHost? = activeHost

        /**
         * Starts [Ec0CompanionService] as a foreground service and returns the
         * shared [CompanionServiceHost] instance.
         */
        fun startServiceAndGetHost(
            context: Context,
            initialSettings: SettingsState? = null,
            ec0StoreDir: File = File(context.applicationContext.filesDir, "ec0"),
        ): CompanionServiceHost {
            if (initialSettings != null) {
                _latestSettings.value = initialSettings
            }
            val appCtx = context.applicationContext
            val host = ensureHostStarted(appCtx, ec0StoreDir)
            runCatching {
                val intent = Intent(appCtx, Ec0CompanionService::class.java)
                appCtx.startForegroundService(intent)
            }
            return host
        }

        internal fun ensureHostStarted(
            appContext: Context,
            ec0StoreDir: File = File(appContext.filesDir, "ec0"),
        ): CompanionServiceHost {
            synchronized(hostLock) {
                activeHost?.let { existing ->
                    existing.start()
                    return existing
                }

                val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                activeServiceScope = serviceScope

                val downloadDir = File(
                    appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                    "ec0",
                ).also { it.mkdirs() }

                val runtime = AndroidCompanionRuntime.createWithPersistentStores(
                    storeDir = ec0StoreDir,
                    phoneModelName = _latestSettings.value.alias.ifBlank { Build.MODEL.ifBlank { "Android" } },
                    callbacks = AndroidHonestPlatformCallbacks(appContext),
                    listenPort = 0,
                    downloadDir = downloadDir,
                )

                val decideCallback: suspend (PrepareUploadRequestDtoV2, String) -> ReceiverAcceptance =
                    { request, _ ->
                        val fingerprint = request.info.fingerprint
                        if (fingerprint in _latestSettings.value.trustedFingerprints) {
                            ReceiverAcceptance.ACCEPT(request.files.keys)
                        } else {
                            val decision = CompletableDeferred<ReceiverAcceptance>()
                            pendingRequestRef = request to decision
                            _incomingRequest.value = IncomingRequest(
                                senderAlias = request.info.alias,
                                senderFingerprint = fingerprint,
                                senderIp = "",
                                files = request.files.values.map {
                                    IncomingFileInfo(it.fileName, it.size)
                                },
                            )
                            try {
                                decision.await()
                            } finally {
                                _incomingRequest.value = null
                                pendingRequestRef = null
                            }
                        }
                    }

                val ktorReceiver = LocalsendReceiverKtor(
                    config = LocalsendReceiverConfig(
                        alias = _latestSettings.value.alias,
                        deviceModel = _latestSettings.value.deviceModel,
                        deviceType = _latestSettings.value.deviceType,
                        requiredPin = _latestSettings.value.pin.takeIf { _latestSettings.value.requirePin },
                        destination = Path.of(downloadDir.absolutePath),
                        acceptPolicy = AcceptPolicy.ASK,
                        fingerprint = OWN_FINGERPRINT,
                    ),
                    decide = decideCallback,
                )

                val receiverHandle = object : CompanionReceiverHandle {
                    override val transferQueue: StateFlow<List<TransferItem>>
                        get() = ktorReceiver.transferQueue

                    override fun start() {
                        ktorReceiver.start()
                    }

                    override fun stop() {
                        ktorReceiver.stop()
                    }
                }

                val multicastHandle = object : MulticastLockHandle {
                    private var lock: WifiManager.MulticastLock? = null

                    override val isHeld: Boolean
                        get() = lock?.isHeld == true

                    override fun acquire() {
                        if (lock?.isHeld == true) return
                        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                        lock = wifi.createMulticastLock("ec0-discovery").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    }

                    override fun release() {
                        runCatching {
                            if (lock?.isHeld == true) {
                                lock?.release()
                            }
                        }
                        lock = null
                    }
                }

                val discovery = LocalsendUdpDiscovery(
                    settingsProvider = { _latestSettings.value },
                    ownFingerprint = { OWN_FINGERPRINT },
                )

                val host = CompanionServiceHost(
                    runtime = runtime,
                    multicastLock = multicastHandle,
                    receiver = receiverHandle,
                    discoverSweep = { discovery.discoverSweep() },
                    scope = serviceScope,
                )
                activeHost = host
                host.start()
                return host
            }
        }

        internal fun shutdownActiveHost() {
            synchronized(hostLock) {
                activeHost?.stop()
                activeHost = null
                activeServiceScope?.cancel()
                activeServiceScope = null
            }
        }
    }
}
