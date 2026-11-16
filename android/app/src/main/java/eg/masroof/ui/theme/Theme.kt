package eg.masroof.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eg.masroof.R
import eg.masroof.core.DigitStyle

// Brand palette: a near-white page, ink text and one deep Nile green. Amber (tertiary) marks things that need attention; red and
// green only carry meaning. Panels are flat tints of the page (no shadows), so the dark green home panel is the one
// loud surface. Dynamic color is deliberately off so the app looks the same on every device.
private val Light: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF0B4A3B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEBE4),
        onPrimaryContainer = Color(0xFF062E24),
        inversePrimary = Color(0xFF8FD9BC),
        // Secondary is a quieter green: Material uses it for selected chips and tonal buttons, and the app for metadata.
        secondary = Color(0xFF3C6A5B),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCEBE4),
        onSecondaryContainer = Color(0xFF062E24),
        // Tertiary is the amber: "this needs your attention".
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFBEFD2),
        onTertiaryContainer = Color(0xFF3A2600),
        background = Color(0xFFFBFCFB),
        onBackground = Color(0xFF111716),
        surface = Color(0xFFFBFCFB),
        onSurface = Color(0xFF111716),
        surfaceVariant = Color(0xFFE9EDEB),
        onSurfaceVariant = Color(0xFF55605D),
        surfaceTint = Color(0xFF0B4A3B),
        inverseSurface = Color(0xFF232A28),
        inverseOnSurface = Color(0xFFF1F4F2),
        surfaceBright = Color(0xFFFBFCFB),
        surfaceDim = Color(0xFFDDE2DF),
        // Cards across the app are drawn in the lowest container: a quiet tint of the page.
        surfaceContainerLowest = Color(0xFFF1F4F2),
        surfaceContainerLow = Color(0xFFF4F6F5),
        surfaceContainer = Color(0xFFEDF0EE),
        surfaceContainerHigh = Color(0xFFE7EBE9),
        surfaceContainerHighest = Color(0xFFE0E5E2),
        outline = Color(0xFF75807D),
        outlineVariant = Color(0xFFDDE2DF),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFFBE4E1),
        onErrorContainer = Color(0xFF4A0E0A),
        scrim = Color(0xFF000000),
    )

private val Dark: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF8FD9BC),
        onPrimary = Color(0xFF00382B),
        primaryContainer = Color(0xFF123F33),
        onPrimaryContainer = Color(0xFFCDEBDD),
        inversePrimary = Color(0xFF0B4A3B),
        secondary = Color(0xFF9CCBB9),
        onSecondary = Color(0xFF0A3327),
        secondaryContainer = Color(0xFF1D3F34),
        onSecondaryContainer = Color(0xFFCDEBDD),
        tertiary = Color(0xFFE9C16C),
        onTertiary = Color(0xFF3A2600),
        tertiaryContainer = Color(0xFF4A3608),
        onTertiaryContainer = Color(0xFFFBEFD2),
        background = Color(0xFF0C0F0E),
        onBackground = Color(0xFFE6EAE8),
        surface = Color(0xFF0C0F0E),
        onSurface = Color(0xFFE6EAE8),
        surfaceVariant = Color(0xFF222927),
        onSurfaceVariant = Color(0xFFA6B1AD),
        surfaceTint = Color(0xFF8FD9BC),
        inverseSurface = Color(0xFFE6EAE8),
        inverseOnSurface = Color(0xFF1A201E),
        surfaceBright = Color(0xFF2B3331),
        surfaceDim = Color(0xFF0C0F0E),
        // In the dark the card tint is lighter than the page, so panels still read as panels.
        surfaceContainerLowest = Color(0xFF161B1A),
        surfaceContainerLow = Color(0xFF131817),
        surfaceContainer = Color(0xFF1A201E),
        surfaceContainerHigh = Color(0xFF212826),
        surfaceContainerHighest = Color(0xFF29312E),
        outline = Color(0xFF7C8884),
        outlineVariant = Color(0xFF2A3230),
        error = Color(0xFFF0958C),
        onError = Color(0xFF4A0E0A),
        errorContainer = Color(0xFF5C1B16),
        onErrorContainer = Color(0xFFFBE4E1),
        scrim = Color(0xFF000000),
    )

/**
 * Colors Material 3 has no slot for: spend going down / income (green), going up / over budget (red), and the dark
 * green home panel ([hero]) with its two text tones.
 */
@Immutable
data class ExtraColors(
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val negative: Color,
    val negativeContainer: Color,
    val onNegativeContainer: Color,
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
)

private val LightExtra =
    ExtraColors(
        positive = Color(0xFF1B7A4B),
        positiveContainer = Color(0xFFDDF1E5),
        onPositiveContainer = Color(0xFF07301C),
        negative = Color(0xFFB3261E),
        negativeContainer = Color(0xFFFBE4E1),
        onNegativeContainer = Color(0xFF4A0E0A),
        hero = Color(0xFF0B3B30),
        onHero = Color(0xFFF3F8F5),
        onHeroMuted = Color(0xFFB4CEC4),
    )

private val DarkExtra =
    ExtraColors(
        positive = Color(0xFF7ED3A2),
        positiveContainer = Color(0xFF123D27),
        onPositiveContainer = Color(0xFFDDF1E5),
        negative = Color(0xFFF0958C),
        negativeContainer = Color(0xFF5C1B16),
        onNegativeContainer = Color(0xFFFBE4E1),
        hero = Color(0xFF12352B),
        onHero = Color(0xFFEAF4EF),
        onHeroMuted = Color(0xFFA5C2B7),
    )

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Convenience accessor: `MaterialTheme.extra.positive`. */
val MaterialTheme.extra: ExtraColors
    @Composable get() = LocalExtraColors.current

/** IBM Plex Sans Arabic (OFL, bundled): one family for Arabic and Latin, so both languages share a voice. */
private val Plex =
    FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
    )

// Letter spacing stays 0: Arabic is a joined script and spacing breaks the joins.
private fun style(
    weight: FontWeight,
    size: Int,
    line: Int,
) = TextStyle(
    fontFamily = Plex,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

/**
 * One large figure, semibold headings, medium row titles, regular body. Line heights are generous because Arabic
 * needs the room for its marks.
 */
@Suppress("MagicNumber") // a type scale is a table of sizes
private val AppTypography =
    Typography(
        displayLarge = style(FontWeight.SemiBold, 52, 62),
        displayMedium = style(FontWeight.SemiBold, 44, 54),
        displaySmall = style(FontWeight.SemiBold, 38, 48),
        headlineLarge = style(FontWeight.SemiBold, 30, 40),
        headlineMedium = style(FontWeight.SemiBold, 26, 36),
        headlineSmall = style(FontWeight.SemiBold, 22, 32),
        titleLarge = style(FontWeight.SemiBold, 20, 30),
        titleMedium = style(FontWeight.Medium, 16, 24),
        titleSmall = style(FontWeight.Medium, 14, 22),
        bodyLarge = style(FontWeight.Normal, 16, 26),
        bodyMedium = style(FontWeight.Normal, 14, 22),
        bodySmall = style(FontWeight.Normal, 12, 18),
        labelLarge = style(FontWeight.Medium, 14, 20),
        labelMedium = style(FontWeight.Medium, 12, 18),
        labelSmall = style(FontWeight.Medium, 11, 16),
    )

private const val XS_RADIUS = 6
private const val SMALL_RADIUS = 10
private const val MEDIUM_RADIUS = 18
private const val LARGE_RADIUS = 24
private const val XL_RADIUS = 28

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(XS_RADIUS.dp),
        small = RoundedCornerShape(SMALL_RADIUS.dp),
        medium = RoundedCornerShape(MEDIUM_RADIUS.dp),
        large = RoundedCornerShape(LARGE_RADIUS.dp),
        extraLarge = RoundedCornerShape(XL_RADIUS.dp),
    )

/** How amounts are rendered: Arabic label/currency and the digit script. Provided once at the root. */
data class MoneyStyle(
    val arabic: Boolean,
    val digits: DigitStyle,
)

val LocalMoneyStyle = staticCompositionLocalOf { MoneyStyle(arabic = false, digits = DigitStyle.WESTERN) }

@Composable
fun MasroofTheme(
    digitOverride: DigitStyle? = null,
    content: @Composable () -> Unit,
) {
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val digits = digitOverride ?: if (arabic) DigitStyle.ARABIC_INDIC else DigitStyle.WESTERN
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalMoneyStyle provides MoneyStyle(arabic, digits),
        LocalExtraColors provides if (dark) DarkExtra else LightExtra,
    ) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
