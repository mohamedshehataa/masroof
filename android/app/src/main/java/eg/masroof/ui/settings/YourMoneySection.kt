package eg.masroof.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.components.SectionCard

/** "Your money": a plain list of rows into the money-life screens (Goals now; STORY-206 moves these into the app's tabs). */
@Composable
fun YourMoneySection(onOpenGoals: () -> Unit) {
    SectionCard(stringResource(R.string.goals_settings_section)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onOpenGoals)
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.goals_settings_row))
        }
    }
}
