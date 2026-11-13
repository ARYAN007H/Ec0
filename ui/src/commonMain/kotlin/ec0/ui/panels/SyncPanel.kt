/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 */
package ec0.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.widgets.SectionHeader

/**
 * Continuity features (clipboard / notifications / photos). Every card shows
 * the REAL live state from capabilities — an off feature says so honestly
 * with what turns it on, never fake data.
 */
@Composable
fun SyncPanel(bridge: UiBridge) {
    val caps = bridge.capabilities
    val settings by bridge.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Sync & continuity")

        FeatureCard(
            icon = Icons.Filled.ContentCopy,
            accent = ec0Colors().synergy,
            title = "Clipboard sync",
            live = caps.clipboardSync,
            onToggle = if (caps.clipboardSync) null else null,
            detail = if (caps.clipboardSync) {
                "Copy on one device, paste on the other — live"
            } else {
                "Needs the continuity service — desktop tray attached, phone service ships with the background-service slice"
            },
        )
        FeatureCard(
            icon = Icons.Filled.Notifications,
            accent = ec0Colors().localSend,
            title = "Notification relay",
            live = caps.notifications,
            detail = if (caps.notifications) {
                "Phone notifications mirrored to this device"
            } else {
                "Needs the notification-listener grant — arrives with the service slice"
            },
        )
        FeatureCard(
            icon = Icons.Filled.PhotoLibrary,
            accent = ec0Colors().kdeConnect,
            title = "Photo backup",
            live = caps.photoSync,
            detail = if (caps.photoSync) {
                "Camera roll syncs to the paired device automatically"
            } else {
                "Arrives with the photo-sync slice (Syncthing-backed, resumable)"
            },
        )
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    live: Boolean,
    detail: String,
    onToggle: (() -> Unit)? = null,
) {
    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(accent.copy(alpha = if (live) 0.28f else 0.12f), Ec0Shapes.tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Surface(
                        shape = CircleShape,
                        color = (if (live) ec0Colors().success else MaterialTheme.colorScheme.outline)
                            .copy(alpha = 0.15f),
                    ) {
                        Text(
                            if (live) "LIVE" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (live) ec0Colors().success else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(0.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onToggle != null) {
                Switch(checked = live, onCheckedChange = { onToggle() })
            }
        }
    }
}
