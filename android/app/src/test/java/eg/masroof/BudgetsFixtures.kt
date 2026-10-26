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

    private fun money(minor: Long) = """{"amountMinor":$minor,"currency":"EGP"}"""

    private fun item(
        id: String,
        category: String?,
        limit: Long,
        spent: Long,
        bps: Int,
        fired: String,
        daysLeft: Int = 6,
    ) = """{"budget":{"id":"$id","householdId":"99999999-9999-4999-8999-999999999999",
        "categoryId":${category?.let { "\"$it\"" } ?: "null"},"period":"MONTHLY","amount":${money(limit)},
        "rollover":false,"alertThresholds":[50,80,100]},"spent":${money(spent)},"remaining":${money(limit - spent)},
        "progressBps":$bps,"alertsFired":$fired,"onTrack":${bps < 10_000},"daysLeft":$daysLeft,
        "periodRange":{"label":"2026-10","from":"2026-10-01","to":"2026-10-31"}}"""

    /** Groceries 50% of 1,000 EGP; total 82.5% of 4,000 EGP; car 120% of 500 EGP (over). Server order is arbitrary. */
    fun json() =
        """{"status":{"period":{"label":"2026-10","from":"2026-10-01","to":"2026-10-31"},"items":[""" +
            item(ID_GROCERIES, GROCERIES, 100_000, 50_000, 5_000, "[50]") + "," +
            item(ID_TOTAL, null, 400_000, 330_000, 8_250, "[50,80]") + "," +
            item(ID_CAR, CAR, 50_000, 60_000, 12_000, "[50,80,100]") +
            """]},"fetchedAtMillis":1000}"""

    fun snapshot(): BudgetsSnapshot = apiJson.decodeFromString(BudgetsSnapshot.serializer(), json())
}

class FakeBudgetsRepository : BudgetsRepository {
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
