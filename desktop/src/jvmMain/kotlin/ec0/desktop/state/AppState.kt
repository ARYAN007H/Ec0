package ec0.desktop.state

import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.dto.RegisterDtoV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Seam between the UI and the protocol stacks. The real implementation
 * (LocalSend HTTP server + Synergy WebSocket transport, T10) plugs in here;
 * until then [DemoDeviceSource] provides deterministic sample devices.
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
    private val deviceSource: DeviceSource = DemoDeviceSource(),
) {
    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

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

    /** Enqueue an outgoing transfer. Real protocol wiring is a later PR;
     *  the item stays PENDING until a transport drives it. */
    fun enqueueOutgoing(fileName: String, sizeBytes: Long, peer: UiDevice) {
        val item = TransferItem(
            id = "out-${System.nanoTime()}",
            fileName = fileName,
            direction = TransferDirection.OUTGOING,
            peerAlias = peer.alias,
            sizeBytes = sizeBytes,
        )
        _transfers.update { it + item }
        ensureTicker()
    }

    /** Demo-only: seeds one fake incoming transfer that runs to completion
     *  so the queue UI can be exercised before protocol integration. */
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
            fingerprint = "pending-cert", // replaced by the TLS layer
            port = s.port,
            protocol = s.protocol,
            download = true,
        )
    }

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
    }
}
