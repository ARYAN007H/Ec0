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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Binder
import android.view.KeyEvent
import android.os.Build
import android.os.Environment
import java.util.logging.Logger
import android.os.IBinder
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import ec0.core.engine.EngineCapability
import ec0.core.engine.EngineConstants
import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.android.PairingStepState
import ec0.core.engine.crypto.PinnedPeer
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.FindMyRingCommand
import ec0.core.engine.protocol.WorkstationLockCommand
import ec0.core.engine.protocol.ProximitySecurityProtocol
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
    private val clipboardEchoGuard: ClipboardEchoGuard? = null,
    /** Service-owned: lives as long as the runtime so the wake activity can stop it. */
    val ringController: FindMyRingController = FindMyRingController(appContext),
) : AndroidPlatformCallbacks {

    private val mediaLog = Logger.getLogger("ec0.android.media")

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
            // The apply fires the system clipboard listener — record the sha so
            // [AndroidClipboardWatcher] suppresses the echo instead of pushing back.
            clipboardEchoGuard?.lastAppliedFromDesktopSha256 = item.sha256Hex
            true
        }.getOrDefault(false)
    }

    override fun onExecuteInlineReply(reply: NotificationReplyCommand): Boolean =
        Ec0NotificationListener.sendInlineReply(reply, appContext)

    override fun onDismissSystemNotification(notifId: String): Boolean =
        Ec0NotificationListener.dismissSystemNotification(notifId)

    // Desktop→phone injection stays a no-op until an AccessibilityService/IME lands
    // (the phone is the remote-input SENDER, not the receiver — see RemoteInputScreen).
    override fun onInjectKeyEvent(event: InputKeyEvent): Boolean = false
    override fun onInjectPointerEvent(event: InputPointerEvent): Boolean = false
    override fun onCursorHandoff(command: CursorHandoffCommand): Boolean = false
    override fun onCameraControlCommand(command: CamDroidControlCommand): CameraStatusInfo? = null
    override fun onHotspotControlCommand(command: HotspotControlCommand): HotspotStatusInfo? = null

    /**
     * v1: decline-only is fine (the A5x call UX is bad from a service; intentional).
     */
    override fun onCallActionCommand(command: CallActionCommand): Boolean = false

    /**
     * Feature A2: real media executor. Play/pause/next/previous dispatch
     * `KEYCODE_MEDIA_*` through [AudioManager.dispatchMediaKeyEvent] — they act on
     * whatever the active MediaSession is (any player, no per-app integration).
     * Volume uses the music stream. Returns a best-effort MediaSessionInfo so the
     * desktop's media tile can render the outcome; null when the player state can't
     * be observed (MediaSessionManager listing needs the listener grant we already
     * hold, but a synthesis from the command alone adds no information — null is
     * honest).
     */
    override fun onMediaControlCommand(command: MediaControlCommand): MediaSessionInfo? {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            mediaLog.warning("media control: no AudioManager")
            return null
        }
        runCatching {
            when (command.action) {
                MediaControlCommand.ACTION_PLAY -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
                MediaControlCommand.ACTION_PAUSE -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
                MediaControlCommand.ACTION_PLAY_PAUSE -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                MediaControlCommand.ACTION_NEXT -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
                MediaControlCommand.ACTION_PREVIOUS -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                MediaControlCommand.ACTION_SET_VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = ((command.volumePct ?: 100) * max) / 100
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
                }
                else -> mediaLog.warning("media control: unhandled action ${command.action}")
            }
        }.onFailure { mediaLog.warning("media control failed: $it") }
        return null
    }

    private fun dispatchMediaKey(audio: AudioManager, keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * Feature A2: find-my-phone executor — drives [FindMyRingController]. The
     * desktop's `find_my_ring` quick command (hub dashboard siren toggle) lands
     * here via the engine's handoff/proximity service.
     */
    override fun onFindMyRingCommand(command: FindMyRingCommand) {
        ringController.onFindMyRing(command)
    }

    /**
     * Scoped-storage reality: on Android 11+ (API 30+) MediaStore gives access to
     * the phone's own media without any grant; full-directory browsing (VFS roots)
     * needs MANAGE_EXTERNAL_STORAGE. Below 11 the legacy READ_EXTERNAL_STORAGE
     * grant is the gate. Kept as one predicate so the capability flag and the
     * future VFS executor agree.
     */
    private fun hasVfsReadAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        }
        return runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

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
            // Deep-integration capabilities (plan §P2/P4) — declared only when the
            // phone genuinely supports them:
            add(EngineCapability.REMOTE_INPUT) // sender UI ships with the app (A1)
            add(EngineCapability.MEDIA_CONTROL) // AudioManager media-key dispatch (A2)
            add(EngineCapability.FIND_MY) // ring executor ships with the app (A2)
            // VFS_READ requires storage access (scoped MediaStore on 11+ or the
            // legacy grant below 11) — the desktop file browser only lights up
            // when files are actually listable.
            if (hasVfsReadAccess()) {
                add(EngineCapability.VFS_READ)
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
        /**
         * THIS device's LocalSend fingerprint. LocalSend peers trust THIS id — it is
         * PERSISTED in the settings DataStore (regenerated only if blank). A per-boot
         * random value made every peer's stored trust stale on the next service start,
         * silently breaking phone→PC auto-accept.
         */
        @Volatile
        private var cachedOwnFingerprint: String? = null

        @Volatile
        private var appContextHolder: Context? = null

        val OWN_FINGERPRINT: String
            get() {
                cachedOwnFingerprint?.let { return it }
                val fp = _latestSettings.value.ownFingerprint.ifBlank {
                    java.util.UUID.randomUUID().toString()
                }
                cachedOwnFingerprint = fp
                if (_latestSettings.value.ownFingerprint.isBlank()) {
                    persistOwnFingerprint(fp)
                }
                return fp
            }

        private fun persistOwnFingerprint(fp: String) {
            val context = appContextHolder ?: return
            val scope = activeServiceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                runCatching {
                    val store = AndroidSettingsStore(context)
                    val current = store.load()
                    if (current.ownFingerprint.isBlank()) {
                        store.save(current.copy(ownFingerprint = fp))
                    }
                }
            }
        }

        private val hostLock = Any()

        @Volatile
        private var activeHost: CompanionServiceHost? = null

        @Volatile
        private var activeClipboardWatcher: AndroidClipboardWatcher? = null

        /** Shared with [FindMyRingActivity]'s stop button — set when the runtime
         *  callbacks (and their ring controller) are constructed. */
        @Volatile
        var ringControllerRef: FindMyRingController? = null

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
            appContextHolder = context.applicationContext
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

                val clipboardEchoGuard = ClipboardEchoGuard()
                val findMyRingController = FindMyRingController(appContext)
                ringControllerRef = findMyRingController
                val runtime = AndroidCompanionRuntime.createWithPersistentStores(
                    storeDir = ec0StoreDir,
                    phoneModelName = _latestSettings.value.alias.ifBlank { Build.MODEL.ifBlank { "Android" } },
                    callbacks = AndroidHonestPlatformCallbacks(appContext, clipboardEchoGuard, findMyRingController),
                    listenPort = EngineConstants.PRIMARY_PORT,
                    downloadDir = downloadDir,
                )
                val clipboardWatcher = AndroidClipboardWatcher(
                    appContext = appContext,
                    runtime = runtime,
                    echoGuard = clipboardEchoGuard,
                    isAppFocused = { isAppFocusedForClipboard },
                    pushScope = serviceScope,
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
                activeClipboardWatcher = clipboardWatcher
                clipboardWatcher.start()
                host.start()

                // A4: QS tile seams — the tiles read/write the live setting and
                // send the lock command through the active host.
                val clipboardSettingsStore = AndroidSettingsStore(
                    appContext.applicationContext.ec0SettingsDataStore,
                )
                Ec0TileState.clipboardSyncEnabled = { clipboardWatcher.isStarted() }
                Ec0TileState.setClipboardSyncEnabled = { enabled -> clipboardWatcher.setEnabled(enabled) }
                Ec0TileState.lockWorkstation = {
                    val desktopId = host.pinnedPeers.value
                        .firstOrNull { host.runtime.node.activeSessionFor(it.deviceId) != null }
                        ?.deviceId
                    val session = desktopId?.let { host.runtime.node.activeSessionFor(it) }
                    if (session == null) {
                        false
                    } else {
                        runCatching {
                            session.sendCmd(
                                cmdType = ProximitySecurityProtocol.CMD_WORKSTATION_LOCK,
                                cmdBody = WorkstationLockCommand(
                                    action = WorkstationLockCommand.ACTION_LOCK,
                                    reason = "qs_tile",
                                ).toJsonObject(),
                                category = ProximitySecurityProtocol.CATEGORY_SECURITY,
                                timestampMs = System.currentTimeMillis(),
                            )
                            true
                        }.getOrDefault(false)
                    }
                }
                return host
            }
        }

        /**
         * Android 10+ clipboard contract: phone→PC pushes only while ec0's Activity is
         * resumed (clipboard read access is granted to the focused app's UID, which the
         * foreground service shares). Called from [MainActivity] lifecycle callbacks.
         */
        @Volatile
        var isAppFocusedForClipboard: Boolean = false
            private set

        fun onMainActivityFocusChanged(focused: Boolean) {
            isAppFocusedForClipboard = focused
            if (focused) activeClipboardWatcher?.onFocusGained()
        }

        /**
         * ColorOS clipboard workaround: the foreground service's clipboard READ can be
         * denied even while ec0 is focused, but the Activity's own read succeeds (the
         * focused-app UID grant covers the Activity). MainActivity reads the clip on
         * focus and hands non-blank text here; the watcher runs the exact same
         * policy/echo/dedupe tail as the listener path.
         */
        fun pushClipboardFromActivity(text: String, isSensitive: Boolean = false) {
            activeClipboardWatcher?.pushText(text, isSensitive)
        }

        internal fun shutdownActiveHost() {
            synchronized(hostLock) {
                activeClipboardWatcher?.stop()
                activeClipboardWatcher = null
                activeHost?.stop()
                activeHost = null
                activeServiceScope?.cancel()
                activeServiceScope = null
            }
        }
    }
}
