package eg.masroof.ask

import eg.masroof.core.CairoTime
import java.time.Year
import java.time.YearMonth

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
        /** True when the question names a period that could not be read ("last 0 days", "month 14", "in 1999", "3 weeks ago"). */
        val invalid: Boolean,
    )

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val MAX_DAYS = 366
    private const val MAX_WEEKS = 52
    private const val MAX_MONTHS_AGO = 24
    private const val MONTHS = 12
    private const val LEAP_YEAR = 2024
    private const val TWO = 2
    private const val YEAR = "(20[0-9]{2})"
    private const val PREFIX = "(?:ب|ل)?(?:ال)?"
    private val CUES = setOf("in", "on", "during", "for", "since", "last", "this", "of", "from", "to", "until", "till", "by")

    /** TAKE: it is a period. SKIP: it is something else (a verb, part of a name) and stays in the text. UNREADABLE: cannot tell. */
    private enum class Verdict { TAKE, SKIP, UNREADABLE }

    private class Rule(
        val regex: Regex,
        /** False when the match is not really a period (an ambiguous word like "may" without a cue); it is left in the text. */
        val accept: (MatchResult, String) -> Verdict = { _, _ -> Verdict.TAKE },
        val build: (MatchResult) -> AskPeriod?,
    )

    private fun fixed(
        pattern: String,
        period: AskPeriod,
    ) = Rule(Regex("$B(?:$pattern)$E")) { period }

    /** Words that are months but also ordinary English: only a month with a cue. */
    private val ambiguousMonths = mapOf("may" to 5, "march" to 3, "mar" to 3)

    private val monthNames: Map<String, Int> =
        buildMap {
            val english = "january february march april may june july august september october november december".split(' ')
            english.forEachIndexed { i, n ->
                put(n, i + 1)
                if (n.length > 3) put(n.take(3), i + 1)
            }
            put("sept", 9)
            val arabic = "يناير فبراير مارس أبريل مايو يونيو يوليو أغسطس سبتمبر أكتوبر نوفمبر ديسمبر يونيه يوليه".split(' ')
            arabic.forEachIndexed { i, n ->
                put(
                    ArabicText.normalize(n),
                    if (i < MONTHS) {
                        i + 1
                    } else if (i == MONTHS) {
                        6
                    } else {
                        7
                    },
                )
            }
            put("yanayer", 1)
            put("febrayer", 2)
            put("mares", 3)
            put("abril", 4)
            put("mayo", 5)
            put("yonyo", 6)
            put("yolyo", 7)
            put("aghostos", 8)
        }

    private fun alternation(names: Collection<String>) = names.sortedByDescending { it.length }.joinToString("|")

    private val clearMonths = monthNames.filterKeys { it !in ambiguousMonths }
    private val anyMonth = alternation(monthNames.keys)

    private val PRONOUNS = setOf("i", "we", "you", "he", "she", "it", "they")
    private val AFTER_MODAL = PRONOUNS + "be"
    private val VERB_PARTICLES = setOf("to", "into", "on")
    private val YEAR_CUES = setOf("in", "during", "for", "of", "year", "سنه", "عام")

    private fun neighbours(
        m: MatchResult,
        text: String,
    ): Pair<String?, String?> =
        text
            .substring(0, m.range.first)
            .trim()
            .split(' ')
            .lastOrNull()
            ?.takeIf { it.isNotEmpty() } to
            text
                .substring(m.range.last + 1)
                .trim()
                .split(' ')
                .firstOrNull()
                ?.takeIf { it.isNotEmpty() }

    /**
     * "may" / "mar" / "march" are months, except as the verb or modal ("I may", "may I", "march to") or part of a name
     * ("at Mar Mina Pharmacy"). A month needs a cue, a year or day number after it, or to open or close the question.
     */
    private fun ambiguousVerdict(
        m: MatchResult,
        text: String,
    ): Verdict {
        val (before, after) = neighbours(m, text)
        val word = m.groupValues[1]
        val modal = word == "may" && (before in PRONOUNS || after in AFTER_MODAL)
        val marching = word == "march" && after in VERB_PARTICLES
        val namePart = before == "at"
        val month =
            m.groups[2] != null ||
                before in CUES ||
                after != null &&
                after.all(Char::isDigit) ||
                before == null ||
                after == null
        return when {
            modal || marching || namePart -> Verdict.SKIP
            month -> Verdict.TAKE
            else -> Verdict.UNREADABLE
        }
    }

    /** A bare 20xx is a year after a cue word, or when it closes the question and is not in the future. */
    private fun yearVerdict(
        m: MatchResult,
        text: String,
    ): Verdict {
        val (before, after) = neighbours(m, text)
        val prefixed = m.value != m.groupValues[1]
        val plausible = m.groupValues[1].toInt() <= Year.now(CairoTime.zone).value
        val closing = after == null && plausible
        return if (prefixed || closing || before in YEAR_CUES) Verdict.TAKE else Verdict.SKIP
    }

    private fun yearOf(m: MatchResult, group: Int) = m.groups[group]?.value?.toInt()

    private val rules: List<Rule> =
        listOf(
            Rule(Regex("$B(?:last|past|akher|akhir|اخر) ([0-9]{1,4}) (?:days|day|ايام|يوم|ayam|yom)$E")) { m ->
                m.groupValues[1]
                    .toInt()
                    .takeIf { it in 1..MAX_DAYS }
                    ?.let(AskPeriod::lastDays)
            },
            Rule(Regex("$B(?:last|past|akher|akhir|اخر) ([0-9]{1,3}) (?:weeks|week|اسابيع|اسبوع)$E")) { m ->
                m.groupValues[1]
                    .toInt()
                    .takeIf { it in 1..MAX_WEEKS }
                    ?.let { AskPeriod.lastDays(it * 7) }
            },
            Rule(Regex("$B(?:([0-9]{1,3}) months? ago|(?:من|قبل) (?:([0-9]{1,3}) (?:شهور|اشهر|شهر)|(شهرين)))$E")) { m ->
                val n = (m.groups[1] ?: m.groups[2])?.value?.toInt() ?: TWO
                n.takeIf { it in 1..MAX_MONTHS_AGO }?.let(AskPeriod::monthsAgo)
            },
            Rule(
                Regex("$B(?:(?:from|between|من) )?([0-9]{1,2}) (?:(?:to|and|till|until|ل|الي|لحد) )?([0-9]{1,2}) ($anyMonth)(?: $YEAR)?$E"),
            ) { m ->
                val from = m.groupValues[1].toInt()
                val to = m.groupValues[2].toInt()
                val month = monthNames.getValue(m.groupValues[3])
                val last = YearMonth.of(yearOf(m, 4) ?: LEAP_YEAR, month).lengthOfMonth()
                if (from in 1..to && to <= last) AskPeriod.dayRange(from, to, month, yearOf(m, 4)) else null
            },
            Rule(Regex("$B(${alternation(clearMonths.keys)})(?: $YEAR)?$E")) { m ->
                AskPeriod.monthOfYear(monthNames.getValue(m.groupValues[1]), yearOf(m, 2))
            },
            Rule(Regex("$B(${alternation(ambiguousMonths.keys)})(?: $YEAR)?$E"), accept = ::ambiguousVerdict) { m ->
                AskPeriod.monthOfYear(ambiguousMonths.getValue(m.groupValues[1]), yearOf(m, 2))
            },
            Rule(Regex("$B(?:ب|ل)?(?:شهر|month|shahr) ([0-9]{1,2})(?: $YEAR)?$E")) { m ->
                m.groupValues[1]
                    .toInt()
                    .takeIf { it in 1..MONTHS }
                    ?.let { AskPeriod.monthOfYear(it, yearOf(m, 2)) }
            },
            fixed(
                "last week|previous week|(?:el |al )?esbo3 (?:el madi|elli fat|el fayet|elmadi)|" +
                    "${PREFIX}اسبوع (?:اللي فات|اللي فاتت|الماضي|السابق|الفايت|اللي عدي|اللي عدا)",
                AskPeriod.LAST_WEEK,
            ),
            fixed(
                "this week|current week|${PREFIX}اسبوع (?:ده|دا|هذا|الحالي|الجاري)|هذا الاسبوع|" +
                    "(?:el |al )?esbo3 (?:da|dah|deh)|el usbou3 da",
                AskPeriod.THIS_WEEK,
            ),
            fixed(
                "last month|previous month|${PREFIX}شهر (?:اللي فات|اللي فاتت|الماضي|السابق|الفايت|اللي عدي|اللي عدا)|" +
                    "(?:el |al )?(?:shahr|shahar|shaher|sahr|sha7r) (?:el madi|elli fat|el fayet|elmadi|el madee)",
                AskPeriod.LAST_MONTH,
            ),
            fixed(
                "this month|current month|${PREFIX}شهر (?:ده|دا|هذا|الحالي|الجاري)|هذا الشهر|" +
                    "(?:el |al )?(?:shahr|shahar|shaher|sahr|sha7r) (?:da|dah|deh|dh)",
                AskPeriod.THIS_MONTH,
            ),
            fixed(
                "last year|previous year|$PREFIX(?:سنه|عام) (?:اللي فات|اللي فاتت|الماضي|السابق|الفايت|اللي عدي|اللي عدا)|" +
                    "(?:el |al )?sana (?:el madya|el madia|elli fat|el madi)",
                AskPeriod.LAST_YEAR,
            ),
            fixed(
                "this year|${PREFIX}سنه (?:دي|ديه|الحاليه|هذه)|هذه السنه|${PREFIX}عام (?:ده|دا|هذا|الحالي)|هذا العام|" +
                    "(?:el |al )?sana (?:di|dih|deh|dee)",
                AskPeriod.THIS_YEAR,
            ),
            Rule(
                Regex("$B(?:(?:year|سنه|عام) )?$YEAR(?! (?:egp|le|جنيه|pound|pounds))$E"),
                accept = ::yearVerdict,
            ) { m -> AskPeriod.year(m.groupValues[1].toInt()) },
            fixed("yesterday|امبارح|مبارح|امس|embare7|embar7|embareh|embarh", AskPeriod.YESTERDAY),
            fixed(
                "today|todays|النهارده|النهارد|انهارده|النهاردا|اليوم|(?:el |al )?(?:nahar|nhar|enhar)(?:da|dah|deh|dh)",
                AskPeriod.TODAY,
            ),
        )

    /** What is left of a period phrase the rules could not read. */
    private val unreadable =
        listOf(
            Regex("$B(?:ago|منذ)$E"),
            Regex("$B(?:in|during|for|of|year|سنه|عام) (?:19|20)[0-9]{2}$E"),
            Regex("$B(?:past|last|akher|akhir|اخر) [0-9]+$E"),
            Regex("$B(?:from|between|من) [0-9]+ (?:(?:to|and|till|until|ل|الي|لحد) )?[0-9]+$E"),
        )

    fun scan(normalized: String): Result {
        val chars = normalized.toCharArray()
        val found = ArrayList<Pair<Int, AskPeriod>>()
        var invalid = false
        for (rule in rules) {
            for (m in rule.regex.findAll(String(chars)).toList()) {
                val verdict = rule.accept(m, String(chars))
                if (verdict == Verdict.UNREADABLE) invalid = true
                if (verdict != Verdict.TAKE) continue
                val period = rule.build(m)
                if (period == null) invalid = true else found.add(m.range.first to period)
                for (i in m.range) chars[i] = ' '
            }
        }
        val left = String(chars)
        if (unreadable.any { it.containsMatchIn(left) }) invalid = true
        return Result(found.sortedBy { it.first }.map { it.second }, left, invalid)
    }
}
