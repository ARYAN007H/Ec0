/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature A5: device management screen (Settings → "Paired devices"):
 *   - lists [PeerManagementBridge.peers]: alias, fingerprint prefix (human
 *     verification — the SAME prefix renders on the desktop's own screen),
 *     trust level, pinned-at, live-session state,
 *   - unpair (revokes the engine pin + LocalSend trust + best-effort wire
 *     unpair notice over a live session),
 *   - per-device capability grants (remote input, VFS read) persisted via the
 *     Android settings store.
 *
 * The shared :ui Settings panel renders a "Paired devices" card when the
 * platform bridge is non-null (Android only) and opens this screen.
 */

package ec0.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.PairedPeerInfo

/** A5 screen. [onClose] pops back to Settings. */
@Composable
fun PairedDevicesScreen(
    peers: List<PairedPeerInfo>,
    onUnpair: (String) -> Unit,
    onSetCapability: (deviceId: String, wireName: String, granted: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paired devices", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose, shape = RoundedCornerShape(10.dp)) { Text("Done") }
        }
        Text(
            "Verify the fingerprint prefix against the one shown on the desktop's own screen " +
                "before trusting a new pair. Unpairing revokes both ends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (peers.isEmpty()) {
            Text("No pinned peers", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(peers, key = { it.deviceId }) { peer ->
                    PairedPeerCard(
                        peer = peer,
                        onUnpair = { onUnpair(peer.deviceId) },
                        onSetCapability = { wireName, granted ->
                            onSetCapability(peer.deviceId, wireName, granted)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedPeerCard(
    peer: PairedPeerInfo,
    onUnpair: () -> Unit,
    onSetCapability: (wireName: String, granted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(peer.alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (peer.connected) "connected" else "offline",
                    style = MaterialTheme.typography.caption,
                    color = if (peer.connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "fingerprint: " + peer.fingerprintPrefix,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "trusted since " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(peer.pinnedAtMs)) +
                    " · " + peer.trustLevel,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CapabilityRow("Remote input", "remote_input", peer.grantedCapabilities, onSetCapability)
            CapabilityRow("VFS read", "vfs", peer.grantedCapabilities, onSetCapability)
            Button(onClick = onUnpair, shape = RoundedCornerShape(10.dp)) {
                Text("Unpair")
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    wireName: String,
    granted: Set<String>,
    onSetCapability: (wireName: String, granted: Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = wireName in granted,
            onCheckedChange = { checked -> onSetCapability(wireName, checked) },
        )
    }
}
