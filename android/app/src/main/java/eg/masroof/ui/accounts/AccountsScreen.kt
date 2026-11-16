package eg.masroof.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.wealth.AccountField
import eg.masroof.data.wealth.AccountForm
import eg.masroof.data.wealth.AccountKinds
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.BalanceField
import eg.masroof.data.wealth.SUPPORTED_CURRENCIES
import eg.masroof.data.wealth.WealthValidation
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.components.MessageState
import eg.masroof.ui.theme.AppIcons
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.wealth.FormField

@Composable
fun AccountsRoute(viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountsContent(
        state,
        AccountsActions(
            onSave = { draft, id -> viewModel.save(draft, id) },
            onSetBalance = { account, minor, at -> viewModel.setBalance(account, minor, at) },
            onArchive = { viewModel.archive(it) },
        ),
    )
}

private fun kindLabel(kind: String?): Int =
    when (kind) {
        AccountKinds.CREDIT_CARD -> R.string.accounts_kind_credit_card
        AccountKinds.CASH -> R.string.accounts_kind_cash
        AccountKinds.WALLET -> R.string.accounts_kind_wallet
        else -> R.string.accounts_kind_debit
    }

@Composable
fun AccountsContent(
    state: AccountsUiState,
    actions: AccountsActions,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var balanceFor by remember { mutableStateOf<AccountEntity?>(null) }
    if (adding || editing != null) {
        AccountFormDialog(
            editing = editing,
            existing = state.accounts.map { it.account },
            onSave = { draft ->
                actions.onSave(draft, editing?.id)
                adding = false
                editing = null
            },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
    balanceFor?.let { account ->
        SetBalanceDialog(
            onSave = { minor, at ->
                actions.onSetBalance(account, minor, at)
                balanceFor = null
            },
            onDismiss = { balanceFor = null },
        )
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.accounts_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accounts_add)) }
        if (state.loaded && state.accounts.isEmpty()) {
            MessageState(
                stringResource(R.string.accounts_empty_title),
                stringResource(R.string.accounts_empty_body),
                Modifier.heightIn(min = 240.dp),
                AppIcons.Info,
            )
        }
        // The accounts sit flat on the page, one after the other, with a hairline between them.
        state.accounts.forEachIndexed { index, view ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountRow(view, onEdit = {
                editing = view.account
            }, onBalance = { balanceFor = view.account }, onArchive = { actions.onArchive(view.account.id) })
        }
    }
}

@Composable
private fun AccountRow(
    view: AccountView,
    onEdit: () -> Unit,
    onBalance: () -> Unit,
    onArchive: () -> Unit,
) {
    val a = view.account
    val style = LocalMoneyStyle.current
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val title = listOfNotNull(a.emoji, a.label).joinToString(" ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                val detail =
                    listOfNotNull(stringResource(kindLabel(a.kind)), a.institution, a.last4?.let { "•••• $it" }).joinToString(" - ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = quiet)
            }
            // A plain account's balance is the amount at the end of its row; a card's figures follow below.
            if (view.card == null && view.balanceMinor != null) {
                AmountText(Money(view.balanceMinor, a.currency), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (view.balanceMinor == null) {
            Text(stringResource(R.string.accounts_no_balance), style = MaterialTheme.typography.bodyMedium, color = quiet)
        } else {
            CardFigures(view)
        }
        view.check?.let {
            val date = DateFormat.shortDate(CairoTime.dateOf(it.atMillis), style.arabic, style.digits)
            Text(stringResource(R.string.accounts_balance_as_of, date), style = MaterialTheme.typography.bodySmall, color = quiet)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onBalance,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.accounts_set_balance)) }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accounts_edit)) }
            TextButton(onClick = onArchive, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accounts_archive)) }
        }
    }
}

/** A credit card's figures: what is owed, then what is left and what was spent side by side, then the next due date. */
@Composable
private fun CardFigures(view: AccountView) {
    val card = view.card ?: return
    val currency = view.account.currency
    val style = LocalMoneyStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Figure(R.string.accounts_outstanding, Money(card.outstandingMinor, currency), MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            card.availableMinor?.let {
                Figure(R.string.accounts_available, Money(it, currency), MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
            }
            Figure(
                R.string.accounts_spent_since_statement,
                Money(card.spentSinceStatementMinor, currency),
                MaterialTheme.typography.bodyLarge,
                Modifier.weight(1f),
            )
        }
        card.nextDue?.let {
            Text(
                stringResource(R.string.accounts_next_due, DateFormat.shortDate(it, style.arabic, style.digits)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Figure(
    label: Int,
    money: Money,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(money, style = style)
    }
}

@Composable
internal fun AccountFormDialog(
    editing: AccountEntity?,
    existing: List<AccountEntity>,
    onSave: (eg.masroof.data.wealth.AccountDraft) -> Unit,
    onDismiss: () -> Unit,
    /** "Add account" from an unknown-card answer (STORY-208): the four digits the card showed. */
    initialLast4: String = "",
) {
    var form by remember {
        mutableStateOf(
            editing?.let {
                AccountForm(
                    it.label,
                    it.kind ?: AccountKinds.DEBIT,
                    it.institution.orEmpty(),
                    it.last4.orEmpty(),
                    it.currency,
                    it.emoji.orEmpty(),
                    it.creditLimitMinor?.let { m -> plainMinor(m) }.orEmpty(),
                    it.statementDay?.toString().orEmpty(),
                    it.dueDay?.toString().orEmpty(),
                )
            } ?: AccountForm(last4 = initialLast4),
        )
    }
    var errors by remember { mutableStateOf(emptySet<AccountField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing == null) R.string.accounts_add else R.string.accounts_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountKinds.all.chunked(2).forEach { kinds ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kinds.forEach { k ->
                            FilterChip(
                                selected = form.kind == k,
                                onClick = { form = form.copy(kind = k) },
                                label = { Text(stringResource(kindLabel(k))) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                FormField(
                    form.label,
                    { form = form.copy(label = it) },
                    R.string.accounts_field_label,
                    error =
                        R.string.accounts_error_label.takeIf {
                            AccountField.LABEL in
                                errors
                        },
                )
                FormField(form.institution, { form = form.copy(institution = it) }, R.string.accounts_field_institution)
                val last4Error =
                    when {
                        AccountField.LAST4 in errors -> R.string.accounts_error_last4
                        AccountField.DUPLICATE in errors -> R.string.accounts_error_duplicate
                        else -> null
                    }
                FormField(
                    form.last4,
                    { form = form.copy(last4 = it) },
                    R.string.accounts_field_last4,
                    error = last4Error,
                    keyboard = KeyboardType.Number,
                )
                FormField(form.emoji, { form = form.copy(emoji = it) }, R.string.accounts_field_emoji)
                Text(stringResource(R.string.accounts_field_currency), style = MaterialTheme.typography.labelMedium)
                SUPPORTED_CURRENCIES.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { code ->
                            FilterChip(
                                selected = form.currency == code,
                                onClick = { form = form.copy(currency = code) },
                                label = { Text(code) },
                                enabled = editing == null,
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                if (form.kind == AccountKinds.CREDIT_CARD) {
                    FormField(
                        form.limit,
                        { form = form.copy(limit = it) },
                        R.string.accounts_field_limit,
                        error = R.string.accounts_error_limit.takeIf { AccountField.LIMIT in errors },
                        keyboard = KeyboardType.Decimal,
                    )
                    FormField(
                        form.statementDay,
                        { form = form.copy(statementDay = it) },
                        R.string.accounts_field_statement_day,
                        error = R.string.accounts_error_day.takeIf { AccountField.STATEMENT_DAY in errors },
                        keyboard = KeyboardType.Number,
                    )
                    FormField(
                        form.dueDay,
                        { form = form.copy(dueDay = it) },
                        R.string.accounts_field_due_day,
                        error = R.string.accounts_error_day.takeIf { AccountField.DUE_DAY in errors },
                        keyboard = KeyboardType.Number,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val checked = WealthValidation.account(form, existing, editing?.id)
                    errors = checked.errors
                    checked.value?.let(onSave)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.accounts_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accounts_cancel)) }
        },
    )
}

/** `1250050` -> `12500.50`, for filling a field with a stored amount. */
private fun plainMinor(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

@Composable
private fun SetBalanceDialog(
    onSave: (Long, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptySet<BalanceField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_set_balance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(
                    amount,
                    { amount = it },
                    R.string.accounts_balance_field,
                    error = R.string.accounts_balance_error.takeIf { BalanceField.AMOUNT in errors },
                    keyboard = KeyboardType.Decimal,
                )
                FormField(
                    date,
                    { date = it },
                    R.string.accounts_balance_date,
                    error = R.string.accounts_balance_date_error.takeIf { BalanceField.DATE in errors },
                )
                FormField(
                    time,
                    { time = it },
                    R.string.accounts_balance_time,
                    error =
                        when {
                            BalanceField.TIME in errors -> R.string.accounts_balance_time_error
                            BalanceField.FUTURE in errors -> R.string.accounts_balance_future
                            else -> null
                        },
                )
                Text(stringResource(R.string.accounts_balance_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minor = WealthValidation.parseBalance(amount)
                    val moment = WealthValidation.balanceMoment(date, time, System.currentTimeMillis())
                    errors = moment.errors + if (minor == null) setOf(BalanceField.AMOUNT) else emptySet()
                    val at = moment.value
                    if (minor != null && at != null) onSave(minor, at)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.accounts_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accounts_cancel)) }
        },
    )
}
