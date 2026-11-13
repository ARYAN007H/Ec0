/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Production-Grade Continuity Design System & Reusable Compose Primitives
 * (`ec0.desktop.continuity`):
 *   - 60-30-10 Color Discipline with complete Dark Slate (`#0B1120`) and Crisp
 *     Light (`#F8FAFC`) semantic palettes (`ContinuityPalette`, `ContinuityTheme`).
 *   - Single-Elevation Depth: flat 1px hairline-bordered surfaces (`elevation = 0.dp`)
 *     with zero nested card shadows.
 *   - Zero-Pill Metadata Discipline: informational metadata rendered as unboxed
 *     inline text with `·` separators; interactive segmented controls (`SegmentedOptionBar`)
 *     for functional state selection.
 *   - Tabular Monospace Figures (`FontFamily.Monospace`) for telemetry, SAS codes,
 *     byte counters, and network ports.
 */
package ec0.desktop.continuity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Colors
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class ContinuityPalette(
    val isDark: Boolean,
    val canvasBackground: Color,
    val surfacePrimary: Color,
    val surfaceSubtle: Color,
    val borderHairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentPrimary: Color,
    val accentOnPrimary: Color,
    val statusNominal: Color,
    val statusWarning: Color,
    val statusDanger: Color,
) {
    fun toMaterialColors(): Colors = if (isDark) {
        darkColors(
            primary = accentPrimary,
            primaryVariant = accentPrimary,
            secondary = statusNominal,
            background = canvasBackground,
            surface = surfacePrimary,
            error = statusDanger,
            onPrimary = accentOnPrimary,
            onSecondary = Color.White,
            onBackground = textPrimary,
            onSurface = textPrimary,
            onError = Color.White,
        )
    } else {
        lightColors(
            primary = accentPrimary,
            primaryVariant = accentPrimary,
            secondary = statusNominal,
            background = canvasBackground,
            surface = surfacePrimary,
            error = statusDanger,
            onPrimary = accentOnPrimary,
            onSecondary = Color.White,
            onBackground = textPrimary,
            onSurface = textPrimary,
            onError = Color.White,
        )
    }

    companion object {
        val Dark = ContinuityPalette(
            isDark = true,
            canvasBackground = Color(0xFF0B1120),
            surfacePrimary = Color(0xFF111827),
            surfaceSubtle = Color(0xFF1E293B),
            borderHairline = Color(0xFF26334D),
            textPrimary = Color(0xFFF1F5F9),
            textSecondary = Color(0xFFCBD5E1),
            textMuted = Color(0xFF94A3B8),
            accentPrimary = Color(0xFF3B82F6),
            accentOnPrimary = Color(0xFFFFFFFF),
            statusNominal = Color(0xFF10B981),
            statusWarning = Color(0xFFF59E0B),
            statusDanger = Color(0xFFEF4444),
        )

        val Light = ContinuityPalette(
            isDark = false,
            canvasBackground = Color(0xFFF8FAFC),
            surfacePrimary = Color(0xFFFFFFFF),
            surfaceSubtle = Color(0xFFF1F5F9),
            borderHairline = Color(0xFFE2E8F0),
            textPrimary = Color(0xFF0F172A),
            textSecondary = Color(0xFF334155),
            textMuted = Color(0xFF64748B),
            accentPrimary = Color(0xFF2563EB),
            accentOnPrimary = Color(0xFFFFFFFF),
            statusNominal = Color(0xFF16A34A),
            statusWarning = Color(0xFFD97706),
            statusDanger = Color(0xFFDC2626),
        )
    }
}

val LocalContinuityPalette = staticCompositionLocalOf { ContinuityPalette.Dark }

enum class ContinuityStudioTab(val label: String) {
    OVERVIEW("Overview"),
    CAMERA_STUDIO("Camera Studio"),
    DISPLAY_INPUT("Display & Input"),
    AIRDROP_CLIPBOARD("AirDrop & Clipboard"),
    SYSTEM_SECURITY("System & Security"),
}

@Composable
fun ContinuityTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) ContinuityPalette.Dark else ContinuityPalette.Light
    CompositionLocalProvider(LocalContinuityPalette provides palette) {
        MaterialTheme(
            colors = palette.toMaterialColors(),
            content = content,
        )
    }
}

/**
 * Single-elevation flat surface container with a 1px hairline border, clean
 * section header, unboxed `·`-separated metadata subtitle, and optional action slot.
 */
@Composable
fun ContinuitySectionSurface(
    title: String,
    metadataItems: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalContinuityPalette.current
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, palette.borderHairline, shape),
        shape = shape,
        color = palette.surfacePrimary,
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metadataItems.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        UnboxedMetadataRow(items = metadataItems)
                    }
                }
                if (headerActions != null) {
                    Spacer(Modifier.width(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = headerActions,
                    )
                }
            }
            Divider(color = palette.borderHairline, thickness = 1.dp)
            content()
        }
    }
}

/**
 * Renders informational metadata as quiet unboxed inline text separated by ` · `
 * (Zero-Pill Metadata Discipline).
 */
@Composable
fun UnboxedMetadataRow(
    items: List<String>,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalContinuityPalette.current
    val filtered = items.filter { it.isNotBlank() }
    if (filtered.isEmpty()) return
    Text(
        text = filtered.joinToString("  ·  "),
        color = palette.textMuted,
        fontSize = 12.sp,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Clean interactive segmented control bar (`<button>` equivalent) for switching
 * modes, filters, resolutions, codecs, or screen edges.
 */
@Composable
fun <T> SegmentedOptionBar(
    options: List<T>,
    selected: T,
    labelSelector: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalContinuityPalette.current
    val outerShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .background(palette.surfaceSubtle, outerShape)
            .border(1.dp, palette.borderHairline, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            val isSelected = option == selected
            val itemShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(itemShape)
                    .background(
                        if (isSelected) palette.accentPrimary else Color.Transparent,
                        itemShape,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelSelector(option),
                    color = if (isSelected) palette.accentOnPrimary else palette.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * High-density metric cell with monospace tabular figures for aligned numerical
 * readouts (battery %, frame rate, transfer throughput, SAS codes).
 */
@Composable
fun TabularMetricCell(
    label: String,
    value: String,
    subtext: String? = null,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalContinuityPalette.current
    Column(
        modifier = modifier
            .background(palette.surfaceSubtle, RoundedCornerShape(8.dp))
            .border(1.dp, palette.borderHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = palette.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = valueColor ?: palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtext.isNullOrBlank()) {
            Text(
                text = subtext,
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
