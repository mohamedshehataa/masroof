package eg.masroof.ui.ask

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.theme.AppIcons

/** Speech-bubble glyph for "Ask Masroof": the outlined one from the app's own icon set. */
val AskIcon: ImageVector = AppIcons.Chat

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
