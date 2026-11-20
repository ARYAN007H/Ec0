/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #9: Spotlight-style Quick Command Palette (`Ctrl+K` / Search button).
 * Feature #12: Searchable 20-item Encrypted Clipboard Shelf + Arrival Undo Toast.
 */
package ec0.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ec0.ui.models.ClipboardToastUi
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes

private data class PaletteAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val run: () -> Unit,
)

@Composable
fun CommandPaletteOverlay(
    bridge: UiBridge,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val actions = remember(bridge) {
        listOf(
            PaletteAction(
                id = "otp",
                title = "Auto-Type Latest 2FA OTP",
                subtitle = "Type most recent 6-digit verification code from phone",
                keywords = "otp 2fa code verify password",
                run = { bridge.autoTypeLatestOtp() },
            ),
            PaletteAction(
                id = "photo",
                title = "Insert Photo from Phone",
                subtitle = "Capture photo on phone camera and insert on PC",
                keywords = "insert photo camera picture continuity",
                run = { bridge.requestPhoneCapture("photo") },
            ),
            PaletteAction(
                id = "scan",
                title = "Scan Document from Phone",
                subtitle = "Capture document scan as PDF directly to desktop",
                keywords = "scan pdf document insert",
                run = { bridge.requestPhoneCapture("document_scan") },
            ),
            PaletteAction(
                id = "sketch",
                title = "Insert Sketch from Phone",
                subtitle = "Draw on phone touchscreen and insert onto PC",
                keywords = "sketch draw stylus insert",
                run = { bridge.requestPhoneCapture("sketch") },
            ),
            PaletteAction(
                id = "audio_sink",
                title = "Toggle Phone-to-PC Audio Sink",
                subtitle = "Stream phone audio to PC speakers via PipeWire",
                keywords = "audio airplay speaker sink music",
                run = { bridge.togglePhoneAudioSink() },
            ),
            PaletteAction(
                id = "call_audio",
                title = "Toggle Full-Duplex Call Audio on PC",
                subtitle = "Route active phone call through PC headset & mic",
                keywords = "call audio headset mic phone",
                run = { bridge.toggleCallAudioHandoff() },
            ),
            PaletteAction(
                id = "sidecar",
                title = "Toggle Wireless Second Display (Sidecar)",
                subtitle = "Extend desktop onto paired Android tablet or phone",
                keywords = "sidecar second display monitor tablet stylus",
                run = { bridge.toggleSidecarDisplay() },
            ),
            PaletteAction(
                id = "focus_dnd",
                title = "Toggle Focus / Do Not Disturb",
                subtitle = "Silence notifications across Linux PC and Android",
                keywords = "focus dnd mute meeting silent",
                run = {
                    val next = if (bridge.focusMode.value == "off") "dnd" else "off"
                    bridge.setFocusMode(next)
                },
            ),
            PaletteAction(
                id = "webdav",
                title = "Mount / Unmount Phone Storage (~/Phone)",
                subtitle = "Loopback authenticated WebDAV mount for Dolphin/Nautilus",
                keywords = "mount vfs webdav phone storage files",
                run = { bridge.toggleWebDavPhoneMount() },
            ),
            PaletteAction(
                id = "turbo",
                title = "Wi-Fi Direct 5 GHz Turbo Channel",
                subtitle = "Upgrade transfer link to direct high-speed P2P channel",
                keywords = "wifi direct turbo failover speed",
                run = { bridge.toggleWifiDirectTurbo() },
            ),
            PaletteAction(
                id = "ring",
                title = "Find My Phone (Ring Siren)",
                subtitle = "Play full-volume chime on paired Android device",
                keywords = "find ring locate siren alarm",
                run = { bridge.triggerFindMyPhone(true) },
            ),
            PaletteAction(
                id = "mirror",
                title = "Start Phone Screen Mirror",
                subtitle = "Mirror Android display over encrypted link",
                keywords = "mirror scrcpy screen phone",
                run = { bridge.startScreenMirror() },
            ),
            PaletteAction(
                id = "diagnose",
                title = "Run System Health Diagnostics",
                subtitle = "Check mDNS, uinput, PipeWire, v4l2loopback, keystore",
                keywords = "doctor health diagnose fix bundle",
                run = { bridge.runHealthDiagnostics() },
            ),
            PaletteAction(
                id = "bundle",
                title = "Export PII-Redacted Support Bundle",
                subtitle = "Write scrubbed diagnostics JSON to ~/.config/ec0/diagnostics",
                keywords = "export support logs redact privacy",
                run = { bridge.exportRedactedSupportBundle() },
            ),
        )
    }

    val filtered = remember(query, actions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) actions
        else actions.filter {
            it.title.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable { onDismiss() }
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = Ec0Shapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(top = 36.dp)
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Quick Command Palette",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close command palette")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a command (e.g. OTP, Photo, Sidecar, Focus, Mount)...") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    filtered.take(8).forEachIndexed { idx, action ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    action.run()
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    action.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (idx < filtered.take(8).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "No matching continuity commands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClipboardArrivalToast(
    toast: ClipboardToastUi,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = Ec0Shapes.pill,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                toast.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (toast.canUndo) {
                TextButton(onClick = onUndo) {
                    Icon(
                        Icons.Filled.Undo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inversePrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun ClipboardShelfCard(bridge: UiBridge) {
    val entries by bridge.clipboardShelf.collectAsState()
    var search by remember { mutableStateOf("") }
    val filtered = remember(entries, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter {
            it.preview.lowercase().contains(q) || it.sourceDevice.lowercase().contains(q)
        }
    }

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Clipboard Shelf", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "20-item encrypted history · sensitive OTPs auto-scrub in 30s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { bridge.pushClipboardNow() }, shape = Ec0Shapes.pill) {
                    Text("Sync Now")
                }
            }

            if (entries.isNotEmpty()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search clipboard shelf...") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (filtered.isEmpty()) {
                Text(
                    "Copy text on either device to populate your cross-device shelf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filtered.take(8).forEachIndexed { idx, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (entry.sensitive) "•••••••• (sensitive · 30s TTL)" else entry.preview,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${entry.sourceDevice}${if (entry.pinned) " · Pinned" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { bridge.toggleClipboardPin(entry.id) }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = "Pin",
                                tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { bridge.copyClipboardShelfEntry(entry.id) }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (idx < filtered.take(8).lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}
