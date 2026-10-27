package eg.masroof

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.BudgetsRemote
import eg.masroof.data.budgets.BudgetsRepository
import eg.masroof.data.budgets.BudgetsSnapshot
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.remote.apiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The /api/analytics/budgets wire format, decoded by the real generated client models. */
object BudgetsFixtures {
    const val GROCERIES = "7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60"
    const val CAR = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"
    const val ID_GROCERIES = "11111111-1111-4111-8111-111111111111"
    const val ID_TOTAL = "22222222-2222-4222-8222-222222222222"
    const val ID_CAR = "33333333-3333-4333-8333-333333333333"

    val categories =
        listOf(
            CategoryEntity(GROCERIES, "Groceries", "بقالة", 1),
            CategoryEntity(CAR, "Car", "سيارة", 2),
        )

    private fun money(
        minor: Long,
        currency: String = "EGP",
    ) = """{"amountMinor":$minor,"currency":"$currency"}"""

    @Suppress("LongParameterList")
    private fun item(
        id: String,
        category: String?,
        limit: Long,
        spent: Long,
        bps: Int,
        fired: String,
        daysLeft: Int = 6,
        period: String = "MONTHLY",
        rolledOver: Long? = null,
        currency: String = "EGP",
    ) = """{"budget":{"id":"$id","householdId":"99999999-9999-4999-8999-999999999999",
        "categoryId":${category?.let { "\"$it\"" } ?: "null"},"period":"$period","amount":${money(limit, currency)},
        "rollover":${rolledOver != null},"alertThresholds":[50,80,100]},"spent":${money(spent, currency)},
        "remaining":${money(limit + (rolledOver ?: 0) - spent, currency)},
        "progressBps":$bps,"alertsFired":$fired,"onTrack":${bps < 10_000},"daysLeft":$daysLeft,
        "periodRange":{"label":"2026-10","from":"2026-10-01","to":"2026-10-31"}}"""

    /** Groceries 50% of 1,000 EGP; total 82.5% of 4,000 EGP; car 120% of 500 EGP (over). Server order is arbitrary. */
    fun json() =
        """{"status":{"period":{"label":"2026-10","from":"2026-10-01","to":"2026-10-31"},"items":[""" +
            item(ID_GROCERIES, GROCERIES, 100_000, 50_000, 5_000, "[50]") + "," +
            item(ID_TOTAL, null, 400_000, 330_000, 8_250, "[50,80]") + "," +
            item(ID_CAR, CAR, 50_000, 60_000, 12_000, "[50,80,100]") +
            """]},"fetchedAtMillis":1000}"""

    private fun wrap(items: String) =
        """{"status":{"period":{"label":"2026-10","from":"2026-10-01","to":"2026-10-31"},"items":[$items]},"fetchedAtMillis":1000}"""

    const val ID_WEEKLY = "44444444-4444-4444-8444-444444444444"

    /** A weekly USD budget of 1,000 with 200 rolled over (effective 1,200), 500 spent. */
    fun weeklyRollover(): BudgetsSnapshot =
        apiJson.decodeFromString(
            BudgetsSnapshot.serializer(),
            wrap(item(ID_WEEKLY, GROCERIES, 100_000, 50_000, 4_167, "[]", period = "WEEKLY", rolledOver = 20_000, currency = "USD")),
        )

    /** Rollover used the whole limit: server reports full progress with nothing spent. */
    fun exhausted(): BudgetsSnapshot =
        apiJson.decodeFromString(
            BudgetsSnapshot.serializer(),
            wrap(item(ID_CAR, CAR, 100_000, 0, 10_000, "[50,80,100]", rolledOver = -100_000)),
        )

    fun five(): BudgetsSnapshot {
        val ids = (1..5).map { "5555555$it-5555-4555-8555-555555555555" }
        return apiJson.decodeFromString(
            BudgetsSnapshot.serializer(),
            wrap(ids.mapIndexed { i, id -> item(id, null, 100_000, 10_000L * (i + 1), 1_000 * (i + 1), "[]") }.joinToString(",")),
        )
    }

    fun snapshot(): BudgetsSnapshot = apiJson.decodeFromString(BudgetsSnapshot.serializer(), json())
}

open class FakeBudgetsRepository : BudgetsRepository {
    val snapshot = MutableStateFlow<BudgetsSnapshot?>(null)
    var refreshOutcome: RefreshOutcome = RefreshOutcome.Ok
    var mutation: MutationOutcome = MutationOutcome.Ok
    var refreshes = 0
    val created = mutableListOf<BudgetDraft>()
    val updated = mutableListOf<Pair<String, BudgetDraft>>()
    val deleted = mutableListOf<String>()

    override fun observe(): Flow<BudgetsSnapshot?> = snapshot

    override suspend fun refresh(): RefreshOutcome {
        refreshes++
        return refreshOutcome
    }

    override suspend fun create(draft: BudgetDraft): MutationOutcome {
        created += draft
        return mutation
    }

    override suspend fun update(
        id: String,
        draft: BudgetDraft,
    ): MutationOutcome {
        updated += id to draft
        return mutation
    }

    override suspend fun delete(id: String): MutationOutcome {
        deleted += id
        return mutation
    }
}

class FakeBudgetsRemote : BudgetsRemote {
    var failure: Exception? = null
    val calls = mutableListOf<String>()

    override suspend fun fetch(nowMillis: Long): BudgetsSnapshot {
        failure?.let { throw it }
        return BudgetsFixtures.snapshot().copy(fetchedAtMillis = nowMillis)
    }

    override suspend fun create(draft: BudgetDraft) {
        failure?.let { throw it }
        calls += "create:${draft.amountMinor}"
    }

    override suspend fun update(
        id: String,
        draft: BudgetDraft,
    ) {
        failure?.let { throw it }
        calls += "update:$id"
    }

    override suspend fun delete(id: String) {
        failure?.let { throw it }
        calls += "delete:$id"
    }
}
