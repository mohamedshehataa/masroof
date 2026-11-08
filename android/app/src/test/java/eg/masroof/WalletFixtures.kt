package eg.masroof

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.WalletRow
import eg.masroof.data.goals.GoalSummary
import eg.masroof.data.wallet.WalletSource
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.NetWorth
import eg.masroof.ui.insights.TrendBar
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.wallet.WalletFigures
import eg.masroof.ui.wallet.WalletMath
import eg.masroof.ui.wallet.WalletUi
import eg.masroof.ui.wallet.WalletViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

internal val WALLET_TODAY: LocalDate = LocalDate.of(2026, 11, 10)
internal val WALLET_NOV: AnalyticsPeriod = AnalyticsPeriod.month(YearMonth.of(2026, 11), 1)

/** A loaded Wallet state with only the figures a test cares about; everything else is empty. */
@Suppress("LongParameterList")
internal fun walletUi(
    spent: Long = 0L,
    deltaBps: Int? = null,
    pendingReview: Int = 0,
    trend: List<TrendBar> = emptyList(),
    recent: List<ExpenseRow> = emptyList(),
    status: LoadStatus = LoadStatus.READY,
    todayMinor: Long? = null,
) = WalletUi(
    loaded = true,
    status = status,
    today = WALLET_TODAY,
    figures = WalletFigures(WALLET_NOV, spent, todayMinor, 0L, null, deltaBps, pendingReview, emptyList(), trend),
    choices = WalletMath.periodChoices(WALLET_TODAY, 1),
    recent = recent,
)

/** Wallet is one long list: bring the row with [text] into view before asserting on it. */
internal fun ComposeContentTestRule.scrollWalletTo(text: String) {
    onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
}

/** A clock stopped at [iso] (UTC). */
internal fun fixedClock(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

/** A Wallet source whose reads are whatever the test hands in; the defaults are an empty phone. */
internal class FakeWalletSource(
    private val rows: Flow<List<WalletRow>> = flowOf(emptyList()),
) : WalletSource {
    override fun rows(
        from: Long,
        to: Long,
    ) = rows

    override fun categories(): Flow<List<CategoryEntity>> = flowOf(emptyList())

    override fun accounts(): Flow<List<AccountEntity>> = flowOf(emptyList())

    override fun accountViews(): Flow<List<AccountView>> = flowOf(emptyList())

    override fun netWorth(): Flow<NetWorth> = flowOf(NetWorth.EMPTY)

    override fun goals(): Flow<List<GoalSummary>> = flowOf(emptyList())
}

/** The start screen's view model over fakes, with no database and no network. */
internal fun walletVm(
    source: WalletSource,
    repo: ExpenseRepository,
    clock: Clock,
    settings: SettingsRepository = FakeSettings(),
) = WalletViewModel(source, repo, settings, emptySet(), clock, SavedStateHandle())

internal fun walletRow(
    id: String,
    amount: Long,
    iso: String,
    status: String = "CONFIRMED",
) = WalletRow(id, "EXPENSE", status, "EGP", amount, Instant.parse(iso).toEpochMilli(), null, null, null, null)
