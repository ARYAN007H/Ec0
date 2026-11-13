/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Shared expressive widgets used across all panels.
 */
package ec0.ui.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TabletMac
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.core.localsend.DeviceType
import ec0.ui.models.ProtocolFamily
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.protocolAccent

object DeviceTypeIcon {
    fun forDevice(device: ec0.ui.models.UiDevice): ImageVector = when (device.deviceType) {
        DeviceType.MOBILE -> Icons.Filled.PhoneAndroid
        DeviceType.WEB, DeviceType.HEADLESS -> Icons.Filled.TabletMac
        else -> Icons.Filled.Computer
    }
}

@Composable
fun ProtocolChip(protocol: ProtocolFamily, accent: Color) {
    Surface(
        shape = Ec0Shapes.chip,
        color = accent.copy(alpha = 0.15f),
    ) {
        Text(
            protocol.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SectionHeader(title: String, badge: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Animated expressive progress bar — sweeping shimmer on indeterminate feel. */
@Composable
fun Ec0Progress(value: Float, accent: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = tween(280),
        label = "transferProgress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp),
        color = accent,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = null,
    )
}

/** Compact transfer row used in the dashboard strip and the Transfers panel. */
@Composable
fun TransferRow(item: TransferItem, compact: Boolean, onCancel: (() -> Unit)?) {
    val accent = when (item.state) {
        TransferState.FAILED -> MaterialTheme.colorScheme.error
        TransferState.CANCELLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val indeterminate = item.state == TransferState.RUNNING && item.progressPct == 0
    val shimmer = if (indeterminate) {
        val t = rememberInfiniteTransition(label = "shimmer")
        val v by t.animateFloat(
            0f, 0.35f,
            infiniteRepeatable(tween(700, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "shimmerValue",
        )
        v
    } else 1f

    Surface(
        shape = Ec0Shapes.tile,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = Ec0Shapes.chip,
                    color = accent.copy(alpha = 0.14f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "${if (item.direction == TransferDirection.INCOMING) "from" else "to"} ${item.peerAlias}" +
                            (item.error?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(10.dp))
                StateBadge(item.state, item.progressPct)
                if (onCancel != null && item.state in setOf(TransferState.PENDING, TransferState.RUNNING)) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onCancel,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            "✕",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
            if (item.state == TransferState.RUNNING || item.state == TransferState.PENDING) {
                Spacer(Modifier.height(10.dp))
                Ec0Progress(item.progressPct / 100f * shimmer, accent)
            }
        }
    }
}

@Composable
private fun StateBadge(state: TransferState, progressPct: Int) {
    val (label, color) = when (state) {
        TransferState.DONE -> "done" to MaterialTheme.colorScheme.primary
        TransferState.FAILED -> "failed" to MaterialTheme.colorScheme.error
        TransferState.CANCELLED -> "cancelled" to MaterialTheme.colorScheme.outline
        TransferState.PENDING -> "queued" to MaterialTheme.colorScheme.secondary
        TransferState.RUNNING -> "$progressPct%" to MaterialTheme.colorScheme.primary
        TransferState.PAUSED -> "paused" to ec0.ui.theme.ec0Colors().warning
    }
    Surface(
        shape = Ec0Shapes.chip,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Horizontal strip of recent transfers (Home dashboard bottom). */
@Composable
fun TransferStrip(transfers: List<TransferItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        transfers.takeLast(3).asReversed().forEach { item ->
            TransferRow(item, compact = true, onCancel = null)
        }
    }
}

/** Big expressive empty-state card. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = Ec0Shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(18.dp).size(30.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
