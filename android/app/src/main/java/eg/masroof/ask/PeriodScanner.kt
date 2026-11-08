package eg.masroof.ask

/**
 * Finds the periods named in a normalised question (English, Egyptian Arabic, Franco) and blanks them out of the text so
 * the rest can be read as a subject. All patterns are written in [ArabicText.normalize] form.
 */
internal object PeriodScanner {
    class Result(
        /** In the order they appear in the question. */
        val periods: List<AskPeriod>,
        /** The question with every matched period replaced by spaces. */
        val remaining: String,
        /** True when a number was out of range ("last 0 days", "month 14"): the question is not understood. */
        val invalid: Boolean,
    )

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val MAX_DAYS = 366
    private const val MONTHS = 12

    private class Rule(
        val regex: Regex,
        val build: (MatchResult) -> AskPeriod?,
    )

    private fun fixed(
        pattern: String,
        period: AskPeriod,
    ) = Rule(Regex("$B(?:$pattern)$E")) { period }

    private val monthNames: Map<String, Int> =
        buildMap {
            val english =
                listOf(
                    "january",
                    "february",
                    "march",
                    "april",
                    "may",
                    "june",
                    "july",
                    "august",
                    "september",
                    "october",
                    "november",
                    "december",
                )
            english.forEachIndexed { i, n ->
                put(n, i + 1)
                if (n.length > 3) put(n.take(3), i + 1)
            }
            put("sept", 9)
            val arabic =
                listOf(
                    "يناير",
                    "فبراير",
                    "مارس",
                    "أبريل",
                    "مايو",
                    "يونيو",
                    "يوليو",
                    "أغسطس",
                    "سبتمبر",
                    "أكتوبر",
                    "نوفمبر",
                    "ديسمبر",
                )
            arabic.forEachIndexed { i, n -> put(ArabicText.normalize(n), i + 1) }
            put(ArabicText.normalize("يونيه"), 6)
            put(ArabicText.normalize("يوليه"), 7)
            put(ArabicText.normalize("اغسطس"), 8)
            put("yanayer", 1)
            put("febrayer", 2)
            put("mares", 3)
            put("abril", 4)
            put("mayo", 5)
            put("yonyo", 6)
            put("yolyo", 7)
            put("aghostos", 8)
        }

    private val rules: List<Rule> =
        listOf(
            Rule(Regex("$B(?:last|past|akher|akhir|اخر) ([0-9]{1,4}) (?:days|day|ايام|يوم|ayam|yom)$E")) { m ->
                m.groupValues[1]
                    .toInt()
                    .takeIf { it in 1..MAX_DAYS }
                    ?.let(AskPeriod::lastDays)
            },
            Rule(
                Regex(
                    B + "(" + monthNames.keys.sortedByDescending { it.length }.joinToString("|") + ")(?: (20[0-9]{2}))?" + E,
                ),
            ) { m ->
                val year = m.groups[2]?.value?.toInt()
                AskPeriod.monthOfYear(monthNames.getValue(m.groupValues[1]), year)
            },
            Rule(Regex("$B(?:شهر|month|shahr) ([0-9]{1,2})(?: (20[0-9]{2}))?$E")) { m ->
                m.groupValues[1]
                    .toInt()
                    .takeIf { it in 1..MONTHS }
                    ?.let { AskPeriod.monthOfYear(it, m.groups[2]?.value?.toInt()) }
            },
            fixed(
                "last week|previous week|(?:el |al )?esbo3 (?:el madi|elli fat|el fayet|elmadi)|" +
                    "(?:ب|ل)?(?:ال)?اسبوع (?:اللي فات|اللي فاتت|الماضي|السابق|الفايت|اللي عدي|اللي عدا)",
                AskPeriod.LAST_WEEK,
            ),
            fixed(
                "this week|current week|(?:ب|ل)?(?:ال)?اسبوع (?:ده|دا|هذا|الحالي|الجاري)|هذا الاسبوع|" +
                    "(?:el |al )?esbo3 (?:da|dah|deh)|el usbou3 da",
                AskPeriod.THIS_WEEK,
            ),
            fixed(
                "last month|previous month|(?:ب|ل)?(?:ال)?شهر (?:اللي فات|اللي فاتت|الماضي|السابق|الفايت|اللي عدي|اللي عدا)|" +
                    "(?:el |al )?(?:shahr|shahar|shaher|sahr|sha7r) (?:el madi|elli fat|el fayet|elmadi|el madee)",
                AskPeriod.LAST_MONTH,
            ),
            fixed(
                "this month|current month|(?:ب|ل)?(?:ال)?شهر (?:ده|دا|هذا|الحالي|الجاري)|هذا الشهر|" +
                    "(?:el |al )?(?:shahr|shahar|shaher|sahr|sha7r) (?:da|dah|deh|dh)",
                AskPeriod.THIS_MONTH,
            ),
            fixed(
                "this year|(?:ب|ل)?(?:ال)?سنه (?:دي|ديه|الحاليه|هذه)|هذه السنه|(?:ب|ل)?(?:ال)?عام (?:ده|دا|هذا|الحالي)|هذا العام|" +
                    "(?:el |al )?sana (?:di|dih|deh|dee)",
                AskPeriod.THIS_YEAR,
            ),
            fixed("yesterday|امبارح|مبارح|امس|embare7|embar7|embareh|embarh", AskPeriod.YESTERDAY),
            fixed(
                "today|todays|النهارده|النهارد|انهارده|النهاردا|اليوم|(?:el |al )?(?:nahar|nhar|enhar)(?:da|dah|deh|dh)",
                AskPeriod.TODAY,
            ),
        )

    fun scan(normalized: String): Result {
        val chars = normalized.toCharArray()
        val found = ArrayList<Pair<Int, AskPeriod>>()
        var invalid = false
        for (rule in rules) {
            for (m in rule.regex.findAll(String(chars)).toList()) {
                val period = rule.build(m)
                if (period == null) invalid = true else found.add(m.range.first to period)
                for (i in m.range) chars[i] = ' '
            }
        }
        return Result(found.sortedBy { it.first }.map { it.second }, String(chars), invalid)
    }
}
