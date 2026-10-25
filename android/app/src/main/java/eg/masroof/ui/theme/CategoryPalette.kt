package eg.masroof.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Stable color per category so the same category looks the same in lists, the donut and the legend. The palette is
 * chosen to read on both light and dark surfaces; uncategorised rows are neutral slate.
 */
object CategoryPalette {
    val colors: List<Color> =
        listOf(
            Color(0xFF0F766E),
            Color(0xFFD97706),
            Color(0xFF4F46E5),
            Color(0xFFDB2777),
            Color(0xFF0284C7),
            Color(0xFF65A30D),
            Color(0xFF9333EA),
            Color(0xFFDC2626),
            Color(0xFFEA580C),
            Color(0xFF0891B2),
        )

    val neutral = Color(0xFF64748B)

    /** Deterministic: the same [key] (a category id) always maps to the same color. */
    fun colorFor(key: String?): Color = if (key == null) neutral else colors[Math.floorMod(key.hashCode(), colors.size)]
}
