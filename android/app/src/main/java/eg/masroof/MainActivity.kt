package eg.masroof

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import eg.masroof.data.AppSettings
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.ui.add.AddExpenseRoute
import eg.masroof.ui.home.HomeRoute
import eg.masroof.ui.login.LoginRoute
import eg.masroof.ui.settings.SettingsRoute
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.transactions.TransactionsRoute
import javax.inject.Inject

private enum class Tab(
    val route: String,
    val label: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Transactions("transactions", R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val ADD_ROUTE = "add"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current by settings.settings.collectAsState(initial = AppSettings())
            val sessionState by session.state.collectAsState()
            LaunchedEffect(Unit) { session.restore() }
            MasroofTheme(digitOverride = current.digitStyle) {
                when (val s = sessionState) {
                    SessionState.Restoring -> Unit
                    is SessionState.SignedOut ->
                        Scaffold { padding -> LoginRoute(s.notice, modifier = Modifier.padding(padding)) }
                    is SessionState.SignedIn -> MasroofRoot()
                }
            }
        }
    }
}

@Composable
private fun MasroofRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    Scaffold(
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
            if (destination?.route != ADD_ROUTE) {
                FloatingActionButton(onClick = { nav.navigate(ADD_ROUTE) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_add))
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) {
                HomeRoute(onSeeAll = { nav.navigate(Tab.Transactions.route) })
            }
            composable(Tab.Transactions.route) { TransactionsRoute() }
            composable(Tab.Settings.route) { SettingsRoute() }
            composable(ADD_ROUTE) { AddExpenseRoute(onDone = { nav.popBackStack() }) }
        }
    }
}
