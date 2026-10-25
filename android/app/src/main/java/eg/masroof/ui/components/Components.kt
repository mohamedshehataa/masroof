package eg.masroof.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.extra
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.LoadStatus

/** Amount rendered per locale and digit setting, with tabular numerals so columns line up. */
@Composable
fun AmountText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    val s = LocalMoneyStyle.current
    Text(
        text = MoneyFormat.format(money, s.arabic, s.digits),
        modifier = modifier,
        color = color,
        style = style.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
    )
}

/** Colored circle with the first letter of the category: the stand-in for a category icon. */
@Composable
fun CategoryAvatar(
    name: String,
    key: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val color = CategoryPalette.colorFor(key)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = AVATAR_ALPHA))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val AVATAR_ALPHA = 0.18f

/**
 * Change versus the previous period as a chip: red up arrow when spending grew, green down arrow when it fell.
 * [deltaBps] null means there is nothing to compare with, so no chip is shown.
 */
@Composable
fun DeltaChip(
    deltaBps: Int?,
    modifier: Modifier = Modifier,
) {
    if (deltaBps == null) return
    val s = LocalMoneyStyle.current
    val percent = PercentFormat.format(kotlin.math.abs(deltaBps), s.arabic, s.digits)
    val extra = MaterialTheme.extra
    val chip =
        when {
            deltaBps > 0 ->
                Chip(extra.negativeContainer, extra.onNegativeContainer, "↑", stringResource(R.string.delta_up_desc, percent))
            deltaBps < 0 ->
                Chip(extra.positiveContainer, extra.onPositiveContainer, "↓", stringResource(R.string.delta_down_desc, percent))
            else ->
                Chip(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "=",
                    stringResource(R.string.delta_flat_desc),
                )
        }
    Row(
        modifier
            .clip(CircleShape)
            .background(chip.container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = chip.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(chip.arrow, color = chip.content, style = MaterialTheme.typography.labelMedium)
        Text(percent, color = chip.content, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
    }
}

private data class Chip(
    val container: Color,
    val content: Color,
    val arrow: String,
    val description: String,
)

/** A titled card: the building block of the Insights and Home screens. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
fun ExpenseListItem(
    row: ExpenseRow,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
) {
    val style = LocalMoneyStyle.current
    val categoryName =
        row.category?.let { if (style.arabic) it.nameAr else it.nameEn } ?: stringResource(R.string.uncategorized)
    val title = row.merchant ?: row.notes ?: stringResource(R.string.expense_fallback_title)
    val date = DateFormat.shortDate(row.date, style.arabic, style.digits)
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryAvatar(categoryName, row.category?.id)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$categoryName · $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sourceLabel =
                    when (row.source) {
                        "SMS" -> R.string.source_sms
                        "PUSH" -> R.string.source_notification
                        else -> null
                    }
                if (sourceLabel != null) {
                    Text(
                        stringResource(sourceLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = SKELETON_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SKELETON_MS), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .alpha(pulse)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

private const val SKELETON_LOW = 0.45f
private const val SKELETON_MS = 800

@Composable
fun MessageState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (icon != null) 16.dp else 0.dp),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (action != null) Column(Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
fun RetryButton(onRetry: () -> Unit) {
    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
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
            MessageState(stringResource(R.string.offline_title), stringResource(R.string.offline_body), modifier, Icons.Filled.Warning) {
                RetryButton(onRetry)
            }
        status == LoadStatus.SIGN_IN ->
            MessageState(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_body), modifier, Icons.Filled.Info) {
                RetryButton(onRetry)
            }
        status == LoadStatus.ERROR ->
            MessageState(stringResource(R.string.error_title), stringResource(R.string.error_body), modifier, Icons.Filled.Warning) {
                RetryButton(onRetry)
            }
        else ->
            MessageState(
                stringResource(R.string.empty_title),
                stringResource(R.string.empty_body),
                modifier,
                Icons.Filled.ShoppingCart,
            )
    }
}

/** True when the device language is Arabic (used where a plain locale check is enough). */
@Composable
fun isArabic(): Boolean = LocalConfiguration.current.locales[0].language == "ar"
