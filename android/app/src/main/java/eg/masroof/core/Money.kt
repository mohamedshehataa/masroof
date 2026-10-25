package eg.masroof.core

/** Money = integer minor units + ISO-4217 currency. Never a floating point type. */
data class Money(
    val amountMinor: Long,
    val currency: String = EGP,
) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return Money(amountMinor + other.amountMinor, currency)
    }

    companion object {
        const val EGP = "EGP"

        fun zero(currency: String = EGP) = Money(0L, currency)
    }
}

enum class DigitStyle { WESTERN, ARABIC_INDIC }

/**
 * Formats and parses money without any floating point.
 * `1,250 EGP` (en) and `١٬٢٥٠ ج.م` (ar, Arabic-Indic digits). Minor units are shown only when non-zero.
 */
object MoneyFormat {
    private const val MINOR_PER_MAJOR = 100L
    private const val ARABIC_ZERO = '٠'

    fun format(
        money: Money,
        arabic: Boolean,
        digits: DigitStyle,
    ): String {
        val negative = money.amountMinor < 0
        val abs = if (negative) -money.amountMinor else money.amountMinor
        val major = abs / MINOR_PER_MAJOR
        val minor = (abs % MINOR_PER_MAJOR).toInt()
        val groupSep = if (digits == DigitStyle.ARABIC_INDIC) '٬' else ','
        val decimalSep = if (digits == DigitStyle.ARABIC_INDIC) '٫' else '.'
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(group(major.toString(), groupSep))
        if (minor != 0) sb.append(decimalSep).append(minor.toString().padStart(2, '0'))
        val number = localizeDigits(sb.toString(), digits)
        return "$number ${symbol(money.currency, arabic)}"
    }

    fun symbol(
        currency: String,
        arabic: Boolean,
    ): String = if (arabic && currency == Money.EGP) "ج.م" else currency

    /** Parses user input such as `12.50`, `12,5`, `١٢٫٥٠` into minor units; null when invalid or not positive. */
    fun parseMinor(input: String): Long? {
        val normalized =
            input
                .trim()
                .map { toWestern(it) }
                .joinToString("")
                .replace(',', '.')
        if (normalized.isEmpty() || !normalized.all { it.isDigit() || it == '.' }) return null
        val parts = normalized.split('.')
        if (parts.size > 2 || parts[0].isEmpty() && parts.getOrNull(1).isNullOrEmpty()) return null
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > 2) return null
        val major = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minor = fraction.padEnd(2, '0').toInt()
        val total = major.toBigInteger() * MINOR_PER_MAJOR.toBigInteger() + minor.toBigInteger()
        if (total.bitLength() > Long.SIZE_BITS - 1) return null
        return total.toLong().takeIf { it > 0 }
    }

    private fun group(
        digits: String,
        sep: Char,
    ): String =
        digits
            .reversed()
            .chunked(3)
            .joinToString(sep.toString())
            .reversed()

    private fun localizeDigits(
        text: String,
        digits: DigitStyle,
    ): String =
        if (digits == DigitStyle.WESTERN) {
            text
        } else {
            text.map { if (it in '0'..'9') ARABIC_ZERO + (it - '0') else it }.joinToString("")
        }

    private fun toWestern(c: Char): Char =
        when (c) {
            in '٠'..'٩' -> '0' + (c - ARABIC_ZERO)
            '٫' -> '.'
            else -> c
        }
}
