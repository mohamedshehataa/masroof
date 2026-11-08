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
