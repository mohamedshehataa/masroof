package eg.masroof

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import eg.masroof.data.AppSettings
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.DbStartup
import eg.masroof.ui.AppLanguage
import eg.masroof.ui.accounts.AccountsRoute
import eg.masroof.ui.add.AddExpenseRoute
import eg.masroof.ui.ask.AskIcon
import eg.masroof.ui.ask.AskRoute
import eg.masroof.ui.bills.AddPlanRoute
import eg.masroof.ui.bills.BillsRoute
import eg.masroof.ui.bills.PlanDetailRoute
import eg.masroof.ui.bills.SubscriptionDetailRoute
import eg.masroof.ui.bills.SubscriptionFormRoute
import eg.masroof.ui.budgets.BudgetsRoute
import eg.masroof.ui.categories.CategoriesRoute
import eg.masroof.ui.categories.MerchantRulesRoute
import eg.masroof.ui.edit.EditExpenseRoute
import eg.masroof.ui.goals.GoalDetailRoute
import eg.masroof.ui.goals.GoalFormRoute
import eg.masroof.ui.goals.GoalsRoute
import eg.masroof.ui.insights.InsightsRoute
import eg.masroof.ui.nav.openIn
import eg.masroof.ui.nav.openTab
import eg.masroof.ui.nav.selectedTab
import eg.masroof.ui.nav.tabOwnerOf
import eg.masroof.ui.plan.PlanScreen
import eg.masroof.ui.receipts.ReceiptsListRoute
import eg.masroof.ui.review.ReviewAddRoute
import eg.masroof.ui.review.ReviewRoute
import eg.masroof.ui.settings.SettingsRoute
import eg.masroof.ui.startup.StartupBlockedScreen
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.transactions.TransactionsRoute
import eg.masroof.ui.viewmodel.UndoDeleteViewModel
import eg.masroof.ui.wallet.WalletActions
import eg.masroof.ui.wallet.WalletRoute
import eg.masroof.ui.wealth.WealthRoute
import eg.masroof.work.EXTRA_OPEN_ROUTE
import eg.masroof.work.ForegroundSync
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The first destination, always Home (REQ-AND-130). */
internal const val START_ROUTE = "home"

/** The bottom bar (REQ-UI-081). Insights, Transactions and Settings are opened from Wallet, not from the bar. */
private enum class Tab(
    val route: String,
    val label: Int,
    val icon: ImageVector,
) {
    Wallet(START_ROUTE, R.string.nav_wallet, Icons.Filled.Home),
    Bills("bills", R.string.bills_title, Icons.Filled.Notifications),
    Plan("plan", R.string.nav_plan, Icons.Filled.CheckCircle),
    Wealth("wealth", R.string.wealth_title, Icons.Filled.AccountBox),
    Ask("ask", R.string.nav_ask, AskIcon),
}

private const val INSIGHTS_ROUTE = "insights"
private const val TRANSACTIONS_ROUTE = "transactions"
private const val SETTINGS_ROUTE = "settings"

/** The tab one destination belongs to; a screen shared by several tabs counts as Wallet here ([selectedTab] gives the real highlight). */
internal fun tabRouteOf(route: String?): String = tabOwnerOf(route) ?: START_ROUTE

// "add" opens a blank form; "add?receipt=<id>" opens it with a waiting receipt that is attached on Save (REQ-RCPT-023).
private const val ADD_ROUTE = "add?receipt={receipt}&mic={mic}"
private const val RECEIPTS_ROUTE = "receipts"
private const val BUDGETS_ROUTE = "budgets"
private const val CATEGORIES_ROUTE = "settings/categories"
private const val RULES_ROUTE = "settings/merchant-rules"
private const val ASK_ROUTE = "ask"
private const val ACCOUNTS_ROUTE = "accounts"
private const val EDIT_ROUTE = "edit/{id}"
private const val GOALS_ROUTE = "goals"
private const val GOAL_NEW_ROUTE = "goals-new"
private const val GOAL_DETAIL_ROUTE = "goals/{id}"
private const val GOAL_EDIT_ROUTE = "goals/{id}/edit"
private const val REVIEW_ROUTE = "review"
private const val REVIEW_ADD_ROUTE = "review/{id}/add"
private const val BILL_ADD_ROUTE = "bills/add"
private const val BILL_DETAIL_ROUTE = "bills/{id}"
private const val SUB_ADD_ROUTE = "subscriptions/add?name={name}&amount={amount}&cur={cur}&date={date}&merchant={merchant}"
private const val SUB_DETAIL_ROUTE = "subscriptions/{id}"
private const val SUB_EDIT_ROUTE = "subscriptions/{id}/edit"

/** The only route a notification may open: a bills plan by its UUID (the shape UuidIdFactory makes). */
private val OPENABLE_ROUTE = Regex("""(bills|subscriptions)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

// No floating add button on screens with their own primary action or an input field at the bottom (Wallet has its Add action).
private val FAB_HIDDEN_ROUTES =
    setOf(START_ROUTE, ADD_ROUTE, EDIT_ROUTE, REVIEW_ADD_ROUTE, BILL_ADD_ROUTE, SUB_ADD_ROUTE, SUB_EDIT_ROUTE, ASK_ROUTE)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        /** Set only by the share target: open the Receipts list. The one extra this exported activity reads. */
        const val EXTRA_OPEN_RECEIPTS = "eg.masroof.OPEN_RECEIPTS"

        /** True only for the fixed boolean; a hostile or unreadable extras bundle counts as false. */
        @Suppress("TooGenericExceptionCaught") // BadParcelableException, ClassCastException ...
        internal fun wantsReceipts(intent: Intent?): Boolean =
            try {
                intent?.getBooleanExtra(EXTRA_OPEN_RECEIPTS, false) == true
            } catch (_: Exception) {
                false
            }

        /** The in-app route a reminder asks for, only when it is on the allow-list; hostile extras count as none. */
        @Suppress("TooGenericExceptionCaught")
        internal fun routeFrom(intent: Intent?): String? =
            try {
                intent?.getStringExtra(EXTRA_OPEN_ROUTE)?.takeIf { OPENABLE_ROUTE.matches(it) }
            } catch (_: Exception) {
                null
            }
    }

    private var openReceipts by mutableStateOf(false)
    private var openRoute by mutableStateOf<String?>(null)

    /**
     * The launch intent is replaced by a bare one before any navigation sees it: the navigation library reads deep-link extras
     * (`android-support-nav:controller:deepLinkIds`) from the activity intent, and any app can start this exported activity.
     */
    private fun takeIntent(intent: Intent?, fresh: Boolean) {
        val wants = fresh && wantsReceipts(intent)
        val route = if (fresh) routeFrom(intent) else null
        setIntent(Intent(this, MainActivity::class.java))
        if (wants) openReceipts = true
        if (route != null) openRoute = route
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent, fresh = true)
    }

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var foregroundSync: ForegroundSync

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        // App start or return to foreground: do not wait out a long backoff once the server may be back.
        if (DatabaseStartup.isBlocked()) return
        lifecycleScope.launch { foregroundSync.onForeground() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeIntent(intent, savedInstanceState == null)
        val startup = DatabaseStartup.state()
        if (startup !is DbStartup.Ready) {
            setContent { MasroofTheme { StartupBlockedScreen(startup) } }
            return
        }
        setContent {
            val current by settings.settings.collectAsState(initial = AppSettings())
            MasroofTheme(digitOverride = current.digitStyle) {
                // No sign-in screen and no session check: the app is the whole product (REQ-AND-130). Home is the first screen
                // whether or not a session exists, and a rejected or lost session never blocks it.
                MasroofRoot(
                    openReceipts = openReceipts,
                    onReceiptsOpened = { openReceipts = false },
                    openRoute = openRoute,
                    onRouteOpened = { openRoute = null },
                    onToggleLanguage = {
                        AppLanguage.toggle(this@MainActivity)
                        recreate()
                    },
                )
            }
        }
    }
}

// currentBackStack is the only way to read the whole stack (the highlight needs the nearest tab-owned entry under a shared screen).
@SuppressLint("RestrictedApi")
@Composable
private fun MasroofRoot(
    openReceipts: Boolean,
    onReceiptsOpened: () -> Unit,
    openRoute: String?,
    onRouteOpened: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val stack by nav.currentBackStack.collectAsState()
    val selected = selectedTab(stack.map { it.destination.route })
    val snackbars = remember { SnackbarHostState() }
    val undo: UndoDeleteViewModel = hiltViewModel()
    LaunchedEffect(openReceipts) {
        if (openReceipts) {
            nav.navigate(RECEIPTS_ROUTE)
            onReceiptsOpened()
        }
    }
    LaunchedEffect(openRoute) {
        if (openRoute != null) {
            // A reminder opens inside the tab that owns it, so the highlight and Back agree.
            nav.openIn(tabRouteOf(openRoute), openRoute)
            onRouteOpened()
        }
    }
    val deletedText = stringResource(R.string.edit_deleted)
    val undoText = stringResource(R.string.edit_undo)
    // "Deleted - Undo" stays for the Long duration (about 10 s, at least 5 s). Undo restores the soft-deleted entry.
    LaunchedEffect(Unit) {
        undo.deleted.collect { id ->
            val result = snackbars.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo.undo(id)
        }
    }

    fun openTab(route: String) = nav.openTab(route)

    fun openIn(
        tab: Tab,
        route: String,
    ) = nav.openIn(tab.route, route)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab.route,
                        onClick = { openTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
        topBar = {
            eg.masroof.ui.settings
                .RestorePendingBanner(onOpenSettings = { nav.navigate(SETTINGS_ROUTE) })
        },
        floatingActionButton = {
            if (destination?.route !in FAB_HIDDEN_ROUTES) {
                FloatingActionButton(onClick = { nav.navigate("add") }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_add))
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Wallet.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Wallet.route) {
                WalletRoute(
                    actions =
                        WalletActions(
                            onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
                            onOpenInsights = { nav.navigate(INSIGHTS_ROUTE) },
                            onOpenTransactions = { nav.navigate(TRANSACTIONS_ROUTE) },
                            onOpenExpense = { id -> nav.navigate("edit/$id") },
                            onAdd = { nav.navigate("add") },
                            onSayIt = { nav.navigate("add?mic=true") },
                            onAsk = { openTab(Tab.Ask.route) },
                            onOpenAccounts = { openIn(Tab.Wealth, ACCOUNTS_ROUTE) },
                            onOpenWealth = { openTab(Tab.Wealth.route) },
                            onOpenBills = { openTab(Tab.Bills.route) },
                            onOpenBudgets = { openTab(Tab.Plan.route) },
                            onOpenGoals = { openIn(Tab.Plan, GOALS_ROUTE) },
                            onTurnOnCapture = { nav.navigate(SETTINGS_ROUTE) },
                            onAddBill = { openIn(Tab.Bills, BILL_ADD_ROUTE) },
                        ),
                    onOpenReceipts = { nav.navigate(RECEIPTS_ROUTE) },
                )
            }
            composable(INSIGHTS_ROUTE) {
                InsightsRoute(onOpenBudgets = { openTab(Tab.Plan.route) }, onOpenAsk = { openTab(Tab.Ask.route) })
            }
            composable(Tab.Ask.route) { AskRoute(onBack = { nav.popBackStack() }, onOpenExpense = { id -> nav.navigate("edit/$id") }) }
            composable(Tab.Plan.route) {
                PlanScreen(onOpenGoal = { id -> nav.navigate("goals/$id") }, onNewGoal = { nav.navigate(GOAL_NEW_ROUTE) })
            }
            composable(BUDGETS_ROUTE) { BudgetsRoute() }
            composable(TRANSACTIONS_ROUTE) { TransactionsRoute(onOpenExpense = { id -> nav.navigate("edit/$id") }) }
            composable(EDIT_ROUTE) { EditExpenseRoute(onDone = { nav.popBackStack() }) }
            composable(SETTINGS_ROUTE) {
                SettingsRoute(
                    onToggleLanguage = onToggleLanguage,
                    onOpenReview = { nav.navigate(REVIEW_ROUTE) },
                    onOpenReceipts = { nav.navigate(RECEIPTS_ROUTE) },
                    onOpenCategories = { nav.navigate(CATEGORIES_ROUTE) },
                    onOpenRules = { nav.navigate(RULES_ROUTE) },
                    onRestored = { nav.popBackStack() },
                )
            }
            composable(CATEGORIES_ROUTE) { CategoriesRoute() }
            composable(RULES_ROUTE) { MerchantRulesRoute() }
            composable(Tab.Wealth.route) { WealthRoute(onOpenAccounts = { nav.navigate(ACCOUNTS_ROUTE) }) }
            composable(ACCOUNTS_ROUTE) { AccountsRoute() }
            composable(Tab.Bills.route) {
                BillsRoute(
                    onAdd = { nav.navigate(BILL_ADD_ROUTE) },
                    onOpenPlan = { id -> nav.navigate("bills/$id") },
                    onAddSubscription = { nav.navigate("subscriptions/add") },
                    onOpenSubscription = { id -> nav.navigate("subscriptions/$id") },
                    onAddSuggestion = { s ->
                        val name = Uri.encode(s.name)
                        val merchant = Uri.encode(s.key)
                        val query = "name=$name&amount=${s.amountMinor}&cur=${s.currency}&date=${s.nextRenewal}&merchant=$merchant"
                        nav.navigate("subscriptions/add?$query")
                    },
                )
            }
            composable(
                SUB_ADD_ROUTE,
                arguments =
                    listOf(
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("amount") {
                            type = NavType.LongType
                            defaultValue = 0L
                        },
                        navArgument("cur") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("date") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("merchant") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) {
                SubscriptionFormRoute(onSaved = { nav.popBackStack() })
            }
            composable(SUB_EDIT_ROUTE) { SubscriptionFormRoute(onSaved = { nav.popBackStack() }) }
            composable(SUB_DETAIL_ROUTE) {
                SubscriptionDetailRoute(onClose = { nav.popBackStack() }, onEdit = { id -> nav.navigate("subscriptions/$id/edit") })
            }
            composable(BILL_ADD_ROUTE) {
                AddPlanRoute(onSaved = { id -> nav.navigate("bills/$id") { popUpTo(BILL_ADD_ROUTE) { inclusive = true } } })
            }
            composable(BILL_DETAIL_ROUTE) { PlanDetailRoute(onClose = { nav.popBackStack() }) }
            composable(GOALS_ROUTE) {
                GoalsRoute(onOpenGoal = { id -> nav.navigate("goals/$id") }, onNewGoal = { nav.navigate(GOAL_NEW_ROUTE) })
            }
            composable(GOAL_NEW_ROUTE) { GoalFormRoute(onDone = { nav.popBackStack() }) }
            composable(GOAL_DETAIL_ROUTE) {
                GoalDetailRoute(onEdit = { id -> nav.navigate("goals/$id/edit") }, onClosed = { nav.popBackStack() })
            }
            composable(GOAL_EDIT_ROUTE) { GoalFormRoute(onDone = { nav.popBackStack() }) }
            composable(REVIEW_ROUTE) { ReviewRoute(onCreate = { id -> nav.navigate("review/$id/add") }) }
            composable(REVIEW_ADD_ROUTE) { entry ->
                ReviewAddRoute(id = entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack(REVIEW_ROUTE, false) })
            }
            composable(RECEIPTS_ROUTE) { ReceiptsListRoute(onNewEntry = { id -> nav.navigate("add?receipt=$id") }) }
            composable(
                ADD_ROUTE,
                arguments =
                    listOf(
                        navArgument("receipt") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("mic") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
            ) { entry ->
                AddExpenseRoute(
                    onDone = { nav.popBackStack() },
                    onOpenReceipts = { nav.navigate(RECEIPTS_ROUTE) { popUpTo(ADD_ROUTE) { inclusive = true } } },
                    startWithMic = entry.arguments?.getBoolean("mic") == true,
                )
            }
        }
    }
}
