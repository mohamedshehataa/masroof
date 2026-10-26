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
                "processing|under\\s+review|will\\s+be|will\\s+follow|disputed|credited\\s+back|refunded\\s+to\\s+(?:the\\s+)?sender|" +
                "not\\s+(?:successful|completed|processed|approved|delivered)|being\\s+processed|in\\s+progress|timed?\\s+out|expired|" +
                "did\\s+not\\s+go\\s+through|revert(?:ed)?|incomplete|unpaid|pre-?authori[sz]ation|preauth|insufficient|" +
                "subject\\s+to\\b.{0,40}?confirmation|error\\s+\\d|put\\s+on\\s+hold|denied|denial|blocked|aborted|suspended|retry|" +
                "waiting|refused|fail|cancel|decline|reject|chargeback|invalid|unauthori[sz]ed|not\\s+authori[sz]ed|held|" +
                "under\\s+process|status\\s*:|limit\\s+exceeded)\\b|" +
                // a status glued to a reference: "PENDING123", "FAILED01"
                "\\b(?:pending|failed|declined|rejected|denied|cancel+ed|void|fail)\\d",
            ci,
        )

    // folded Arabic: ة -> ه, ى -> ي, أ إ آ -> ا
    private val arabic =
        Regex(
            "لم\\s+(?:يتم|تتم)|(?<![\\p{L}])و?فشل|مرفوض|(?<![\\p{L}])و?رفض|(?<![\\p{L}])[وب]?ملغ(?:ي|يه|اه|ا)(?![\\p{L}])|" +
                "(?<![\\p{L}])[وب]?الغا[ءؤو]|(?<![\\p{L}])معلق|قيد\\s+التنفيذ|(?<!حساب\\s)(?<![\\p{L}])جاري(?![\\p{L}])|" +
                "(?<![\\p{L}])[وك]?طلب(?:ك|كم|ه|ها)?(?![\\p{L}])|مرتجع|(?<![\\p{L}])[وب]?ارجاع|(?<![\\p{L}])و?رده|(?<![\\p{L}])[وب]?عكس|" +
                "(?<![\\p{L}])[وس]?سيتم|استرداد\\s+للمرسل|قيد\\s+(?:المراجعه|الانتظار)|غير\\s+(?:مكتمل|ناجح)|لم\\s+تكتمل|" +
                "(?<![\\p{L}])[وا]?تلغت|(?<![\\p{L}])[وا]?ترفضت|مش\\s+ناجح|تعذر|(?<![\\p{L}])ب?انتظار|تحت\\s+المراجعه|لم\\s+يصل|" +
                "(?<![\\p{L}])مرتد(?![\\p{L}])|بشكل\\s+غير|قيد\\s+المعالجه|(?<![\\p{L}])[مو]?وقوف|(?<![\\p{L}])متوقف|" +
                "(?<![\\p{L}])منتهيه(?![\\p{L}])|غير\\s+مقبول|لم\\s+تنجح|(?<![\\p{L}])مردود|(?<![\\p{L}])الحاله(?![\\p{L}])|لعدم\\s+كفايه",
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

    /** Words that are never part of a merchant or a person's name: a slot holding one is not a name. */
    private val nonNames =
        setOf(
            "cashback",
            "return",
            "returns",
            "returned",
            "refund",
            "refunds",
            "refunded",
            "reversal",
            "reversed",
            "fee",
            "fees",
            "charge",
            "charges",
            "void",
            "error",
            "failed",
            "cancelled",
            "canceled",
            "pending",
            "declined",
            "rejected",
            "expired",
            "timed",
            "out",
            "incomplete",
            "unpaid",
            "reverted",
            "request",
            "requested",
            "hold",
            "scheduled",
            "disputed",
            "insufficient",
            "funds",
            "completed",
            "approved",
            "successful",
            "successfully",
            "not",
            "none",
            "null",
            "n/a",
            "unknown",
        )

    fun isNonName(token: String): Boolean = token.lowercase().trim('.', ',', ':', ';') in nonNames

    /** A word that introduces an identifier or reference: a number right after it is a code, never a store number. */
    private val identifierWords =
        (
            "id no num number trx txn ref rrn acc acct auth code account tid mid terminal reference card invoice order bill " +
                "receipt pos transaction رقم حساب فاتورة فاتوره طلب بطاقة بطاقه " +
                "total amount amt value balance bal vat tax discount qty quantity phone mobile tel " +
                "مبلغ قيمة قيمه رصيد ضريبة ضريبه خصم كمية كميه هاتف موبايل"
        ).split(" ").toSet()

    /** A currency code, word or abbreviation: it marks an amount, so it is never part of a merchant name. */
    private val currencyWords =
        (
            "egp le l.e usd eur gbp sar sr aed dhs dh kwd qar jpy cad chf cny yen riyal " +
                "جنيه جنيها جنية جنيهات ج جم ج.م دولار يورو ريال درهم pound pounds dollar dollars euro euros"
        ).split(" ").toSet()

    private fun bare(token: String) = token.lowercase().trim('.', ',', ':', ';')

    fun isIdentifierWord(token: String): Boolean = bare(token) in identifierWords

    fun isCurrencyWord(token: String): Boolean = bare(token) in currencyWords

    /** A single word that is a status word: it must stay a literal token in a learned shape, never merge into a name slot. */
    fun isStatusWord(word: String): Boolean {
        val w = word.lowercase()
        return blocks(w) || (w.length > 1 && blocks(w.drop(1)) && w.first() in "وكبفل")
    }
}
