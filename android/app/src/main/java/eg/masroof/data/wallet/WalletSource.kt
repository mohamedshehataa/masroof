package eg.masroof.data.wallet

import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.WalletRow
import eg.masroof.data.goals.GoalRepository
import eg.masroof.data.goals.GoalSummary
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Everything Wallet reads besides the recent list and the coming-up sources. A seam so the view model needs no database in tests. */
interface WalletSource {
    /** Entries that can count in the figures, from [from] to [to] (epoch millis): the D2 filter without flagged entries. */
    fun rows(
        from: Long,
        to: Long,
    ): Flow<List<WalletRow>>

    fun categories(): Flow<List<CategoryEntity>>

    fun accounts(): Flow<List<AccountEntity>>

    fun accountViews(): Flow<List<AccountView>>

    fun netWorth(): Flow<NetWorth>

    fun goals(): Flow<List<GoalSummary>>
}

class RoomWalletSource
    @Inject
    constructor(
        private val db: AppDatabase,
        private val wealth: WealthRepository,
        private val goals: GoalRepository,
    ) : WalletSource {
        override fun rows(
            from: Long,
            to: Long,
        ) = db.expenses().observeWalletRows(from, to)

        override fun categories() = db.lookups().observeEveryCategory()

        override fun accounts() = db.lookups().observeAccounts()

        override fun accountViews() = wealth.observeAccountViews()

        override fun netWorth() = wealth.observeNetWorth()

        override fun goals() = goals.observeGoals()
    }
