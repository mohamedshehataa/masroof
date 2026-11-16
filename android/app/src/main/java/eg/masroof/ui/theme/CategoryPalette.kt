package eg.masroof.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Stable color per category so the same category looks the same in lists, the donut and the legend. The palette is
 * muted to sit beside the brand green and to read on both light and dark surfaces; uncategorised rows are neutral grey.
 */
object CategoryPalette {
    val colors: List<Color> =
        listOf(
            Color(0xFF0E7C66),
            Color(0xFFC98A1B),
            Color(0xFF3F6C92),
            Color(0xFFA8496B),
            Color(0xFF5E7F3A),
            Color(0xFF7A5BA8),
            Color(0xFFC2603A),
            Color(0xFF2F8C9B),
            Color(0xFF8C6D4F),
            Color(0xFF4B5FA8),
        )

    val neutral = Color(0xFF6B7673)

    /** Deterministic: the same [key] (a category id) always maps to the same color. */
    fun colorFor(key: String?): Color = if (key == null) neutral else colors[Math.floorMod(key.hashCode(), colors.size)]
}
