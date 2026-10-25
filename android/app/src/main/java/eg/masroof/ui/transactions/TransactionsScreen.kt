package eg.masroof.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.components.ListStates
import eg.masroof.ui.components.StatusBanner
import eg.masroof.ui.viewmodel.TransactionsUiState
import eg.masroof.ui.viewmodel.TransactionsViewModel

@Composable
fun TransactionsRoute(viewModel: TransactionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransactionsContent(state, onRetry = viewModel::refresh, onRetryEntry = viewModel::retryEntry, onDeleteEntry = viewModel::deleteEntry)
}

@Composable
fun TransactionsContent(
    state: TransactionsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.transactions_title), style = MaterialTheme.typography.headlineSmall)
        if (state.rows.isNotEmpty()) StatusBanner(state.status, onRetry)
        ListStates(state.status, isEmpty = state.rows.isEmpty(), onRetry = onRetry) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.rows, key = { it.id }) { ExpenseListItem(it, onRetryEntry = onRetryEntry, onDeleteEntry = onDeleteEntry) }
            }
        }
    }
}
