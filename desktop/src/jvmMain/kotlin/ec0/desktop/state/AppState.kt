package ec0.desktop.state

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.core.localsend.client.RealDeviceSource
import ec0.core.localsend.client.SynergyDiscoveryProbe
import ec0.core.localsend.client.LocalsendHttp
import ec0.core.localsend.client.NothingAcceptedException
import ec0.core.localsend.client.PinRequiredException
import ec0.core.localsend.client.RecipientDeclinedException
import ec0.core.localsend.client.TooManyAttemptsException
import ec0.core.localsend.client.UploadCancelledException
import ec0.ui.models.DeviceSource
import ec0.ui.models.DemoDeviceSource
import ec0.ui.models.SettingsState
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.models.UiDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path

/** Root observable state for the whole window. */
class AppState(
    private val scope: CoroutineScope,
    deviceSource: DeviceSource? = null,
) {
    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val http = LocalsendHttp()

    // Built after construction so the lambdas can read `settings` lazily.
    private val deviceSource: DeviceSource = deviceSource ?: RealDeviceSource(
        settingsProvider = { settings.value },
        synergyProbe = SynergyDiscoveryProbe(enabled = { settings.value.synergyProbeEnabled }),
        announceDownload = true,
    )

    /**
     * T14 receiver: accepts incoming LocalSend v2 pushes while the app runs.
     * Same fingerprint as the discovery announce (RealDeviceSource's
     * per-process id) so peers see one consistent identity. Alias/PIN changes
     * apply on app restart — the receiver config is built once.
     */
    private val receiver: LocalsendReceiverServer = LocalsendReceiverServer(
        config = LocalsendReceiverConfig(
            alias = _settings.value.alias,
            deviceModel = _settings.value.deviceModel,
            deviceType = _settings.value.deviceType,
            requiredPin = _settings.value.pin.takeIf { _settings.value.requirePin },
            destination = Path.of(
                _settings.value.downloadDir.ifBlank {
                    System.getProperty("user.home") + "/Downloads"
                },
            ),
            acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL,
            fingerprint = RealDeviceSource.OWN_FINGERPRINT,
        ),
    )

    /** Combined queue: outgoing sends + incoming receiver transfers. */
    val transfers: StateFlow<List<TransferItem>> =
        combine(_transfers, receiver.transferQueue) { outgoing, incoming -> outgoing + incoming }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var scanJob: Job? = null
    private var tickerJob: Job? = null

    init {
        runCatching { receiver.start() }.onFailure {
            System.err.println("ec0: LocalSend receiver failed to start: ${it.message}")
        }
    }

    /** Run one discovery sweep; devices are replaced on success. */
    fun startScan() {
        if (_scanning.value) return
        scanJob = scope.launch {
            _scanning.value = true
            try {
                _devices.value = deviceSource.discover()
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Send a real file to a LocalSend peer: prepare-upload → upload with
     *  progress, states updated on the queue item as the protocol advances.
     *  [cancelTransfer] aborts a running upload at the next chunk boundary
     *  (the connection is disconnected) — see the CANCELLED mapping below. */
    fun sendFile(file: File, peer: UiDevice) {
        val item = TransferItem(
            id = "out-${System.nanoTime()}",
            fileName = file.name,
            direction = TransferDirection.OUTGOING,
            peerAlias = peer.alias,
            sizeBytes = file.length(),
            state = TransferState.RUNNING,
        )
        _transfers.update { it + item }
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (isCancelled(item.id)) throw UploadCancelledException()
                val fileDto = FileDto(
                    id = item.id,
                    fileName = file.name,
                    size = file.length(),
                    fileType = guessMimeType(file.name),
                )
                val response = http.prepareUpload(
                    peer = peer,
                    info = toRegisterDto(),
                    files = mapOf(item.id to fileDto),
                    // PIN goes whenever the user stored one — the peer decides
                    // whether it's required (401 tells us otherwise).
                    pin = _settings.value.pin.takeIf { it.isNotEmpty() },
                )
                val token = response.files[item.id]
                    ?: throw NothingAcceptedException()
                if (isCancelled(item.id)) throw UploadCancelledException()
                updateItem(item.id) { it.copy(progressPct = 0) }
                file.inputStream().use { input ->
                    http.uploadFile(
                        peer = peer,
                        sessionId = response.sessionId,
                        fileId = item.id,
                        token = token,
                        fileName = file.name,
                        bytes = input,
                        size = file.length(),
                        cancelSignal = { isCancelled(item.id) },
                    ) { pct -> updateItem(item.id) { it.copy(progressPct = pct) } }
                }
            }.onSuccess {
                // Guard: a cancelled transfer must not be flipped to DONE by
                // a late success path.
                updateItem(item.id) { current ->
                    if (current.state in FINISHED_STATES) current
                    else current.copy(progressPct = 100, state = TransferState.DONE)
                }
            }.onFailure { t ->
                when (t) {
                    is UploadCancelledException, is kotlinx.coroutines.CancellationException ->
                        updateItem(item.id) { current ->
                            if (current.state in FINISHED_STATES) current
                            else current.copy(state = TransferState.CANCELLED)
                        }
                    is PinRequiredException ->
                        updateItem(item.id) { it.copy(state = TransferState.FAILED, error = "PIN required / wrong PIN (401)") }
                    is RecipientDeclinedException ->
                        updateItem(item.id) { it.copy(state = TransferState.FAILED, error = "Recipient declined (403)") }
                    is TooManyAttemptsException ->
                        updateItem(item.id) { it.copy(state = TransferState.FAILED, error = "Too many PIN attempts (429)") }
                    is NothingAcceptedException ->
                        updateItem(item.id) { it.copy(state = TransferState.FAILED, error = "No files accepted (204)") }
                    else ->
                        updateItem(item.id) { it.copy(state = TransferState.FAILED, error = "Failed: ${t.message ?: t.javaClass.simpleName}") }
                }
            }
        }
    }

    /** Demo-only: seeds one fake incoming transfer that runs to completion
     *  so the queue UI can be exercised without a peer. */
    fun seedDemoTransfer() {
        val item = TransferItem(
            id = "demo-${System.nanoTime()}",
            fileName = "demo-photo.png",
            direction = TransferDirection.INCOMING,
            peerAlias = "A5x",
            sizeBytes = 2_400_000,
            state = TransferState.RUNNING,
        )
        _transfers.update { it + item }
        ensureTicker()
    }

    /** Cooperative cancel: flags the item; the upload loop checks between
     *  progress callbacks and aborts via the connection. */
    fun cancelTransfer(id: String) =
        _transfers.update { list -> list.map {
            if (it.id == id && it.state in ACTIVE_STATES) it.copy(state = TransferState.CANCELLED) else it
        } }

    fun clearFinished() =
        _transfers.update { list -> list.filterNot {
            it.state in FINISHED_STATES
        } }

    fun updateSettings(transform: (SettingsState) -> SettingsState) =
        _settings.update(transform)

    /**
     * Build the LocalSend register payload from current settings — the seam
     * where the UI hands real settings to the protocol layer (T5 DTOs).
     */
    fun toRegisterDto(): RegisterDtoV2 {
        val s = _settings.value
        return RegisterDtoV2(
            alias = s.alias,
            version = ec0.core.localsend.LocalsendV2.PROTOCOL_VERSION,
            deviceModel = s.deviceModel,
            deviceType = s.deviceType,
            fingerprint = RealDeviceSource.OWN_FINGERPRINT,
            port = s.port,
            // We do not host the HTTP server yet — honest announcement.
            protocol = ProtocolType.HTTP,
            download = false,
        )
    }

    private fun updateItem(id: String, transform: (TransferItem) -> TransferItem) =
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun isCancelled(id: String): Boolean =
        _transfers.value.any { it.id == id && it.state == TransferState.CANCELLED }

    /** Advance demo transfers; real transports replace this ticker. */
    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(200)
                _transfers.update { list -> list.map { item ->
                    if (item.id.startsWith("demo-") && item.state == TransferState.RUNNING) {
                        val next = item.progressPct + 5
                        if (next >= 100) item.copy(progressPct = 100, state = TransferState.DONE)
                        else item.copy(progressPct = next)
                    } else item
                } }
            }
        }
    }

    companion object {
        val ACTIVE_STATES = setOf(TransferState.PENDING, TransferState.RUNNING, TransferState.PAUSED)
        val FINISHED_STATES = setOf(TransferState.DONE, TransferState.FAILED, TransferState.CANCELLED)

        fun guessMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "txt" -> "text/plain"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }
}
