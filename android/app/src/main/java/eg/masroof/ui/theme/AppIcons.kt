@file:Suppress("MagicNumber") // icon geometry on a 24 x 24 grid

package eg.masroof.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val GRID = 24f
private const val STROKE = 1.75f

/** One outlined glyph: every path is a round-capped line of the same weight, so the set reads as one family. */
private fun lineIcon(
    name: String,
    autoMirror: Boolean = false,
    draw: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = GRID.dp,
            defaultHeight = GRID.dp,
            viewportWidth = GRID,
            viewportHeight = GRID,
            autoMirror = autoMirror,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

private fun PathBuilder.circle(
    cx: Float,
    cy: Float,
    r: Float,
) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
}

private fun PathBuilder.line(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
) {
    moveTo(x1, y1)
    lineTo(x2, y2)
}

/** A rectangle with [r]-rounded corners, from its top-left corner ([x], [y]). */
private fun PathBuilder.roundRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
) {
    moveTo(x + r, y)
    horizontalLineToRelative(w - 2 * r)
    arcToRelative(r, r, 0f, false, true, r, r)
    verticalLineToRelative(h - 2 * r)
    arcToRelative(r, r, 0f, false, true, -r, r)
    horizontalLineToRelative(-(w - 2 * r))
    arcToRelative(r, r, 0f, false, true, -r, -r)
    verticalLineToRelative(-(h - 2 * r))
    arcToRelative(r, r, 0f, false, true, r, -r)
    close()
}

/**
 * The app's own outlined icons. The core Material set is small and filled; these are drawn to one weight so the bottom
 * bar, the home actions and the empty states share a look. Emoji stay only where they are the user's own data
 * (categories, accounts, goals).
 */
object AppIcons {
    val Wallet =
        lineIcon("Wallet") {
            roundRect(3.5f, 8f, 17f, 12f, 2.5f)
            moveTo(3.5f, 10.5f)
            verticalLineTo(6.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineTo(16f)
            verticalLineTo(8f)
            line(16.4f, 14f, 16.6f, 14f)
        }

    val Calendar =
        lineIcon("Calendar") {
            roundRect(4f, 5.5f, 16f, 15f, 2.5f)
            line(4f, 10f, 20f, 10f)
            line(8.5f, 3.5f, 8.5f, 7f)
            line(15.5f, 3.5f, 15.5f, 7f)
        }

    val Target =
        lineIcon("Target") {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 4.5f)
            line(11.9f, 12f, 12.1f, 12f)
        }

    /** A trend line; it mirrors in Arabic, where time runs from the right. */
    val Growth =
        lineIcon("Growth", autoMirror = true) {
            moveTo(3.5f, 17f)
            lineTo(9f, 11.5f)
            lineTo(13f, 14.5f)
            lineTo(20.5f, 6.5f)
            moveTo(15.5f, 6.5f)
            horizontalLineTo(20.5f)
            verticalLineTo(11.5f)
        }

    val Chat =
        lineIcon("Chat") {
            moveTo(5.5f, 4.5f)
            horizontalLineTo(18.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineTo(14.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(10.5f)
            lineTo(6.5f, 20f)
            verticalLineTo(16.5f)
            horizontalLineTo(5.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(6.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
        }

    val Plus =
        lineIcon("Plus") {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }

    val Mic =
        lineIcon("Mic") {
            roundRect(9f, 3.5f, 6f, 11f, 3f)
            moveTo(5.5f, 11.5f)
            arcToRelative(6.5f, 6.5f, 0f, false, false, 13f, 0f)
            line(12f, 18f, 12f, 20.5f)
        }

    val Receipt =
        lineIcon("Receipt") {
            moveTo(6f, 3.5f)
            horizontalLineTo(18f)
            verticalLineTo(20.5f)
            lineToRelative(-3f, -2f)
            lineToRelative(-3f, 2f)
            lineToRelative(-3f, -2f)
            lineToRelative(-3f, 2f)
            close()
            line(9.5f, 8.5f, 14.5f, 8.5f)
            line(9.5f, 12.5f, 14.5f, 12.5f)
        }

    val Inbox =
        lineIcon("Inbox") {
            moveTo(3.5f, 13f)
            lineTo(6.5f, 5f)
            horizontalLineTo(17.5f)
            lineTo(20.5f, 13f)
            verticalLineTo(17.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            close()
            moveTo(3.5f, 13f)
            horizontalLineTo(8.5f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, 7f, 0f)
            horizontalLineTo(20.5f)
        }

    val Bank =
        lineIcon("Bank") {
            moveTo(3.5f, 9.5f)
            lineTo(12f, 4f)
            lineTo(20.5f, 9.5f)
            close()
            line(6f, 12.5f, 6f, 17f)
            line(10f, 12.5f, 10f, 17f)
            line(14f, 12.5f, 14f, 17f)
            line(18f, 12.5f, 18f, 17f)
            line(3.5f, 20f, 20.5f, 20f)
        }

    val Bars =
        lineIcon("Bars") {
            line(5.5f, 19f, 5.5f, 11f)
            line(12f, 19f, 12f, 5f)
            line(18.5f, 19f, 18.5f, 14f)
        }

    val Person =
        lineIcon("Person") {
            circle(12f, 8.5f, 3.75f)
            moveTo(4.5f, 20f)
            arcToRelative(7.5f, 6.5f, 0f, false, true, 15f, 0f)
        }

    val People =
        lineIcon("People") {
            circle(9f, 9f, 3.25f)
            moveTo(3f, 19.5f)
            arcToRelative(6f, 5.5f, 0f, false, true, 12f, 0f)
            moveTo(15.5f, 6f)
            arcToRelative(3.25f, 3.25f, 0f, false, true, 0f, 6.25f)
            moveTo(17.5f, 14.5f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, 3.5f, 5f)
        }

    val Lock =
        lineIcon("Lock") {
            roundRect(5f, 10.5f, 14f, 10f, 2.5f)
            moveTo(8f, 10.5f)
            verticalLineTo(8f)
            arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
            verticalLineTo(10.5f)
        }

    val Check =
        lineIcon("Check") {
            circle(12f, 12f, 8.5f)
            moveTo(8.25f, 12.25f)
            lineTo(11f, 15f)
            lineTo(15.75f, 9.5f)
        }

    val Globe =
        lineIcon("Globe") {
            circle(12f, 12f, 8.5f)
            line(3.5f, 12f, 20.5f, 12f)
            moveTo(12f, 3.5f)
            arcToRelative(13f, 13f, 0f, false, true, 0f, 17f)
            arcToRelative(13f, 13f, 0f, false, true, 0f, -17f)
        }

    val Bell =
        lineIcon("Bell") {
            moveTo(6f, 16.5f)
            verticalLineTo(11f)
            arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
            verticalLineTo(16.5f)
            lineTo(19.5f, 18f)
            horizontalLineTo(4.5f)
            close()
            moveTo(10f, 20.5f)
            arcToRelative(2.2f, 2.2f, 0f, false, false, 4f, 0f)
        }

    val Info =
        lineIcon("Info") {
            circle(12f, 12f, 8.5f)
            line(12f, 11.25f, 12f, 16f)
            line(11.9f, 8f, 12.1f, 8f)
        }

    val Alert =
        lineIcon("Alert") {
            moveTo(12f, 4.5f)
            lineTo(20.5f, 19f)
            horizontalLineTo(3.5f)
            close()
            line(12f, 10f, 12f, 14f)
            line(11.9f, 16.5f, 12.1f, 16.5f)
        }

    /** Points to the end edge (mirrors in Arabic): "this row opens something". */
    val ChevronEnd =
        lineIcon("ChevronEnd", autoMirror = true) {
            moveTo(9.5f, 6.5f)
            lineTo(15f, 12f)
            lineTo(9.5f, 17.5f)
        }

    val ChevronDown =
        lineIcon("ChevronDown") {
            moveTo(6.5f, 9.5f)
            lineTo(12f, 15f)
            lineTo(17.5f, 9.5f)
        }
}
