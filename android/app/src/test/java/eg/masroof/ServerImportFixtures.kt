package eg.masroof

import eg.masroof.data.serverimport.ExpensesBatch
import eg.masroof.data.serverimport.ImportException
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ServerAccount
import eg.masroof.data.serverimport.ServerBudget
import eg.masroof.data.serverimport.ServerCategory
import eg.masroof.data.serverimport.ServerExpense
import eg.masroof.data.serverimport.ServerExpenseRow
import eg.masroof.data.serverimport.ServerForgetter
import eg.masroof.data.serverimport.ServerImportSource
import eg.masroof.data.serverimport.ServerMember
import eg.masroof.data.serverimport.ServerSettings
import eg.masroof.data.serverimport.SessionKeystore
import java.time.Instant

internal const val SRV_ME = "srv-member-1"
internal const val SRV_OTHER = "srv-member-2"

/** The 29 default category names of the phone (`DefaultSeed`) plus two the household added on the server. */
internal val DEFAULT_CATEGORY_NAMES =
    listOf(
        "Groceries",
        "Dining out",
        "Car",
        "Transport",
        "Bills",
        "Rent & housing",
        "Health",
        "Education",
        "Kids",
        "Clothing",
        "Personal care",
        "Household items",
        "Electronics",
        "Entertainment",
        "Subscriptions",
        "Travel",
        "Gifts & charity",
        "Fees & charges",
        "Pets",
        "Other",
        "Fuel",
        "Maintenance",
        "Parking & tolls",
        "Insurance",
        "Electricity",
        "Water",
        "Gas",
        "Internet",
        "Mobile",
    )

internal fun serverCategories(): List<ServerCategory> =
    (DEFAULT_CATEGORY_NAMES + listOf("Gym", "Coffee")).mapIndexed { i, name ->
        ServerCategory("srv-cat-$i", name, "ar-$name", i * 10, null, null, archived = false)
    }

internal fun serverAccounts() =
    listOf(
        ServerAccount("srv-acc-1", "CIB card", "EGP", "CARD", "CIB", "1111"),
        ServerAccount("srv-acc-2", "Cash", "EGP", "CASH", null, null),
        ServerAccount("srv-acc-3", "Vodafone Cash", "EGP", "WALLET", "Vodafone", null),
    )

internal fun serverMembers() = listOf(ServerMember(SRV_ME, "Mohamed"), ServerMember(SRV_OTHER, "Mona"))

internal fun serverBudgets() =
    listOf(
        ServerBudget("srv-bud-1", null, "MONTHLY", 1_000_000, "EGP", rollover = false, alertThresholds = listOf(50, 80, 100)),
        ServerBudget("srv-bud-2", "srv-cat-0", "MONTHLY", 300_000, "EGP", rollover = true, alertThresholds = listOf(80)),
        ServerBudget("srv-bud-3", "srv-cat-1", "WEEKLY", 50_000, "EGP", rollover = false, alertThresholds = emptyList()),
        ServerBudget("srv-bud-4", "srv-cat-2", "CUSTOM", 70_000, "EGP", rollover = false, alertThresholds = listOf(100)),
    )

internal const val SERVER_LIVE = 1_240
private const val SERVER_DELETED = 20
internal val SERVER_BASE: Long = Instant.parse("2026-06-01T10:00:00Z").toEpochMilli()

/** A valid server row; every field a test may care about has a default. */
@Suppress("LongParameterList") // a test builder
internal fun serverExpense(
    id: String,
    amount: Long = 12_500,
    at: Long = SERVER_BASE,
    type: String = "EXPENSE",
    status: String = "CONFIRMED",
    currency: String = "EGP",
    category: String? = null,
    account: String? = null,
    paidBy: String? = null,
    merchant: String? = "Spinneys",
    notes: String? = "weekly",
    deleted: Boolean = false,
    createdAt: Long = at + 1_000,
) = ServerExpense(id, type, status, amount, currency, at, createdAt, category, account, paidBy, merchant, notes, "CARD", deleted)

/** 1,240 live expenses (plus 20 soft-deleted ones) that exercise every type, status and lookup. */
internal fun serverExpenses(): List<ServerExpense> {
    val live =
        (0 until SERVER_LIVE).map { i ->
            serverExpense(
                id = "srv-exp-%04d".format(i),
                amount = 100L + i,
                at = SERVER_BASE + i * 7L * 60_000,
                type =
                    when {
                        i % 97 == 0 -> "TRANSFER"
                        i % 50 == 0 -> "INCOME"
                        i % 61 == 0 -> "REFUND"
                        i % 211 == 0 -> "CASH_WITHDRAWAL"
                        else -> "EXPENSE"
                    },
                status = if (i % 40 == 0) "PENDING_REVIEW" else "CONFIRMED",
                category = if (i % 10 == 0) null else "srv-cat-${i % 31}",
                account = "srv-acc-${i % 3 + 1}",
                paidBy = if (i % 2 == 0) SRV_ME else SRV_OTHER,
            )
        }
    val deleted =
        (0 until SERVER_DELETED).map { serverExpense("srv-del-%02d".format(it), amount = 999, deleted = true) }
    // interleaved, so deleted rows sit in the middle of pages
    return live.take(600) + deleted + live.drop(600)
}

/** A scripted server: pages of [pageSize] rows, with faults injected by call number (0-based, counted over all calls). */
internal class FakeImportSource(
    var rows: List<ServerExpenseRow> = serverExpenses().map { ServerExpenseRow.Valid(it) },
    private val pageSize: Int = 200,
) : ServerImportSource {
    var categories = serverCategories()
    var accounts = serverAccounts()
    var members = serverMembers()
    var budgets = serverBudgets()
    var settings = ServerSettings(monthStartDay = 25, countAtmWithdrawalsAsSpending = true)

    /** Calls of any kind made so far (a record of "the network"). */
    var calls = 0
        private set
    var expenseCalls = 0
        private set

    /** Throws for the [n]th call to `expenses` (0-based), then behaves again. */
    val failExpenseCall = mutableMapOf<Int, ImportFailureKind>()

    /** Runs before the [n]th call to `expenses` (0-based): a kill is a cancel here. */
    val beforeExpenseCall = mutableMapOf<Int, () -> Unit>()

    override suspend fun expenses(
        cursor: String?,
        limit: Int,
    ): ExpensesBatch {
        calls++
        val n = expenseCalls++
        beforeExpenseCall[n]?.invoke()
        failExpenseCall[n]?.let { throw ImportException(it) }
        val from = cursor?.toInt() ?: 0
        val to = minOf(rows.size, from + pageSize)
        return ExpensesBatch(rows.subList(from, to), if (to < rows.size) to.toString() else null)
    }

    override suspend fun categories() = categories.also { calls++ }

    override suspend fun accounts() = accounts.also { calls++ }

    override suspend fun members() = members.also { calls++ }

    override suspend fun budgets() = budgets.also { calls++ }

    override suspend fun settings() = settings.also { calls++ }
}

internal class FakeSessionKeystore : SessionKeystore {
    var destroyed = 0

    override fun destroy() {
        destroyed++
    }
}

internal class RecordingForgetter : ServerForgetter {
    var forgets = 0

    override suspend fun forget() {
        forgets++
    }
}
