package eg.masroof

import eg.masroof.core.Money
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.NewExpense
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.SyncReport
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity
import eg.masroof.data.remote.RemoteSource
import eg.masroof.data.remote.UploadResult
import eg.masroof.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

class FakeRemote : RemoteSource {
    var expenses: List<ExpenseEntity> = emptyList()
    var offline = false
    var uploadResult: suspend (OutboxEntity) -> UploadResult = { UploadResult.Retry }
    val uploadedKeys = mutableListOf<String>()

    override suspend fun fetchExpenses(): List<ExpenseEntity> {
        if (offline) throw IOException("offline")
        return expenses
    }

    override suspend fun fetchCategories(): List<CategoryEntity> = emptyList()

    override suspend fun fetchAccounts(): List<AccountEntity> = emptyList()

    override suspend fun upload(item: OutboxEntity): UploadResult {
        uploadedKeys += item.clientId
        return uploadResult(item)
    }
}

class FakeScheduler : SyncScheduler {
    var calls = 0

    override fun schedule() {
        calls++
    }
}

class FakeRepository : ExpenseRepository {
    val all = MutableStateFlow<List<ExpenseEntity>>(emptyList())
    val categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val pending = MutableStateFlow(0)
    var outcome: RefreshOutcome = RefreshOutcome.Ok
    var refreshCalls = 0
    val added = mutableListOf<NewExpense>()

    override fun observeAll() = all

    override fun observeRecent(limit: Int) = all

    override fun observeMonthTotal() = MutableStateFlow(Money(all.value.sumOf { it.amountMinor }))

    override fun observeCategories() = categories

    override fun observeAccounts() = MutableStateFlow<List<AccountEntity>>(emptyList())

    override fun observePendingCount() = pending

    override suspend fun refresh(): RefreshOutcome {
        refreshCalls++
        return outcome
    }

    override suspend fun add(entry: NewExpense): String {
        added += entry
        return "id-${added.size}"
    }

    override suspend fun syncOutbox() = SyncReport(0, 0, false)

    val retried = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun retryFailed(id: String) {
        retried += id
    }

    override suspend fun deleteFailed(id: String) {
        deleted += id
    }
}

fun expense(
    id: String,
    minor: Long,
    at: Long = 1_000L,
    pending: Boolean = false,
) = ExpenseEntity(id, minor, "EGP", at, "Shop $id", null, null, null, pending)
