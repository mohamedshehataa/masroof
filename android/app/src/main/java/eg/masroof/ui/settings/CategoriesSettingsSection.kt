package eg.masroof.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.components.SectionCard

/** The two Settings rows of STORY-207: Categories and Merchant rules. */
@Composable
fun CategoriesSettingsSection(
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
) {
    SectionCard(stringResource(R.string.cat_settings_title)) {
        TextButton(onClick = onOpenCategories, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cat_open)) }
        TextButton(onClick = onOpenRules, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rules_open)) }
    }
}
