package eg.masroof

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eg.masroof.ui.edit.EditActions
import eg.masroof.ui.edit.EditExpenseContent
import eg.masroof.ui.nav.openIn
import eg.masroof.ui.nav.openTab
import eg.masroof.ui.nav.selectedTab
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.EditFormState
import eg.masroof.ui.viewmodel.EditUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Composable
private fun TabHost(onNav: (NavHostController) -> Unit) {
    val nav = rememberNavController()
    onNav(nav)
    NavHost(nav, startDestination = "home") {
        val routes = listOf("home", "bills", "bills/{id}", "plan", "wealth", "ask", "goals", "accounts", "settings", "add", "edit/{id}")
        routes.forEach { route ->
            composable(route) { Text("screen-$route") }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WalletNavigationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun route() = nav.currentBackStackEntry?.destination?.route

    @Test
    fun `REQ-UI-081 a tile that opens another tab's screen switches to that tab first and Back returns to the tab`() {
        compose.setContent { TabHost { nav = it } }
        compose.runOnIdle { nav.openIn("plan", "goals") }
        compose.waitForIdle()
        assertEquals("goals", route())
        assertEquals("plan", tabRouteOf(route()))
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals("plan", route())
        assertEquals("plan", tabRouteOf(route()))
    }

    @Test
    fun `REQ-UI-081 switching tabs keeps each tab's own stack and Back from a sub screen returns to its tab`() {
        compose.setContent { TabHost { nav = it } }
        compose.runOnIdle {
            nav.openIn("wealth", "accounts")
            nav.openTab("bills")
            nav.openTab("wealth")
        }
        compose.waitForIdle()
        assertEquals("accounts", route())
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals("wealth", route())
        compose.onNodeWithText("screen-wealth").assertIsDisplayed()
    }

    @Test
    fun `REQ-UI-081 a reminder opens inside the Bills tab and Back returns to it`() {
        compose.setContent { TabHost { nav = it } }
        compose.runOnIdle { nav.openIn(tabRouteOf("bills/0b9a7a52"), "bills/abc") }
        compose.waitForIdle()
        assertEquals("bills/{id}", route())
        assertEquals("bills", selectedTab(nav.currentBackStack.value.map { it.destination.route }))
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals("bills", route())
    }

    @Test
    fun `REQ-UI-081 a shared screen keeps the highlight of the tab whose stack it is on`() {
        val add = "add?receipt={receipt}&mic={mic}"
        assertEquals("home", selectedTab(listOf(null, "home", add)))
        assertEquals("bills", selectedTab(listOf(null, "home", "bills", add)))
        assertEquals("plan", selectedTab(listOf(null, "home", "plan", add)))
        assertEquals("wealth", selectedTab(listOf(null, "home", "wealth", add)))
        assertEquals("ask", selectedTab(listOf(null, "home", "ask", "edit/{id}")))
        assertEquals("bills", selectedTab(listOf(null, "home", "bills", "bills/{id}", "edit/{id}")))
        assertEquals("home", selectedTab(listOf(null, "home", "review", "receipts")))
        assertEquals("home", selectedTab(emptyList()))
    }

    @Test
    fun `REQ-UI-081 the highlight follows the real back stack of a shared screen`() {
        compose.setContent { TabHost { nav = it } }
        compose.runOnIdle {
            nav.openTab("bills")
            nav.navigate("add")
        }
        compose.waitForIdle()
        assertEquals("add", route())
        assertEquals("bills", selectedTab(nav.currentBackStack.value.map { it.destination.route }))
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals("bills", route())
    }

    @Test
    fun `REQ-UI-081 the selected tab survives recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TabHost { nav = it } }
        compose.runOnIdle { nav.openTab("wealth") }
        compose.waitForIdle()
        assertEquals("wealth", route())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("wealth", route())
        assertEquals("wealth", tabRouteOf(route()))
        compose.onNodeWithText("screen-wealth").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditExcludeToggleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AN-175 the Edit screen has a Don't count in totals switch that reports the change`() {
        val changes = mutableListOf<Boolean>()
        val state = EditUiState(EditFormState(loaded = true, excluded = false))
        compose.setContent {
            MasroofTheme { EditExpenseContent(state, EditActions(onExcluded = { changes += it })) }
        }
        compose
            .onNodeWithText("Don't count in totals")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
        compose.onNodeWithText("Don't count in totals").performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `REQ-AN-175 an excluded entry opens the Edit screen with the switch present`() {
        val state = EditUiState(EditFormState(loaded = true, excluded = true))
        compose.setContent { MasroofTheme { EditExpenseContent(state, EditActions()) } }
        compose
            .onNodeWithText("Don't count in totals")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
    }
}
