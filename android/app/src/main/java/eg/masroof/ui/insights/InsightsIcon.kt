package eg.masroof.ui.insights

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Three-bar chart glyph for the Insights tab (the core icon set has none; the extended set is a large dependency). */
val InsightsIcon: ImageVector =
    materialIcon(name = "Filled.Insights") {
        materialPath {
            moveTo(5f, 9.2f)
            horizontalLineToRelative(3f)
            verticalLineTo(19f)
            horizontalLineTo(5f)
            close()
            moveTo(10.6f, 5f)
            horizontalLineToRelative(2.8f)
            verticalLineTo(19f)
            horizontalLineToRelative(-2.8f)
            close()
            moveTo(16.2f, 13f)
            horizontalLineToRelative(2.8f)
            verticalLineTo(19f)
            horizontalLineToRelative(-2.8f)
            close()
        }
    }
