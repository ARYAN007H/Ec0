/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * In-app QR scanner for the phone onboarding screen (Feature 2, Android companion
 * slice): CameraX ImageAnalysis + zxing-core decode of the desktop's `ec0://pair?…`
 * / `ec0pair://…` pairing URI. Replaces the paste-first flow (Gboard mangles long
 * unspaced URIs — see the paste field's keyboard-options comment in
 * AndroidOnboardingScreen).
 *
 * Layers, each independently testable:
 *  - [PairUriExtractor]     — pure URI extraction from decoded text
 *  - [QrFrameDecoder]       — pure YUV-planar → zxing decode (rotation handled)
 *  - [QrScannerViewModel]   — Idle / RequestingCamera / Scanning / Decoded state machine
 *  - [QrScannerCard]        — M3E card: permission gate, camera preview, result handoff
 */

package ec0.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pure URI extraction from a decoded QR payload (unit-tested). */
object PairUriExtractor {
    private const val EC0PAIR_SCHEME = "ec0pair://"
    private const val EC0_PAIR_SCHEME = "ec0://pair?"
    private val WHITESPACE = Regex("\\s+")

    /**
     * Returns the pairing URI inside [payload], or null when the payload carries
     * neither scheme. The desktop QR encodes exactly the URI, but camera framing and
     * keyboard-driven text can inject whitespace mid-URI (Gboard line-wraps) — strip
     * it after locating the scheme start. Trailing non-URI prose is safe: the
     * engine's URI parser rejects unknown suffixes explicitly.
     */
    fun extractPairUri(payload: String): String? {
        if (payload.isBlank()) return null
        for (scheme in listOf(EC0PAIR_SCHEME, EC0_PAIR_SCHEME)) {
            val idx = payload.indexOf(scheme)
            if (idx >= 0) {
                return payload.substring(idx).replace(WHITESPACE, "")
            }
        }
        return null
    }
}

/** Pure YUV-planar → zxing decode (unit-tested via an encode→decode roundtrip). */
object QrFrameDecoder {
    fun decode(data: ByteArray, width: Int, height: Int, rotationDegrees: Int): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return runCatching {
            val (luma, w, h) = when (rotationDegrees) {
                90 -> rotate90(data, width, height)
                180 -> rotate180(data, width, height)
                270 -> rotate270(data, width, height)
                else -> Triple(data, width, height)
            }
            val source = PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
    }

    /** 90° clockwise luminance rotation (portrait camera frames → upright QR). */
    internal fun rotate90(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = data[y * width + x]
            }
        }
        return Triple(out, height, width)
    }

    internal fun rotate180(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[(height - 1 - y) * width + (width - 1 - x)] = data[y * width + x]
            }
        }
        return Triple(out, width, height)
    }

    internal fun rotate270(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[(width - 1 - x) * height + y] = data[y * width + x]
            }
        }
        return Triple(out, height, width)
    }
}

/** Scanner state machine: Idle → RequestingCamera → Scanning → Decoded → handoff. */
sealed interface QrScannerState {
    data object Idle : QrScannerState
    data object RequestingCamera : QrScannerState
    data object Scanning : QrScannerState
    data class Decoded(val uri: String) : QrScannerState
}

class QrScannerViewModel(
    private val decoder: (ByteArray, Int, Int, Int) -> String? = QrFrameDecoder::decode,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<QrScannerState>(QrScannerState.Idle)
    val state: StateFlow<QrScannerState> = _state.asStateFlow()

    fun onScanRequested() {
        if (_state.value is QrScannerState.Idle) _state.value = QrScannerState.RequestingCamera
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (_state.value !is QrScannerState.RequestingCamera) return
        _state.value = if (granted) QrScannerState.Scanning else QrScannerState.Idle
    }

    /** CameraX frame callback — decode only while scanning; first URI wins. */
    fun onFrame(data: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (_state.value !is QrScannerState.Scanning) return
        scope.launch {
            runCatching {
                val text = decoder(data, width, height, rotationDegrees) ?: return@launch
                val uri = PairUriExtractor.extractPairUri(text) ?: return@launch
                // Only transition while still scanning (a stale frame must not
                // restart the flow after Decoded → onConsumed).
                _state.compareAndSet(QrScannerState.Scanning, QrScannerState.Decoded(uri))
            }
        }
    }

    /** The screen consumed the URI (auto-filled + auto-started pairing). */
    fun onConsumed() {
        if (_state.value is QrScannerState.Decoded) _state.value = QrScannerState.Idle
    }

    /** App backgrounded — stop analysis (the composable unbinds the camera too). */
    fun onPause() {
        if (_state.value is QrScannerState.Scanning || _state.value is QrScannerState.Decoded) {
            _state.value = QrScannerState.Idle
        }
    }
}

/**
 * "Or scan the desktop QR" expandable card. Expanding requests CAMERA (the shared
 * permission-helper contract: explicit launcher + honest state — denial surfaces a
 * paste-fallback hint, never a dead camera view). When the scanner decodes a
 * pairing URI it calls [onDecoded] exactly once and returns to Idle.
 */
@Composable
fun QrScannerCard(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm = remember { QrScannerViewModel() }
    val state by vm.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        vm.onCameraPermissionResult(granted)
    }

    fun startScanning() {
        permissionDenied = false
        vm.onScanRequested()
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.onCameraPermissionResult(true)
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded) startScanning()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Or scan the desktop QR",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse scanner" else "Expand scanner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                when (val current = state) {
                    is QrScannerState.Idle -> ScannerHint(
                        if (permissionDenied) {
                            "Camera permission denied — grant it in Settings, or paste the URI above"
                        } else {
                            "Scanner paused — collapse and re-expand to retry"
                        }
                    )
                    is QrScannerState.RequestingCamera -> ScannerHint("Waiting for camera permission…")
                    is QrScannerState.Scanning -> QrCameraPreview(vm)
                    is QrScannerState.Decoded -> {
                        LaunchedEffect(current.uri) {
                            onDecoded(current.uri)
                            vm.onConsumed()
                        }
                        ScannerHint("QR decoded — pairing…")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QrCameraPreview(vm: QrScannerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy ->
            val payload = runCatching {
                val buffer = proxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                Triple(bytes, proxy.width, proxy.height)
            }.getOrNull()
            if (payload != null) {
                vm.onFrame(payload.first, payload.second, payload.third, proxy.imageInfo.rotationDegrees)
            }
            proxy.close()
        }
        runCatching {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { provider.unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(1f),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black,
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
    }
}
