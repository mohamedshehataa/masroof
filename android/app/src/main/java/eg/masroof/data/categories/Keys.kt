package eg.masroof.data.categories

import java.text.Normalizer
import java.util.Locale

private val ARABIC_MARKS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
private val LATIN_MARKS = Regex("\\p{M}+")
private val SPACES = Regex("\\s+")

/** Lower case, Latin accents and Arabic diacritics dropped, alef/yaa/taa-marbuta variants unified, spaces collapsed. */
internal fun foldText(raw: String): String {
    val arabicFolded =
        ARABIC_MARKS
            .replace(raw, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
    val decomposed = Normalizer.normalize(arabicFolded, Normalizer.Form.NFD)
    return SPACES.replace(LATIN_MARKS.replace(decomposed, ""), " ").trim().lowercase(Locale.ROOT)
}

/** At most [n] Unicode code points: a surrogate pair is never split. */
internal fun String.capCodePoints(n: Int): String = if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))

/**
 * The merchant key of a rule: the merchant label as stored, folded as [foldText] (case, accents, Arabic spelling, spaces).
 * Digits are kept: parser labels are already digit-free, and "3M" or "7-Eleven" must stay themselves. "Talabat  EG" and
 * "TALABAT EG" share one key. Empty when blank, and an empty key never gets a rule. Only ever computed from the structured
 * merchant field of a record, never from message text.
 */
object MerchantKey {
    fun of(merchant: String?): String = if (merchant.isNullOrBlank()) "" else foldText(merchant.capCodePoints(MAX_MERCHANT))
}

/** The comparison form of a category name for the duplicate check. */
object CategoryNameKey {
    fun of(name: String): String = foldText(name)
}
