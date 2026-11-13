/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.models.TransferDirection
import ec0.ui.widgets.EmptyState
import ec0.ui.widgets.SectionHeader
import ec0.ui.widgets.TransferRow

@Composable
fun TransfersPanel(bridge: UiBridge) {
    val transfers by bridge.transfers.collectAsState()
    val incoming = transfers.count { it.direction == TransferDirection.INCOMING }
    val active = transfers.count { it.state == ec0.ui.models.TransferState.RUNNING }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(
            "Transfers",
            badge = transfers.size.toString(),
        )
        Text(
            "$active running · $incoming incoming · ${transfers.size - incoming} outgoing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (transfers.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SwapHoriz,
                title = "No transfers yet",
                hint = "Files you send or receive show up here with live progress",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(transfers.asReversed(), key = { it.id }) { item ->
                    TransferRow(item, compact = false, onCancel = { bridge.cancelTransfer(item.id) })
                }
            }
        }
    }
}
