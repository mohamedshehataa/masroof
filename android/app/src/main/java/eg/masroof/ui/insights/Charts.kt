package eg.masroof.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.core.DateFormat
import eg.masroof.core.DigitStyle
import java.time.DayOfWeek

/** One slice of the category donut; [bps] is its share of spending in basis points. */
data class DonutSlice(
    val color: Color,
    val bps: Int,
)

private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private const val SLICE_GAP = 2f
private const val BPS_TOTAL = 10_000f

/**
 * Ring chart of category shares. Drawn on a Canvas and mirrored in RTL so it reads in the same direction as the text.
 * [description] is the TalkBack summary with the key numbers; the ring itself is decorative.
 */
@Composable
fun CategoryDonut(
    slices: List<DonutSlice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    centerContent: @Composable () -> Unit = {},
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.size(size).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
            val stroke = 24.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, FULL_CIRCLE, false, topLeft, arc, style = Stroke(stroke))
            var start = START_ANGLE
            slices.forEach { slice ->
                val sweep = FULL_CIRCLE * slice.bps / BPS_TOTAL
                val drawn = (sweep - SLICE_GAP).coerceAtLeast(1f)
                drawArc(slice.color, start + SLICE_GAP / 2, drawn, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) { centerContent() }
    }
}

/** One column of a [BarChart]. [value] is minor units, used only for the bar height. */
data class BarItem(
    val label: String,
    val value: Long,
    val highlighted: Boolean,
)

private const val MIN_BAR_FRACTION = 0.04f
private const val BAR_WIDTH_FRACTION = 0.62f

/**
 * Vertical bars with a label under each. Built from layouts (not a Canvas) so it mirrors automatically in RTL: the
 * oldest month sits at the start edge. The [highlighted] bar uses the brand color, the rest a tint of it.
 */
@Composable
fun BarChart(
    items: List<BarItem>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    showLabels: Boolean = true,
) {
    val max = items.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier.fillMaxWidth().height(height).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val fraction =
                if (item.value <= 0) 0f else (item.value.toFloat() / max.toFloat()).coerceAtLeast(MIN_BAR_FRACTION)
            Column(Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(BAR_WIDTH_FRACTION)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                if (item.highlighted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                                },
                            ),
                    )
                }
                if (showLabels) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Saturday-first column of a date (the Egyptian week): Sat = 0 ... Fri = 6. */
internal fun weekdayIndex(day: DayOfWeek): Int = (day.value + 1) % WEEK_DAYS

internal const val WEEK_DAYS = 7
private const val HEAT_LEVELS = 4
private val LEVEL_ALPHA = floatArrayOf(0.22f, 0.42f, 0.68f, 1f)

/** Heat level 0 (no spend) to 4 (the busiest day) for [value] against the period [max]. */
internal fun heatLevel(
    value: Long,
    max: Long,
): Int {
    if (value <= 0 || max <= 0) return 0
    // ceil(4 * value / max) without floating point
    return ((value * HEAT_LEVELS + max - 1) / max).toInt().coerceIn(1, HEAT_LEVELS)
}

/**
 * Month calendar of daily spend, weeks as rows, Saturday first. Cell darkness shows the day's share of the busiest day.
 * Rows are layouts so the grid mirrors in RTL. [description] carries the key numbers for TalkBack.
 */
@Composable
fun CalendarHeatmap(
    days: List<CalendarDay>,
    arabic: Boolean,
    digits: DigitStyle,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.total.amountMinor }
    val lead = weekdayIndex(days.first().date.dayOfWeek)
    val cells: List<CalendarDay?> = List(lead) { null } + days
    val weeks = cells.chunked(WEEK_DAYS)
    val weekdayOrder = List(WEEK_DAYS) { DayOfWeek.SATURDAY.plus(it.toLong()) }
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            weekdayOrder.forEach { d ->
                Text(
                    DateFormat.weekday(d, arabic, narrow = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until WEEK_DAYS) {
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) HeatCell(day, max, digits)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(
    day: CalendarDay,
    max: Long,
    digits: DigitStyle,
) {
    val level = heatLevel(day.total.amountMinor, max)
    val primary = MaterialTheme.colorScheme.primary
    val background = if (level == 0) MaterialTheme.colorScheme.surfaceVariant else primary.copy(alpha = LEVEL_ALPHA[level - 1])
    val textColor = if (level >= HEAT_LEVELS - 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            DateFormat.dayNumber(day.date, digits),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = textColor,
        )
    }
}

/** A weekday row of [WeekdayBars]. */
data class WeekdayBar(
    val day: DayOfWeek,
    val value: Long,
    val amount: String,
)

private const val WEEKDAY_LABEL_WIDTH = 44
private const val WEEKDAY_AMOUNT_WIDTH = 88

/** Horizontal bars, one per weekday (Saturday first); the busiest day is highlighted. */
@Composable
fun WeekdayBars(
    bars: List<WeekdayBar>,
    arabic: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val max = bars.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L
    val top = bars.maxByOrNull { it.value }?.takeIf { it.value > 0 }
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { bar ->
            val fraction = if (bar.value <= 0) 0f else (bar.value.toFloat() / max.toFloat()).coerceAtLeast(MIN_BAR_FRACTION)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.weekday(bar.day, arabic, narrow = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(WEEKDAY_LABEL_WIDTH.dp),
                )
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (bar === top) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                },
                            ),
                    )
                }
                Text(
                    bar.amount,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(WEEKDAY_AMOUNT_WIDTH.dp).padding(start = 8.dp),
                )
            }
        }
    }
}
