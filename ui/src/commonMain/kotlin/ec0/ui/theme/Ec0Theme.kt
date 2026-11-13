/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 design system — Material 3 Expressive language: wide tonal palettes,
 * oversized corner shapes, spring-driven motion. One theme for desktop and
 * phone so both ends read as the same product.
 */
package ec0.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---- Motion: expressive springs (bouncy for entrances, snappy for taps) ----
object Ec0Motion {
    fun <T> bouncy() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
}

// ---- Shapes: oversized expressive corners ----
object Ec0Shapes {
    val hero = RoundedCornerShape(32.dp)
    val card = RoundedCornerShape(24.dp)
    val tile = RoundedCornerShape(18.dp)
    val chip = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(100)
}

// ---- Extended semantic colors (protocol accents + states) ----
data class Ec0Colors(
    val localSend: Color,
    val synergy: Color,
    val kdeConnect: Color,
    val success: Color,
    val warning: Color,
    val heroContainer: Color,
    val onHeroContainer: Color,
)

val LocalEc0Colors = staticCompositionLocalOf {
    Ec0Colors(
        localSend = Color(0xFF00B3C4),
        synergy = Color(0xFF9A6CFF),
        kdeConnect = Color(0xFF7BC96A),
        success = Color(0xFF4CAF50),
        warning = Color(0xFFFFB74D),
        heroContainer = Color(0xFFE5DBFF),
        onHeroContainer = Color(0xFF21005D),
    )
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF6750E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DBFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF00B3C4),
    onSecondary = Color(0xFF00333A),
    secondaryContainer = Color(0xFFB4ECFF),
    onSecondaryContainer = Color(0xFF001F26),
    tertiary = Color(0xFF7BC96A),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE7E0F3),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1ECF4),
    surfaceContainerHigh = Color(0xFFECE6EF),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF5DD5E2),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF004F5B),
    onSecondaryContainer = Color(0xFFB4ECFF),
    tertiary = Color(0xFF7BC96A),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4CF),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1C1B1F),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val DarkEc0Colors = Ec0Colors(
    localSend = Color(0xFF5DD5E2),
    synergy = Color(0xFFCFBCFF),
    kdeConnect = Color(0xFF9BD98D),
    success = Color(0xFF7DD97F),
    warning = Color(0xFFFFCC7A),
    heroContainer = Color(0xFF4F378B),
    onHeroContainer = Color(0xFFEADDFF),
)

private val LightEc0Colors = Ec0Colors(
    localSend = Color(0xFF00A5B5),
    synergy = Color(0xFF7E4FE0),
    kdeConnect = Color(0xFF5B9E4D),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFC77700),
    heroContainer = Color(0xFFE5DBFF),
    onHeroContainer = Color(0xFF21005D),
)

/** ec0 shape scale — deliberately larger than M3 defaults. */
private val Ec0ShapeScale = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = Ec0Shapes.chip,
    medium = Ec0Shapes.tile,
    large = Ec0Shapes.card,
    extraLarge = Ec0Shapes.hero,
)

@Composable
fun Ec0Theme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = Ec0ShapeScale,
        content = content,
    )
}

/** Extended accents — use inside a @Composable via [LocalEc0Colors.current]. */
@Composable
fun ec0Colors(): Ec0Colors = LocalEc0Colors.current

@Composable
fun protocolAccent(protocol: ec0.ui.models.ProtocolFamily): Color = when (protocol) {
    ec0.ui.models.ProtocolFamily.LOCALSEND -> ec0Colors().localSend
    ec0.ui.models.ProtocolFamily.SYNERGY -> ec0Colors().synergy
    ec0.ui.models.ProtocolFamily.KDECONNECT -> ec0Colors().kdeConnect
}
