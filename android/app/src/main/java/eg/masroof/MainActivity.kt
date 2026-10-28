package eg.masroof

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import eg.masroof.ui.add.AddExpenseRoute
import eg.masroof.ui.budgets.BudgetsRoute
import eg.masroof.ui.edit.EditExpenseRoute
import eg.masroof.ui.home.HomeRoute
import eg.masroof.ui.insights.InsightsIcon
import eg.masroof.ui.insights.InsightsRoute
import eg.masroof.ui.receipts.ReceiptsListRoute
import eg.masroof.ui.review.ReviewAddRoute
import eg.masroof.ui.review.ReviewRoute
import eg.masroof.ui.settings.SettingsRoute
import eg.masroof.ui.startup.StartupBlockedScreen
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.transactions.TransactionsRoute
import eg.masroof.ui.viewmodel.UndoDeleteViewModel
import eg.masroof.work.ForegroundSync
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The first destination, always Home (REQ-AND-130). */
internal const val START_ROUTE = "home"

private enum class Tab(
    val route: String,
    val label: Int,
    val icon: ImageVector,
) {
    Home(START_ROUTE, R.string.nav_home, Icons.Filled.Home),
    Transactions("transactions", R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    Insights("insights", R.string.nav_insights, InsightsIcon),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

// "add" opens a blank form; "add?receipt=<id>" opens it with a waiting receipt that is attached on Save (REQ-RCPT-023).
private const val ADD_ROUTE = "add?receipt={receipt}"
private const val RECEIPTS_ROUTE = "receipts"
private const val BUDGETS_ROUTE = "budgets"
private const val EDIT_ROUTE = "edit/{id}"
private const val REVIEW_ROUTE = "review"
private const val REVIEW_ADD_ROUTE = "review/{id}/add"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        /** Set only by the share target: open the Receipts list. The one extra this exported activity reads. */
        const val EXTRA_OPEN_RECEIPTS = "eg.masroof.OPEN_RECEIPTS"
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
        val startup = DatabaseStartup.state()
        if (startup !is DbStartup.Ready) {
            setContent { MasroofTheme { StartupBlockedScreen(startup) } }
            return
        }
        // Only the share target sets this fixed flag; it can only open the Receipts list, and not again after a rotation.
        val openReceipts = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_OPEN_RECEIPTS, false) == true
        setContent {
            val current by settings.settings.collectAsState(initial = AppSettings())
            MasroofTheme(digitOverride = current.digitStyle) {
                // No sign-in screen and no session check: the app is the whole product (REQ-AND-130). Home is the first screen
                // whether or not a session exists, and a rejected or lost session never blocks it.
                MasroofRoot(
                    openReceipts = openReceipts,
                    onToggleLanguage = {
                        AppLanguage.toggle(this@MainActivity)
                        recreate()
                    },
                )
            }
        }
    }
}

@Composable
private fun MasroofRoot(
    openReceipts: Boolean,
    onToggleLanguage: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val snackbars = remember { SnackbarHostState() }
    val undo: UndoDeleteViewModel = hiltViewModel()
    LaunchedEffect(Unit) { if (openReceipts) nav.navigate(RECEIPTS_ROUTE) }
    val deletedText = stringResource(R.string.edit_deleted)
    val undoText = stringResource(R.string.edit_undo)
    // "Deleted - Undo" stays for the Long duration (about 10 s, at least 5 s). Undo restores the soft-deleted entry.
    LaunchedEffect(Unit) {
        undo.deleted.collect { id ->
            val result = snackbars.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo.undo(id)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination?.route != ADD_ROUTE && destination?.route != EDIT_ROUTE && destination?.route != REVIEW_ADD_ROUTE) {
                FloatingActionButton(onClick = { nav.navigate("add") }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_add))
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) {
                HomeRoute(
                    onSeeAll = { nav.navigate(Tab.Transactions.route) },
                    onOpenInsights = { nav.navigate(Tab.Insights.route) },
                    onOpenBudgets = { nav.navigate(BUDGETS_ROUTE) },
                    onOpenExpense = { id -> nav.navigate("edit/$id") },
                )
            }
            composable(Tab.Insights.route) { InsightsRoute(onOpenBudgets = { nav.navigate(BUDGETS_ROUTE) }) }
            composable(BUDGETS_ROUTE) { BudgetsRoute() }
            composable(Tab.Transactions.route) { TransactionsRoute(onOpenExpense = { id -> nav.navigate("edit/$id") }) }
            composable(EDIT_ROUTE) { EditExpenseRoute(onDone = { nav.popBackStack() }) }
            composable(Tab.Settings.route) {
                SettingsRoute(
                    onToggleLanguage = onToggleLanguage,
                    onOpenReview = { nav.navigate(REVIEW_ROUTE) },
                    onOpenReceipts = { nav.navigate(RECEIPTS_ROUTE) },
                )
            }
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
                    ),
            ) {
                AddExpenseRoute(onDone = { nav.popBackStack() }, onOpenReceipts = {
                    nav.navigate(RECEIPTS_ROUTE) {
                        popUpTo("add?receipt={receipt}") {
                            inclusive =
                                true
                        }
                    }
                })
            }
        }
    }
}
