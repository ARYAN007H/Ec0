/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 Android client v0.1 — LocalSend discovery + file send over the exact
 * same core stack as the desktop app (core/localsend.client, moved out of
 * desktop so both ends share protocol code).
 */
package ec0.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ec0.core.localsend.DeviceType
import ec0.core.localsend.client.LocalsendHttp
import ec0.core.localsend.client.LocalsendUdpDiscovery
import ec0.core.uimodels.SettingsState
import ec0.core.uimodels.TransferDirection
import ec0.core.uimodels.TransferItem
import ec0.core.uimodels.TransferState
import ec0.core.uimodels.UiDevice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android-side app state: same shape as the desktop AppState, phone flavor. */
class AndroidAppState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val settings = SettingsState(
        alias = Build.MODEL.ifBlank { "Android" },
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        deviceType = DeviceType.MOBILE,
    )

    private val discovery = LocalsendUdpDiscovery(
        settingsProvider = { settings },
        // HTTP-mode fingerprint semantics — random per process run, mirroring
        // the desktop client (cert SHA-256 comes with the HTTPS server).
        ownFingerprint = { OWN_FINGERPRINT },
    )
    private val http = LocalsendHttp()

    private val _devices = MutableStateFlow<List<UiDevice>>(emptyList())
    val devices: StateFlow<List<UiDevice>> = _devices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Android filters multicast at the Wi-Fi driver — a held MulticastLock is
     * mandatory for UDP :53317 discovery (reference: KDE Connect's
     * UdpListener does the same).
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startScan() {
        if (_scanning.value) return
        if (multicastLock == null) {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ec0-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        scanJob = scope.launch {
            _scanning.value = true
            try {
                _devices.value = discovery.discoverSweep()
                _status.value = if (_devices.value.isEmpty()) "No devices found" else ""
            } catch (t: Throwable) {
                _status.value = "Scan failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _scanning.value = false
            }
        }
    }

    /** SAF uri → cache file → LocalSend v2 send (prepare-upload → upload). */
    fun sendFile(uri: android.net.Uri, peer: UiDevice) {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: "file-${System.currentTimeMillis()}"

            val item = TransferItem(
                id = "out-${System.nanoTime()}",
                fileName = displayName,
                direction = TransferDirection.OUTGOING,
                peerAlias = peer.alias,
                sizeBytes = 0,
                state = TransferState.RUNNING,
            )
            _transfers.update { it + item }
            try {
                // Stage into cache so we know a real size + rewindable stream.
                val staged = File(context.cacheDir, "ec0-out-${System.nanoTime()}-$displayName")
                val size = resolver.openInputStream(uri)!!.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                    staged.length()
                }
                if (size == 0L) throw IllegalStateException("Empty file or unreadable URI")

                val response = http.prepareUpload(
                    peer = peer,
                    info = ec0.core.localsend.dto.RegisterDtoV2(
                        alias = settings.alias,
                        version = ec0.core.localsend.LocalsendV2.PROTOCOL_VERSION,
                        deviceModel = settings.deviceModel,
                        deviceType = settings.deviceType,
                        fingerprint = OWN_FINGERPRINT,
                        port = settings.port,
                        // Honest announcement: no HTTP server hosted yet.
                        protocol = ec0.core.localsend.ProtocolType.HTTP,
                        download = false,
                    ),
                    files = mapOf(
                        item.id to ec0.core.localsend.dto.FileDto(
                            id = item.id,
                            fileName = displayName,
                            size = size,
                            fileType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                            sha256 = null,
                        ),
                    ),
                    pin = null,
                )
                val token = response.files[item.id] ?: throw IllegalStateException("Recipient accepted no files (204)")
                staged.inputStream().use { input ->
                    http.uploadFile(
                        peer = peer,
                        sessionId = response.sessionId,
                        fileId = item.id,
                        token = token,
                        fileName = displayName,
                        bytes = input,
                        size = size,
                    ) { pct -> updateItem(item.id) { it.copy(progressPct = pct) } }
                }
                updateItem(item.id) { it.copy(progressPct = 100, state = TransferState.DONE) }
                staged.delete()
            } catch (t: Throwable) {
                updateItem(item.id) {
                    it.copy(state = TransferState.FAILED, error = t.message ?: t.javaClass.simpleName)
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
    private val appState by lazy { AndroidAppState(applicationContext, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Ec0App(appState) }
    }

    override fun onDestroy() {
        appState.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ec0App(state: AndroidAppState) {
    val devices by state.devices.collectAsState()
    val transfers by state.transfers.collectAsState()
    val scanning by state.scanning.collectAsState()
    val status by state.status.collectAsState()

    var pendingPeer: UiDevice? = null
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val peer = pendingPeer
        if (uri != null && peer != null) state.sendFile(uri, peer)
        pendingPeer = null
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ec0") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = state::startScan, enabled = !scanning) {
                    Text(if (scanning) "Scanning…" else "Scan")
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            if (transfers.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        transfers.takeLast(3).forEach { t ->
                            Text(
                                "${t.fileName} → ${t.peerAlias}: ${t.progressPct}%" +
                                    (t.error?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.id }) { device ->
                    Card(
                        onClick = {
                            pendingPeer = device
                            pickFile.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.alias, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${device.protocol} · ${device.hostAddress}:${device.port}" +
                                    (if (device.download) " · can receive" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
