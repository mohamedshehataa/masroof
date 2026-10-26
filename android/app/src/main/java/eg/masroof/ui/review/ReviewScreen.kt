package eg.masroof.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.ui.add.AddExpenseRoute
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.ReviewViewModel

/** The local "Needs review" list: messages that never left the phone (REQ-SMS-014). */
@Composable
fun ReviewRoute(
    onCreate: (String) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    ReviewContent(items, onCreate = onCreate, onDismiss = viewModel::dismiss)
}

@Composable
fun ReviewContent(
    items: List<ReviewItemEntity>?,
    onCreate: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.review_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.review_intro), style = MaterialTheme.typography.bodySmall)
        when {
            items == null -> CircularProgressIndicator()
            items.isEmpty() -> Text(stringResource(R.string.review_empty), style = MaterialTheme.typography.bodyMedium)
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item -> ReviewCard(item, onCreate, onDismiss) }
                }
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItemEntity,
    onCreate: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.sender, style = MaterialTheme.typography.titleSmall)
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            Text(reasonLabel(item.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onCreate(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_create))
                }
                TextButton(onClick = { onDismiss(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_dismiss))
                }
            }
        }
    }
}

@Composable
private fun reasonLabel(reason: String): String =
    stringResource(
        when (reason) {
            "secret" -> R.string.review_reason_secret
            "unsupported_currency" -> R.string.review_reason_unsupported_currency
            "unexplained_number" -> R.string.review_reason_unexplained_number
            "rejected" -> R.string.review_reason_rejected
            else -> R.string.review_reason_other
        },
    )

/** The add form prefilled from a review item; saving it dismisses the item. The text itself is never sent. */
@Composable
fun ReviewAddRoute(
    id: String,
    onDone: () -> Unit,
    review: ReviewViewModel = hiltViewModel(),
    add: AddExpenseViewModel = hiltViewModel(),
) {
    LaunchedEffect(id) { review.prefill(id)?.let { add.prefill(it.amountText, it.notes) } }
    AddExpenseRoute(
        onDone = {
            review.dismiss(id)
            onDone()
        },
        viewModel = add,
    )
}
