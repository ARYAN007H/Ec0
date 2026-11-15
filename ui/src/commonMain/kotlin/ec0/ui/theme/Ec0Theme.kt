/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ec0 design system — Material 3 Expressive language: wide tonal palettes,
 * oversized corner shapes, spring-driven motion. One theme for desktop and
 * phone so both ends read as the same product.
 *
 * Color: curated M3 Expressive tonal schemes per seed (Material Theme Builder
 * values, dark + light). The seed is a user setting — pick it in Settings →
 * Appearance → Color theme.
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

/** Curated M3 Expressive seeds. [id] is what [ec0.ui.models.SettingsState.themeSeed] stores. */
enum class Ec0Seed(val id: String, val label: String, val swatch: Color) {
    AMBER("amber", "Warm Amber", Color(0xFFFFB947)),
    VIOLET("violet", "Electric Violet", Color(0xFFD0BCFF)),
    TEAL("teal", "Jade Teal", Color(0xFF7CD5CB));

    companion object {
        fun fromId(id: String?): Ec0Seed = entries.firstOrNull { it.id == id?.lowercase() } ?: AMBER
    }
}

// ---- Extended semantic colors (protocol accents + states) — seed-independent ----
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
        localSend = Color(0xFF67D4FF),
        synergy = Color(0xFFCFBCFF),
        kdeConnect = Color(0xFF8CD3A4),
        success = Color(0xFF5BE095),
        warning = Color(0xFFFFB947),
        heroContainer = Color(0xFF5D4000),
        onHeroContainer = Color(0xFFFFDDB0),
    )
}

private fun darkScheme(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, onSecondary: Color, secondaryContainer: Color, onSecondaryContainer: Color,
    tertiary: Color, onTertiary: Color, tertiaryContainer: Color, onTertiaryContainer: Color,
    surface: Color, onSurface: Color, onSurfaceVariant: Color,
    outline: Color, outlineVariant: Color,
    containerLowest: Color, containerLow: Color, container: Color, containerHigh: Color, containerHighest: Color,
): ColorScheme = darkColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary,
    secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surface = surface, onSurface = onSurface,
    surfaceVariant = containerHighest, onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = containerLowest, surfaceContainerLow = containerLow,
    surfaceContainer = container, surfaceContainerHigh = containerHigh,
    surfaceContainerHighest = containerHighest,
    outline = outline, outlineVariant = outlineVariant,
)

private fun lightScheme(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, onSecondary: Color, secondaryContainer: Color, onSecondaryContainer: Color,
    tertiary: Color, onTertiary: Color, tertiaryContainer: Color, onTertiaryContainer: Color,
    surface: Color, onSurface: Color, onSurfaceVariant: Color,
    outline: Color, outlineVariant: Color,
    containerLowest: Color, containerLow: Color, container: Color, containerHigh: Color, containerHighest: Color,
): ColorScheme = lightColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary,
    secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surface = surface, onSurface = onSurface,
    surfaceVariant = containerHigh, onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = containerLowest, surfaceContainerLow = containerLow,
    surfaceContainer = container, surfaceContainerHigh = containerHigh,
    surfaceContainerHighest = containerHighest,
    outline = outline, outlineVariant = outlineVariant,
)

// ---- Warm Amber (default): cold void, warm signal — the design board's scheme ----
private val AmberDark = darkScheme(
    primary = Color(0xFFFFB947), onPrimary = Color(0xFF422C00),
    primaryContainer = Color(0xFF5D4000), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFD7C3A8), onSecondary = Color(0xFF3A2E1B),
    secondaryContainer = Color(0xFF524430), onSecondaryContainer = Color(0xFFF4DFC3),
    tertiary = Color(0xFF67D4FF), onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF004D68), onTertiaryContainer = Color(0xFFBEE9FF),
    surface = Color(0xFF131316), onSurface = Color(0xFFE6E6EB), onSurfaceVariant = Color(0xFFB8BAC4),
    outline = Color(0xFF858894), outlineVariant = Color(0xFF3A3D47),
    containerLowest = Color(0xFF0C0D10), containerLow = Color(0xFF191A1F),
    container = Color(0xFF1F2127), containerHigh = Color(0xFF292B32), containerHighest = Color(0xFF34373F),
)

private val AmberLight = lightScheme(
    primary = Color(0xFF825500), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0), onPrimaryContainer = Color(0xFF291800),
    secondary = Color(0xFF705B3E), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCEDD6), onSecondaryContainer = Color(0xFF271904),
    tertiary = Color(0xFF3B6938), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDF0B4), onTertiaryContainer = Color(0xFF002204),
    surface = Color(0xFFFFF8F3), onSurface = Color(0xFF201A12), onSurfaceVariant = Color(0xFF4F4539),
    outline = Color(0xFF817567), outlineVariant = Color(0xFFD2C4B4),
    containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFFBF2E7),
    container = Color(0xFFF5ECE0), containerHigh = Color(0xFFEFE6DA), containerHighest = Color(0xFFE9E0D4),
)

// ---- Electric Violet ----
private val VioletDark = darkScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
    containerLowest = Color(0xFF0F0D13), containerLow = Color(0xFF1D1B20),
    container = Color(0xFF211F26), containerHigh = Color(0xFF2B2930), containerHighest = Color(0xFF36343B),
)

private val VioletLight = lightScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFF7F2FA),
    container = Color(0xFFF3EDF7), containerHigh = Color(0xFFECE6F0), containerHighest = Color(0xFFE6E0E9),
)

// ---- Jade Teal ----
private val TealDark = darkScheme(
    primary = Color(0xFF7CD5CB), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFF98F2E7),
    secondary = Color(0xFFB1CCC7), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47), onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFB0C9E8), onTertiary = Color(0xFF19324B),
    tertiaryContainer = Color(0xFF314962), onTertiaryContainer = Color(0xFFD0E4FF),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF889390), outlineVariant = Color(0xFF3F4947),
    containerLowest = Color(0xFF090F0E), containerLow = Color(0xFF161D1C),
    container = Color(0xFF1A2120), containerHigh = Color(0xFF252B2A), containerHighest = Color(0xFF303635),
)

private val TealLight = lightScheme(
    primary = Color(0xFF006A62), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF98F2E7), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF48617A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD0E4FF), onTertiaryContainer = Color(0xFF001D34),
    surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFEEF5F3),
    container = Color(0xFFE9EFED), containerHigh = Color(0xFFE3EAE8), containerHighest = Color(0xFFDDE4E2),
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
fun Ec0Theme(
    dark: Boolean,
    seed: Ec0Seed = Ec0Seed.AMBER,
    content: @Composable () -> Unit,
) {
    val scheme = when (seed) {
        Ec0Seed.AMBER -> if (dark) AmberDark else AmberLight
        Ec0Seed.VIOLET -> if (dark) VioletDark else VioletLight
        Ec0Seed.TEAL -> if (dark) TealDark else TealLight
    }
    MaterialTheme(
        colorScheme = scheme,
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
