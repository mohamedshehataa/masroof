package eg.masroof.data.wealth

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AccountFlowRow
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Wealth and Accounts screens read and write. Room is the source; there is no network. */
@Singleton
class WealthRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
    ) {
        fun today(): LocalDate = CairoTime.today(clock.instant())

        fun observeAccountViews(): Flow<List<AccountView>> =
            combine(db.lookups().observeAccounts(), db.accountBalances().observeAll(), db.accountBalances().observeFlowRows()) {
                accounts,
                balances,
                flows,
                ->
                val today = today()
                val byAccount = BalanceMath.attribute(accounts, flows)
                accounts.map {
                    accountView(
                        it,
                        BalanceMath.latest(
                            balances.filter { b ->
                                b.accountId == it.id
                            },
                        ),
                        byAccount[it.id].orEmpty(),
                        today,
                    )
                }
            }

        internal fun accountView(
            account: AccountEntity,
            check: AccountBalanceEntity?,
            rows: List<AccountFlowRow>,
            today: LocalDate,
        ): AccountView {
            if (check == null) return AccountView(account, null, null, null)
            val card = account.kind == KIND_CREDIT_CARD
            val balance = BalanceMath.balance(check, rows, card)
            return AccountView(account, check, balance, if (card) cardStatus(account, balance, rows, today) else null)
        }

        private fun cardStatus(
            account: AccountEntity,
            outstanding: Long,
            rows: List<AccountFlowRow>,
            today: LocalDate,
        ): CardStatus {
            val statementDay = account.statementDay
            val sinceStatement =
                if (statementDay == null) {
                    0L
                } else {
                    val last = CardCycle.lastStatement(today, statementDay)
                    rows
                        .filter {
                            it.currency == account.currency &&
                                BalanceMath.counts(it.status) &&
                                CairoTime.dateOf(it.occurredAtMillis) > last
                        }.sumOf { spendDelta(it) }
                }
            val dueDay = account.dueDay
            return CardStatus(
                outstandingMinor = outstanding,
                availableMinor = account.creditLimitMinor?.let { it - outstanding },
                spentSinceStatementMinor = sinceStatement,
                nextDue = if (statementDay != null && dueDay != null) CardCycle.nextDue(today, statementDay, dueDay) else null,
            )
        }

        /** Card spending after a statement: purchases, cash and transfers add; refunds subtract; payments are not spending. */
        private fun spendDelta(row: AccountFlowRow): Long =
            when (row.type) {
                "EXPENSE", "CASH_WITHDRAWAL", "TRANSFER" -> row.amountMinor
                "REFUND" -> -row.amountMinor
                else -> 0L
            }

        fun observeAssetViews(): Flow<List<AssetView>> =
            db.assets().observeAll().map { list ->
                val today = today()
                list.filter { !it.archived }.map { AssetView(it, AssetValuation.valueOf(it, today, CairoTime::dateOf)) }
            }

        fun observeDebts(): Flow<List<DebtEntity>> = db.debts().observeAll()

        fun observeSnapshots(): Flow<List<NetWorthSnapshotEntity>> = db.netWorthSnapshots().observeAll()

        fun observeNetWorth(): Flow<NetWorth> =
            combine(
                observeAccountViews(),
                observeAssetViews(),
                observeDebts(),
                db.installmentPayments().observeUnpaidTotal(Money.EGP),
            ) { accounts, assets, debts, installments ->
                NetWorthMath.compute(accounts, assets, debts, installments)
            }

        suspend fun addAccount(draft: AccountDraft): String {
            val id = UUID.randomUUID().toString()
            db.withTransaction {
                db.lookups().upsertAccounts(
                    listOf(
                        AccountEntity(
                            id = id,
                            label = draft.label,
                            currency = draft.currency,
                            kind = draft.kind,
                            institution = draft.institution,
                            last4 = draft.last4,
                        ),
                    ),
                )
                update(id, draft)
            }
            return id
        }

        suspend fun updateAccount(
            id: String,
            draft: AccountDraft,
        ) = update(id, draft)

        private suspend fun update(
            id: String,
            draft: AccountDraft,
        ) = db.accountBalances().updateAccountDetails(
            id,
            draft.label,
            draft.kind,
            draft.institution,
            draft.last4,
            draft.emoji,
            draft.limitMinor,
            draft.statementDay,
            draft.dueDay,
        )

        suspend fun archiveAccount(
            id: String,
            archived: Boolean,
        ) = db.accountBalances().setAccountArchived(id, archived)

        /** Stores a MANUAL balance check in the account's currency at [atMillis] (now when null). */
        suspend fun setBalance(
            account: AccountEntity,
            balanceMinor: Long,
            atMillis: Long? = null,
        ) {
            val now = clock.millis()
            db.accountBalances().insert(
                AccountBalanceEntity(
                    UUID.randomUUID().toString(),
                    account.id,
                    balanceMinor,
                    account.currency,
                    minOf(atMillis ?: now, now),
                    "MANUAL",
                    now,
                ),
            )
            refreshSnapshot()
        }

        /** The first counted item (and every later change) updates today's point at once, not at the next start. */
        private suspend fun refreshSnapshot() {
            runCatching { snapshotToday() }
        }

        suspend fun addAsset(draft: AssetDraft) {
            val now = clock.millis()
            db.assets().insert(
                AssetEntity(
                    id = UUID.randomUUID().toString(),
                    kind = draft.kind,
                    name = draft.name,
                    currency = Money.EGP,
                    valueMinor = draft.valueMinor,
                    karat = null,
                    principalMinor = draft.principalMinor,
                    annualRateBp = draft.rateBp,
                    startAtMillis = draft.startAtMillis,
                    maturityAtMillis = draft.maturityAtMillis,
                    goalId = null,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
            refreshSnapshot()
        }

        suspend fun updateAssetValue(
            asset: AssetEntity,
            valueMinor: Long,
        ) {
            db.assets().update(asset.copy(valueMinor = valueMinor, updatedAtMillis = clock.millis()))
            refreshSnapshot()
        }

        suspend fun setAssetHidden(
            asset: AssetEntity,
            hidden: Boolean,
        ) {
            db.assets().update(asset.copy(hiddenFromTotals = hidden, updatedAtMillis = clock.millis()))
            refreshSnapshot()
        }

        suspend fun deleteAsset(asset: AssetEntity) {
            db.assets().delete(asset)
            refreshSnapshot()
        }

        suspend fun addDebt(draft: DebtDraft) {
            db.debts().insert(
                DebtEntity(
                    id = UUID.randomUUID().toString(),
                    name = draft.name,
                    kind = if (draft.owedToMe) "PERSON" else "LOAN",
                    direction = if (draft.owedToMe) "OWED_TO_ME" else "I_OWE",
                    amountMinor = draft.amountMinor,
                    currency = Money.EGP,
                    dueAtMillis = draft.dueAtMillis,
                    settledAtMillis = null,
                    notes = null,
                    createdAtMillis = clock.millis(),
                ),
            )
            refreshSnapshot()
        }

        suspend fun settleDebt(debt: DebtEntity) {
            db.debts().update(debt.copy(settledAtMillis = clock.millis()))
            refreshSnapshot()
        }

        suspend fun deleteDebt(debt: DebtEntity) {
            db.debts().delete(debt)
            refreshSnapshot()
        }

        /**
         * Writes today's (Cairo) net worth point: the row for the day is created or updated, never duplicated. Nothing is
         * written while there is nothing to count, so an empty wealth never records a fake zero. Returns whether it wrote.
         */
        suspend fun snapshotToday(): Boolean {
            val worth = observeNetWorth().first()
            if (!worth.hasData) return false
            val day = today().toString()
            val now = clock.millis()
            val dao = db.netWorthSnapshots()
            db.withTransaction {
                val existing = dao.findByDate(day)
                val row =
                    NetWorthSnapshotEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        cairoDate = day,
                        assetsMinor = worth.assetsMinor,
                        debtsMinor = worth.debtsMinor,
                        netMinor = worth.netMinor,
                        currency = Money.EGP,
                        breakdown = worth.breakdown(),
                        createdAtMillis = existing?.createdAtMillis ?: now,
                    )
                if (existing == null) dao.insert(row) else dao.update(row)
            }
            return true
        }
    }
