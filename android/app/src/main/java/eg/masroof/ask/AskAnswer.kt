package eg.masroof.ask

import java.time.LocalDate

/** A text in both app languages (category names are stored that way). */
data class LocalizedText(
    val en: String,
    val ar: String,
)

/**
 * The Cairo calendar days an answer covers, both ends inclusive, plus the question's own period for labels
 * ([period] is null for a period that has no name, like "the week before last"; the UI then shows the dates).
 */
data class PeriodInfo(
    val period: AskPeriod?,
    val from: LocalDate,
    val to: LocalDate,
)

/** One entry behind an answer; [id] opens it in Edit. [amountMinor] is signed (refunds are negative). EGP. */
data class EntryLine(
    val id: String,
    val merchant: String?,
    val category: LocalizedText?,
    val date: LocalDate,
    val amountMinor: Long,
)

/** One slice of the donut / one row of the ranked list. [bps] sums to 10,000 over the slices and [percent] to 100. */
data class CategoryShare(
    val categoryId: String?,
    val name: LocalizedText,
    val amountMinor: Long,
    val bps: Int,
    val percent: Int,
    /** The question that tapping the row asks; null for "Other" and "Uncategorised". */
    val query: AskQuery?,
    val isOther: Boolean = false,
)

/** A category whose total moved between two periods. */
data class CategoryMove(
    val name: LocalizedText,
    val currentMinor: Long,
    val previousMinor: Long,
)

data class MerchantStat(
    val name: String,
    val totalMinor: Long,
    val visits: Int,
)

enum class AskChipKind {
    SUGGESTED_CATEGORY,
    FREQUENCY,
    WHY_HIGH,
    COMPARE_MONTHS,
    WHERE_MONEY_WENT,
    PAYDAY,
    ROAST,
    SHOW_ENTRIES,
    COMPARE_PREVIOUS,
    TOP_PLACES,
    BIGGEST,
}

/** A tappable suggestion or follow-up. The label is built by the UI from [kind] and [arg] in the app language. */
data class AskChip(
    val kind: AskChipKind,
    val query: AskQuery,
    val arg: LocalizedText? = null,
)

/** The first thing the screen says: one fact from the data (the top merchant this period) and the suggestion chips. */
data class AskIntro(
    val topMerchant: MerchantStat?,
    val chips: List<AskChip>,
)

enum class RoastKind { FREQUENT_MERCHANT, BIGGEST_CATEGORY, LATE_NIGHT, GENERIC }

/** One playful line built only from the user's own figures. [name] is a merchant or category; [count] a number of visits or purchases. */
data class RoastLine(
    val kind: RoastKind,
    val name: LocalizedText? = null,
    val count: Int = 0,
    val percent: Int = 0,
)

/** What Ask says back, as data. The UI turns it into text in the app language and digit setting. */
sealed interface AskAnswer {
    val followUps: List<AskChip>

    /** REQ-AN-160. [totalMinor] is the figure Insights shows for the same period and filter. */
    data class SpendTotal(
        val period: PeriodInfo,
        val subject: LocalizedText?,
        val totalMinor: Long,
        val count: Int,
        val otherCurrencyCount: Int,
        val entries: List<EntryLine>,
        override val followUps: List<AskChip>,
        /** Words of the question that were left out ("mom" in "groceries for mom"); the answer says so. */
        val ignored: String? = null,
    ) : AskAnswer

    /** The entries behind a total, newest first (at most [AskCalculator.ENTRY_LIST_LIMIT]); [count] is how many there are. */
    data class EntryList(
        val period: PeriodInfo,
        val subject: LocalizedText?,
        val count: Int,
        val entries: List<EntryLine>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** The words did not match a category, account or merchant: no number, but the closest categories as chips. */
    data class UnknownSubject(
        val text: String,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** REQ-AN-161. [shares] is empty when nothing was spent. */
    data class Breakdown(
        val period: PeriodInfo,
        val totalMinor: Long,
        val otherCurrencyCount: Int,
        val shares: List<CategoryShare>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** REQ-AN-162. [deltaBps] is null when the previous period has no spending. [moves] is empty for a filtered question. */
    data class Compare(
        val current: PeriodInfo,
        val previous: PeriodInfo,
        val subject: LocalizedText?,
        val currentMinor: Long,
        val previousMinor: Long,
        val deltaBps: Int?,
        val moves: List<CategoryMove>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** REQ-AN-163. [visits] 0 means no entries for the merchant in the period. */
    data class MerchantVisits(
        val period: PeriodInfo,
        val name: LocalizedText,
        val visits: Int,
        val totalMinor: Long,
        val averageMinor: Long,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    data class TopMerchants(
        val period: PeriodInfo,
        val merchants: List<MerchantStat>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** REQ-AN-164. [leftMinor] is null without income in the period. */
    data class Payday(
        val daysToPayday: Int,
        val payday: LocalDate,
        val incomeMinor: Long,
        val spentMinor: Long,
        val leftMinor: Long?,
        val projectedMinor: Long,
        val dailyAverageMinor: Long,
        val hasData: Boolean,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    data class Biggest(
        val period: PeriodInfo,
        val entry: EntryLine?,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    data class Why(
        val period: PeriodInfo,
        val subject: LocalizedText,
        val totalMinor: Long,
        val previousMinor: Long,
        val deltaBps: Int?,
        val merchants: List<MerchantStat>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** REQ-AN-165. [lines] is empty when there is nothing to roast yet. */
    data class Roast(
        val lines: List<RoastLine>,
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** Both sides of a comparison are the same period; there is nothing to compare. */
    data class SamePeriod(
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** The question names a period that could not be read; no number, only the suggestion chips. */
    data class PeriodUnreadable(
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** The question named several different kinds of thing ("groceries and talabat"); pick one. */
    data class OneAtATime(
        override val followUps: List<AskChip>,
    ) : AskAnswer

    data class NotUnderstood(
        override val followUps: List<AskChip>,
    ) : AskAnswer

    /** The data could not be read; the screen offers a retry. */
    data object Failed : AskAnswer {
        override val followUps: List<AskChip> = emptyList()
    }
}
