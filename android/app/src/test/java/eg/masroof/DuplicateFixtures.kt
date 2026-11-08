package eg.masroof

import eg.masroof.core.CairoTime
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import java.time.LocalDateTime

/** The instant of a Cairo wall-clock time on a winter day (UTC+2), so "00:01 Cairo" is 22:01 UTC of the day before. */
internal fun cairo(
    date: String,
    time: String,
): Long =
    LocalDateTime
        .parse("${date}T$time")
        .atZone(CairoTime.zone)
        .toInstant()
        .toEpochMilli()

internal const val DUP_DAY = "2026-11-06"
internal const val DUP_AMOUNT = 145_000L

/** A typed entry. Untimed by default: a back-dated entry is stored at Cairo noon with the time unknown. */
@Suppress("LongParameterList") // a test builder: every column a test may care about
internal fun manualEntry(
    id: String,
    amount: Long = DUP_AMOUNT,
    at: Long = cairo(DUP_DAY, "12:00"),
    timeKnown: Boolean = false,
    category: String? = null,
    notes: String? = null,
    paidBy: String? = null,
    merchant: String? = null,
    type: String = "EXPENSE",
): ExpenseEntity =
    answerEntry(id, type = type, amount = amount, at = at, source = "MANUAL", category = category, merchant = merchant)
        .copy(timeKnown = timeKnown, notes = notes, paidByMemberId = paidBy)

/** An entry the phone captured from an SMS (or a notification). */
@Suppress("LongParameterList") // a test builder: every column a test may care about
internal fun captureEntry(
    id: String,
    amount: Long = DUP_AMOUNT,
    at: Long = cairo(DUP_DAY, "14:05"),
    source: String = "SMS",
    last4: String? = "1111",
    method: String? = "CARD",
    category: String? = null,
    merchant: String? = null,
    type: String = "EXPENSE",
): ExpenseEntity =
    answerEntry(id, type = type, amount = amount, at = at, source = source, last4 = last4, method = method, category = category)
        .copy(timeKnown = true, merchant = merchant)

internal fun manualSource(expenseId: String) = ExpenseSourceEntity("$expenseId:src", expenseId, "MANUAL", expenseId, 1L, null, null)

internal fun captureSource(
    expenseId: String,
    channel: String = "SMS",
    sender: String? = "CIB",
) = ExpenseSourceEntity("$expenseId:src", expenseId, channel, "$expenseId:local", 1L, sender, "1111")
