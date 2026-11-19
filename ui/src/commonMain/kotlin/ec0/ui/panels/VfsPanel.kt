/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone file browser (VFS): scoped read-only directory listing and one-tap
 * download from the paired phone's granted storage roots.
 *
 * Layout note: renders inside an intrinsic-height Column (never a nested
 * fillMaxSize LazyColumn) so embedding inside SharePanel's vertical scroll
 * never hits Compose infinite-height constraint crashes.
 */
package ec0.ui.panels

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import ec0.ui.models.UiBridge
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors
import ec0.ui.widgets.formatByteSize

@Composable
fun VfsPanel(bridge: UiBridge) {
    val browseState by bridge.vfsBrowseState.collectAsState()
    var pathInput by remember { mutableStateOf("/") }

    val navigateTo: (String) -> Unit = { next ->
        val normalized = next.trim().ifEmpty { "/" }
        pathInput = normalized
        bridge.vfsBrowse(normalized)
    }

    Card(
        shape = Ec0Shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Phone storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Browse & pull files from granted folders over your encrypted link",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                browseState?.entries?.let { entries ->
                    Surface(shape = Ec0Shapes.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            "${entries.size} item(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Quick root pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf("/" to "Roots", "/Downloads" to "Downloads", "/DCIM" to "Camera", "/Documents" to "Documents", "/Pictures" to "Pictures").forEach { (rootPath, label) ->
                    val active = browseState?.path == rootPath
                    Surface(
                        onClick = { navigateTo(rootPath) },
                        shape = Ec0Shapes.pill,
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("Path") },
                    singleLine = true,
                    shape = Ec0Shapes.chip,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(onClick = { navigateTo(pathInput) }, shape = Ec0Shapes.pill) {
                    Text("Browse")
                }
            }

            browseState?.error?.let { error ->
                Surface(
                    shape = Ec0Shapes.chip,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            browseState?.lastDownloadedPath?.let { saved ->
                Surface(
                    shape = Ec0Shapes.chip,
                    color = ec0Colors().success.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ec0Colors().success, modifier = Modifier.size(16.dp))
                        Text(
                            "Saved to $saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val currentPath = browseState?.path
            if (currentPath != null && currentPath != "/") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    TextButton(
                        onClick = {
                            val parent = currentPath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
                            navigateTo(parent)
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Up")
                    }
                    val crumbs = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                    var accum = ""
                    crumbs.forEach { segment ->
                        accum = "$accum/$segment"
                        val target = accum
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        TextButton(onClick = { navigateTo(target) }) {
                            Text(segment, maxLines = 1)
                        }
                    }
                }
            }

            val state = browseState
            if (state == null) {
                Surface(
                    shape = Ec0Shapes.tile,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Tap a folder above (like Roots or Downloads) to explore your paired phone's files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else if (state.entries.isEmpty()) {
                Surface(
                    shape = Ec0Shapes.tile,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "This folder is empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.entries.forEachIndexed { idx, entry ->
                        val childPath = if (currentPath == null || currentPath == "/") {
                            "/${entry.name}"
                        } else {
                            "${currentPath.trimEnd('/')}/${entry.name}"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isDir) { navigateTo(childPath) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Icon(
                                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                contentDescription = null,
                                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.isDir) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "Open folder", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                            } else {
                                Text(
                                    formatByteSize(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { bridge.vfsDownload(childPath) }) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pull")
                                }
                            }
                        }
                        if (idx < state.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }
}
