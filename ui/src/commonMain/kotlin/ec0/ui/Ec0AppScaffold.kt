/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The full-suite app shell: five sections (Home, Transfers, Share, Sync,
 * Settings) over one shared implementation. Rail on wide screens, bottom bar
 * on phones — same components, same motion, same product.
 */
package ec0.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import ec0.ui.models.UiBridge
import ec0.ui.panels.HomePanel
import ec0.ui.panels.SettingsPanel
import ec0.ui.panels.SharePanel
import ec0.ui.panels.SyncPanel
import ec0.ui.panels.TransfersPanel
import ec0.ui.theme.Ec0Theme

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    TRANSFERS("Transfers", Icons.Filled.SwapHoriz),
    SHARE("Share", Icons.Filled.Share),
    SYNC("Sync", Icons.Filled.Sync),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * Root shell. [pickFile] delegates to the platform picker; the host calls
 * back into [UiBridge.sendFile] with an opaque handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ec0App(
    bridge: UiBridge,
    pickFile: (onPicked: (Any?) -> Unit) -> Unit,
    compact: Boolean,
) {
    val dark = bridge.settings.collectAsState().value.darkTheme
    val systemDark = isSystemInDarkTheme()
    Ec0Theme(dark = dark ?: systemDark) {
        var section by remember { mutableStateOf(Section.HOME) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (compact) {
                Column {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        SectionContent(section, bridge, pickFile)
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Section.entries.forEach { s ->
                            NavigationBarItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = s.label) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                }
            } else {
                Row {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        header = { RailBrand() },
                    ) {
                        Section.entries.forEach { s ->
                            NavigationRailItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = s.label) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().weight(1f)) {
                        SectionContent(section, bridge, pickFile)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailBrand() {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "ec0",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionContent(section: Section, bridge: UiBridge, pickFile: (onPicked: (Any?) -> Unit) -> Unit) {
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            (slideInHorizontally(tween(260)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(220)))
                .togetherWith(
                    slideOutHorizontally(tween(260)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(180)),
                )
        },
        label = "section",
    ) { s ->
        when (s) {
            Section.HOME -> HomePanel(bridge, pickFile)
            Section.TRANSFERS -> TransfersPanel(bridge)
            Section.SHARE -> SharePanel(bridge, pickFile)
            Section.SYNC -> SyncPanel(bridge)
            Section.SETTINGS -> SettingsPanel(bridge)
        }
    }
}
