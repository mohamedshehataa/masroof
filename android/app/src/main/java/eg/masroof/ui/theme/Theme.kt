package eg.masroof.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import eg.masroof.core.DigitStyle

private val Teal = Color(0xFF0F766E)
private val TealLight = Color(0xFF5EEAD4)
private val Amber = Color(0xFFB45309)
private val AmberLight = Color(0xFFFBBF24)

// Brand palette; dynamic color is deliberately off so the app looks the same on every device.
private val Light: ColorScheme =
    lightColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        secondary = Amber,
        onSecondary = Color.White,
        background = Color(0xFFF8FAFA),
        surface = Color(0xFFFFFFFF),
    )

private val Dark: ColorScheme =
    darkColorScheme(
        primary = TealLight,
        onPrimary = Color(0xFF00201D),
        secondary = AmberLight,
        onSecondary = Color(0xFF2B1700),
        background = Color(0xFF0E1514),
        surface = Color(0xFF151D1C),
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
    CompositionLocalProvider(LocalMoneyStyle provides MoneyStyle(arabic, digits)) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
    }
}
