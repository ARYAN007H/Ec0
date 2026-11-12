package ec0.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.desktop.state.ProtocolFamily
import ec0.desktop.state.UiDevice

/** Device list panel: discovered peers with protocol badges + rescan. */
@Composable
fun DeviceListPanel(
    devices: List<UiDevice>,
    scanning: Boolean,
    onRescan: () -> Unit,
    onSend: (UiDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", style = MaterialTheme.typography.h6)
            Spacer(Modifier.width(12.dp))
            if (scanning) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            Spacer(Modifier.weight(1f))
            Button(onClick = onRescan, enabled = !scanning) { Text("Rescan") }
        }
        Spacer(Modifier.height(12.dp))
        if (devices.isEmpty()) {
            EmptyDevices(scanning)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(device, onSend = { onSend(device) })
                }
            }
        }
    }
}

@Composable
private fun EmptyDevices(scanning: Boolean) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (scanning) "Scanning…" else "No devices yet",
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Devices appear when they announce on the LAN (UDP :53317 / :1716 / Synergy ports).",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun DeviceCard(device: UiDevice, onSend: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.alias, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                ProtocolBadge(device.protocol)
                if (!device.online) {
                    Spacer(Modifier.width(8.dp))
                    Text("offline", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSend, enabled = device.online) { Text("Send") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(device.deviceModel, device.deviceType?.wire).joinToString(" · ")
                    .ifEmpty { "unknown model" } + "  —  :${device.port}",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ProtocolBadge(family: ProtocolFamily) {
    val label = when (family) {
        ProtocolFamily.LOCALSEND -> "LocalSend"
        ProtocolFamily.SYNERGY -> "Synergy"
        ProtocolFamily.KDECONNECT -> "KDE Connect"
    }
    Text(
        label,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .border(
                1.dp, MaterialTheme.colors.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp),
            ),
    )
}
