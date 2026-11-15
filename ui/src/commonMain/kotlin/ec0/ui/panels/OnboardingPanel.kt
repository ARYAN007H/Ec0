/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * First-run onboarding (ec0↔ec0 pairing): this end shows the engine pairing QR,
 * the phone's in-app scanner captures it, both sides pin TRUSTED_QR once. Shown
 * as the HOME surface until a peer is trusted — or until the user explores
 * unpaired. M3 Expressive per the design board (Desktop — first run).
 */
package ec0.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import ec0.ui.models.UiBridge
import ec0.ui.widgets.QrCodeCanvas

@Composable
fun OnboardingPanel(
    bridge: UiBridge,
    onExploreUnpaired: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val qr by bridge.pairingQr.collectAsState()
    val alias = bridge.settings.collectAsState().value.alias

    if (compact) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            QrStage(alias, qr, 252.dp)
            OnboardingCopy(compact)
            ExploreButton(onExploreUnpaired, Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 56.dp, vertical = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(52.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                OnboardingCopy(compact = false)
                Spacer(modifier = Modifier.height(26.dp))
                ExploreButton(
                    onExploreUnpaired,
                    Modifier.width(230.dp),
                )
            }
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                QrStage(alias, qr, 264.dp)
            }
        }
    }
}

/** Eyebrow, headline, body, the three steps, and the TLS footer. */
@Composable
private fun OnboardingCopy(compact: Boolean) {
    Surface(
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            "SETUP · ONE STEP",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))
    Text(
        "Connect your phone.",
        style = TextStyle(
            fontSize = if (compact) 32.sp else 38.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        "After this one scan, the phone and this device behave like one machine — " +
            "files land where they should, the clipboard follows you, notifications " +
            "show up where you are.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(if (compact) 14.dp else 20.dp))
    val steps = listOf(
        "Open ec0 on the phone",
        "Tap Connect to PC",
        "Scan. Trust is one tap, once.",
    )
    steps.forEachIndexed { index, step ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(100),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
    Spacer(modifier = Modifier.height(if (compact) 10.dp else 22.dp))
    Text(
        "The code carries a one-time key that expires in 2 minutes. Nothing leaves " +
            "your network — pairing is TLS-pinned and signed.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** White QR panel + refresh chip + the beacon→link bridge to a phone glyph. */
@Composable
private fun QrStage(alias: String, qr: ec0.ui.models.PairingQr?, panelSize: androidx.compose.ui.unit.Dp) {
    Text(
        "THIS PC · $alias",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(14.dp))
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.size(panelSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (qr != null) {
                QrCodeCanvas(
                    modules = qr.modules,
                    size = qr.size,
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                )
            } else {
                Text(
                    "Starting pairing\nservice…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
    Surface(
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                "Code refreshes automatically",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Spacer(modifier = Modifier.height(18.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(78.dp)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                    shape = RoundedCornerShape(100),
                ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.tertiary,
            ),
        ) {
            Icon(
                Icons.Outlined.Smartphone,
                contentDescription = "the phone pairs from here",
                modifier = Modifier.padding(7.dp).size(16.dp),
            )
        }
    }
}

@Composable
private fun ExploreButton(onExploreUnpaired: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.clickable { onExploreUnpaired() },
    ) {
        Text(
            "Explore unpaired",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}
