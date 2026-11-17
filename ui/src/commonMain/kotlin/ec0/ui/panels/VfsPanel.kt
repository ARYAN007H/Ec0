/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B6 — read-only VFS browser panel: path bar → directory listing from the
 * phone's granted roots → per-file "Download here" (chunks land in the
 * desktop's download directory). Honest states: no session / errors / gaps.
 */
package ec0.ui.panels

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec0.ui.models.VfsBrowseState
import ec0.ui.models.UiBridge
import ec0.ui.widgets.SectionHeader

@Composable
fun VfsPanel(bridge: UiBridge) {
    val browseState by bridge.vfsBrowseState.collectAsState()
    var pathInput by remember { mutableStateOf("/") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Phone files", fontSize = 24.sp)
        Text(
            "Read-only · scoped to the roots the phone grants",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text("Path") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.FilledTonalButton(onClick = { bridge.vfsBrowse(pathInput) }) {
                Text("Browse")
            }
        }

        browseState?.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val path = browseState?.path
        if (path != null && path != "/") {
            androidx.compose.material3.TextButton(
                onClick = {
                    val parent = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/")
                    pathInput = parent
                    bridge.vfsBrowse(parent)
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Up")
            }
        }

        SectionHeader("Directory", badge = browseState?.entries?.size?.toString() ?: "0")

        if (browseState == null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Browse a path to list the phone's files",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(browseState!!.entries, key = { it.name }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(entry.name, modifier = Modifier.weight(1f))
                        if (!entry.isDir) {
                            Text(
                                formatSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.TextButton(
                                onClick = { val p = browseState!!.path ?: return@onClick; bridge.vfsDownload("${p.trimEnd('/')}/${entry.name}") },
                            ) {
                                Text("Download here")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)} MB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)} KB"
    else -> "$bytes B"
}
