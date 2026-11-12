package ec0.desktop.state

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.desktop.discovery.RealDeviceSource
import ec0.desktop.discovery.SynergyDiscoveryProbe
import ec0.desktop.localsend.LocalsendHttp
import ec0.desktop.localsend.NothingAcceptedException
import ec0.desktop.localsend.PinRequiredException
import ec0.desktop.localsend.RecipientDeclinedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Seam between the UI and the protocol stacks. [RealDeviceSource] is the
 * production source (LocalSend UDP :53317 works today; the Synergy probe is
 * T4-gated). [DemoDeviceSource] remains available for UI-only development.
 */
interface DeviceSource {
    /** One discovery sweep over the configured transports. */
    suspend fun discover(): List<UiDevice>
}

/**
 * Deterministic sample devices for UI development. Explicitly a demo seam —
 * NOT real discovery.
 */
class DemoDeviceSource : DeviceSource {
    override suspend fun discover(): List<UiDevice> = listOf(
        UiDevice(
            id = "demo-phone",
            alias = "A5x",
            deviceModel = "OPPO A5x 5G",
            deviceType = DeviceType.MOBILE,
            protocol = ProtocolFamily.LOCALSEND,
            port = 53317,
            fingerprint = "demo-fingerprint-phone",
            download = true,
        ),
        UiDevice(
            id = "demo-laptop",
            alias = "arch-box",
            deviceModel = "Linux",
            deviceType = DeviceType.DESKTOP,
            protocol = ProtocolFamily.SYNERGY,
            port = 39888,
            fingerprint = "demo-fingerprint-laptop",
        ),
        UiDevice(
            id = "demo-tablet",
            alias = "Pad 2",
            deviceModel = "Lenovo",
            deviceType = DeviceType.HEADLESS,
            protocol = ProtocolFamily.KDECONNECT,
            port = 1716,
            fingerprint = "demo-fingerprint-tablet",
        ),
    )
}

/** Root observable state for the whole window. */
class AppState(
    private val scope: CoroutineScope,
    private val deviceSource: DeviceSource = RealDeviceSource(
        settingsProvider = { settings.value },
        synergyProbe = SynergyDiscoveryProbe(enabled = { settings.value.synergyProbeEnabled }),
    ),
) {
    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val http = LocalsendHttp()

    private var scanJob: Job? = null
    private var tickerJob: Job? = null

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
     *  progress, states updated on the queue item as the protocol advances. */
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
        ensureTicker()
        scope.launch(Dispatchers.IO) {
            runCatching {
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
                    pin = _settings.value.pin.takeIf { _settings.value.requirePin },
                )
                val token = response.files[item.id]
                    ?: throw NothingAcceptedException()
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
                    ) { pct -> updateItem(item.id) { it.copy(progressPct = pct) } }
                }
            }.onSuccess {
                updateItem(item.id) { it.copy(progressPct = 100, state = TransferState.DONE) }
            }.onFailure { t ->
                if (t is CancellationException) {
                    updateItem(item.id) { it.copy(state = TransferState.CANCELLED) }
                } else {
                    val msg = when (t) {
                        is PinRequiredException -> "PIN required / wrong PIN (401)"
                        is RecipientDeclinedException -> "Recipient declined (403)"
                        is NothingAcceptedException -> "No files accepted (204)"
                        else -> "Failed: ${t.message ?: t.javaClass.simpleName}"
                    }
                    updateItem(item.id) { it.copy(state = TransferState.FAILED, error = msg) }
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
