package eg.masroof.core

/**
 * Formats basis points (1250 = 12.5%) without floating point, honouring the digit setting.
 * `12.5%` / `+12%` (en) and `١٢٫٥٪` (ar, Arabic-Indic digits). One decimal only when it is not zero.
 */
object PercentFormat {
    private const val BPS_PER_TENTH = 10
    private const val TENTHS_PER_PERCENT = 10

    fun format(
        bps: Int,
        arabic: Boolean,
        digits: DigitStyle,
        signed: Boolean = false,
    ): String {
        val negative = bps < 0
        val abs = if (negative) -bps else bps
        // Round half up to a tenth of a percent.
        val tenths = (abs + BPS_PER_TENTH / 2) / BPS_PER_TENTH
        val whole = tenths / TENTHS_PER_PERCENT
        val fraction = tenths % TENTHS_PER_PERCENT
        val decimalSep = if (digits == DigitStyle.ARABIC_INDIC) '٫' else '.'
        val number = if (fraction == 0) whole.toString() else "$whole$decimalSep$fraction"
        val sign =
            when {
                negative && tenths != 0 -> "-"
                signed && tenths != 0 -> "+"
                else -> ""
            }
        val localized = localize("$sign$number", digits)
        return localized + if (arabic) "٪" else "%"
    }

    private fun localize(
        text: String,
        digits: DigitStyle,
    ): String =
        if (digits == DigitStyle.WESTERN) {
            text
        } else {
            text.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
        }
}
