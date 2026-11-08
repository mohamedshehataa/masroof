package eg.masroof.ask

/**
 * Turns a typed question (English, Egyptian Arabic, Franco or a mix) into an [AskQuery]. Pure rules: no model, no network,
 * no Android types. Words are matched after [ArabicText.normalize] and article stripping ([ParserLexicon] holds the lists).
 */
object QuestionParser {
    private const val MAX_LENGTH = 300

    /** What the question says once its periods are taken out. */
    private class Facts(
        val text: String,
        val periods: List<AskPeriod>,
        val tokens: Set<String>,
        private val padded: String,
        /** The words left over once filler and intent words are dropped: the thing being asked about. */
        val subject: String,
    ) {
        val period: AskPeriod get() = periods.firstOrNull() ?: AskPeriod.THIS_MONTH

        fun has(phrases: List<String>) = phrases.any { padded.contains(" $it ") }

        fun hasAny(words: Set<String>) = tokens.any { it in words }

        fun subjectOrNull(): AskSubject.Text? = subject.takeIf { it.isNotEmpty() }?.let(AskSubject::Text)
    }

    fun parse(question: String): AskQuery {
        val text = ArabicText.normalize(question.take(MAX_LENGTH))
        if (text.isEmpty()) return AskQuery.NotUnderstood
        val scan = PeriodScanner.scan(text)
        if (scan.invalid) return AskQuery.PeriodUnreadable
        val tokens = ArabicText.tokens(scan.remaining)
        val subject =
            ArabicText
                .normalize(scan.remaining)
                .split(' ')
                .filter { it.isNotEmpty() && ArabicText.stripAl(it) !in ParserLexicon.STOP && !it.all(Char::isDigit) }
                .joinToString(" ")
        val facts = Facts(text, scan.periods, tokens.toSet(), " ${tokens.joinToString(" ")} ", subject)
        return roast(facts)
            ?: payday(facts)
            ?: compare(facts)
            ?: topMerchants(facts)
            ?: frequency(facts)
            ?: why(facts)
            ?: biggest(facts)
            ?: breakdown(facts)
            ?: spend(facts)
            ?: bare(facts)
    }

    private fun roast(f: Facts): AskQuery? {
        val whole = f.text == "roast" || f.has(ParserLexicon.ROAST_PHRASES)
        val stem = f.tokens.any { t -> ParserLexicon.ROAST_STEMS.any { t.contains(it) } || t.startsWith("etre2") }
        return if (whole || stem) AskQuery.Roast else null
    }

    private fun payday(f: Facts) =
        if (f.hasAny(ParserLexicon.PAYDAY_WORDS) || f.has(ParserLexicon.PAYDAY_PHRASES)) AskQuery.Payday else null

    private fun compare(f: Facts): AskQuery? {
        val named = f.hasAny(ParserLexicon.COMPARE_WORDS) || f.has(ParserLexicon.COMPARE_PHRASES)
        val lastPeriod = f.periods.any { it == AskPeriod.LAST_MONTH || it == AskPeriod.LAST_WEEK }
        if (!named && !(lastPeriod && f.has(ParserLexicon.COMPARE_NEEDS_PERIOD))) return null
        val subject = f.subjectOrNull()
        val periods = f.periods
        return when {
            periods.size >= 2 -> AskQuery.Compare(periods[0], periods[1], subject)
            periods.size == 1 && periods[0] == AskPeriod.LAST_MONTH -> AskQuery.Compare(AskPeriod.THIS_MONTH, AskPeriod.LAST_MONTH, subject)
            periods.size == 1 && periods[0] == AskPeriod.LAST_WEEK -> AskQuery.Compare(AskPeriod.THIS_WEEK, AskPeriod.LAST_WEEK, subject)
            periods.size == 1 -> AskQuery.Compare(periods[0], null, subject)
            ArabicText.tokens(f.text).any { it == "week" || it == "اسبوع" || it == "esbo3" } ->
                AskQuery.Compare(AskPeriod.THIS_WEEK, null, subject)
            else -> AskQuery.Compare(AskPeriod.THIS_MONTH, null, subject)
        }
    }

    private fun topMerchants(f: Facts) = if (f.has(ParserLexicon.TOP_MERCHANT_PHRASES)) AskQuery.TopMerchants(f.period) else null

    private fun frequency(f: Facts): AskQuery? {
        if (!f.has(ParserLexicon.FREQUENCY_PHRASES)) return null
        return if (f.subject.isEmpty()) AskQuery.NotUnderstood else AskQuery.MerchantVisits(f.period, f.subject)
    }

    private fun why(f: Facts): AskQuery? {
        if (!f.hasAny(ParserLexicon.WHY_WORDS) || !f.hasAny(ParserLexicon.HIGH_WORDS)) return null
        return f.subjectOrNull()?.let { AskQuery.Why(f.period, it) } ?: AskQuery.NotUnderstood
    }

    private fun biggest(f: Facts): AskQuery? {
        if (!f.hasAny(ParserLexicon.BIGGEST_WORDS) && !f.has(ParserLexicon.BIGGEST_PHRASES)) return null
        return if (f.hasAny(ParserLexicon.CATEGORY_WORDS)) AskQuery.Breakdown(f.period) else AskQuery.Biggest(f.period)
    }

    private fun breakdown(f: Facts): AskQuery? {
        val strong = f.has(ParserLexicon.BREAKDOWN_STRONG)
        val weak = f.subject.isEmpty() && f.has(ParserLexicon.BREAKDOWN_WEAK)
        return if (strong || weak) AskQuery.Breakdown(f.period) else null
    }

    private fun spend(f: Facts): AskQuery? {
        if (!f.hasAny(ParserLexicon.SPEND_WORDS) && !f.has(ParserLexicon.SPEND_PHRASES)) return null
        return AskQuery.Spend(f.period, f.subjectOrNull())
    }

    /** No intent word at all: a period alone is a spending total, a lone word is an implicit question the engine may reject. */
    private fun bare(f: Facts): AskQuery {
        val subject = f.subjectOrNull()
        return when {
            subject == null -> if (f.periods.isEmpty()) AskQuery.NotUnderstood else AskQuery.Spend(f.period)
            f.hasAny(ParserLexicon.QUESTION_WORDS) -> AskQuery.NotUnderstood
            else -> AskQuery.Spend(f.period, subject, implicit = f.periods.isEmpty())
        }
    }
}
