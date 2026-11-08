package eg.masroof.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DuplicateCard
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.theme.LocalMoneyStyle

/**
 * REQ-DEDUP-063: "Possible duplicate", the typed entry and the captured one side by side. Only typed fields are shown (amount,
 * date and time, merchant, category, where it came from), never message text. A swipe is a "Keep both" (REQ-DEDUP-065).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateCardItem(
    card: DuplicateCard,
    categoryNames: Map<String, String>,
    onMerge: (DuplicateCard) -> Unit,
    onKeepBoth: (DuplicateCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    // a swipe to either side is "Keep both": the card leaves the list once the choice is remembered
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) onKeepBoth(card)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(stringResource(R.string.duplicates_keep_both), style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        DuplicateCardBody(card, categoryNames, onMerge, onKeepBoth)
    }
}

@Composable
private fun DuplicateCardBody(
    card: DuplicateCard,
    categoryNames: Map<String, String>,
    onMerge: (DuplicateCard) -> Unit,
    onKeepBoth: (DuplicateCard) -> Unit,
) {
    val style = LocalMoneyStyle.current
    val manualAmount = MoneyFormat.format(Money(card.manual.amountMinor, card.manual.currency), style.arabic, style.digits)
    val captureAmount = MoneyFormat.format(Money(card.capture.amountMinor, card.capture.currency), style.arabic, style.digits)
    val captureLabel = captureLabel(card)
    val description = stringResource(R.string.duplicates_a11y, manualAmount, captureAmount, captureLabel)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.duplicates_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                // the two columns swap sides in a right-to-left language on their own
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Side(card.manual, categoryNames, stringResource(R.string.duplicates_manual), manual = true, Modifier.weight(1f))
                    Side(card.capture, categoryNames, captureLabel, manual = false, Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { onMerge(card) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.duplicates_merge))
                }
                OutlinedButton(onClick = { onKeepBoth(card) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.duplicates_keep_both))
                }
            }
        }
    }
}

@Composable
private fun Side(
    e: ExpenseEntity,
    categoryNames: Map<String, String>,
    label: String,
    manual: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = LocalMoneyStyle.current
    val date = DateFormat.shortDate(CairoTime.dateOf(e.occurredAtMillis), style.arabic, style.digits)
    val whenText =
        if (e.timeKnown) {
            stringResource(R.string.duplicates_date_time, date, DateFormat.timeOfDay(e.occurredAtMillis, style.digits))
        } else {
            date
        }
    val merchant = e.merchant?.takeIf { it.isNotBlank() } ?: stringResource(R.string.duplicates_no_merchant)
    val category = e.categoryId?.let { categoryNames[it] } ?: stringResource(R.string.uncategorized)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // the icon is decorative: the label next to it says the same
            Icon(
                sourceIcon(e, manual),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
        AmountText(Money(e.amountMinor, e.currency))
        Text(whenText, style = MaterialTheme.typography.bodySmall)
        Text(merchant, style = MaterialTheme.typography.bodyMedium)
        Text(category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceIcon(
    e: ExpenseEntity,
    manual: Boolean,
) = when {
    manual -> Icons.Filled.Edit
    e.source == "PUSH" -> Icons.Filled.Notifications
    else -> Icons.Filled.Email
}

/** "SMS from CIB" / "Notification": where the captured entry came from (a sender label, never message text). */
@Composable
private fun captureLabel(card: DuplicateCard): String {
    val base = stringResource(if (card.capture.source == "PUSH") R.string.source_notification else R.string.source_sms)
    val sender = card.captureSources.senderId?.takeIf { it.isNotBlank() }
    return if (sender == null) base else stringResource(R.string.duplicates_source_from, base, sender)
}
