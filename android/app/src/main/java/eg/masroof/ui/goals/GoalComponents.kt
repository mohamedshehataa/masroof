package eg.masroof.ui.goals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.PercentFormat
import eg.masroof.data.goals.GoalMath
import eg.masroof.ui.theme.LocalMoneyStyle

/** Money in the goal's own currency per locale and digit setting. */
@Composable
internal fun goalMoney(
    minor: Long,
    currency: String,
): String {
    val s = LocalMoneyStyle.current
    return MoneyFormat.format(Money(minor, currency), s.arabic, s.digits)
}

/** Whole percent from basis points, half away from zero: 1588 bp is "16%". */
@Composable
internal fun wholePercent(bp: Int): String {
    val s = LocalMoneyStyle.current
    return PercentFormat.format((bp + HALF_PERCENT_BP) / BP_PER_PERCENT * BP_PER_PERCENT, s.arabic, s.digits)
}

private const val HALF_PERCENT_BP = 50
private const val BP_PER_PERCENT = 100
private const val RING_START = -90f
private const val FULL_CIRCLE = 360f
private const val MAX_BP = 10_000f

/** A ring with the goal emoji in the middle, filled to [percentBp] of the target. */
@Composable
fun GoalRing(
    emoji: String?,
    percentBp: Int,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = this.size.minDimension * RING_STROKE, cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val box = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 0f, FULL_CIRCLE, false, Offset(inset, inset), box, style = stroke)
            val sweep = FULL_CIRCLE * percentBp.coerceIn(0, MAX_BP.toInt()) / MAX_BP
            drawArc(fill, RING_START, sweep, false, Offset(inset, inset), box, style = stroke)
        }
        Text(emoji ?: "🎯", fontSize = (size.value * EMOJI_SCALE).sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

private const val RING_STROKE = 0.1f
private const val EMOJI_SCALE = 0.42f

/**
 * "Money you've added": the running total as a step line, one step per recorded movement. Drawn only from [steps]
 * (oldest first); nothing is drawn for fewer than one movement.
 */
@Composable
fun GoalStepChart(
    steps: List<Long>,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        if (steps.isEmpty()) return@Canvas
        val top = steps.max().coerceAtLeast(1L).toFloat()
        val bottom = steps.min().coerceAtMost(0L).toFloat()
        val range = (top - bottom).coerceAtLeast(1f)
        val dx = if (steps.size == 1) size.width else size.width / steps.size

        fun y(v: Long) = size.height - (v - bottom) / range * size.height

        fun x(v: Float) = if (rtl) size.width - v else v
        val path = Path().apply { moveTo(x(0f), y(0L)) }
        var previous = 0L
        steps.forEachIndexed { i, v ->
            path.lineTo(x(dx * i), y(previous))
            path.lineTo(x(dx * i), y(v))
            path.lineTo(x(dx * (i + 1)), y(v))
            previous = v
        }
        drawPath(path, line, style = Stroke(width = STEP_STROKE, cap = StrokeCap.Round))
    }
}

private val CHART_HEIGHT = 120.dp
private const val STEP_STROKE = 6f

/** The lines under a goal's figures: days to go, monthly need and pace. */
@Composable
internal fun goalPaceText(progress: GoalMath.Progress, currency: String): String =
    when (val pace = progress.pace) {
        GoalMath.Pace.Reached -> stringResource(R.string.goals_reached)
        GoalMath.Pace.OnTrack -> stringResource(R.string.goals_on_track)
        is GoalMath.Pace.Behind -> stringResource(R.string.goals_behind, goalMoney(pace.byMinor, currency))
        is GoalMath.Pace.PastDate -> stringResource(R.string.goals_past_date, goalMoney(pace.leftMinor, currency))
        GoalMath.Pace.None -> ""
    }

@Composable
internal fun goalDaysText(days: Int): String =
    pluralStringResource(R.plurals.goals_days_to_go, days, DateFormat.number(days, LocalMoneyStyle.current.digits))

@Composable
internal fun goalSavedOfTarget(progress: GoalMath.Progress, currency: String): String =
    stringResource(R.string.goals_of, goalMoney(progress.savedMinor, currency), goalMoney(progress.targetMinor, currency))

@Composable
internal fun goalRingDescription(card: GoalCardUi): String =
    stringResource(
        R.string.goals_ring_desc,
        card.name,
        wholePercent(card.progress.percentBp),
    )
