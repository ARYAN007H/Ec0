/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone First-Run Onboarding (`AndroidOnboardingScreen`):
 *   - Rendered when `trustedPeerCount == 0` (or while completing one-time setup
 *     right after pairing).
 *   - Follows Material 3 Expressive (`Ec0Theme`, `Ec0Shapes`, `LocalEc0Colors`)
 *     matching the design board's phone row:
 *       1. "Connect to PC" hero + Amber -> Cyan beacon-to-link bridge.
 *       2. Paste-code field (`ec0pair://…` / `ec0://pair?…`), Paste button, Pair button,
 *          and live non-blocking endpoint-walk progress indicator.
 *       3. One-time setup cards:
 *            - Battery exemption + ColorOS Auto-launch (`ColorOsBatteryHelper`)
 *            - Notification-listener access (`NotificationAccessHelper`)
 */
package ec0.android

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec0.core.engine.android.PairingStepState
import ec0.ui.theme.Ec0Shapes
import ec0.ui.theme.ec0Colors

@Composable
fun AndroidOnboardingScreen(
    state: AndroidAppState,
    onContinueToApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by state.settings.collectAsState()
    val pairingState by state.pairingState.collectAsState()
    val trustedPeerCount by state.trustedPeerCount.collectAsState()
    val pinnedPeers by state.pinnedPeers.collectAsState()

    var uriInput by rememberSaveable { mutableStateOf("") }
    var batteryExempted by remember {
        mutableStateOf(state.isBatteryOptimizationIgnored())
    }
    var notifGranted by remember {
        mutableStateOf(state.isNotificationListenerAccessGranted())
    }

    val isBusy = pairingState is PairingStepState.Parsing ||
        pairingState is PairingStepState.Pinned ||
        pairingState is PairingStepState.Connecting

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Eyebrow + Hero header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = Ec0Shapes.pill,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = "SETUP · PHONE COMPANION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Text(
                text = settings.alias,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Connect to PC.",
            style = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "Paste the pairing URI shown on your desktop's onboarding screen " +
                "(ec0pair://… or ec0://pair?…). Once paired, your PC and phone pin " +
                "each other with mutual Ed25519 keys and reconnect automatically on LAN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Expressive beacon -> link bridge indicator
        BeaconLinkBridgeRow(
            phoneAlias = settings.alias,
            pairedDesktopAlias = pinnedPeers.firstOrNull()?.alias,
        )

        // Card 1: Paste pairing code + Pair button
        Surface(
            shape = Ec0Shapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = Ec0Shapes.chip,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Step 1 · Pairing link",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Paste the one-time URI from your desktop",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = uriInput,
                    onValueChange = {
                        uriInput = it
                        if (pairingState is PairingStepState.Failed) {
                            state.resetPairingState()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    singleLine = false,
                    maxLines = 4,
                    shape = Ec0Shapes.tile,
                    // Pairing URIs are opaque base64url/hex — Gboard's autocorrect
                    // recomposes long unspaced runs and silently corrupts them
                    // (observed: `id=<64hex>` collapsed to `id=8pk=8`). No
                    // capitalization, no autocorrect, URI keyboard layout.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    label = { Text("Pairing URI (ec0pair://…)") },
                    placeholder = {
                        Text(
                            "ec0pair://v=1&id=…&pk=…&name=…&nonce=…&port=49880&ips=…",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val pasted = readClipboardPlainText(context)
                            if (!pasted.isNullOrBlank()) {
                                uriInput = pasted.trim()
                            }
                        },
                        enabled = !isBusy,
                        shape = Ec0Shapes.pill,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste pairing URI",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { state.pairWithUri(uriInput) },
                        enabled = uriInput.isNotBlank() && !isBusy,
                        shape = Ec0Shapes.pill,
                        modifier = Modifier.weight(1.3f),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pairing…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Pair with PC", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Live Pairing State feedback banner
                PairingStatusBanner(
                    pairingState = pairingState,
                    onRetry = {
                        if (uriInput.isNotBlank()) {
                            state.pairWithUri(uriInput)
                        }
                    },
                )
            }
        }

        // One-time setup section header
        Text(
            text = "ONE-TIME ANDROID SETUP",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Setup Card 1: ColorOS Battery Exemption & Auto-launch
        SetupPermissionCard(
            icon = Icons.Outlined.BatteryChargingFull,
            title = "Background battery exemption",
            subtitle = "Prevents ColorOS HansManager & OplusQualityProtect from freezing ec0 when your screen turns off.",
            isGranted = batteryExempted,
            grantedLabel = "Unrestricted",
            pendingLabel = "Recommended",
            primaryButtonLabel = if (batteryExempted) "Re-check battery settings" else "Allow background usage",
            onPrimaryClick = {
                state.requestBatteryOptimizationExemption()
                batteryExempted = state.isBatteryOptimizationIgnored()
            },
            secondaryButtonLabel = "ColorOS Auto-launch",
            onSecondaryClick = {
                state.openColorOsAutoLaunchSettings()
            },
        )

        // Setup Card 2: Notification Listener Access
        SetupPermissionCard(
            icon = Icons.Outlined.Notifications,
            title = "Notification relay access",
            subtitle = "Mirrors phone notifications to your Linux desktop with inline RemoteInput quick-replies.",
            isGranted = notifGranted,
            grantedLabel = "Relay active",
            pendingLabel = "Enable relay",
            primaryButtonLabel = if (notifGranted) "Manage listener access" else "Grant notification access",
            onPrimaryClick = {
                state.requestNotificationListenerAccess()
                notifGranted = state.isNotificationListenerAccessGranted()
            },
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Footer action: Enter app once trusted (or Explore unpaired)
        if (trustedPeerCount > 0) {
            Button(
                onClick = onContinueToApp,
                shape = Ec0Shapes.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Continue to ec0 ($trustedPeerCount trusted PC)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Surface(
                shape = Ec0Shapes.pill,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContinueToApp() },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "Explore unpaired",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun BeaconLinkBridgeRow(
    phoneAlias: String,
    pairedDesktopAlias: String?,
) {
    Surface(
        shape = Ec0Shapes.tile,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp).size(16.dp),
                    )
                }
                Text(
                    text = phoneAlias,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .height(3.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                        shape = Ec0Shapes.pill,
                    ),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pairedDesktopAlias ?: "Linux PC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pairedDesktopAlias != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingStatusBanner(
    pairingState: PairingStepState,
    onRetry: () -> Unit,
) {
    when (pairingState) {
        is PairingStepState.Idle -> Unit

        is PairingStepState.Parsing -> {
            Surface(
                shape = Ec0Shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Parsing pairing URI and verifying Ed25519 key fingerprint…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is PairingStepState.Pinned -> {
            Surface(
                shape = Ec0Shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Pinned ${pairingState.payload.alias} (TRUSTED_QR) · Starting endpoint walk…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is PairingStepState.Connecting -> {
            val hostPreview = pairingState.payload.ips.firstOrNull() ?: "mDNS"
            Surface(
                shape = Ec0Shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Column {
                        Text(
                            text = "Connecting to ${pairingState.payload.alias}…",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Walking $hostPreview:${pairingState.payload.port} (${pairingState.connectionState.name}) — this can take a few seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        is PairingStepState.Connected -> {
            Surface(
                shape = Ec0Shapes.tile,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = "Paired & connected to ${pairingState.payload.alias}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Pinned as TRUSTED_QR (${pairingState.payload.deviceId.take(12)}…)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        is PairingStepState.Failed -> {
            val pinned = pairingState.pinnedPayload
            Surface(
                shape = Ec0Shapes.tile,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (pinned != null) {
                                "Pinned ${pinned.alias} (TRUSTED_QR), but initial dial timed out"
                            } else {
                                "Invalid pairing URI"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = pairingState.message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onRetry,
                        shape = Ec0Shapes.pill,
                    ) {
                        Text("Retry connection", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    grantedLabel: String,
    pendingLabel: String,
    primaryButtonLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    val semanticColors = ec0Colors()
    Surface(
        shape = Ec0Shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = Ec0Shapes.chip,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = Ec0Shapes.pill,
                    color = if (isGranted) {
                        semanticColors.success.copy(alpha = 0.18f)
                    } else {
                        semanticColors.warning.copy(alpha = 0.18f)
                    },
                ) {
                    Text(
                        text = if (isGranted) grantedLabel else pendingLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isGranted) semanticColors.success else semanticColors.warning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onPrimaryClick,
                    shape = Ec0Shapes.pill,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(primaryButtonLabel, fontWeight = FontWeight.SemiBold)
                }
                if (secondaryButtonLabel != null && onSecondaryClick != null) {
                    OutlinedButton(
                        onClick = onSecondaryClick,
                        shape = Ec0Shapes.pill,
                    ) {
                        Text(secondaryButtonLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun readClipboardPlainText(context: Context): String? {
    return runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()
}
