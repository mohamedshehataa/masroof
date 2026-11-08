package eg.masroof.ui.ask

import android.content.res.Resources
import eg.masroof.R
import eg.masroof.ask.AskAnswer
import eg.masroof.ask.AskChip
import eg.masroof.ask.AskChipKind
import eg.masroof.ask.AskPeriodKind
import eg.masroof.ask.EntryLine
import eg.masroof.ask.LocalizedText
import eg.masroof.ask.MerchantStat
import eg.masroof.ask.PeriodInfo
import eg.masroof.ask.RoastKind
import eg.masroof.ask.RoastLine
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.ui.theme.MoneyStyle
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What an answer card says, ready to show: a headline, supporting lines and an optional small title above. */
data class AnswerText(
    val title: String?,
    val headline: String,
    val lines: List<String>,
)

/**
 * Turns [AskAnswer] data into sentences in the app language, with amounts and numbers in the digit setting
 * (`4,020 EGP` / `٤٬٠٢٠ ج.م`). Strings live in `strings_ask.xml` (values and values-ar).
 */
class AskTexts(
    private val res: Resources,
    private val style: MoneyStyle,
) {
    private val locale: Locale = Locale.forLanguageTag(if (style.arabic) "ar" else "en")

    fun money(minor: Long): String = MoneyFormat.format(Money(minor), style.arabic, style.digits)

    /** `+1,020 EGP` / `-500 EGP`. */
    fun signedMoney(minor: Long): String = (if (minor > 0) "+" else "") + money(minor)

    private fun number(n: Int) = DateFormat.number(n, style.digits)

    private fun percent(bps: Int) = PercentFormat.format(bps, style.arabic, style.digits, signed = true)

    /** A share's name; the "Other" slice has none of its own. */
    fun shareName(share: eg.masroof.ask.CategoryShare): String = if (share.isOther) res.getString(R.string.ask_other) else name(share.name)

    fun name(text: LocalizedText): String = if (style.arabic) text.ar else text.en

    private fun monthName(info: PeriodInfo): String {
        val month = DateTimeFormatter.ofPattern("LLLL", locale).format(info.from)
        return month + " " + DateFormat.digitsAs(info.from.year.toString(), style.digits)
    }

    private fun range(info: PeriodInfo) =
        res.getString(
            R.string.ask_p_range,
            DateFormat.shortDate(info.from, style.arabic, style.digits),
            DateFormat.shortDate(info.to, style.arabic, style.digits),
        )

    /** "this month", "last week", "in September 2026", "in the last 7 days": reads after a verb or an amount. */
    fun periodPhrase(info: PeriodInfo): String {
        val p = info.period ?: return range(info)
        return when (p.kind) {
            AskPeriodKind.TODAY -> res.getString(R.string.ask_p_today)
            AskPeriodKind.YESTERDAY -> res.getString(R.string.ask_p_yesterday)
            AskPeriodKind.THIS_WEEK -> res.getString(R.string.ask_p_this_week)
            AskPeriodKind.LAST_WEEK -> res.getString(R.string.ask_p_last_week)
            AskPeriodKind.THIS_MONTH -> res.getString(R.string.ask_p_this_month)
            AskPeriodKind.LAST_MONTH -> res.getString(R.string.ask_p_last_month)
            AskPeriodKind.THIS_YEAR -> res.getString(R.string.ask_p_this_year)
            AskPeriodKind.LAST_DAYS -> res.getQuantityString(R.plurals.ask_p_last_days, p.days, number(p.days))
            AskPeriodKind.LAST_YEAR -> res.getString(R.string.ask_p_last_year)
            AskPeriodKind.YEAR -> res.getString(R.string.ask_p_month, DateFormat.digitsAs(info.from.year.toString(), style.digits))
            AskPeriodKind.MONTHS_AGO -> res.getString(R.string.ask_p_month, monthName(info))
            AskPeriodKind.DAY_RANGE -> dayRangePhrase(info)
            AskPeriodKind.SINCE_YEAR -> res.getString(R.string.ask_p_since, DateFormat.digitsAs(info.from.year.toString(), style.digits))
            AskPeriodKind.MONTH_OF_YEAR -> res.getString(R.string.ask_p_month, monthName(info))
        }
    }

    private fun dayRangePhrase(info: PeriodInfo): String =
        if (info.from == info.to) {
            res.getString(R.string.ask_p_on, DateFormat.shortDate(info.from, style.arabic, style.digits))
        } else {
            range(info)
        }

    /** A short label for a chart bar. */
    fun barLabel(info: PeriodInfo): String {
        val p = info.period ?: return DateFormat.shortDate(info.from, style.arabic, style.digits)
        return when (p.kind) {
            AskPeriodKind.MONTH_OF_YEAR, AskPeriodKind.MONTHS_AGO -> monthName(info)
            AskPeriodKind.YEAR, AskPeriodKind.LAST_YEAR, AskPeriodKind.SINCE_YEAR ->
                DateFormat.digitsAs(
                    info.from.year.toString(),
                    style.digits,
                )
            AskPeriodKind.LAST_DAYS, AskPeriodKind.DAY_RANGE -> DateFormat.shortDate(info.from, style.arabic, style.digits)
            else -> periodPhrase(info).replaceFirstChar { it.titlecase(locale) }
        }
    }

    private fun times(n: Int) = res.getQuantityString(R.plurals.ask_times, n, number(n))

    fun visits(n: Int) = res.getQuantityString(R.plurals.ask_visits, n, number(n))

    private fun entriesCount(n: Int) = res.getQuantityString(R.plurals.ask_entries, n, number(n))

    private fun otherCurrency(n: Int): String? =
        if (n <= 0) null else res.getQuantityString(R.plurals.ask_other_currency, n, number(n))

    fun chipLabel(chip: AskChip): String {
        val arg = chip.arg?.let(::name).orEmpty()
        return when (chip.kind) {
            AskChipKind.SUGGESTED_CATEGORY -> arg
            AskChipKind.FREQUENCY -> res.getString(R.string.ask_chip_frequency, arg)
            AskChipKind.WHY_HIGH -> res.getString(R.string.ask_chip_why, arg)
            AskChipKind.COMPARE_MONTHS -> res.getString(R.string.ask_chip_compare_months)
            AskChipKind.WHERE_MONEY_WENT -> res.getString(R.string.ask_chip_where)
            AskChipKind.PAYDAY -> res.getString(R.string.ask_chip_payday)
            AskChipKind.ROAST -> res.getString(R.string.ask_chip_roast)
            AskChipKind.SHOW_ENTRIES -> res.getString(R.string.ask_chip_show_entries)
            AskChipKind.COMPARE_PREVIOUS -> res.getString(R.string.ask_chip_compare_prev)
            AskChipKind.TOP_PLACES -> res.getString(R.string.ask_chip_top_places)
            AskChipKind.BIGGEST -> res.getString(R.string.ask_chip_biggest)
        }
    }

    /** "4 visits to Amazon this month, 16,460 EGP" */
    fun greetingFact(m: MerchantStat): String = res.getString(R.string.ask_greeting_fact, visits(m.visits), m.name, money(m.totalMinor))

    fun entryTitle(e: EntryLine): String = e.merchant ?: e.category?.let(::name) ?: res.getString(R.string.ask_no_merchant)

    fun entryDate(e: EntryLine): String = DateFormat.shortDate(e.date, style.arabic, style.digits)

    fun entryDescription(e: EntryLine): String = res.getString(R.string.ask_entry_desc, entryTitle(e), entryDate(e), money(e.amountMinor))

    fun roastLine(l: RoastLine): String =
        when (l.kind) {
            RoastKind.FREQUENT_MERCHANT -> res.getString(R.string.ask_roast_merchant, l.name?.let(::name).orEmpty(), visits(l.count))
            RoastKind.BIGGEST_CATEGORY ->
                res.getString(
                    R.string.ask_roast_category,
                    l.name?.let(::name).orEmpty(),
                    PercentFormat.format(l.percent * PERCENT_TO_BPS, style.arabic, style.digits),
                )
            RoastKind.LATE_NIGHT -> res.getString(R.string.ask_roast_late, number(l.count))
            RoastKind.GENERIC -> res.getString(R.string.ask_roast_generic)
        }

    /** The sentences of [answer]. [AskAnswer.Failed] has none (the card shows its own retry). */
    fun text(answer: AskAnswer): AnswerText =
        when (answer) {
            is AskAnswer.SpendTotal -> spendText(answer)
            is AskAnswer.EntryList -> entryListText(answer)
            is AskAnswer.UnknownSubject -> AnswerText(null, res.getString(R.string.ask_unknown_subject, answer.text), emptyList())
            is AskAnswer.Breakdown -> breakdownText(answer)
            is AskAnswer.Compare -> compareText(answer)
            is AskAnswer.MerchantVisits -> visitsText(answer)
            is AskAnswer.TopMerchants -> topText(answer)
            is AskAnswer.Payday -> paydayText(answer)
            is AskAnswer.Biggest -> biggestText(answer)
            is AskAnswer.Why -> whyText(answer)
            is AskAnswer.Roast ->
                if (answer.lines.isEmpty()) {
                    AnswerText(null, res.getString(R.string.ask_roast_empty), emptyList())
                } else {
                    AnswerText(null, roastLine(answer.lines.first()), answer.lines.drop(1).map(::roastLine))
                }
            is AskAnswer.PeriodUnreadable -> AnswerText(null, res.getString(R.string.ask_period_unreadable), emptyList())
            is AskAnswer.SamePeriod -> AnswerText(null, res.getString(R.string.ask_same_period), emptyList())
            is AskAnswer.OneAtATime -> AnswerText(null, res.getString(R.string.ask_one_at_a_time), emptyList())
            is AskAnswer.NotUnderstood -> AnswerText(null, res.getString(R.string.ask_not_understood), emptyList())
            AskAnswer.Failed -> AnswerText(null, res.getString(R.string.ask_failed), emptyList())
        }

    private fun spendText(a: AskAnswer.SpendTotal): AnswerText {
        val period = periodPhrase(a.period)
        val subject = a.subject?.let(::name)
        val head =
            when {
                a.count == 0 && subject != null -> res.getString(R.string.ask_spend_none_subject, subject, period)
                a.count == 0 -> res.getString(R.string.ask_spend_none, period)
                subject != null -> res.getString(R.string.ask_spend_subject, money(a.totalMinor), subject, period)
                else -> res.getString(R.string.ask_spend_all, money(a.totalMinor), period)
            }
        val left = a.ignored?.let { res.getString(R.string.ask_ignored, subject.orEmpty(), it) }
        val lines = listOfNotNull(if (a.count > 0) entriesCount(a.count) else null, otherCurrency(a.otherCurrencyCount), left)
        return AnswerText(null, head, lines)
    }

    private fun entryListText(a: AskAnswer.EntryList): AnswerText {
        val period = periodPhrase(a.period)
        val subject = a.subject?.let(::name)
        val head =
            when {
                a.count == 0 -> res.getString(R.string.ask_entries_none, period)
                subject != null -> res.getString(R.string.ask_entries_head_subject, subject, period)
                else -> res.getString(R.string.ask_entries_head, period)
            }
        val shown =
            if (a.count >
                a.entries.size
            ) {
                listOf(res.getString(R.string.ask_entries_shown, number(a.entries.size), number(a.count)))
            } else {
                emptyList()
            }
        return AnswerText(null, head, shown)
    }

    private fun breakdownText(a: AskAnswer.Breakdown): AnswerText {
        val period = periodPhrase(a.period)
        val head =
            if (a.shares.isEmpty()) {
                res.getString(R.string.ask_spend_none, period)
            } else {
                res.getString(R.string.ask_breakdown_total, money(a.totalMinor), period)
            }
        return AnswerText(null, head, listOfNotNull(otherCurrency(a.otherCurrencyCount)))
    }

    private fun compareText(a: AskAnswer.Compare): AnswerText {
        val cur = periodPhrase(a.current)
        val prev = periodPhrase(a.previous)
        val subject = a.subject?.let(::name)
        val head =
            if (subject != null) {
                res.getString(R.string.ask_compare_head_subject, subject, money(a.currentMinor), cur, money(a.previousMinor), prev)
            } else {
                res.getString(R.string.ask_compare_head, money(a.currentMinor), cur, money(a.previousMinor), prev)
            }
        val diff = a.currentMinor - a.previousMinor
        val lines = ArrayList<String>()
        if (a.deltaBps != null) {
            lines.add(res.getString(R.string.ask_compare_diff, signedMoney(diff), percent(a.deltaBps)))
        } else {
            lines.add(res.getString(R.string.ask_compare_no_prev, prev))
            if (diff != 0L) lines.add(res.getString(R.string.ask_compare_diff_plain, signedMoney(diff)))
        }
        if (a.moves.isNotEmpty()) {
            lines.add(res.getString(R.string.ask_compare_moves))
            a.moves.forEach {
                lines.add(
                    res.getString(R.string.ask_move_row, name(it.name), signedMoney(it.currentMinor - it.previousMinor)),
                )
            }
        }
        return AnswerText(null, head, lines)
    }

    private fun visitsText(a: AskAnswer.MerchantVisits): AnswerText {
        val period = periodPhrase(a.period)
        if (a.visits == 0) return AnswerText(null, res.getString(R.string.ask_visits_none, name(a.name), period), emptyList())
        val head = res.getString(R.string.ask_visits_head, times(a.visits), period, money(a.totalMinor), money(a.averageMinor))
        return AnswerText(name(a.name), head, emptyList())
    }

    private fun topText(a: AskAnswer.TopMerchants): AnswerText {
        val period = periodPhrase(a.period)
        val head = if (a.merchants.isEmpty()) res.getString(R.string.ask_top_none, period) else res.getString(R.string.ask_top_head, period)
        return AnswerText(null, head, emptyList())
    }

    fun topRow(m: MerchantStat): String = res.getString(R.string.ask_top_row, m.name, money(m.totalMinor), visits(m.visits))

    private fun paydayText(a: AskAnswer.Payday): AnswerText {
        val head = res.getQuantityString(R.plurals.ask_payday_days, a.daysToPayday, number(a.daysToPayday))
        if (!a.hasData) return AnswerText(null, head, listOf(res.getString(R.string.ask_payday_empty)))
        val lines = ArrayList<String>()
        val left = a.leftMinor
        when {
            left == null -> lines.add(res.getString(R.string.ask_payday_no_income, money(a.spentMinor), money(a.dailyAverageMinor)))
            left >= 0 -> lines.add(res.getString(R.string.ask_payday_income, money(a.incomeMinor), money(a.spentMinor), money(left)))
            else -> lines.add(res.getString(R.string.ask_payday_over, money(a.incomeMinor), money(a.spentMinor), money(-left)))
        }
        lines.add(res.getString(R.string.ask_payday_pace, money(a.projectedMinor)))
        return AnswerText(null, head, lines)
    }

    private fun biggestText(a: AskAnswer.Biggest): AnswerText {
        val period = periodPhrase(a.period)
        val e = a.entry ?: return AnswerText(null, res.getString(R.string.ask_spend_none, period), emptyList())
        val head = res.getString(R.string.ask_biggest_head, period, money(e.amountMinor))
        return AnswerText(null, head, listOf(res.getString(R.string.ask_biggest_line, entryDate(e), entryTitle(e))))
    }

    private fun whyText(a: AskAnswer.Why): AnswerText {
        val period = periodPhrase(a.period)
        val head = res.getString(R.string.ask_spend_subject, money(a.totalMinor), name(a.subject), period)
        val lines = ArrayList<String>()
        if (a.deltaBps != null) {
            lines.add(res.getString(R.string.ask_why_prev, money(a.previousMinor), percent(a.deltaBps)))
        } else {
            lines.add(res.getString(R.string.ask_why_no_prev))
        }
        if (a.merchants.isNotEmpty()) lines.add(res.getString(R.string.ask_why_places, a.merchants.joinToString(", ") { it.name }))
        return AnswerText(null, head, lines)
    }

    private companion object {
        const val PERCENT_TO_BPS = 100
    }
}
