package eg.masroof.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.components.ListStates
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.viewmodel.HomeUiState
import eg.masroof.ui.viewmodel.HomeViewModel
import eg.masroof.ui.viewmodel.LoadStatus

@Composable
fun HomeRoute(
    onSeeAll: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state,
        onRetry = viewModel::refresh,
        onSeeAll = onSeeAll,
        onRetryEntry = viewModel::retryEntry,
        onDeleteEntry = viewModel::deleteEntry,
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.home_this_month), style = MaterialTheme.typography.labelLarge)
                AmountText(state.total, style = MaterialTheme.typography.displaySmall)
                if (state.pendingCount > 0) {
                    Text(
                        stringResource(R.string.pending_count, state.pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        if (state.recent.isNotEmpty()) StatusBanner(state.status, onRetry)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.height(48.dp),
            ) { Text(stringResource(R.string.home_see_all)) }
        }
        ListStates(state.status, isEmpty = state.recent.isEmpty(), onRetry = onRetry) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recent, key = { it.id }) { ExpenseListItem(it, onRetryEntry = onRetryEntry, onDeleteEntry = onDeleteEntry) }
            }
        }
    }
}

/** Used by previews/tests. */
val HomeLoading = HomeUiState(status = LoadStatus.LOADING)
