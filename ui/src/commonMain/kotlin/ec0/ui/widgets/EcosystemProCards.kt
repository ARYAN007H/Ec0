/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Shared Material 3 Expressive cards surfacing Features #1..#15 cleanly
 * across desktop and mobile hosts via `UiBridge`.
 */
package ec0.ui.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors

@Composable
fun ContinuityStudioAndCaptureCard(bridge: UiBridge) {
    val audioSinkActive by bridge.audioSinkActive.collectAsState()
    val callAudioOnPc by bridge.callAudioOnPc.collectAsState()
    val pendingOtp by bridge.pendingOtpCode.collectAsState()
    val activeTab by bridge.activeBrowserTabHandoff.collectAsState()
    val cameraFx by bridge.cameraEffects.collectAsState()

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Continuity Capture, Audio & Studio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Feature #5: Pending 2FA OTP Auto-Type Banner
            pendingOtp?.let { code ->
                Surface(
                    shape = Ec0Shapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            "2FA OTP ready ($code) · expires in 60s",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = bridge::autoTypeLatestOtp, shape = Ec0Shapes.pill) {
                            Text("Auto-Type")
                        }
                    }
                }
            }

            // Feature #7: Passive Browser Tab & Video Timestamp Resume Banner
            activeTab?.let { tab ->
                Surface(
                    shape = Ec0Shapes.chip,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { tab.url },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val tsText = if (tab.mediaPositionSec > 0L) "Resume at ${tab.mediaPositionSec}s" else tab.url
                            Text(
                                tsText,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(onClick = bridge::openActiveBrowserTabHandoff, shape = Ec0Shapes.pill) {
                            Text("Resume")
                        }
                    }
                }
            }

            // Feature #2: Insert from Phone
            Text("Insert from Phone", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                FilledTonalButton(onClick = { bridge.requestPhoneCapture("photo") }, shape = Ec0Shapes.pill) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Take Photo")
                }
                FilledTonalButton(onClick = { bridge.requestPhoneCapture("document_scan") }, shape = Ec0Shapes.pill) {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Document")
                }
                FilledTonalButton(onClick = { bridge.requestPhoneCapture("sketch") }, shape = Ec0Shapes.pill) {
                    Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Insert Sketch")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Feature #1: Audio Handoff & AirPlay-style Sink
            Text("Desktop Audio Handoff", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                FilledTonalButton(
                    onClick = bridge::togglePhoneAudioSink,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (audioSinkActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Speaker, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (audioSinkActive) "Audio Sink: ON" else "Phone Audio Sink")
                }
                FilledTonalButton(
                    onClick = bridge::toggleCallAudioHandoff,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (callAudioOnPc) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.HeadsetMic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (callAudioOnPc) "Call Audio on PC" else "Route Call to PC")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Feature #11: Continuity Camera Studio Effects + Adaptive Bitrate
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Camera Studio Effects (${cameraFx.targetBitrateKbps / 1000} Mbps adaptive)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                FilledTonalButton(
                    onClick = bridge::toggleCameraCenterStage,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (cameraFx.centerStage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Text("Center Stage")
                }
                FilledTonalButton(
                    onClick = bridge::toggleCameraPortraitBlur,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (cameraFx.portraitBlur) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Text("Portrait Blur")
                }
                FilledTonalButton(
                    onClick = bridge::toggleCameraDeskView,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (cameraFx.deskView) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Text("Desk View")
                }
            }
        }
    }
}

@Composable
fun FocusSidecarAndTurboCard(bridge: UiBridge) {
    val focusMode by bridge.focusMode.collectAsState()
    val meetingAutoFocus by bridge.meetingAutoFocusEnabled.collectAsState()
    val sidecarActive by bridge.sidecarActive.collectAsState()
    val turboActive by bridge.wifiDirectTurboActive.collectAsState()

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DoNotDisturbOn, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Focus Sync, Sidecar & Turbo Link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Feature #6: Focus Mode pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf("off" to "Off", "dnd" to "Do Not Disturb", "work" to "Work", "meeting" to "Meeting", "sleep" to "Sleep").forEach { (mode, label) ->
                    val selected = focusMode.equals(mode, ignoreCase = true)
                    FilledTonalButton(
                        onClick = { bridge.setFocusMode(mode) },
                        shape = Ec0Shapes.pill,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Text(label)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("PipeWire Meeting Auto-Focus", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Automatically silences your phone whenever a PC mic stream is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = meetingAutoFocus, onCheckedChange = bridge::setMeetingAutoFocusEnabled)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Feature #4 Sidecar + Feature #8 Wi-Fi Direct Turbo
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                FilledTonalButton(
                    onClick = bridge::toggleSidecarDisplay,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (sidecarActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (sidecarActive) "Sidecar Display: ON" else "Extend Display (Sidecar)")
                }
                FilledTonalButton(
                    onClick = bridge::toggleWifiDirectTurbo,
                    shape = Ec0Shapes.pill,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (turboActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (turboActive) "Wi-Fi Direct Turbo: ON" else "Wi-Fi Direct 5 GHz Turbo")
                }
            }
        }
    }
}

@Composable
fun SmsInboxCard(bridge: UiBridge) {
    val threads by bridge.smsThreads.collectAsState()
    var address by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("SMS Messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = bridge::requestSmsSync, shape = Ec0Shapes.pill) {
                    Text("Sync")
                }
            }
            if (threads.isEmpty()) {
                Text(
                    "Sync SMS threads from your paired Android phone or compose a quick text below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                threads.take(5).forEachIndexed { idx, thread ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "${thread.displayName.ifBlank { thread.address }} (${thread.messageCount})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            thread.lastBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (idx < threads.take(5).lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(32) },
                    placeholder = { Text("+15551234567") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.weight(0.42f),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(500) },
                    placeholder = { Text("Message...") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.weight(0.58f),
                )
                FilledTonalButton(
                    onClick = {
                        if (address.isNotBlank() && body.isNotBlank()) {
                            bridge.sendSmsMessage(address.trim(), body.trim())
                            body = ""
                        }
                    },
                    shape = Ec0Shapes.pill,
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun HealthDiagnosticsCard(bridge: UiBridge) {
    val keystoreLabel by bridge.keystoreBackendLabel.collectAsState()
    val checks by bridge.diagnosticChecks.collectAsState()

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.HealthAndSafety, contentDescription = null, tint = ec0Colors().success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Health Center & Hardware Key Sealing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Keystore backend: $keystoreLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = bridge::runHealthDiagnostics, shape = Ec0Shapes.pill) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run Diagnostics")
                }
                FilledTonalButton(onClick = bridge::exportRedactedSupportBundle, shape = Ec0Shapes.pill) {
                    Text("Export Redacted Bundle")
                }
            }

            checks.forEach { check ->
                Surface(
                    shape = Ec0Shapes.chip,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "[${check.severity}] ${check.title}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            check.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (check.fixHint.isNotBlank()) {
                            Text(
                                "Fix: ${check.fixHint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
