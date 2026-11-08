package eg.masroof.ask

/** When a question is about. Resolved against today and the household month start day by the engine (Cairo dates). */
enum class AskPeriodKind { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_DAYS, MONTH_OF_YEAR }

data class AskPeriod(
    val kind: AskPeriodKind,
    /** Number of days for [AskPeriodKind.LAST_DAYS]. */
    val days: Int = 0,
    /** 1..12 for [AskPeriodKind.MONTH_OF_YEAR]. */
    val month: Int = 0,
    /** Null means the most recent such month. */
    val year: Int? = null,
) {
    companion object {
        val TODAY = AskPeriod(AskPeriodKind.TODAY)
        val YESTERDAY = AskPeriod(AskPeriodKind.YESTERDAY)
        val THIS_WEEK = AskPeriod(AskPeriodKind.THIS_WEEK)
        val LAST_WEEK = AskPeriod(AskPeriodKind.LAST_WEEK)
        val THIS_MONTH = AskPeriod(AskPeriodKind.THIS_MONTH)
        val LAST_MONTH = AskPeriod(AskPeriodKind.LAST_MONTH)
        val THIS_YEAR = AskPeriod(AskPeriodKind.THIS_YEAR)

        fun lastDays(n: Int) = AskPeriod(AskPeriodKind.LAST_DAYS, days = n)

        fun monthOfYear(
            month: Int,
            year: Int? = null,
        ) = AskPeriod(AskPeriodKind.MONTH_OF_YEAR, month = month, year = year)
    }
}

/** What a question is filtered by. The parser only produces [Text]; the engine resolves it against the user's own data. */
sealed interface AskSubject {
    /** Words from the question, normalised ([ArabicText.normalize]); not yet matched to anything. */
    data class Text(
        val text: String,
    ) : AskSubject

    /** [ids] holds the category and its sub-categories (sub-categories roll up into their parent). */
    data class Category(
        val ids: Set<String>,
        val nameEn: String,
        val nameAr: String,
    ) : AskSubject

    /** [key] is a normalised merchant name to match; [display] the spelling stored on the entries. */
    data class Merchant(
        val key: String,
        val display: String,
    ) : AskSubject

    data class Account(
        val ids: Set<String>,
        val label: String,
    ) : AskSubject
}

/** The typed form of a question. Money questions are answered over Room by `core/analytics`, never by a model. */
sealed interface AskQuery {
    /** REQ-AN-160. [implicit] is true when the question had neither a spending word nor a period ("groceries"). */
    data class Spend(
        val period: AskPeriod,
        val subject: AskSubject? = null,
        val implicit: Boolean = false,
    ) : AskQuery

    /** The entries behind a [Spend] answer (follow-up chip). */
    data class Entries(
        val period: AskPeriod,
        val subject: AskSubject?,
    ) : AskQuery

    /** REQ-AN-161. */
    data class Breakdown(
        val period: AskPeriod,
    ) : AskQuery

    /** REQ-AN-162. [previous] null means "the period before [current]"; the engine puts the later period first. */
    data class Compare(
        val current: AskPeriod,
        val previous: AskPeriod? = null,
        val subject: AskSubject? = null,
    ) : AskQuery

    /** REQ-AN-163: how often and how much at one merchant. [merchant] is normalised text. */
    data class MerchantVisits(
        val period: AskPeriod,
        val merchant: String,
    ) : AskQuery

    data class TopMerchants(
        val period: AskPeriod,
    ) : AskQuery

    /** REQ-AN-164. */
    data object Payday : AskQuery

    data class Biggest(
        val period: AskPeriod,
    ) : AskQuery

    /** "Why is groceries so high": the total, the previous period and the places behind it. */
    data class Why(
        val period: AskPeriod,
        val subject: AskSubject,
    ) : AskQuery

    /** REQ-AN-165. */
    data object Roast : AskQuery

    data object NotUnderstood : AskQuery
}
