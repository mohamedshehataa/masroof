package eg.masroof.ingest.parse

/**
 * The shape of a message for on-device template learning (round 15). The shape is the normalized text in which every number,
 * date, time and masked id is a typed slot and every word that is not a plain banking word is a single `<W>` slot, so a
 * signature holds no digit and no name (merchants, people, bank branding all become `<W>`). A message that looks secret has no
 * shape and is never learned. Signatures stay on the phone, keyed by sender, and are wiped on logout.
 */
object TemplateShape {
    class Shape(
        val signature: String,
        /** The raw number tokens in text order; slot i of the signature's `<N>` marks is `numbers[i]`. Never leaves the phone. */
        val numbers: List<String>,
    )

    private const val MAX_TOKENS = 60

    private val slotPattern =
        Regex(
            "(?<date>\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}[a-z]{3}\\d{2,4})" +
                "|(?<time>\\d{1,2}:\\d{2}(?::\\d{2})?)" +
                "|(?<num>[*•]*\\d[\\d.,]*\\d|[*•]*\\d)",
        )
    private val word = Regex("\\p{L}[\\p{L}'/]*")

    /** The shape of [text], or null when it looks secret, has no number, or is too long to be a bank message. */
    fun of(text: String): Shape? {
        val n = TextNormalizer.normalizeText(text)
        if (n.isBlank() || SecretDetector.reason(n) != null || SecretDetector.hasHintWord(n)) return null
        val t = SecretDetector.fold(n).lowercase()
        val numbers = mutableListOf<String>()
        val tokens = mutableListOf<String>()

        fun words(chunk: String) {
            for (w in word.findAll(chunk)) {
                val token = if (w.value in VOCABULARY) w.value else SLOT_WORD
                if (token == SLOT_WORD && tokens.lastOrNull() == SLOT_WORD) continue
                tokens += token
            }
        }
        var last = 0
        for (m in slotPattern.findAll(t)) {
            words(t.substring(last, m.range.first))
            when {
                m.groups["date"] != null -> tokens += SLOT_DATE
                m.groups["time"] != null -> tokens += SLOT_TIME
                else -> {
                    numbers += m.value.trimStart('*', '•')
                    tokens += SLOT_NUMBER
                }
            }
            last = m.range.last + 1
        }
        words(t.substring(last))
        if (numbers.isEmpty() || tokens.size > MAX_TOKENS) return null
        return Shape(tokens.joinToString(" "), numbers)
    }

    private const val SLOT_NUMBER = "<N>"
    private const val SLOT_DATE = "<D>"
    private const val SLOT_TIME = "<T>"
    private const val SLOT_WORD = "<W>"

    /** Plain banking words. Names, merchants and brands are not here, so they collapse into `<W>`. */
    private val VOCABULARY: Set<String> =
        (
            "your you account a/c acct wallet card credit debit prepaid ending with in was has have been is are were used for " +
                "charged debited credited deducted purchase purchased payment paid pay transfer transferred sent send received " +
                "receive refund refunded deposit deposited salary incoming outgoing withdrawal withdrawn withdraw cash atm " +
                "instapay fee fees charge commission successful successfully completed approved declined failed to from at on " +
                "by of the and or has balance available avail limit egp le thank thanks bill fawry online pos installment " +
                "transaction no number ref reference date time via using into onto will be not this that an it as"
        ).split(' ').toSet() +
            (
                "تم خصم شراء سحب ايداع اضافه استلام استقبال تحويل لحظي دفع سداد راتب مرتب استرداد مرتجع وارد صادر مبلغ بمبلغ " +
                    "بقيمه جنيه جم من الي لدي عند في ب لصالح حساب حسابك حسابكم بطاقه بطاقتك بطاقتكم الائتمانيه الائتمان " +
                    "المنتهيه المنتهي رقم محفظه محفظتك محفظتكم عمليه ناجحه بنجاح فاتوره رصيد رصيدك الرصيد المتاح الحالي رسوم " +
                    "مصاريف عموله شكرا انستاباي ماكينه صراف نقدي نقدا خدمه الخدمه و او ال"
            ).split(' ').toSet()
}
