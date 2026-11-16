package eg.masroof.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import eg.masroof.ui.theme.AppIcons
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.theme.extra
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.LoadStatus

/**
 * Amount rendered per locale and digit setting, with tabular numerals where the typeface has them. With [smallUnit] the
 * currency is set smaller than the figure (the large home numbers); the text itself is unchanged.
 */
@Composable
fun AmountText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    smallUnit: Boolean = false,
) {
    val s = LocalMoneyStyle.current
    val text = MoneyFormat.format(money, s.arabic, s.digits)
    Text(
        text = if (smallUnit) withSmallUnit(text, style) else AnnotatedString(text),
        modifier = modifier,
        color = color,
        style = style.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
    )
}

private const val UNIT_SCALE = 0.5f

/** [text] with everything outside the figure (the currency) at half size and medium weight. */
private fun withSmallUnit(
    text: String,
    style: TextStyle,
): AnnotatedString {
    val first = text.indexOfFirst { it.isDigit() }
    val last = text.indexOfLast { it.isDigit() }
    if (first < 0) return AnnotatedString(text)
    val unit = SpanStyle(fontSize = style.fontSize * UNIT_SCALE, fontWeight = FontWeight.Medium)
    // A minus sign in front of the figure belongs to the figure: it keeps the full size.
    val lead = text.substring(0, first)
    return buildAnnotatedString {
        append(text)
        if (lead.any { it.isLetter() }) addStyle(unit, 0, first)
        if (last < text.lastIndex) addStyle(unit, last + 1, text.length)
    }
}

/**
 * Soft square with the category's emoji; the first letter of the name, on a faint wash of the category's color, when
 * it has none (STORY-207). Emoji tiles are neutral so a list of rows stays calm.
 */
@Composable
fun CategoryAvatar(
    name: String,
    key: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    emoji: String? = null,
) {
    val glyph = emoji?.takeIf { it.isNotBlank() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(TILE_CORNER_PERCENT))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (glyph == null) Modifier.background(CategoryPalette.colorFor(key).copy(alpha = LETTER_TINT)) else Modifier)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph ?: name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val TILE_CORNER_PERCENT = 32
private const val LETTER_TINT = 0.18f

/** A line icon on a soft square: the lead of an action row or an empty state. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(TILE_CORNER_PERCENT)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

/** A flat, tinted panel: the app's card. No shadow and no outline; the tint alone separates it from the page. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = color, contentColor = contentColorFor(color)),
        content = content,
    )
}

/** A thin progress line with a quiet track. [fraction] is clamped to 0..1; the caller describes it for TalkBack. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

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

/** A titled panel: the building block of the Insights and Home screens. The title is a quiet label, not a headline. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

/** The quiet heading above a group of rows or inside a panel. */
@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * What a list row says about the kind of money movement, by its TYPE (a review state alone says nothing about direction: the server
 * also holds large purchases for review). Null for an ordinary confirmed expense.
 */
fun kindLabelRes(row: ExpenseRow): Int? =
    when {
        row.income -> if (row.reviewPending) R.string.income_pending_review else R.string.income_received
        row.transfer -> if (row.reviewPending) R.string.transfer_pending_review else R.string.transfer_label
        row.reviewPending -> R.string.expense_pending_review
        else -> null
    }

@Composable
fun ExpenseListItem(
    row: ExpenseRow,
    modifier: Modifier = Modifier,
    onRetryEntry: (String) -> Unit = {},
    onDeleteEntry: (String) -> Unit = {},
    capturedLocalId: String? = null,
    onUndoCaptured: (String) -> Unit = {},
    onOpen: ((String) -> Unit)? = null,
) {
    val style = LocalMoneyStyle.current
    val categoryName =
        row.category?.let { if (style.arabic) it.nameAr else it.nameEn } ?: stringResource(R.string.uncategorized)
    val title =
        row.merchant?.takeIf { it.isNotBlank() } ?: row.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.expense_fallback_title)
    val date = DateFormat.shortDate(row.date, style.arabic, style.digits)
    // Every row opens the edit screen, which works on Room (STORY-170).
    val openLabel = stringResource(R.string.edit_open)
    val open = onOpen != null
    // A flat row on the page, like a line in a ledger: no card, the amount aligned at the end.
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (open) Modifier.clickable(onClickLabel = openLabel) { onOpen(row.id) } else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryAvatar(categoryName, row.category?.id, emoji = row.category?.emoji)
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
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                val kindLabel = kindLabelRes(row)
                if (kindLabel != null) {
                    Text(
                        stringResource(kindLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (row.excluded) {
                    Text(
                        stringResource(R.string.wallet_excluded_marker),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.paidBy != null) {
                    val paidByText = stringResource(R.string.paid_by_chip, row.paidBy)
                    Text(
                        paidByText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.semantics { contentDescription = paidByText },
                    )
                }
                if (capturedLocalId != null) {
                    // recorded by the phone itself in the last 7 days: say so, and let the user take it back
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.captured_chip),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { onUndoCaptured(capturedLocalId) }, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.captured_undo))
                        }
                    }
                }
                if (row.pending) {
                    Text(
                        stringResource(R.string.pending_sync),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
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
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(TILE_CORNER_PERCENT))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
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
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
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
            MessageState(stringResource(R.string.offline_title), stringResource(R.string.offline_body), modifier, AppIcons.Alert) {
                RetryButton(onRetry)
            }
        status == LoadStatus.SIGN_IN ->
            MessageState(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_body), modifier, AppIcons.Info) {
                RetryButton(onRetry)
            }
        status == LoadStatus.ERROR ->
            MessageState(stringResource(R.string.error_title), stringResource(R.string.error_body), modifier, AppIcons.Alert) {
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
