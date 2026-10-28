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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.Money
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.ui.add.AddExpenseRoute
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.ReviewSaveResult
import eg.masroof.ui.viewmodel.ReviewViewModel

/** The local "Needs review" list: messages that never left the phone (REQ-SMS-014). */
@Composable
fun ReviewRoute(
    onCreate: (String) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    SecureWindow()
    val snackbar = remember { SnackbarHostState() }
    val already = stringResource(R.string.review_already_recorded)
    val failed = stringResource(R.string.review_save_failed)
    val invalid = stringResource(R.string.review_amount_invalid)
    LaunchedEffect(viewModel) {
        viewModel.results.collect { result ->
            val message =
                when (result) {
                    ReviewSaveResult.ALREADY_RECORDED -> already
                    ReviewSaveResult.FAILED -> failed
                    ReviewSaveResult.AMOUNT_INVALID -> invalid
                    else -> null
                }
            message?.let { snackbar.showSnackbar(it) }
        }
    }
    ReviewContent(items, onCreate = onCreate, onDismiss = viewModel::dismiss, onSave = viewModel::save, snackbar = snackbar)
}

@Composable
fun ReviewContent(
    items: List<ReviewItemEntity>?,
    onCreate: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSave: (String) -> Unit = {},
    snackbar: SnackbarHostState? = null,
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
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item -> ReviewCard(item, onCreate, onDismiss, onSave) }
                }
        }
        snackbar?.let { SnackbarHost(it) }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItemEntity,
    onCreate: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    val suggested = item.sugKind != null && item.sugAmountMinor != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.sender, style = MaterialTheme.typography.titleSmall)
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            if (suggested) Suggestion(item)
            Text(reasonLabel(item.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (suggested) {
                    Button(onClick = { onSave(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.review_save))
                    }
                }
                OutlinedButton(onClick = { onCreate(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (suggested) R.string.review_edit else R.string.review_create))
                }
                TextButton(onClick = { onDismiss(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_dismiss))
                }
            }
        }
    }
}

/** What the engine read from the message: kind, amount, card or account, label. Saving uploads exactly these typed fields. */
@Composable
private fun Suggestion(item: ReviewItemEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.review_suggestion_title), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(item.sugKind.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            AmountText(Money(item.sugAmountMinor ?: 0L, item.sugCurrency ?: Money.EGP))
        }
        val detail = listOfNotNull(item.sugMerchant, item.sugLast4?.let { "**$it" }).joinToString(" - ")
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun kindLabel(kind: String): String =
    stringResource(
        when (kind) {
            "PURCHASE" -> R.string.review_kind_purchase
            "TRANSFER_OUT" -> R.string.review_kind_transfer_out
            "TRANSFER_IN" -> R.string.review_kind_transfer_in
            "INCOME" -> R.string.review_kind_income
            "REFUND" -> R.string.review_kind_refund
            "CASH_WITHDRAWAL" -> R.string.review_kind_cash
            else -> R.string.review_kind_fee
        },
    )

@Composable
private fun reasonLabel(reason: String): String =
    stringResource(
        when (reason) {
            "secret" -> R.string.review_reason_secret
            "unsupported_currency" -> R.string.review_reason_unsupported_currency
            "unexplained_number" -> R.string.review_reason_unexplained_number
            "unconfirmed" -> R.string.review_reason_unconfirmed
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
            // a hand-edited save never teaches a format; the item is dropped
            review.confirmEdited(id)
            onDone()
        },
        viewModel = add,
    )
}
