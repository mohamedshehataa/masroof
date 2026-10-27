package eg.masroof.data.receipts

import eg.masroof.core.MoneyFormat
import java.math.BigDecimal

/** Server limits for purchased items (REQ-RCPT-031), checked on the phone so the common mistakes never cost a round trip. */
const val MAX_ITEMS = 100
const val MAX_ITEM_NAME = 200
private const val MAX_QTY_DECIMALS = 3
private const val MAX_QTY = 999_999L
private const val ARABIC_ZERO = '٠'

/** Arabic-Indic digits and separators to their Western forms; a comma is a decimal separator too. Everything else is kept. */
private fun western(text: String): String =
    text
        .trim()
        .map {
            when (it) {
                in '٠'..'٩' -> '0' + (it - ARABIC_ZERO)
                '٫', ',' -> '.'
                else -> it
            }
        }.joinToString("")

/**
 * A quantity such as `2`, `1.5` or `٠٫٧٥` as a canonical decimal string (`2`, `1.5`, `0.75`): greater than 0, at most 999999,
 * at most 3 decimals. Null when invalid. BigDecimal only: a quantity is not money but is still never a binary float.
 */
fun parseQty(input: String): String? {
    val t = western(input)
    if (!Regex("""\d{1,7}(\.\d{1,$MAX_QTY_DECIMALS})?""").matches(t)) return null
    val value = BigDecimal(t)
    if (value.signum() <= 0 || value > BigDecimal(MAX_QTY)) return null
    return value.stripTrailingZeros().toPlainString()
}

/** The same canonical form for a quantity the server sent back (`1.500` -> `1.5`), so an unchanged item compares equal. */
fun canonicalQty(qty: String?): String? = qty?.let { runCatching { BigDecimal(it).stripTrailingZeros().toPlainString() }.getOrDefault(it) }

/** An item amount in minor units, reusing the amount field's parser; unlike a transaction, an item may be 0. Null when invalid. */
fun parseItemAmount(input: String): Long? {
    val t = western(input)
    if (Regex("""0*(\.0*)?""").matches(t) && t.any { it == '0' }) return 0L
    return MoneyFormat.parseMinor(t)
}

/** An item name trimmed, 1 to [MAX_ITEM_NAME] code points, no NUL; null when invalid. */
fun cleanItemName(input: String): String? {
    val t = input.trim()
    if (t.isEmpty() || '\u0000' in t || t.codePointCount(0, t.length) > MAX_ITEM_NAME) return null
    return t
}
