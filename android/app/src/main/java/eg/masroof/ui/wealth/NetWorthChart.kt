package eg.masroof.ui.wealth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
    Column(modifier = Modifier.fillMaxWidth()) {
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

            fun x(i: Int): Float {
                val f = if (points.size == 1) size.width / 2f else size.width * i / (points.size - 1).toFloat()
                return if (rtl) size.width - f else f
            }

            fun y(v: Long) = size.height - (size.height * (v - min).toFloat() / span.toFloat()).coerceIn(0f, size.height)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            for (i in 1 until points.size) {
                drawLine(line, Offset(x(i - 1), y(points[i - 1].netMinor)), Offset(x(i), y(points[i].netMinor)), 6f, StrokeCap.Round)
            }
            drawCircle(line, 10f, Offset(x(index), y(points[index].netMinor)))
        }
        if (points.size == 1) Text(stringResource(R.string.wealth_history_starts), style = MaterialTheme.typography.bodySmall)
        val point = points[index]
        val date = DateFormat.shortDate(LocalDate.parse(point.cairoDate), style.arabic, style.digits)
        Text(stringResource(R.string.wealth_chart_point, date, moneyText(point.netMinor)), style = MaterialTheme.typography.bodyMedium)
    }
}
