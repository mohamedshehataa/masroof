package eg.masroof

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.ui.insights.TrendBar
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.LoadStatus
import eg.masroof.ui.wallet.WalletFigures
import eg.masroof.ui.wallet.WalletMath
import eg.masroof.ui.wallet.WalletUi
import java.time.LocalDate
import java.time.YearMonth

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
