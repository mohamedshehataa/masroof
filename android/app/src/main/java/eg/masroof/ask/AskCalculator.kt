package eg.masroof.ask

import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsExtras
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.AnalyticsRow
import eg.masroof.core.analytics.BusinessCalendar
import eg.masroof.core.analytics.CategoryLine
import eg.masroof.core.analytics.MerchantLine
import eg.masroof.core.analytics.Projections
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** One stored entry as Ask needs it: the analytics columns plus the id (to open it) and the account (to filter by it). */
data class AskRow(
    val id: String,
    val type: String,
    val status: String,
    val currency: String,
    val amountMinor: Long,
    val occurredAtMillis: Long,
    val categoryId: String?,
    val merchant: String?,
    val accountId: String?,
) {
    fun toAnalytics() = AnalyticsRow(type, status, currency, amountMinor, occurredAtMillis, categoryId, merchant)
}

/** Everything the calculator reads: the settings, the lookups and the entries of the span [AskCalculator.span] asked for. */
data class AskContext(
    val today: LocalDate,
    val monthStartDay: Int,
    val includeCash: Boolean,
    val categories: List<AnalyticsCategory>,
    val accounts: List<AskAccount>,
    val rows: List<AskRow>,
)

/**
 * Answers a typed [AskQuery] from Room rows with the same arithmetic Insights uses (`core/analytics`, ADR-0009 D2): EGP only,
 * refunds netted, transfers never, ATM withdrawals per the setting. Pure: no clock, no database, no network.
 */
object AskCalculator {
    const val ENTRY_LIST_LIMIT = 30
    const val INLINE_ENTRIES = 5
    private const val TOP_SHARES = 5
    private const val TOP_MERCHANTS = 5
    private const val TOP_MOVES = 3
    private const val SUGGESTED = 3
    private const val WHY_MERCHANTS = 3
    private const val MIN_ROAST_VISITS = 3
    private const val MIN_LATE_NIGHT = 2
    private const val BPS = 10_000
    private const val PERCENT = 100
    private const val LATE_NIGHT_FROM = 23
    private const val LATE_NIGHT_BEFORE = 5

    // region periods

    internal fun resolve(
        p: AskPeriod,
        today: LocalDate,
        monthStartDay: Int,
    ): AnalyticsPeriod =
        when (p.kind) {
            AskPeriodKind.TODAY -> day(today)
            AskPeriodKind.YESTERDAY -> day(today.minusDays(1))
            AskPeriodKind.THIS_WEEK -> AnalyticsPeriod.week(today.with(DayOfWeek.MONDAY))
            AskPeriodKind.LAST_WEEK -> AnalyticsPeriod.week(today.with(DayOfWeek.MONDAY).minusDays(WEEK_DAYS))
            AskPeriodKind.THIS_MONTH -> AnalyticsPeriod.currentMonth(today, monthStartDay)
            AskPeriodKind.LAST_MONTH -> AnalyticsPeriod.currentMonth(today, monthStartDay).previous()
            AskPeriodKind.THIS_YEAR ->
                AnalyticsPeriod(
                    today.year.toString(),
                    LocalDate.of(today.year, 1, 1),
                    LocalDate.of(today.year, 12, 31),
                    false,
                )
            AskPeriodKind.LAST_DAYS -> AnalyticsPeriod("last-${p.days}", today.minusDays(p.days - 1L), today, false)
            AskPeriodKind.MONTH_OF_YEAR -> AnalyticsPeriod.month(monthOf(p, today, monthStartDay), monthStartDay)
        }

    private const val WEEK_DAYS = 7L

    private fun day(d: LocalDate) = AnalyticsPeriod(d.toString(), d, d, false)

    /** A month named without a year is the most recent one that is not after the current household month. */
    private fun monthOf(
        p: AskPeriod,
        today: LocalDate,
        monthStartDay: Int,
    ): YearMonth {
        p.year?.let { return YearMonth.of(it, p.month) }
        val current = YearMonth.from(AnalyticsPeriod.currentMonth(today, monthStartDay).from)
        val candidate = YearMonth.of(current.year, p.month)
        return if (candidate.isAfter(current)) candidate.minusYears(1) else candidate
    }

    /** The period right before [a], of the same kind. */
    private fun previousOf(
        p: AskPeriod,
        a: AnalyticsPeriod,
    ): AnalyticsPeriod =
        when (p.kind) {
            AskPeriodKind.THIS_MONTH, AskPeriodKind.LAST_MONTH, AskPeriodKind.MONTH_OF_YEAR,
            AskPeriodKind.THIS_WEEK, AskPeriodKind.LAST_WEEK,
            -> a.previous()
            AskPeriodKind.THIS_YEAR ->
                AnalyticsPeriod((a.from.year - 1).toString(), a.from.minusYears(1), a.to.minusYears(1), false)
            AskPeriodKind.TODAY, AskPeriodKind.YESTERDAY, AskPeriodKind.LAST_DAYS -> {
                val len = a.lengthDays().toLong()
                AnalyticsPeriod("before-${a.from}", a.from.minusDays(len), a.from.minusDays(1), false)
            }
        }

    /** The name of the period before one that has a name, when there is one; otherwise the UI shows dates. */
    private fun previousName(
        p: AskPeriod,
        a: AnalyticsPeriod,
    ): AskPeriod? =
        when (p.kind) {
            AskPeriodKind.THIS_MONTH -> AskPeriod.LAST_MONTH
            AskPeriodKind.THIS_WEEK -> AskPeriod.LAST_WEEK
            AskPeriodKind.TODAY -> AskPeriod.YESTERDAY
            AskPeriodKind.LAST_MONTH, AskPeriodKind.MONTH_OF_YEAR -> {
                val ym = YearMonth.from(a.from).minusMonths(1)
                AskPeriod.monthOfYear(ym.monthValue, ym.year)
            }
            else -> null
        }

    private fun info(
        p: AskPeriod?,
        a: AnalyticsPeriod,
    ) = PeriodInfo(p, a.from, a.to)

    /** The epoch-millis range `[from, to)` of entries [query] reads, so the caller makes one range query. */
    fun span(
        query: AskQuery,
        today: LocalDate,
        monthStartDay: Int,
    ): Pair<Long, Long> {
        val periods = ArrayList<AnalyticsPeriod>()

        fun add(p: AskPeriod) {
            periods.add(resolve(p, today, monthStartDay))
        }

        fun addWithPrevious(p: AskPeriod) {
            val a = resolve(p, today, monthStartDay)
            periods.add(a)
            periods.add(previousOf(p, a))
        }
        when (query) {
            is AskQuery.Spend -> add(query.period)
            is AskQuery.Entries -> add(query.period)
            is AskQuery.Breakdown -> add(query.period)
            is AskQuery.Biggest -> add(query.period)
            is AskQuery.TopMerchants -> add(query.period)
            is AskQuery.MerchantVisits -> add(query.period)
            is AskQuery.Why -> addWithPrevious(query.period)
            is AskQuery.Compare -> {
                addWithPrevious(query.current)
                query.previous?.let(::add)
            }
            AskQuery.Payday, AskQuery.Roast, AskQuery.NotUnderstood -> add(AskPeriod.THIS_MONTH)
        }
        val from = periods.minOf { it.start() }.toEpochMilli()
        val to = periods.maxOf { it.endExclusive() }.toEpochMilli()
        return from to to
    }

    // endregion

    // region row helpers

    private fun AskRow.counts(cash: Boolean) = with(AnalyticsCalculator) { toAnalytics().counts(cash) }

    private fun AskRow.countsAsSpend(cash: Boolean) = with(AnalyticsCalculator) { toAnalytics().countsAsSpend(cash) }

    private fun AskRow.signed() = with(AnalyticsCalculator) { toAnalytics().signed() }

    private fun inPeriod(
        rows: List<AskRow>,
        a: AnalyticsPeriod,
    ): List<AskRow> {
        val from = a.start().toEpochMilli()
        val to = a.endExclusive().toEpochMilli()
        return rows.filter {
            it.occurredAtMillis in from until to && (it.status == STATUS_CONFIRMED || it.status == STATUS_PENDING)
        }
    }

    private const val STATUS_CONFIRMED = "CONFIRMED"
    private const val STATUS_PENDING = "PENDING_REVIEW"
    private const val TYPE_EXPENSE = "EXPENSE"
    private const val TYPE_CASH = "CASH_WITHDRAWAL"

    private val anyRow: (AskRow) -> Boolean = { true }

    private fun categoryRows(ids: Set<String>): (AskRow) -> Boolean = { it.categoryId in ids }

    private fun accountRows(ids: Set<String>): (AskRow) -> Boolean = { it.accountId in ids }

    private fun merchantRows(key: String): (AskRow) -> Boolean {
        val words = key.split(' ').filter { it.isNotEmpty() }
        return { SubjectResolver.merchantMatches(it.merchant, words) }
    }

    private fun predicate(subject: AskSubject?): (AskRow) -> Boolean =
        when (subject) {
            is AskSubject.Category -> categoryRows(subject.ids)
            is AskSubject.Account -> accountRows(subject.ids)
            is AskSubject.Merchant -> merchantRows(subject.key)
            else -> anyRow
        }

    private fun label(subject: AskSubject?): LocalizedText? =
        when (subject) {
            is AskSubject.Category -> LocalizedText(subject.nameEn, subject.nameAr)
            is AskSubject.Merchant -> LocalizedText(subject.display, subject.display)
            is AskSubject.Account -> LocalizedText(subject.label, subject.label)
            else -> null
        }

    private fun entry(
        r: AskRow,
        ctx: AskContext,
    ): EntryLine {
        val category = r.categoryId?.let { id -> ctx.categories.firstOrNull { it.id == id } }
        return EntryLine(
            id = r.id,
            merchant = r.merchant?.trim()?.takeIf { it.isNotEmpty() },
            category = category?.let { LocalizedText(it.nameEn, it.nameAr) },
            date = BusinessCalendar.dateOf(Instant.ofEpochMilli(r.occurredAtMillis)),
            amountMinor = r.signed(),
        )
    }

    private fun newestFirst(rows: List<AskRow>) = rows.sortedWith(compareByDescending<AskRow> { it.occurredAtMillis }.thenBy { it.id })

    private fun total(rows: List<AskRow>) = rows.sumOf { it.signed() }

    private fun byId(ctx: AskContext) = ctx.categories.associateBy { it.id }

    private fun lines(
        rows: List<AskRow>,
        ctx: AskContext,
    ): List<CategoryLine> = AnalyticsCalculator.categoryLines(rows.map { it.toAnalytics() }, emptyList(), byId(ctx), ctx.includeCash)

    // endregion

    /** @param ctx must hold the entries of `span(query, ...)` */
    fun answer(
        query: AskQuery,
        ctx: AskContext,
    ): AskAnswer =
        when (query) {
            is AskQuery.Spend -> spend(query, ctx)
            is AskQuery.Entries -> entries(query, ctx)
            is AskQuery.Breakdown -> breakdown(query, ctx)
            is AskQuery.Compare -> compare(query, ctx)
            is AskQuery.MerchantVisits -> merchantVisits(query, ctx)
            is AskQuery.TopMerchants -> topMerchants(query, ctx)
            AskQuery.Payday -> payday(ctx)
            is AskQuery.Biggest -> biggest(query, ctx)
            is AskQuery.Why -> why(query, ctx)
            AskQuery.Roast -> roast(ctx)
            AskQuery.NotUnderstood -> AskAnswer.NotUnderstood(intro(ctx).chips)
        }

    // region subjects

    private sealed interface Resolution {
        data class Found(
            val subject: AskSubject?,
        ) : Resolution

        data class Unknown(
            val text: String,
        ) : Resolution
    }

    private fun resolveSubject(
        subject: AskSubject?,
        ctx: AskContext,
    ): Resolution {
        if (subject !is AskSubject.Text) return Resolution.Found(subject)
        val merchants = ctx.rows.mapNotNull { it.merchant }.distinct()
        val found = SubjectResolver.resolve(subject.text, ctx.categories, ctx.accounts, merchants)
        return if (found == null) Resolution.Unknown(subject.text) else Resolution.Found(found)
    }

    private fun unknown(
        text: String,
        period: AskPeriod,
        ctx: AskContext,
    ): AskAnswer.UnknownSubject {
        val close =
            SubjectResolver.closest(text, ctx.categories, SUGGESTED).map { c ->
                val subject =
                    AskSubject.Category(
                        setOf(c.id) + ctx.categories.filter { it.parentId == c.id }.map { it.id },
                        c.nameEn,
                        c.nameAr,
                    )
                AskChip(AskChipKind.SUGGESTED_CATEGORY, AskQuery.Spend(period, subject), LocalizedText(c.nameEn, c.nameAr))
            }
        return AskAnswer.UnknownSubject(text, close)
    }

    // endregion

    // region REQ-AN-160

    private fun spend(
        q: AskQuery.Spend,
        ctx: AskContext,
    ): AskAnswer {
        val subject =
            when (val r = resolveSubject(q.subject, ctx)) {
                is Resolution.Found -> r.subject
                is Resolution.Unknown -> return if (q.implicit) {
                    AskAnswer.NotUnderstood(
                        intro(ctx).chips,
                    )
                } else {
                    unknown(r.text, q.period, ctx)
                }
            }
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val match = predicate(subject)
        val inP = inPeriod(ctx.rows, a)
        val counted = inP.filter { it.counts(ctx.includeCash) && match(it) }
        val others = inP.count { !it.counts(ctx.includeCash) && it.currency != EGP && it.countsAsSpend(ctx.includeCash) && match(it) }
        val ordered = newestFirst(counted)
        val chips = ArrayList<AskChip>()
        if (counted.size > INLINE_ENTRIES) chips.add(AskChip(AskChipKind.SHOW_ENTRIES, AskQuery.Entries(q.period, subject)))
        chips.add(AskChip(AskChipKind.COMPARE_PREVIOUS, AskQuery.Compare(q.period, null, subject)))
        chips.add(AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(q.period)))
        return AskAnswer.SpendTotal(
            period = info(q.period, a),
            subject = label(subject),
            totalMinor = total(counted),
            count = counted.size,
            otherCurrencyCount = others,
            entries = ordered.take(INLINE_ENTRIES).map { entry(it, ctx) },
            followUps = chips,
        )
    }

    private fun entries(
        q: AskQuery.Entries,
        ctx: AskContext,
    ): AskAnswer {
        val subject =
            when (val r = resolveSubject(q.subject, ctx)) {
                is Resolution.Found -> r.subject
                is Resolution.Unknown -> return unknown(r.text, q.period, ctx)
            }
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val match = predicate(subject)
        val counted = newestFirst(inPeriod(ctx.rows, a).filter { it.counts(ctx.includeCash) && match(it) })
        val chips =
            listOf(
                AskChip(AskChipKind.COMPARE_PREVIOUS, AskQuery.Compare(q.period, null, subject)),
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(q.period)),
            )
        return AskAnswer.EntryList(
            info(q.period, a),
            label(subject),
            counted.size,
            counted.take(ENTRY_LIST_LIMIT).map { entry(it, ctx) },
            chips,
        )
    }

    // endregion

    // region REQ-AN-161

    /** [amounts] scaled to [units] with the largest-remainder method, so the parts always add up to exactly [units]. */
    internal fun largestRemainder(
        amounts: List<Long>,
        units: Int,
    ): List<Int> {
        val whole = amounts.sum()
        if (whole <= 0) return amounts.map { 0 }
        val floors = amounts.map { (it * units / whole).toInt() }.toMutableList()
        val remainders = amounts.map { it * units % whole }
        val leftover = units - floors.sum()
        val order = amounts.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenByDescending { amounts[it] }.thenBy { it })
        for (i in 0 until leftover) floors[order[i]] += 1
        return floors
    }

    private fun breakdown(
        q: AskQuery.Breakdown,
        ctx: AskContext,
    ): AskAnswer {
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val inP = inPeriod(ctx.rows, a)
        val counted = inP.filter { it.counts(ctx.includeCash) }
        val others = inP.count { it.currency != EGP && it.countsAsSpend(ctx.includeCash) }
        val lines = lines(inP, ctx)
        val top = lines.take(TOP_SHARES)
        val rest = lines.drop(TOP_SHARES)
        val amounts = top.map { it.total } + if (rest.isEmpty()) emptyList() else listOf(rest.sumOf { it.total })
        val bps = largestRemainder(amounts, BPS)
        val percents = largestRemainder(amounts, PERCENT)
        val shares =
            amounts.indices.map { i ->
                if (i < top.size) {
                    val l = top[i]
                    val ids = l.categoryId?.let { id -> setOf(id) + ctx.categories.filter { it.parentId == id }.map { it.id } }
                    val subject = ids?.let { AskSubject.Category(it, l.nameEn, l.nameAr) }
                    CategoryShare(
                        l.categoryId,
                        LocalizedText(l.nameEn, l.nameAr),
                        l.total,
                        bps[i],
                        percents[i],
                        subject?.let { AskQuery.Spend(q.period, it) },
                    )
                } else {
                    CategoryShare(null, LocalizedText("Other", "أخرى"), amounts[i], bps[i], percents[i], null, isOther = true)
                }
            }
        val chips =
            listOf(
                AskChip(AskChipKind.TOP_PLACES, AskQuery.TopMerchants(q.period)),
                AskChip(AskChipKind.COMPARE_PREVIOUS, AskQuery.Compare(q.period, null, null)),
            )
        return AskAnswer.Breakdown(info(q.period, a), total(counted), others, shares, chips)
    }

    // endregion

    // region REQ-AN-162

    private fun compare(
        q: AskQuery.Compare,
        ctx: AskContext,
    ): AskAnswer {
        val subject =
            when (val r = resolveSubject(q.subject, ctx)) {
                is Resolution.Found -> r.subject
                is Resolution.Unknown -> return unknown(r.text, q.current, ctx)
            }
        var curAsk: AskPeriod? = q.current
        var cur = resolve(q.current, ctx.today, ctx.monthStartDay)
        var prevAsk: AskPeriod? = q.previous ?: previousName(q.current, cur)
        var prev = q.previous?.let { resolve(it, ctx.today, ctx.monthStartDay) } ?: previousOf(q.current, cur)
        if (prev.from.isAfter(cur.from)) {
            cur = prev.also { prev = cur }
            curAsk = prevAsk.also { prevAsk = curAsk }
        }
        val match = predicate(subject)
        val curRows = inPeriod(ctx.rows, cur)
        val prevRows = inPeriod(ctx.rows, prev)
        val curTotal = total(curRows.filter { it.counts(ctx.includeCash) && match(it) })
        val prevTotal = total(prevRows.filter { it.counts(ctx.includeCash) && match(it) })
        val moves =
            if (subject == null) {
                val now = lines(curRows, ctx).associateBy { it.categoryId }
                val before = lines(prevRows, ctx).associateBy { it.categoryId }
                (now.keys + before.keys)
                    .map { id ->
                        val any = now[id] ?: before.getValue(id)
                        CategoryMove(LocalizedText(any.nameEn, any.nameAr), now[id]?.total ?: 0L, before[id]?.total ?: 0L)
                    }.filter { it.currentMinor != it.previousMinor }
                    .sortedWith(compareByDescending<CategoryMove> { Math.abs(it.currentMinor - it.previousMinor) }.thenBy { it.name.en })
                    .take(TOP_MOVES)
            } else {
                emptyList()
            }
        val anchor = curAsk ?: AskPeriod.THIS_MONTH
        val chips =
            listOf(
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(anchor)),
                AskChip(AskChipKind.TOP_PLACES, AskQuery.TopMerchants(anchor)),
            )
        return AskAnswer.Compare(
            current = info(curAsk, cur),
            previous = info(prevAsk, prev),
            subject = label(subject),
            currentMinor = curTotal,
            previousMinor = prevTotal,
            deltaBps = Projections.deltaBps(curTotal, prevTotal),
            moves = moves,
            followUps = chips,
        )
    }

    // endregion

    // region REQ-AN-163

    private fun merchantStats(
        rows: List<AskRow>,
        key: List<String>,
    ) = AnalyticsExtras
        .merchants(rows.map { it.toAnalytics() })
        .filter { SubjectResolver.merchantMatches(it.name, key) }

    private fun merchantVisits(
        q: AskQuery.MerchantVisits,
        ctx: AskContext,
    ): AskAnswer {
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val key = SubjectResolver.canonTokens(q.merchant)
        val matched = merchantStats(inPeriod(ctx.rows, a), key)
        val visits = matched.sumOf { it.visits }
        val total = matched.sumOf { it.total }
        val average = if (visits > 0) Projections.divideRounded(total, visits.toLong()) else 0L
        val name = matched.maxByOrNull { it.total }?.name ?: q.merchant
        val chips = ArrayList<AskChip>()
        if (visits > 0) {
            chips.add(AskChip(AskChipKind.SHOW_ENTRIES, AskQuery.Entries(q.period, AskSubject.Merchant(key.joinToString(" "), name))))
        }
        chips.add(AskChip(AskChipKind.TOP_PLACES, AskQuery.TopMerchants(q.period)))
        return AskAnswer.MerchantVisits(info(q.period, a), name, visits, total, average, chips)
    }

    private fun topMerchants(
        q: AskQuery.TopMerchants,
        ctx: AskContext,
    ): AskAnswer {
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val top =
            AnalyticsExtras
                .merchants(inPeriod(ctx.rows, a).map { it.toAnalytics() })
                .take(TOP_MERCHANTS)
                .map { MerchantStat(it.name, it.total, it.visits) }
        val chips =
            listOf(
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(q.period)),
                AskChip(AskChipKind.BIGGEST, AskQuery.Biggest(q.period)),
            )
        return AskAnswer.TopMerchants(info(q.period, a), top, chips)
    }

    // endregion

    // region REQ-AN-164

    private fun payday(ctx: AskContext): AskAnswer {
        val period = AnalyticsPeriod.currentMonth(ctx.today, ctx.monthStartDay)
        val rows = inPeriod(ctx.rows, period)
        val summary =
            AnalyticsCalculator
                .compute(period, ctx.today, rows.map { it.toAnalytics() }, ctx.categories, ctx.includeCash)
                .summary
        val payday = period.to.plusDays(1)
        val chips =
            listOf(
                AskChip(AskChipKind.BIGGEST, AskQuery.Biggest(AskPeriod.THIS_MONTH)),
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(AskPeriod.THIS_MONTH)),
            )
        return AskAnswer.Payday(
            daysToPayday = ChronoUnit.DAYS.between(ctx.today, payday).toInt(),
            payday = payday,
            incomeMinor = summary.income,
            spentMinor = summary.total,
            leftMinor = if (summary.income > 0) summary.income - summary.total else null,
            projectedMinor = summary.projectedTotal,
            dailyAverageMinor = summary.dailyAverage,
            hasData = rows.isNotEmpty(),
            followUps = chips,
        )
    }

    private fun biggest(
        q: AskQuery.Biggest,
        ctx: AskContext,
    ): AskAnswer {
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val top =
            inPeriod(ctx.rows, a)
                .filter { it.currency == EGP && (it.type == TYPE_EXPENSE || (ctx.includeCash && it.type == TYPE_CASH)) }
                .sortedWith(compareByDescending<AskRow> { it.amountMinor }.thenByDescending { it.occurredAtMillis }.thenBy { it.id })
                .firstOrNull()
        val chips =
            listOf(
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(q.period)),
                AskChip(AskChipKind.TOP_PLACES, AskQuery.TopMerchants(q.period)),
            )
        return AskAnswer.Biggest(info(q.period, a), top?.let { entry(it, ctx) }, chips)
    }

    // endregion

    private fun why(
        q: AskQuery.Why,
        ctx: AskContext,
    ): AskAnswer {
        val subject =
            when (val r = resolveSubject(q.subject, ctx)) {
                is Resolution.Found -> r.subject
                is Resolution.Unknown -> return unknown(r.text, q.period, ctx)
            } ?: return AskAnswer.NotUnderstood(intro(ctx).chips)
        val a = resolve(q.period, ctx.today, ctx.monthStartDay)
        val prev = previousOf(q.period, a)
        val match = predicate(subject)
        val cur = inPeriod(ctx.rows, a).filter { it.counts(ctx.includeCash) && match(it) }
        val before = inPeriod(ctx.rows, prev).filter { it.counts(ctx.includeCash) && match(it) }
        val curTotal = total(cur)
        val prevTotal = total(before)
        val merchants =
            AnalyticsExtras
                .merchants(cur.map { it.toAnalytics() })
                .take(WHY_MERCHANTS)
                .map { MerchantStat(it.name, it.total, it.visits) }
        val chips =
            listOf(
                AskChip(AskChipKind.SHOW_ENTRIES, AskQuery.Entries(q.period, subject)),
                AskChip(AskChipKind.COMPARE_PREVIOUS, AskQuery.Compare(q.period, null, subject)),
            )
        return AskAnswer.Why(
            info(q.period, a),
            label(subject) ?: LocalizedText("", ""),
            curTotal,
            prevTotal,
            Projections.deltaBps(curTotal, prevTotal),
            merchants,
            chips,
        )
    }

    // region REQ-AN-165

    private fun lateNight(r: AskRow): Boolean {
        val hour = Instant.ofEpochMilli(r.occurredAtMillis).atZone(BusinessCalendar.CAIRO).hour
        return hour >= LATE_NIGHT_FROM || hour < LATE_NIGHT_BEFORE
    }

    private fun roast(ctx: AskContext): AskAnswer {
        val a = AnalyticsPeriod.currentMonth(ctx.today, ctx.monthStartDay)
        val rows = inPeriod(ctx.rows, a)
        val out = ArrayList<RoastLine>()
        val regular =
            AnalyticsExtras
                .merchants(rows.map { it.toAnalytics() })
                .sortedWith(compareByDescending<MerchantLine> { it.visits }.thenByDescending { it.total }.thenBy { it.name })
                .firstOrNull()
        if (regular != null && regular.visits >= MIN_ROAST_VISITS) {
            out.add(RoastLine(RoastKind.FREQUENT_MERCHANT, LocalizedText(regular.name, regular.name), regular.visits))
        }
        val biggest = lines(rows, ctx).firstOrNull()
        if (biggest != null) {
            val percent = biggest.shareBps / (BPS / PERCENT)
            out.add(RoastLine(RoastKind.BIGGEST_CATEGORY, LocalizedText(biggest.nameEn, biggest.nameAr), biggest.count, percent))
        }
        val late = rows.count { it.counts(false) && it.type == TYPE_EXPENSE && lateNight(it) }
        if (late >= MIN_LATE_NIGHT) out.add(RoastLine(RoastKind.LATE_NIGHT, count = late))
        if (out.size == 1) out.add(RoastLine(RoastKind.GENERIC))
        val chips =
            listOf(
                AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(AskPeriod.THIS_MONTH)),
                AskChip(AskChipKind.TOP_PLACES, AskQuery.TopMerchants(AskPeriod.THIS_MONTH)),
            )
        return AskAnswer.Roast(out.take(3), chips)
    }

    /** The greeting fact (top merchant by total this period) and the suggestion chips built from the data. */
    fun intro(ctx: AskContext): AskIntro {
        val a = AnalyticsPeriod.currentMonth(ctx.today, ctx.monthStartDay)
        val rows = inPeriod(ctx.rows, a)
        val merchant = AnalyticsExtras.merchants(rows.map { it.toAnalytics() }).firstOrNull()
        val category = lines(rows, ctx).firstOrNull()
        val chips = ArrayList<AskChip>()
        if (merchant != null) {
            chips.add(
                AskChip(
                    AskChipKind.FREQUENCY,
                    AskQuery.MerchantVisits(AskPeriod.THIS_MONTH, ArabicText.normalize(merchant.name)),
                    LocalizedText(merchant.name, merchant.name),
                ),
            )
        }
        if (category?.categoryId != null) {
            val ids = setOf(category.categoryId) + ctx.categories.filter { it.parentId == category.categoryId }.map { it.id }
            val subject = AskSubject.Category(ids, category.nameEn, category.nameAr)
            chips.add(
                AskChip(AskChipKind.WHY_HIGH, AskQuery.Why(AskPeriod.THIS_MONTH, subject), LocalizedText(category.nameEn, category.nameAr)),
            )
        }
        chips.add(AskChip(AskChipKind.COMPARE_MONTHS, AskQuery.Compare(AskPeriod.THIS_MONTH, AskPeriod.LAST_MONTH)))
        chips.add(AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(AskPeriod.THIS_MONTH)))
        chips.add(AskChip(AskChipKind.PAYDAY, AskQuery.Payday))
        chips.add(AskChip(AskChipKind.ROAST, AskQuery.Roast))
        return AskIntro(merchant?.let { MerchantStat(it.name, it.total, it.visits) }, chips)
    }

    // endregion

    private const val EGP = "EGP"
}
