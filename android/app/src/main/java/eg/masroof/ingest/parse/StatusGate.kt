package eg.masroof.ingest.parse

/**
 * The status gate (round 16): ONE function that runs before ANY automatic record (a proven family or a learned template) and
 * before template matching. A message that says anywhere that the operation is not (or not yet, or no longer) completed is never
 * recorded or learned automatically: failed, declined, cancelled, reversed, returned, pending, on hold, requested, will be ...
 * It goes to the review list. A refund TO the household ("refund of EGP N credited to your card") is a completed credit, not a
 * status, so it is not listed here.
 */
object StatusGate {
    private val ci = setOf(RegexOption.IGNORE_CASE)

    /** One character of a sentence: anything but its end, where a dot inside a number ("25.00") is not an end. */
    private const val ANY = "(?:[^.!?]|\\.(?=\\d))"

    private val english =
        Regex(
            "\\b(?:failed|fails|failure|declined|rejected|could\\s+not|couldn'?t|unsuccessful(?:ly)?|cancel+ed|void(?:ed)?|" +
                "revers(?:ed|al)|returned|pending|on\\s+hold|(?:authori[sz]ation\\s+)?hold|awaiting|scheduled|requests?|requested|" +
                "processing|under\\s+review|will\\s+be|will\\s+follow|disputed|credited\\s+back|refunded\\s+to\\s+(?:the\\s+)?sender)\\b",
            ci,
        )

    // folded Arabic: ة -> ه, ى -> ي, أ إ آ -> ا
    private val arabic =
        Regex(
            "لم\\s+(?:يتم|تتم)|(?<![\\p{L}])و?فشل|مرفوض|(?<![\\p{L}])و?رفض|(?<![\\p{L}])[وب]?ملغ(?:ي|يه|اه|ا)(?![\\p{L}])|" +
                "(?<![\\p{L}])[وب]?الغا[ءؤو]|(?<![\\p{L}])معلق|قيد\\s+التنفيذ|(?<!حساب\\s)(?<![\\p{L}])جاري(?![\\p{L}])|" +
                "(?<![\\p{L}])[وك]?طلب(?:ك|كم|ه|ها)?(?![\\p{L}])|مرتجع|(?<![\\p{L}])[وب]?ارجاع|(?<![\\p{L}])و?رده|(?<![\\p{L}])[وب]?عكس|" +
                "(?<![\\p{L}])[وس]?سيتم|استرداد\\s+للمرسل",
            ci,
        )

    /** "Cashback of EGP 25.00 will be credited to your account": a promise of a reward is not the status of the purchase. */
    private val rewardPromise =
        Regex(
            "(?:\\bwill\\s+be|وسيتم|سيتم)\\s+$ANY*?(?:cashback|points?|rewards?|كاش\\s*باك|نقاط)$ANY*|" +
                "$ANY*?(?:cashback|points?|rewards?|كاش\\s*باك|نقاط)$ANY*?(?:\\bwill\\s+be|سيتم)$ANY*",
            ci,
        )

    /** True when [folded] (folded, lower-case text) says the operation is not a completed one. */
    fun blocks(folded: String): Boolean {
        val text = rewardPromise.replace(folded, " ")
        return english.containsMatchIn(text) || arabic.containsMatchIn(text)
    }

    /** A single word that is a status word: it must stay a literal token in a learned shape, never merge into a name slot. */
    fun isStatusWord(word: String): Boolean {
        val w = word.lowercase()
        return blocks(w) || (w.length > 1 && blocks(w.drop(1)) && w.first() in "وكبفل")
    }
}
