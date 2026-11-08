package eg.masroof.ui.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** Switches to the tab at [route]: the tab being left is saved (scroll, selections) and the one being opened is restored. */
fun NavController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Opens [route] inside the tab [tabRoute]: the tab is switched to first, so the highlighted tab and Back agree
 * (Back from [route] returns to [tabRoute], not to the screen that asked).
 */
fun NavController.openIn(
    tabRoute: String,
    route: String,
) {
    openTab(tabRoute)
    navigate(route)
}

private const val WALLET_TAB = "home"

/**
 * The bottom-bar tab that owns [route], or null for a screen several tabs share (add, edit, review, receipts ...), which keeps
 * the highlight of the tab whose stack it sits on.
 */
fun tabOwnerOf(route: String?): String? =
    when {
        route == null -> null
        route == "bills" || route.startsWith("bills/") || route.startsWith("subscriptions/") -> "bills"
        route == "plan" || route == "budgets" || route == "goals" || route.startsWith("goals/") || route == "goals-new" -> "plan"
        route == "wealth" || route == "accounts" -> "wealth"
        route == "ask" -> "ask"
        route == WALLET_TAB || route == "insights" || route == "transactions" || route.startsWith("settings") -> WALLET_TAB
        else -> null
    }

/** The highlighted tab for a back stack (routes bottom to top): the nearest entry from the top that belongs to a tab. */
fun selectedTab(stackRoutes: List<String?>): String = stackRoutes.asReversed().firstNotNullOfOrNull { tabOwnerOf(it) } ?: WALLET_TAB
