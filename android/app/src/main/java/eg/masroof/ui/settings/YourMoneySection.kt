package eg.masroof.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.components.SectionCard

/** "Your money": a plain list of rows, one per money feature (Bills first; the others add their row here). */
@Composable
fun YourMoneySection(onOpenBills: () -> Unit = {}) {
    SectionCard(stringResource(R.string.your_money_title)) {
        TextButton(onClick = onOpenBills, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bills_title))
        }
    }
}
