package eg.masroof.ui.answers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.answers.AccountOption
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.PurchaseOption
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.wealth.AccountDraft
import eg.masroof.ui.accounts.AccountFormDialog
import eg.masroof.ui.theme.LocalMoneyStyle
import kotlinx.coroutines.delay

private const val TARGET_DP = 48
private const val CARD_WIDTH_DP = 300
private const val UNDO_VISIBLE_MS = 10_000L

/** Every answer a card can give. The Wallet route fills it from [AnswerCardsViewModel]; each field has a harmless default. */
@Suppress("LongParameterList")
class AnswerActions(
    val onLinkRefund: (entryId: String, purchaseId: String) -> Unit = { _, _ -> },
    val onPickAnother: (entryId: String) -> Unit = {},
    val onNotRefund: (entryId: String) -> Unit = {},
    val onTransferOwn: (entryId: String, accountId: String) -> Unit = { _, _ -> },
    val onTransferChoose: (AnswerCard.Transfer) -> Unit = {},
    val onTransferOther: (entryId: String) -> Unit = {},
    val onAddAccount: (last4: String) -> Unit = {},
    val onLinkCard: (last4: String) -> Unit = {},
    val onIgnoreCard: (last4: String) -> Unit = {},
    val onTrack: (entryId: String) -> Unit = {},
    val onCount: (entryId: String) -> Unit = {},
)

/** "Needs an answer": a horizontally scrolling row of short cards. The caller omits it when there is nothing to ask. */
@Composable
fun AnswerCardsRow(
    cards: List<AnswerCard>,
    actions: AnswerActions,
    modifier: Modifier = Modifier,
) {
    val rowDescription = pluralStringResource(R.plurals.answers_row_desc, cards.size, cards.size)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.answers_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        LazyRow(
            Modifier.fillMaxWidth().semantics { contentDescription = rowDescription },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.key }) { AnswerCardView(it, actions) }
        }
    }
}

@Composable
private fun AnswerCardView(
    card: AnswerCard,
    actions: AnswerActions,
) {
    Card(
        Modifier.width(CARD_WIDTH_DP.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (card) {
                is AnswerCard.Refund -> RefundBody(card, actions)
                is AnswerCard.Transfer -> TransferBody(card, actions)
                is AnswerCard.UnknownCard -> UnknownCardBody(card, actions)
                is AnswerCard.Atm -> AtmBody(card, actions)
            }
        }
    }
}

@Composable
private fun Question(
    text: String,
    card: AnswerCard,
) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Excerpt(card)
}

/** Merchant, amount and date only: the structured record, never message text. */
@Composable
private fun Excerpt(card: AnswerCard) {
    val style = LocalMoneyStyle.current
    val amount = MoneyFormat.format(Money(card.amountMinor, card.currency), style.arabic, style.digits)
    val date = DateFormat.shortDate(CairoTime.dateOf(card.occurredAtMillis), style.arabic, style.digits)
    val merchant = card.merchant?.takeIf { it.isNotBlank() }
    Text(
        if (merchant == null) {
            stringResource(R.string.answers_excerpt, amount, date)
        } else {
            stringResource(R.string.answers_excerpt_merchant, merchant, amount, date)
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun candidateText(option: PurchaseOption): String {
    val style = LocalMoneyStyle.current
    val amount = MoneyFormat.format(Money(option.amountMinor, option.currency), style.arabic, style.digits)
    val date = DateFormat.shortDate(CairoTime.dateOf(option.occurredAtMillis), style.arabic, style.digits)
    val merchant = option.merchant?.takeIf { it.isNotBlank() }
    return if (merchant == null) {
        stringResource(R.string.answers_candidate_no_merchant, amount, date)
    } else {
        stringResource(R.string.answers_candidate, merchant, amount, date)
    }
}

@Composable
private fun AnswerButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)
    if (primary) {
        FilledTonalButton(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun RefundBody(
    card: AnswerCard.Refund,
    actions: AnswerActions,
) {
    val single = card.candidates.singleOrNull()
    val question =
        when {
            single != null -> stringResource(R.string.answers_refund_q_one, candidateText(single))
            card.candidates.isEmpty() -> stringResource(R.string.answers_refund_q_none)
            else -> stringResource(R.string.answers_refund_q_many)
        }
    Question(question, card)
    if (single != null) {
        AnswerButton(stringResource(R.string.answers_refund_link), { actions.onLinkRefund(card.entryId, single.id) }, primary = true)
    } else {
        card.candidates.forEach { c -> AnswerButton(candidateText(c), { actions.onLinkRefund(card.entryId, c.id) }, primary = true) }
    }
    AnswerButton(stringResource(R.string.answers_pick_another), { actions.onPickAnother(card.entryId) })
    AnswerButton(stringResource(R.string.answers_not_refund), { actions.onNotRefund(card.entryId) })
}

@Composable
private fun TransferBody(
    card: AnswerCard.Transfer,
    actions: AnswerActions,
) {
    Question(stringResource(if (card.outgoing) R.string.answers_transfer_out_q else R.string.answers_transfer_in_q), card)
    val only = card.options.singleOrNull()
    if (only != null) {
        val label = stringResource(if (card.outgoing) R.string.answers_transfer_to else R.string.answers_transfer_from, only.label)
        AnswerButton(label, { actions.onTransferOwn(card.entryId, only.id) }, primary = true)
    } else {
        AnswerButton(stringResource(R.string.answers_transfer_choose), { actions.onTransferChoose(card) }, primary = true)
    }
    val other = if (card.outgoing) R.string.answers_transfer_other_out else R.string.answers_transfer_other_in
    AnswerButton(stringResource(other), { actions.onTransferOther(card.entryId) })
}

@Composable
private fun UnknownCardBody(
    card: AnswerCard.UnknownCard,
    actions: AnswerActions,
) {
    Question(stringResource(R.string.answers_card_q, card.last4), card)
    AnswerButton(stringResource(R.string.answers_card_add), { actions.onAddAccount(card.last4) }, primary = true)
    AnswerButton(stringResource(R.string.answers_card_link), { actions.onLinkCard(card.last4) })
    AnswerButton(stringResource(R.string.answers_card_ignore), { actions.onIgnoreCard(card.last4) })
}

@Composable
private fun AtmBody(
    card: AnswerCard.Atm,
    actions: AnswerActions,
) {
    Question(stringResource(R.string.answers_atm_q), card)
    AnswerButton(stringResource(R.string.answers_atm_track), { actions.onTrack(card.entryId) }, primary = true)
    AnswerButton(stringResource(R.string.answers_atm_count), { actions.onCount(card.entryId) })
}

/** "Done - Undo" under the row. It disappears after a while; Undo restores exactly what the answer changed. */
@Composable
fun AnswerUndoBar(
    onUndo: () -> Unit,
    onExpire: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        delay(UNDO_VISIBLE_MS)
        onExpire()
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.answers_done), color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(stringResource(R.string.answers_undo)) }
        }
    }
}

/** An account to choose: used for "which of my accounts" and "link this card to which account". */
@Composable
fun AccountChoiceDialog(
    title: String,
    accounts: List<AccountOption>,
    emptyText: String?,
    onPick: (AccountOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (accounts.isEmpty() && emptyText != null) Text(emptyText)
                accounts.forEach { a ->
                    TextButton(onClick = { onPick(a) }, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) { Text(a.label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                Text(stringResource(R.string.answers_cancel))
            }
        },
    )
}

/** "Pick another": the purchases of the last 60 days, newest first. */
@Composable
fun PurchasePickDialog(
    pick: RefundPick,
    onPick: (PurchaseOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.answers_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (pick.options.isEmpty()) Text(stringResource(R.string.answers_pick_empty))
                pick.options.forEach { o ->
                    TextButton(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) {
                        Text(candidateText(o))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                Text(stringResource(R.string.answers_cancel))
            }
        },
    )
}

private sealed interface AnswerDialog {
    data class OwnAccount(
        val card: AnswerCard.Transfer,
    ) : AnswerDialog

    data class AddAccount(
        val last4: String,
    ) : AnswerDialog

    data class LinkCard(
        val last4: String,
    ) : AnswerDialog
}

/**
 * The row plus its Undo bar and dialogs, wired to [AnswerCardsViewModel]. Returns nothing to show when there is no card and no Undo
 * to offer; Wallet leaves the row out then.
 */
@Composable
fun AnswerCardsSection(
    viewModel: AnswerCardsViewModel,
    cards: List<AnswerCard>,
    accounts: List<AccountEntity>,
    hasUndo: Boolean,
    pick: RefundPick?,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<AnswerDialog?>(null) }
    val actions =
        remember(viewModel) {
            AnswerActions(
                onLinkRefund = viewModel::linkRefund,
                onPickAnother = viewModel::pickAnother,
                onNotRefund = viewModel::notRefund,
                onTransferOwn = viewModel::transferToOwn,
                onTransferChoose = { dialog = AnswerDialog.OwnAccount(it) },
                onTransferOther = viewModel::transferToOther,
                onAddAccount = { dialog = AnswerDialog.AddAccount(it) },
                onLinkCard = { dialog = AnswerDialog.LinkCard(it) },
                onIgnoreCard = viewModel::ignoreCard,
                onTrack = viewModel::trackCash,
                onCount = viewModel::countCash,
            )
        }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cards.isNotEmpty()) AnswerCardsRow(cards, actions)
        if (hasUndo) AnswerUndoBar(onUndo = viewModel::undoLast, onExpire = viewModel::dismissUndo)
    }
    when (val d = dialog) {
        is AnswerDialog.OwnAccount ->
            AccountChoiceDialog(
                title = stringResource(R.string.answers_account_picker_title),
                accounts = d.card.options,
                emptyText = null,
                onPick = {
                    dialog = null
                    viewModel.transferToOwn(d.card.entryId, it.id)
                },
                onDismiss = { dialog = null },
            )
        is AnswerDialog.LinkCard ->
            AccountChoiceDialog(
                title = stringResource(R.string.answers_card_link_title, d.last4),
                accounts = accounts.map { AccountOption(it.id, it.label) },
                emptyText = stringResource(R.string.answers_card_no_accounts),
                onPick = {
                    dialog = null
                    viewModel.linkCard(d.last4, it.id)
                },
                onDismiss = { dialog = null },
            )
        is AnswerDialog.AddAccount ->
            AccountFormDialog(
                editing = null,
                existing = accounts,
                onSave = { draft: AccountDraft ->
                    dialog = null
                    viewModel.addAccount(draft)
                },
                onDismiss = { dialog = null },
                initialLast4 = d.last4,
            )
        null -> Unit
    }
    pick?.let { p ->
        PurchasePickDialog(p, onPick = { viewModel.linkRefund(p.refundId, it.id) }, onDismiss = viewModel::dismissPick)
    }
}
