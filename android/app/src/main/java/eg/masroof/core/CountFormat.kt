package eg.masroof.core

import java.util.Locale

/** A whole count with thousands grouping in the chosen digits (`1,020` / `١٬٠٢٠`). */
object CountFormat {
    private const val ARABIC_ZERO = '٠'

    fun format(
        n: Int,
        digits: DigitStyle,
    ): String {
        val western = "%,d".format(Locale.ENGLISH, n)
        return if (digits == DigitStyle.ARABIC_INDIC) {
            western
                .map {
                    if (it in '0'..'9') {
                        ARABIC_ZERO + (it - '0')
                    } else if (it == ',') {
                        '٬'
                    } else {
                        it
                    }
                }.joinToString("")
        } else {
            western
        }
    }
}
