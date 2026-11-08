package eg.masroof.ui.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.core.analytics.BusinessCalendar
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.goals.GoalRepository
import eg.masroof.data.goals.GoalSummary
import eg.masroof.data.wallet.ComingUpItem
import eg.masroof.data.wallet.ComingUpKind
import eg.masroof.data.wallet.ComingUpSource
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.toRows
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val RECENT_LIMIT = 5
private const val KEY_PERIOD = "wallet_period"
private const val KEY_ACCOUNT = "wallet_account"

/** Bills due in the next days, for the "Bills due" tile. */
data class BillsDue(
    val count: Int,
    val totalMinor: Long,
)

data class WalletUi(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val figures: WalletFigures? = null,
    val choices: List<AnalyticsPeriod> = emptyList(),
    /** The chosen period's label (`YYYY-MM`); null = the current one. */
    val selectedPeriod: String? = null,
    val accounts: List<AccountView> = emptyList(),
    val selectedAccountId: String? = null,
    val balance: WalletBalance = WalletBalance(null, 0, 0),
    val owedMinor: Long = 0L,
    val comingUp: List<ComingUpItem> = emptyList(),
    val recent: List<ExpenseRow> = emptyList(),
    val billsDue: BillsDue = BillsDue(0, 0L),
    val topGoal: GoalSummary? = null,
    /** Nothing recorded yet: Wallet shows the "Get started" cards instead of empty tiles. */
    val firstRun: Boolean = false,
    /** The category emoji by id, for the top-spending legend. */
    val categoryEmoji: Map<String, String> = emptyMap(),
)

private data class Query(
    val today: LocalDate,
    val period: AnalyticsPeriod,
    val choices: List<AnalyticsPeriod>,
    val includeCash: Boolean,
    val accountId: String?,
    val label: String?,
)

/** Wallet reads Room only: every figure is computed on the phone with the same calculator Insights uses (ADR-0009 D2). */
@HiltViewModel
class WalletViewModel
    @Inject
    constructor(
        private val db: AppDatabase,
        expenses: ExpenseRepository,
        settings: SettingsRepository,
        wealth: WealthRepository,
        goals: GoalRepository,
        sources: Set<@JvmSuppressWildcards ComingUpSource>,
        private val clock: Clock,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val today: Flow<LocalDate> =
            flow {
                while (true) {
                    val now = clock.instant()
                    val day = CairoTime.today(now)
                    emit(day)
                    delay(Duration.between(now, BusinessCalendar.startOfDay(day.plusDays(1))).toMillis() + 1)
                }
            }.distinctUntilChanged()

        private val period = savedState.getStateFlow<String?>(KEY_PERIOD, null)
        private val account = savedState.getStateFlow<String?>(KEY_ACCOUNT, null)

        private val query: Flow<Query> =
            combine(settings.settings, today, period, account) { s, day, label, accountId ->
                val chosen = WalletMath.resolve(label, day, s.monthStartDay)
                Query(
                    day,
                    chosen,
                    WalletMath.periodChoices(day, s.monthStartDay),
                    s.includeCashWithdrawals,
                    accountId,
                    label?.takeIf { chosen.label == it },
                )
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val figures: Flow<Pair<Query, WalletFigures>> =
            query.flatMapLatest { q ->
                val (from, to) = WalletMath.range(q.period)
                combine(db.expenses().observeAskRows(from, to), db.lookups().observeEveryCategory()) { rows, cats ->
                    q to WalletMath.figures(q.period, q.today, rows, cats.map { it.toAnalytics() }, q.includeCash, q.accountId)
                }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val comingUp: Flow<List<ComingUpItem>> =
            today.flatMapLatest { day ->
                if (sources.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(sources.map { it.observe(day, WalletMath.COMING_UP_DAYS) }) { lists ->
                        lists.toList().flatten().sortedWith(compareBy({ it.date }, { it.title }, { it.id }))
                    }
                }
            }

        private val money: Flow<Triple<List<AccountView>, Long, GoalSummary?>> =
            combine(wealth.observeAccountViews(), wealth.observeNetWorth(), goals.observeGoals()) { views, worth, list ->
                Triple(views, WalletMath.owed(worth), list.firstOrNull { it.goal.completedAtMillis == null })
            }

        private val lists: Flow<Pair<List<ExpenseRow>, List<CategoryEntity>>> =
            combine(
                expenses.observeRecent(RECENT_LIMIT),
                expenses.observeLabelCategories(),
                expenses.observeMembers(),
            ) { recent, cats, members ->
                toRows(recent, cats, members) to cats
            }

        val state: StateFlow<WalletUi> =
            combine(figures, money, comingUp, lists) { (q, figs), (views, owed, goal), coming, (recent, cats) ->
                val active = views.filter { !it.account.archived }
                val balance = WalletMath.balance(views, q.accountId)
                val due = coming.filter { it.kind == ComingUpKind.INSTALLMENT || it.kind == ComingUpKind.SUBSCRIPTION }
                WalletUi(
                    loaded = true,
                    today = q.today,
                    figures = figs,
                    choices = q.choices,
                    selectedPeriod = q.label,
                    accounts = active,
                    selectedAccountId = q.accountId?.takeIf { id -> active.any { it.account.id == id } },
                    balance = balance,
                    owedMinor = owed,
                    comingUp = coming,
                    recent = recent,
                    billsDue = BillsDue(due.count { !it.incoming }, due.filter { !it.incoming }.sumOf { it.money.amountMinor }),
                    topGoal = goal,
                    firstRun = recent.isEmpty() && balance.minor == null && coming.isEmpty() && goal == null && owed == 0L,
                    categoryEmoji = cats.mapNotNull { c -> c.emoji?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WalletUi())

        fun selectPeriod(label: String?) {
            savedState[KEY_PERIOD] = label
        }

        fun selectAccount(id: String?) {
            savedState[KEY_ACCOUNT] = id
        }

        private fun CategoryEntity.toAnalytics() = AnalyticsCategory(id, parentId, nameEn, nameAr, color, archived)
    }
