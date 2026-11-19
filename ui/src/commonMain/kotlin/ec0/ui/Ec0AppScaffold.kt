/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The full-suite app shell: five sections (Home, Transfers, Share, Sync,
 * Settings) over one shared M3 Expressive implementation. Rail on wide screens,
 * bottom bar on phones — same components, same spring motion, same product.
 */
package ec0.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ec0.ui.models.TransferState
import ec0.ui.models.UiBridge
import ec0.ui.panels.HomePanel
import ec0.ui.panels.OnboardingPanel
import ec0.ui.panels.SettingsPanel
import ec0.ui.panels.SharePanel
import ec0.ui.panels.SyncPanel
import ec0.ui.panels.TransfersPanel
import ec0.ui.theme.Ec0Motion
import ec0.ui.theme.Ec0Seed
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.Ec0Theme
import ec0.ui.theme.ec0Colors

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    TRANSFERS("Transfers", Icons.Filled.SwapHoriz),
    SHARE("Files", Icons.Filled.Share),
    SYNC("Continuity", Icons.Filled.Sync),
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
    val settings by bridge.settings.collectAsState()
    val transfers by bridge.transfers.collectAsState()
    val peerLink by bridge.peerLink.collectAsState()
    val dark = settings.darkTheme
    val systemDark = isSystemInDarkTheme()
    val trustedPeerCount by bridge.trustedPeerCount.collectAsState()
    val pairingQrAvailable = bridge.pairingQr.collectAsState().value != null
    var onboardingDismissed by remember { mutableStateOf(false) }

    val activeTransferCount = transfers.count {
        it.state == TransferState.RUNNING || it.state == TransferState.PENDING
    }
    val showOnboarding = trustedPeerCount == 0 && pairingQrAvailable && !onboardingDismissed

    Ec0Theme(dark = dark ?: systemDark, seed = Ec0Seed.fromId(settings.themeSeed)) {
        var section by remember { mutableStateOf(Section.HOME) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (compact) {
                Column {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (showOnboarding && section == Section.HOME) {
                            OnboardingPanel(
                                bridge,
                                onExploreUnpaired = { onboardingDismissed = true },
                                compact = true,
                            )
                        } else {
                            SectionContent(section, bridge, pickFile, compact = true)
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Section.entries.forEach { s ->
                            NavigationBarItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { SectionIcon(s, activeTransferCount) },
                                label = { Text(s.label, maxLines = 1) },
                            )
                        }
                    }
                }
            } else {
                Row {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        header = { RailBrand(connected = peerLink?.connected == true) },
                    ) {
                        Spacer(Modifier.height(8.dp))
                        Section.entries.forEach { s ->
                            NavigationRailItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { SectionIcon(s, activeTransferCount) },
                                label = { Text(s.label, maxLines = 1) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().weight(1f)) {
                        if (showOnboarding && section == Section.HOME) {
                            OnboardingPanel(
                                bridge,
                                onExploreUnpaired = { onboardingDismissed = true },
                                compact = false,
                            )
                        } else {
                            SectionContent(section, bridge, pickFile, compact = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionIcon(section: Section, activeTransferCount: Int) {
    if (section == Section.TRANSFERS && activeTransferCount > 0) {
        BadgedBox(badge = { Badge { Text(activeTransferCount.toString()) } }) {
            Icon(section.icon, contentDescription = section.label)
        }
    } else {
        Icon(section.icon, contentDescription = section.label)
    }
}

@Composable
private fun RailBrand(connected: Boolean) {
    Column(
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = Ec0Shapes.tile,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "ec0",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (connected) ec0Colors().success else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun SectionContent(
    section: Section,
    bridge: UiBridge,
    pickFile: (onPicked: (Any?) -> Unit) -> Unit,
    compact: Boolean,
) {
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            val slideSpec = Ec0Motion.snappy<IntOffset>()
            val fadeSpec = Ec0Motion.snappy<Float>()
            (slideInHorizontally(slideSpec) { if (forward) it / 8 else -it / 8 } + fadeIn(fadeSpec))
                .togetherWith(
                    slideOutHorizontally(slideSpec) { if (forward) -it / 8 else it / 8 } + fadeOut(fadeSpec),
                )
        },
        label = "section",
    ) { s ->
        when (s) {
            Section.HOME -> HomePanel(bridge, pickFile, compact)
            Section.TRANSFERS -> TransfersPanel(bridge)
            Section.SHARE -> SharePanel(bridge, pickFile)
            Section.SYNC -> SyncPanel(bridge)
            Section.SETTINGS -> SettingsPanel(bridge)
        }
    }
}
