package eg.masroof

import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.ExpenseEntity
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

internal val ANSWER_NOW: Instant = Instant.parse("2026-11-08T09:00:00Z")
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Millis of [days] days (and [hours] hours) before the fixed "now" of the answer tests. */
internal fun ago(
    days: Long,
    hours: Long = 0L,
): Long = ANSWER_NOW.toEpochMilli() - days * DAY_MS - hours * 60 * 60 * 1000

internal class FakeAnswerPrefs(
    initial: AnswerState = AnswerState(),
) : AnswerPrefs {
    val flow = MutableStateFlow(initial)
    override val state = flow

    override suspend fun edit(transform: (AnswerState) -> AnswerState) {
        flow.value = transform(flow.value)
    }

    private val erases =
        java.util.concurrent.atomic
            .AtomicLong()

    override val epoch: Long get() = erases.get()

    override suspend fun clear() {
        erases.incrementAndGet()
        flow.value = AnswerState()
    }
}

@Suppress("LongParameterList") // a test builder: every column a test may care about
internal fun answerEntry(
    id: String,
    type: String = "EXPENSE",
    merchant: String? = null,
    amount: Long = 10_000L,
    at: Long = ago(1),
    source: String? = "SMS",
    accountId: String? = null,
    last4: String? = null,
    method: String? = null,
    category: String? = null,
    status: String = "CONFIRMED",
) = ExpenseEntity(
    id = id,
    amountMinor = amount,
    currency = "EGP",
    occurredAtMillis = at,
    merchant = merchant,
    categoryId = category,
    accountId = accountId,
    notes = null,
    pending = false,
    source = source,
    type = type,
    status = status,
    createdAtMillis = at,
    updatedAtMillis = at,
    paymentMethod = method,
    instrumentLast4 = last4,
)

internal fun answerAccount(
    id: String,
    label: String,
    last4: String? = null,
    kind: String = "DEBIT",
    archived: Boolean = false,
) = AccountEntity(id = id, label = label, currency = "EGP", kind = kind, last4 = last4, archived = archived)
