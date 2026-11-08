package eg.masroof.data.categories

import eg.masroof.ingest.parse.LabelNormalizer
import java.text.Normalizer
import java.util.Locale

private val ARABIC_MARKS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
private val LATIN_MARKS = Regex("\\p{M}+")
private val DIGITS = Regex("\\p{N}+")
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

/**
 * The merchant key of a rule: the merchant label as the app stores it ([LabelNormalizer]), digits removed, folded as
 * [foldText]. "TALABAT EG 123" and "Talabat  EG" share one key. Empty when nothing is left, and an empty key never gets a rule.
 * Only ever computed from the structured merchant field of a record, never from message text.
 */
object MerchantKey {
    fun of(merchant: String?): String {
        if (merchant.isNullOrBlank()) return ""
        return foldText(DIGITS.replace(LabelNormalizer.normalize(merchant), " "))
    }
}

/** The comparison form of a category name for the duplicate check. */
object CategoryNameKey {
    fun of(name: String): String = foldText(name)
}
