/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 Android client — renders the shared full-suite UI (:ui) through the
 * AndroidBridge. Protocol behavior (LocalSend discovery + send) is the exact
 * same core stack the desktop runs.
 */
package ec0.android

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ec0.core.localsend.DeviceType
import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.client.LocalsendHttp
import ec0.core.localsend.client.LocalsendUdpDiscovery
import ec0.core.localsend.server.LocalsendReceiverConfig
import ec0.core.localsend.server.LocalsendReceiverKtor
import ec0.core.localsend.server.ReceiverAcceptance
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.ui.Ec0App
import ec0.ui.models.Capabilities
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.models.UiDevice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android-side app state — implements the shared [UiBridge] seam. */
class AndroidAppState(private val context: Context, private val scope: CoroutineScope) : UiBridge {

    private val _settings = MutableStateFlow(
        SettingsState(
            alias = Build.MODEL.ifBlank { "Android" },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            deviceType = DeviceType.MOBILE,
            protocol = ProtocolType.HTTP,
        ),
    )
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val discovery = LocalsendUdpDiscovery(
        settingsProvider = { _settings.value },
        // HTTP-mode fingerprint semantics — random per process run, mirroring
        // the desktop client (cert SHA-256 comes with the HTTPS server).
        ownFingerprint = { OWN_FINGERPRINT },
        debugLog = { msg -> android.util.Log.d("ec0", msg) },
    )
    private val http = LocalsendHttp()

    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    override val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    override val capabilities: Capabilities = Capabilities(
        canReceive = true, // Ktor receiver below
        clipboardSync = false,
        notifications = false,
        photoSync = false,
        remoteInput = false,
    )

    private var scanJob: Job? = null
    private val cancelled = mutableSetOf<String>()

    /**
     * Android receiver (T14 contract over Ktor CIO). Destination: the app's
     * external files dir (MediaStore public-Downloads integration is a later
     * slice). Fingerprint matches the discovery announce.
     */
    private val receiver = LocalsendReceiverKtor(
        config = LocalsendReceiverConfig(
            alias = _settings.value.alias,
            deviceModel = _settings.value.deviceModel,
            deviceType = _settings.value.deviceType,
            requiredPin = _settings.value.pin.takeIf { _settings.value.requirePin },
            destination = java.nio.file.Path.of(
                (context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir).absolutePath,
            ),
            acceptPolicy = ec0.core.localsend.server.AcceptPolicy.ASK,
            decide = decideCallback,
            fingerprint = OWN_FINGERPRINT,
        ),
    )

    /** Combined queue: outgoing sends + incoming receiver transfers. */
    override val transfers: StateFlow<List<TransferItem>> =
        combine(_transfers, receiver.transferQueue) { outgoing, incoming -> outgoing + incoming }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Incoming transfer awaiting the user's decision (shared-UI dialog). */
    private val _incomingRequest = MutableStateFlow<ec0.ui.models.IncomingRequest?>(null)
    override val incomingRequest: StateFlow<ec0.ui.models.IncomingRequest?> = _incomingRequest.asStateFlow()

    private var pendingRequestRef: Pair<PrepareUploadRequestDtoV2, kotlinx.coroutines.CompletableDeferred<ReceiverAcceptance>>? = null

    private val decideCallback: suspend (PrepareUploadRequestDtoV2, String) -> ReceiverAcceptance =
        { request, _ ->
            val fingerprint = request.info.fingerprint
            if (fingerprint in _settings.value.trustedFingerprints) {
                ReceiverAcceptance.ACCEPT(request.files.keys)
            } else {
                val decision = kotlinx.coroutines.CompletableDeferred<ReceiverAcceptance>()
                pendingRequestRef = request to decision
                _incomingRequest.value = ec0.ui.models.IncomingRequest(
                    senderAlias = request.info.alias,
                    senderFingerprint = fingerprint,
                    senderIp = "",
                    files = request.files.values.map { ec0.ui.models.IncomingFileInfo(it.fileName, it.size) },
                )
                try {
                    decision.await()
                } finally {
                    _incomingRequest.value = null
                    pendingRequestRef = null
                }
            }
        }

    override fun acceptIncoming(trust: Boolean) {
        pendingRequestRef?.let { (request, decision) ->
            if (trust) updateSettings { s -> s.copy(trustedFingerprints = s.trustedFingerprints + request.info.fingerprint) }
            decision.complete(ReceiverAcceptance.ACCEPT(request.files.keys))
        }
    }

    override fun declineIncoming() {
        pendingRequestRef?.second?.complete(ReceiverAcceptance.DECLINE)
    }

    /**
     * Android filters multicast at the Wi-Fi driver — a held MulticastLock is
     * mandatory for UDP :53317 discovery (KDE Connect does the same).
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveryPeriodMs = 5_000L

    init {
        // Multicast reception on Android requires the lock held CONTINUOUSLY —
        // the discovery ticker runs for the app lifetime, so does the lock.
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("ec0-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { _devices.value = discovery.discoverSweep() }
                kotlinx.coroutines.delay(discoveryPeriodMs)
            }
        }
        runCatching { receiver.start() }.onFailure {
            android.util.Log.d("ec0", "receiver failed to start: ${it.message}")
        }
    }

    override fun startScan() {
        if (_scanning.value) return
        if (multicastLock == null) {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ec0-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        scanJob = scope.launch(Dispatchers.IO) {
            _scanning.value = true
            try {
                _devices.value = discovery.discoverSweep()
            } catch (t: Throwable) {
                android.util.Log.d("ec0", "sweep failed: ${t.message}")
            } finally {
                _scanning.value = false
            }
        }
    }

    override fun cancelTransfer(id: String) {
        synchronized(cancelled) { cancelled.add(id) }
        updateItem(id) { it.copy(state = TransferState.CANCELLED) }
    }

    override fun updateSettings(transform: (SettingsState) -> SettingsState) {
        _settings.update(transform)
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
            _transfers.update { it + item }
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
        _transfers.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun release() {
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        val OWN_FINGERPRINT: String = java.util.UUID.randomUUID().toString()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activityScope
        val state = AndroidAppState(applicationContext, activityScope)
        appState = state
        setContent {
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

    override fun onDestroy() {
        scope?.cancel()
        appState?.release()
        appState = null
        super.onDestroy()
    }
}
