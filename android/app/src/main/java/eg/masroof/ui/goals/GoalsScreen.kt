package eg.masroof.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.components.Panel
import eg.masroof.ui.components.ProgressLine
import eg.masroof.ui.components.RetryButton
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.components.SkeletonList
import eg.masroof.ui.theme.AppIcons
import eg.masroof.ui.theme.LocalMoneyStyle

@Composable
fun GoalsRoute(
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalsContent(state, onOpenGoal, onNewGoal, viewModel::retry)
}

@Composable
fun GoalsContent(
    state: GoalsListState,
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.goals_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Button(onClick = onNewGoal, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.goals_new))
            }
        }
        Spacer(Modifier.padding(top = 14.dp))
        when {
            state.loading -> SkeletonList()
            state.error ->
                MessageState(
                    title = stringResource(R.string.goals_error),
                    body = stringResource(R.string.goals_nothing_moves),
                    icon = AppIcons.Alert,
                    action = { RetryButton(onRetry) },
                )
            state.cards.isEmpty() ->
                MessageState(
                    title = stringResource(R.string.goals_empty_title),
                    body = stringResource(R.string.goals_empty_body),
                    icon = AppIcons.Target,
                    action = {
                        Button(onClick = onNewGoal, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.goals_new)) }
                    },
                )
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    state.totals?.let { totals -> item { TotalsCard(totals) } }
                    items(state.cards, key = { it.id }) { GoalCard(it) { onOpenGoal(it.id) } }
                }
        }
    }
}

@Composable
private fun TotalsCard(totals: GoalsTotals) {
    SectionCard(stringResource(R.string.goals_total_title)) {
        val saved = goalMoney(totals.savedMinor, Money.EGP)
        Text(
            stringResource(R.string.goals_of, saved, goalMoney(totals.targetMinor, Money.EGP)),
            style = MaterialTheme.typography.titleMedium,
        )
        val barDescription = stringResource(R.string.goals_total_desc, saved) + ", " + wholePercent(totals.percentBp)
        ProgressLine(totals.percentBp / PERCENT_SCALE, Modifier.semantics { contentDescription = barDescription })
        if (totals.otherCurrencyCount > 0) {
            val digits = LocalMoneyStyle.current.digits
            val count = totals.otherCurrencyCount
            Text(
                pluralStringResource(R.plurals.goals_other_currency, count, DateFormat.number(count, digits)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PERCENT_SCALE = 10_000f

@Composable
private fun GoalCard(
    card: GoalCardUi,
    onClick: () -> Unit,
) {
    Panel(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GoalRing(card.emoji, card.progress.percentBp, goalRingDescription(card))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    goalSavedOfTarget(card.progress, card.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pace = goalPaceText(card.progress, card.currency)
                if (pace.isNotEmpty()) Text(pace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                wholePercent(card.progress.percentBp),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
