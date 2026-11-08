package eg.masroof.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.components.SectionCard

/** "Your money": a plain list of entry rows into the money-life screens. Later stories add their rows here. */
@Composable
fun YourMoneySection(onOpenWealth: () -> Unit) {
    SectionCard(stringResource(R.string.settings_section_money)) {
        OutlinedButton(onClick = onOpenWealth, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_wealth_row))
        }
    }
}
