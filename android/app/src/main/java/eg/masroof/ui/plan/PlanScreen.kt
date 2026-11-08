package eg.masroof.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eg.masroof.R
import eg.masroof.ui.budgets.BudgetsRoute
import eg.masroof.ui.goals.GoalsRoute

private const val BUDGETS = 0
private const val GOALS = 1

/** The Plan tab (STORY-206): budgets and savings goals side by side, one tap apart. The chosen side survives rotation. */
@Composable
fun PlanScreen(
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(BUDGETS) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selected) {
            Tab(
                selected = selected == BUDGETS,
                onClick = { selected = BUDGETS },
                text = { Text(stringResource(R.string.plan_tab_budgets)) },
            )
            Tab(selected = selected == GOALS, onClick = { selected = GOALS }, text = { Text(stringResource(R.string.plan_tab_goals)) })
        }
        if (selected == BUDGETS) BudgetsRoute() else GoalsRoute(onOpenGoal = onOpenGoal, onNewGoal = onNewGoal)
    }
}
