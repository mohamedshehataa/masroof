package eg.masroof.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.ExpenseRow
import eg.masroof.ui.LoadStatus
import eg.masroof.ui.theme.LocalMoneyStyle

/** Amount rendered per locale and digit setting, with tabular numerals so columns line up. */
@Composable
fun AmountText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val s = LocalMoneyStyle.current
    Text(
        text = MoneyFormat.format(money, s.arabic, s.digits),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ExpenseListItem(
    row: ExpenseRow,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
) {
    val arabic = LocalMoneyStyle.current.arabic
    val categoryName =
        row.category?.let { if (arabic) it.nameAr else it.nameEn } ?: stringResource(R.string.uncategorized)
    val title = row.merchant ?: row.notes ?: stringResource(R.string.expense_fallback_title)
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("$categoryName · ${row.date}", style = MaterialTheme.typography.bodySmall)
                if (row.pending) {
                    Text(
                        stringResource(R.string.pending_sync),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (row.failed) {
                    Text(
                        stringResource(R.string.sync_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row {
                        TextButton(onClick = { onRetryEntry(row.id) }, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.retry))
                        }
                        TextButton(onClick = { onDeleteEntry(row.id) }, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
            AmountText(row.money)
        }
    }
}

@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 5,
) {
    val description = stringResource(R.string.loading)
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) {
            Surface(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

@Composable
fun MessageState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (action != null) Column(Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
fun RetryButton(onRetry: () -> Unit) {
    Button(onClick = onRetry, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.retry)) }
}

@Composable
fun StatusBanner(
    status: LoadStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status != LoadStatus.OFFLINE && status != LoadStatus.ERROR && status != LoadStatus.SIGN_IN) return
    val text =
        when (status) {
            LoadStatus.OFFLINE -> R.string.banner_offline
            LoadStatus.SIGN_IN -> R.string.banner_sign_in
            else -> R.string.banner_error
        }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
}

/**
 * The four list states shared by every list: loading skeleton, empty, error-with-retry, offline.
 * When saved rows exist they are always shown (offline-first); [StatusBanner] reports a failed refresh.
 */
@Composable
fun ListStates(
    status: LoadStatus,
    isEmpty: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when {
        !isEmpty -> content()
        status == LoadStatus.LOADING -> SkeletonList(modifier.padding(16.dp))
        status == LoadStatus.OFFLINE ->
            MessageState(stringResource(R.string.offline_title), stringResource(R.string.offline_body), modifier) {
                RetryButton(onRetry)
            }
        status == LoadStatus.SIGN_IN ->
            MessageState(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_body), modifier) {
                RetryButton(onRetry)
            }
        status == LoadStatus.ERROR ->
            MessageState(stringResource(R.string.error_title), stringResource(R.string.error_body), modifier) {
                RetryButton(onRetry)
            }
        else -> MessageState(stringResource(R.string.empty_title), stringResource(R.string.empty_body), modifier)
    }
}

/** True when the device language is Arabic (used where a plain locale check is enough). */
@Composable
fun isArabic(): Boolean = LocalConfiguration.current.locales[0].language == "ar"
