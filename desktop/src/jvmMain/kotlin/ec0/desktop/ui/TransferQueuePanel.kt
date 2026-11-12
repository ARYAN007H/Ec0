package ec0.desktop.ui

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
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec0.desktop.state.TransferDirection
import ec0.desktop.state.TransferItem
import ec0.desktop.state.TransferState
import ec0.desktop.state.AppState

/** Transfer queue panel: direction, progress, per-item cancel, clear-finished. */
@Composable
fun TransferQueuePanel(
    transfers: List<TransferItem>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", style = MaterialTheme.typography.h6)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearFinished) { Text("Clear finished") }
        }
        Spacer(Modifier.height(8.dp))
        if (transfers.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No transfers",
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transfers, key = { it.id }) { item ->
                    TransferRow(item, onCancel = { onCancel(item.id) })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(item: TransferItem, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (item.direction) {
                    TransferDirection.INCOMING -> "↓"
                    TransferDirection.OUTGOING -> "↑"
                },
                style = MaterialTheme.typography.subtitle1,
                color = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(item.fileName, style = MaterialTheme.typography.body1, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("${formatSize(item.sizeBytes)}", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.width(12.dp))
            Text(item.peerAlias, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
            if (item.state in AppState.ACTIVE_STATES) {
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = item.progressPct / 100f,
                modifier = Modifier.weight(1f).height(6.dp),
            )
            Spacer(Modifier.width(12.dp))
            StateLabel(item)
        }
        item.error?.let { err ->
            Spacer(Modifier.height(2.dp))
            Text(err, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
        }
    }
}

@Composable
private fun StateLabel(item: TransferItem) {
    val (text, color) = when (item.state) {
        TransferState.PENDING -> "pending" to MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        TransferState.RUNNING -> "${item.progressPct}%" to MaterialTheme.colors.primary
        TransferState.PAUSED -> "paused" to MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        TransferState.DONE -> "done" to MaterialTheme.colors.primaryVariant
        TransferState.FAILED -> "failed" to MaterialTheme.colors.error
        TransferState.CANCELLED -> "cancelled" to MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    }
    Text(text, style = MaterialTheme.typography.caption, color = color)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}
