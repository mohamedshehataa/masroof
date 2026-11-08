package eg.masroof

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import eg.masroof.core.DigitStyle
import eg.masroof.core.Money
import eg.masroof.core.analytics.AnalyticsCalculator
import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.WalletRow
import eg.masroof.data.wallet.ComingUpItem
import eg.masroof.data.wallet.ComingUpKind
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.DebtLine
import eg.masroof.data.wealth.DebtSource
import eg.masroof.data.wealth.NetWorth
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.wallet.WalletActions
import eg.masroof.ui.wallet.WalletBalance
import eg.masroof.ui.wallet.WalletContent
import eg.masroof.ui.wallet.WalletFigures
import eg.masroof.ui.wallet.WalletMath
import eg.masroof.ui.wallet.WalletSelection
import eg.masroof.ui.wallet.WalletUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 11, 10)
private val NOV = AnalyticsPeriod.month(java.time.YearMonth.of(2026, 11), 1)

private fun at(
    day: String,
    hour: Int = 12,
) = Instant.parse("${day}T${hour.toString().padStart(2, '0')}:00:00Z").toEpochMilli()

private fun row(
    id: String,
    amount: Long,
    day: String,
    type: String = "EXPENSE",
    account: String? = null,
    category: String? = null,
    last4: String? = null,
) = WalletRow(id, type, "CONFIRMED", "EGP", amount, at(day), category, null, account, last4)

private val ROWS =
    listOf(
        row("a", 40_000, "2026-11-10", account = "cib", category = "food"),
        row("b", 10_000, "2026-11-03", account = "cash", category = "food"),
        row("c", 5_000, "2026-11-04", type = "REFUND", account = "cib", category = "food"),
        row("d", 200_000, "2026-11-01", type = "INCOME", account = "cib"),
        row("e", 7_000, "2026-10-20", account = "cib"),
    )
private val ACCOUNTS = listOf(AccountEntity("cib", "CIB", "EGP", last4 = "1234"), AccountEntity("cash", "CASH", "EGP"))
private val CATEGORIES = listOf(AnalyticsCategory("food", null, "Food", "أكل", "#0F766E", false))

private fun account(
    id: String,
    kind: String? = null,
    archived: Boolean = false,
    currency: String = "EGP",
) = AccountEntity(id, id.uppercase(), currency, kind, archived = archived)

private fun view(
    id: String,
    balance: Long?,
    kind: String? = null,
) = AccountView(account(id, kind), null, balance, null)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WalletTest {
    @get:Rule val compose = createComposeRule()

    // --- REQ-UI-082: the numbers ---

    @Test
    fun `REQ-UI-082 spent and today follow the D2 rule and equal the Insights calculator`() {
        val figures = WalletMath.figures(NOV, TODAY, ROWS, ACCOUNTS, CATEGORIES, includeCash = false, accountId = null)
        val insights = AnalyticsCalculator.compute(NOV, TODAY, ROWS.map { it.toAnalytics() }, CATEGORIES, false)
        assertEquals(insights.summary.total, figures.spentMinor)
        assertEquals(45_000L, figures.spentMinor) // 40,000 + 10,000 - 5,000 refund
        assertEquals(40_000L, figures.todayMinor)
        assertEquals(200_000L, figures.incomeMinor)
        assertEquals(7_750, figures.keptBps) // (200,000 - 45,000) / 200,000
        assertEquals("food", figures.categories.first().categoryId)
    }

    @Test
    fun `REQ-UI-082 one account filters spent`() {
        val cib = WalletMath.figures(NOV, TODAY, ROWS, ACCOUNTS, CATEGORIES, false, "cib")
        val cash = WalletMath.figures(NOV, TODAY, ROWS, ACCOUNTS, CATEGORIES, false, "cash")
        assertEquals(35_000L, cib.spentMinor)
        assertEquals(10_000L, cash.spentMinor)
        assertEquals(0L, cash.todayMinor)
    }

    @Test
    fun `REQ-UI-082 another period has no today figure and uses its own month`() {
        val october = AnalyticsPeriod.month(java.time.YearMonth.of(2026, 10), 1)
        val figures = WalletMath.figures(october, TODAY, ROWS, ACCOUNTS, CATEGORIES, false, null)
        assertEquals(7_000L, figures.spentMinor)
        assertNull(figures.todayMinor)
    }

    @Test
    fun `REQ-UI-082 period choices start with the household month and respect the month start day`() {
        val choices = WalletMath.periodChoices(TODAY, 25)
        assertEquals(WalletMath.PERIOD_CHOICES, choices.size)
        assertEquals(LocalDate.of(2026, 10, 25), choices.first().from)
        assertEquals(LocalDate.of(2026, 11, 24), choices.first().to)
        assertEquals(LocalDate.of(2026, 9, 25), choices[1].from)
        assertEquals("2026-10", WalletMath.resolve(null, TODAY, 25).label)
        assertEquals("2026-08", WalletMath.resolve("2026-08", TODAY, 25).label)
        assertEquals("2026-10", WalletMath.resolve("garbage", TODAY, 25).label)
        assertEquals("2026-10", WalletMath.resolve("2026-W40", TODAY, 25).label)
    }

    @Test
    fun `REQ-UI-082 balance sums counted accounts and reports the ones without a balance`() {
        val views = listOf(view("cib", 150_000), view("cash", 20_000), view("wallet", null), view("visa", 30_000, "CREDIT_CARD"))
        val all = WalletMath.balance(views, null)
        assertEquals(170_000L, all.minor) // the card is owed money, not held money
        assertEquals(1, all.uncounted)
        assertEquals(WalletBalance(150_000L, 0, 1), WalletMath.balance(views, "cib"))
        assertEquals(WalletBalance(-30_000L, 0, 1), WalletMath.balance(views, "visa"))
        assertEquals(WalletBalance(null, 1, 1), WalletMath.balance(views, "wallet"))
        assertEquals(WalletBalance(null, 2, 2), WalletMath.balance(listOf(view("a", null), view("b", null)), null))
        assertEquals(WalletBalance(null, 0, 0), WalletMath.balance(emptyList(), null))
    }

    @Test
    fun `REQ-UI-082 you owe is exactly the Debt figure Wealth shows`() {
        val worth =
            NetWorth(
                0,
                981_000,
                emptyList(),
                listOf(
                    DebtLine("d", DebtSource.DEBT, "Ali", 50_000),
                    DebtLine("v", DebtSource.CARD, "Visa", 30_000),
                    DebtLine(null, DebtSource.INSTALLMENTS, null, 900_000),
                    DebtLine("o", DebtSource.OVERDRAWN, "CIB", 1_000),
                ),
                0,
                0,
                true,
            )
        assertEquals(worth.debtsMinor, WalletMath.owed(worth))
        assertEquals(0L, WalletMath.owed(NetWorth.EMPTY))
    }

    @Test
    fun `REQ-UI-082 a credit card without a balance is not an account without a balance`() {
        val views = listOf(view("cib", 10_000), view("visa", null, "CREDIT_CARD"))
        assertEquals(WalletBalance(10_000L, 0, 1), WalletMath.balance(views, null))
    }

    @Test
    fun `REQ-UI-082 the account picker counts SMS spending by the card last four like the balance does`() {
        val rows =
            listOf(
                row("manual", 10_000, "2026-11-05", account = "cib"),
                row("sms", 50_000, "2026-11-06", account = null, last4 = "1234"),
                row("other", 3_000, "2026-11-06", account = null, last4 = "9999"),
            )
        val cib = WalletMath.figures(NOV, TODAY, rows, ACCOUNTS, CATEGORIES, false, "cib")
        assertEquals(60_000L, cib.spentMinor)
        assertEquals(63_000L, WalletMath.figures(NOV, TODAY, rows, ACCOUNTS, CATEGORIES, false, null).spentMinor)
        // two accounts sharing the last four: the entry belongs to neither, exactly as in the balances
        val twins = ACCOUNTS + AccountEntity("twin", "TWIN", "EGP", last4 = "1234")
        assertEquals(10_000L, WalletMath.figures(NOV, TODAY, rows, twins, CATEGORIES, false, "cib").spentMinor)
    }

    @Test
    fun `REQ-UI-082 absurd stored amounts never throw from the figures`() {
        val rows = listOf(row("a", 5_000_000_000_000_000_000, "2026-11-05"), row("b", 5_000_000_000_000_000_000, "2026-11-06"))
        val figures = WalletMath.figuresOrNull(NOV, TODAY, rows, ACCOUNTS, CATEGORIES, false, null)
        assertTrue(figures == null || figures.spentMinor == Long.MAX_VALUE)
    }

    @Test
    fun `REQ-UI-082 the trend ends with the chosen period and uses the same rows`() {
        val figures = WalletMath.figures(NOV, TODAY, ROWS, ACCOUNTS, CATEGORIES, false, null)
        assertEquals(WalletMath.TREND_MONTHS, figures.trend.size)
        assertTrue(figures.trend.last().selected)
        assertEquals(
            figures.spentMinor,
            figures.trend
                .last()
                .total.amountMinor,
        )
        assertEquals(7_000L, figures.trend[figures.trend.size - 2].total.amountMinor)
    }

    @Test
    fun `REQ-UI-082 the period and account choice survive recreation`() {
        val first = SavedStateHandle()
        val selection = WalletSelection(first)
        selection.selectPeriod("2026-09")
        selection.selectAccount("cib")
        // the system saves the handle's values and builds a new one from them
        val restored = WalletSelection(SavedStateHandle(first.keys().associateWith { first.get<Any?>(it) }))
        assertEquals("2026-09", restored.period.value)
        assertEquals("cib", restored.account.value)
    }

    @Test
    fun `REQ-UI-082 the error state offers Retry and keeps the rest of Wallet`() {
        var retried = 0
        val state = walletUi(status = eg.masroof.ui.viewmodel.LoadStatus.ERROR).copy(figures = null)
        compose.setContent { MasroofTheme { WalletContent(state, emptyList(), WalletActions(onRetry = { retried++ })) } }
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar", fontScale = 1.5f)
    fun `REQ-UI-082 Arabic at font scale 1_5 still shows the amount and the actions`() {
        compose.setContent { MasroofTheme { WalletContent(ui(), emptyList(), WalletActions(onAsk = {})) } }
        compose.onAllNodesWithText("٤٥٠ ج.م").onFirst().assertIsDisplayed()
        compose.scrollWalletTo("إضافة")
        compose.onNodeWithText("إضافة").assertIsDisplayed()
    }

    // --- REQ-UI-081: where things live ---

    @Test
    fun `REQ-UI-081 every sub screen keeps its tab highlighted`() {
        assertEquals("home", tabRouteOf(null))
        assertEquals("home", tabRouteOf("home"))
        assertEquals("home", tabRouteOf("insights"))
        assertEquals("home", tabRouteOf("transactions"))
        assertEquals("home", tabRouteOf("settings"))
        assertEquals("home", tabRouteOf("edit/{id}"))
        assertEquals("bills", tabRouteOf("bills"))
        assertEquals("bills", tabRouteOf("bills/{id}"))
        assertEquals("bills", tabRouteOf("bills/add"))
        assertEquals("bills", tabRouteOf("subscriptions/{id}"))
        assertEquals("bills", tabRouteOf("subscriptions/{id}/edit"))
        assertEquals("bills", tabRouteOf("subscriptions/add?name={name}&amount={amount}&cur={cur}&date={date}&merchant={merchant}"))
        assertEquals("plan", tabRouteOf("plan"))
        assertEquals("plan", tabRouteOf("budgets"))
        assertEquals("plan", tabRouteOf("goals"))
        assertEquals("plan", tabRouteOf("goals/{id}/edit"))
        assertEquals("plan", tabRouteOf("goals-new"))
        assertEquals("wealth", tabRouteOf("wealth"))
        assertEquals("wealth", tabRouteOf("accounts"))
        assertEquals("ask", tabRouteOf("ask"))
    }

    @Test
    fun `REQ-UI-081 reminder deep links still land on the allow listed bill`() {
        val id = "0b9a7a52-5d5b-4d63-8a7e-0f6a51a3c1de"
        val intent = android.content.Intent().putExtra(eg.masroof.work.EXTRA_OPEN_ROUTE, "bills/$id")
        assertEquals("bills/$id", MainActivity.routeFrom(intent))
        assertNull(MainActivity.routeFrom(android.content.Intent().putExtra(eg.masroof.work.EXTRA_OPEN_ROUTE, "settings")))
    }

    // --- REQ-UI-082: the screen ---

    private fun ui(
        accounts: List<AccountView> = listOf(view("cib", 150_000), view("wallet", null)),
        selected: String? = null,
        balance: WalletBalance = WalletBalance(150_000L, 1, 2),
        owed: Long = 80_000L,
        firstRun: Boolean = false,
        recent: List<ExpenseRow> = listOf(ExpenseRow("1", "Cafe", Money(5_000), LocalDate.of(2026, 11, 9), false, false, null, null)),
    ) = WalletUi(
        loaded = true,
        today = TODAY,
        figures =
            WalletFigures(
                NOV,
                45_000,
                40_000,
                200_000,
                7_750,
                1_000,
                2,
                WalletMath.figures(NOV, TODAY, ROWS, ACCOUNTS, CATEGORIES, false, null).categories,
            ),
        choices = WalletMath.periodChoices(TODAY, 1),
        accounts = accounts,
        selectedAccountId = selected,
        balance = balance,
        owedMinor = owed,
        comingUp = listOf(ComingUpItem("x", ComingUpKind.INSTALLMENT, "Car loan", LocalDate.of(2026, 11, 12), Money(250_000), false)),
        recent = recent,
        firstRun = firstRun,
    )

    @Test
    fun `REQ-UI-082 wallet shows the period pill spent today balance debts and coming up`() {
        compose.setContent { MasroofTheme { WalletContent(ui(), emptyList(), WalletActions()) } }
        compose.onNodeWithText("1 Nov - 30 Nov").assertIsDisplayed()
        compose.onNodeWithText("Spent").assertIsDisplayed()
        compose.onNodeWithText("450 EGP").assertIsDisplayed()
        compose.onNodeWithText("400 EGP today").assertIsDisplayed()
        compose.onAllNodesWithText("1,500 EGP").onFirst().assertIsDisplayed()
        compose.onNodeWithText("1 account without a balance").assertIsDisplayed()
        compose.onNodeWithText("You owe 800 EGP").assertIsDisplayed()
        compose.onNodeWithText("All accounts").assertIsDisplayed()
    }

    @Test
    fun `REQ-UI-082 quick actions call Add Say it Receipt and Ask`() {
        val calls = mutableListOf<String>()
        val actions =
            WalletActions(
                onAdd = { calls += "add" },
                onSayIt = { calls += "say" },
                onReceipt = { calls += "receipt" },
                onAsk = { calls += "ask" },
            )
        compose.setContent { MasroofTheme { WalletContent(ui(), emptyList(), actions) } }
        listOf("Add", "Say it", "Receipt", "Ask").forEach { compose.onAllNodesWithText(it).onFirst().performClick() }
        assertEquals(listOf("add", "say", "receipt", "ask"), calls)
    }

    @Test
    fun `REQ-UI-081 profile analytics and view all open settings insights and transactions`() {
        val calls = mutableListOf<String>()
        val actions =
            WalletActions(
                onOpenSettings = { calls += "settings" },
                onOpenInsights = { calls += "insights" },
                onOpenTransactions = { calls += "transactions" },
            )
        compose.setContent { MasroofTheme { WalletContent(ui(), emptyList(), actions) } }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Analytics").performClick()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("View all"))
        compose.onNodeWithText("View all").performClick()
        assertEquals(listOf("settings", "insights", "transactions"), calls)
    }

    @Test
    fun `REQ-UI-082 no balance at all asks the user to set balances`() {
        compose.setContent {
            MasroofTheme { WalletContent(ui(balance = WalletBalance(null, 2, 2), owed = 0), emptyList(), WalletActions()) }
        }
        compose.onAllNodesWithText("Set your balances").onFirst().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("You owe", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-UI-082 a selected account is named in the picker`() {
        compose.setContent {
            MasroofTheme { WalletContent(ui(selected = "cib", balance = WalletBalance(150_000L, 0, 1)), emptyList(), WalletActions()) }
        }
        compose.onAllNodesWithText("CIB").onFirst().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("All accounts").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-UI-082 the insight tiles open their screens`() {
        val calls = mutableListOf<String>()
        val actions =
            WalletActions(
                onOpenInsights = { calls += "insights" },
                onOpenBills = { calls += "bills" },
                onOpenBudgets = { calls += "budgets" },
                onOpenGoals = { calls += "goals" },
            )
        compose.setContent { MasroofTheme { WalletContent(ui(), emptyList(), actions) } }
        val list = compose.onAllNodes(hasScrollAction())[0]
        listOf("Top spending", "Bills due", "Budget", "Top goal").forEach {
            list.performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        list.performScrollToNode(hasText("Bills due"))
        compose.onNodeWithText("Bills due").performClick()
        list.performScrollToNode(hasText("Top goal"))
        compose.onNodeWithText("Top goal").performClick()
        list.performScrollToNode(hasText("Budget"))
        compose.onNodeWithText("Budget").performClick()
        assertEquals(listOf("bills", "goals", "budgets"), calls)
    }

    @Test
    fun `REQ-UI-082 a first run shows Get started cards instead of tiles`() {
        val calls = mutableListOf<String>()
        val actions =
            WalletActions(onTurnOnCapture = { calls += "capture" }, onOpenAccounts = { calls += "balances" }, onAddBill = {
                calls +=
                    "bill"
            })
        compose.setContent {
            MasroofTheme {
                WalletContent(
                    ui(accounts = emptyList(), balance = WalletBalance(null, 0, 0), owed = 0, firstRun = true, recent = emptyList()),
                    emptyList(),
                    actions,
                )
            }
        }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Get started"))
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithText("Turn on capture").performClick()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Add a bill"))
        compose.onNodeWithText("Add a bill").performClick()
        assertEquals(listOf("capture", "bill"), calls)
        assertTrue(compose.onAllNodesWithText("Top spending").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-UI-082 amounts use Arabic-Indic digits when that setting is on`() {
        compose.setContent { MasroofTheme(digitOverride = DigitStyle.ARABIC_INDIC) { WalletContent(ui(), emptyList(), WalletActions()) } }
        compose.onAllNodesWithText("٤٥٠", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `REQ-UI-082 while loading wallet shows a skeleton and no figures`() {
        compose.setContent { MasroofTheme { WalletContent(WalletUi(), emptyList(), WalletActions()) } }
        assertTrue(compose.onAllNodesWithText("Spent").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-AN-175 a listed entry that is not counted carries a marker`() {
        val excluded = ExpenseRow("x", "Gold", Money(5_000), LocalDate.of(2026, 11, 9), false, false, null, null, excluded = true)
        val counted = ExpenseRow("y", "Cafe", Money(5_000), LocalDate.of(2026, 11, 9), false, false, null, null)
        compose.setContent { MasroofTheme { WalletContent(walletUi(recent = listOf(excluded, counted)), emptyList(), WalletActions()) } }
        compose.scrollWalletTo("Not counted in totals")
        compose.onAllNodesWithText("Not counted in totals").assertCountEquals(1)
    }
}
