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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eg.masroof.core.DigitStyle

// Brand palette (PROMPT section 11): deep teal primary, warm amber accent; red/green only carry meaning.
// Dynamic color is deliberately off so the app looks the same on every device.
private val Light: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF0F766E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCCFBF1),
        onPrimaryContainer = Color(0xFF042F2E),
        secondary = Color(0xFFB45309),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFEF3C7),
        onSecondaryContainer = Color(0xFF451A03),
        tertiary = Color(0xFF4F46E5),
        onTertiary = Color.White,
        background = Color(0xFFF5F8F7),
        onBackground = Color(0xFF0F1F1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F1F1E),
        surfaceVariant = Color(0xFFE4ECEA),
        onSurfaceVariant = Color(0xFF455856),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F5F4),
        surfaceContainer = Color(0xFFE9F0EF),
        surfaceContainerHigh = Color(0xFFE2EAE8),
        outline = Color(0xFF6F8280),
        outlineVariant = Color(0xFFD3DEDC),
        error = Color(0xFFB91C1C),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF450A0A),
    )

private val Dark: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF5EEAD4),
        onPrimary = Color(0xFF00201D),
        primaryContainer = Color(0xFF0B4F4A),
        onPrimaryContainer = Color(0xFFCCFBF1),
        secondary = Color(0xFFFBBF24),
        onSecondary = Color(0xFF2B1700),
        secondaryContainer = Color(0xFF4A2C05),
        onSecondaryContainer = Color(0xFFFEF3C7),
        tertiary = Color(0xFFA5B4FC),
        onTertiary = Color(0xFF1E1B4B),
        background = Color(0xFF0B1211),
        onBackground = Color(0xFFE3EEEC),
        surface = Color(0xFF111A19),
        onSurface = Color(0xFFE3EEEC),
        surfaceVariant = Color(0xFF1E2B2A),
        onSurfaceVariant = Color(0xFFA9BDBA),
        surfaceContainerLowest = Color(0xFF0B1211),
        surfaceContainerLow = Color(0xFF141E1D),
        surfaceContainer = Color(0xFF182322),
        surfaceContainerHigh = Color(0xFF1E2B2A),
        outline = Color(0xFF7F9693),
        outlineVariant = Color(0xFF2C3E3C),
        error = Color(0xFFF87171),
        onError = Color(0xFF450A0A),
        errorContainer = Color(0xFF5A1A1A),
        onErrorContainer = Color(0xFFFEE2E2),
    )

/** Semantic colors Material 3 has no slot for: spend going down / income (green) and going up / over budget (red). */
@Immutable
data class ExtraColors(
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val negative: Color,
    val negativeContainer: Color,
    val onNegativeContainer: Color,
)

private val LightExtra =
    ExtraColors(
        positive = Color(0xFF15803D),
        positiveContainer = Color(0xFFDCFCE7),
        onPositiveContainer = Color(0xFF052E16),
        negative = Color(0xFFB91C1C),
        negativeContainer = Color(0xFFFEE2E2),
        onNegativeContainer = Color(0xFF450A0A),
    )

private val DarkExtra =
    ExtraColors(
        positive = Color(0xFF4ADE80),
        positiveContainer = Color(0xFF14532D),
        onPositiveContainer = Color(0xFFDCFCE7),
        negative = Color(0xFFF87171),
        negativeContainer = Color(0xFF5A1A1A),
        onNegativeContainer = Color(0xFFFEE2E2),
    )

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Convenience accessor: `MaterialTheme.extra.positive`. */
val MaterialTheme.extra: ExtraColors
    @Composable get() = LocalExtraColors.current

private val Base = Typography()

private fun TextStyle.tuned(
    weight: FontWeight,
    size: Int? = null,
    line: Int? = null,
) = copy(
    fontWeight = weight,
    fontSize = size?.sp ?: fontSize,
    lineHeight = line?.sp ?: lineHeight,
    letterSpacing = 0.sp,
)

private const val DISPLAY_SIZE = 36
private const val DISPLAY_LINE = 44
private const val HEADLINE_SIZE = 24
private const val HEADLINE_LINE = 32

/** A calm money-app scale: one heavy display number, semibold headings, regular body, medium labels. */
private val AppTypography =
    Typography(
        displaySmall = Base.displaySmall.tuned(FontWeight.Bold, DISPLAY_SIZE, DISPLAY_LINE),
        headlineMedium = Base.headlineMedium.tuned(FontWeight.SemiBold),
        headlineSmall = Base.headlineSmall.tuned(FontWeight.SemiBold, HEADLINE_SIZE, HEADLINE_LINE),
        titleLarge = Base.titleLarge.tuned(FontWeight.SemiBold),
        titleMedium = Base.titleMedium.tuned(FontWeight.SemiBold),
        titleSmall = Base.titleSmall.tuned(FontWeight.SemiBold),
        bodyLarge = Base.bodyLarge.tuned(FontWeight.Normal),
        bodyMedium = Base.bodyMedium.tuned(FontWeight.Normal),
        bodySmall = Base.bodySmall.tuned(FontWeight.Normal),
        labelLarge = Base.labelLarge.tuned(FontWeight.Medium),
        labelMedium = Base.labelMedium.tuned(FontWeight.Medium),
        labelSmall = Base.labelSmall.tuned(FontWeight.Medium),
    )

private const val SMALL_RADIUS = 8
private const val MEDIUM_RADIUS = 14
private const val LARGE_RADIUS = 20
private const val XL_RADIUS = 28

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(SMALL_RADIUS.dp),
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
