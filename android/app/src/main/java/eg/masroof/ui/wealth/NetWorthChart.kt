package eg.masroof.ui.wealth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.ui.theme.LocalMoneyStyle
import java.time.LocalDate

private const val AREA_ALPHA = 0.16f

/**
 * The daily net worth line, [points] oldest first (never empty: the caller passes the current value as the one point when
 * there is no history). Tap near a point to read its date and value; the x-axis mirrors in RTL.
 */
@Composable
fun NetWorthChart(
    points: List<NetWorthSnapshotEntity>,
    rangeLabel: String,
) {
    if (points.isEmpty()) return
    val style = LocalMoneyStyle.current
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val page = MaterialTheme.colorScheme.background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val index = (selected ?: points.lastIndex).coerceIn(0, points.lastIndex)
    val description =
        stringResource(
            R.string.wealth_chart_summary,
            rangeLabel,
            moneyText(points.last().netMinor),
            moneyText(points.minOf { it.netMinor }),
            moneyText(points.maxOf { it.netMinor }),
        )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description }
                .pointerInput(points, rtl) {
                    detectTapGestures { tap ->
                        if (points.size > 1) {
                            val step = size.width / (points.size - 1).toFloat()
                            val fromStart = if (rtl) size.width - tap.x else tap.x
                            selected = Math.round(fromStart / step).coerceIn(0, points.lastIndex)
                        }
                    }
                },
        ) {
            val min = points.minOf { it.netMinor }
            val span = (points.maxOf { it.netMinor } - min).coerceAtLeast(1L)
            val stroke = 2.5.dp.toPx()
            val dot = 5.dp.toPx()
            // The line keeps clear of the top and of the baseline, so the round caps and the marker are never clipped.
            val top = dot
            val plot = size.height - dot * 2

            fun x(i: Int): Float {
                val f = if (points.size == 1) size.width / 2f else size.width * i / (points.size - 1).toFloat()
                return if (rtl) size.width - f else f
            }

            fun y(v: Long) = top + plot - (plot * (v - min).toFloat() / span.toFloat()).coerceIn(0f, plot)
            if (points.size > 1) {
                val area =
                    Path().apply {
                        moveTo(x(0), size.height)
                        points.forEachIndexed { i, p -> lineTo(x(i), y(p.netMinor)) }
                        lineTo(x(points.lastIndex), size.height)
                        close()
                    }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = AREA_ALPHA), Color.Transparent)))
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            for (i in 1 until points.size) {
                drawLine(line, Offset(x(i - 1), y(points[i - 1].netMinor)), Offset(x(i), y(points[i].netMinor)), stroke, StrokeCap.Round)
            }
            val marker = Offset(x(index), y(points[index].netMinor))
            drawCircle(line, dot, marker)
            drawCircle(page, dot / 2f, marker)
        }
        if (points.size == 1) {
            Text(
                stringResource(R.string.wealth_history_starts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val point = points[index]
        val date = DateFormat.shortDate(LocalDate.parse(point.cairoDate), style.arabic, style.digits)
        Text(
            stringResource(R.string.wealth_chart_point, date, moneyText(point.netMinor)),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        )
    }
}
