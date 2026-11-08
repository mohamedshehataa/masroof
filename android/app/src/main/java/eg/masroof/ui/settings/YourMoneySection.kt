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

/** "Your money": a plain list of rows into the money-life screens (STORY-206 moves these into the app's tabs). */
@Composable
fun YourMoneySection(
    onOpenGoals: () -> Unit = {},
    onOpenBills: () -> Unit = {},
    onOpenWealth: () -> Unit = {},
) {
    SectionCard(stringResource(R.string.goals_settings_section)) {
        MoneyRow(stringResource(R.string.settings_wealth_row), onOpenWealth)
        MoneyRow(stringResource(R.string.bills_title), onOpenBills)
        MoneyRow(stringResource(R.string.goals_settings_row), onOpenGoals)
    }
}

@Composable
private fun MoneyRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
    }
}
