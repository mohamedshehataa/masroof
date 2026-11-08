package eg.masroof.ui.ask

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R

/** Speech-bubble glyph for "Ask Masroof" (the core icon set has none; the extended set is a large dependency). */
val AskIcon: ImageVector =
    materialIcon(name = "Filled.AskMasroof") {
        materialPath {
            moveTo(4f, 4f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(12f)
            horizontalLineTo(8f)
            lineToRelative(-4f, 4f)
            close()
        }
    }

/** The top-bar button that opens Ask Masroof; a 48dp touch target with a content description for TalkBack. */
@Composable
fun AskButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(AskIcon, contentDescription = stringResource(R.string.ask_open))
    }
}
