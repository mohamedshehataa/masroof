package eg.masroof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.data.AppSettings
import eg.masroof.data.SettingsRepository
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.theme.LocalMoneyStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** The two settings of the spending figures (REQ-AN-145, REQ-AN-148). Each change is saved at once. */
@HiltViewModel
class AnalyticsSettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        val state: StateFlow<AppSettings> =
            settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppSettings())

        /** The picker only offers 1..28; anything else is refused. */
        fun setMonthStartDay(day: Int) {
            if (day !in AppSettings.MONTH_START_DAYS) return
            viewModelScope.launch { settings.update { it.copy(monthStartDay = day) } }
        }

        fun setCountCash(on: Boolean) {
            viewModelScope.launch { settings.update { it.copy(includeCashWithdrawals = on) } }
        }
    }

@Composable
fun AnalyticsSettingsSection(viewModel: AnalyticsSettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val digits = LocalMoneyStyle.current.digits
    var open by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.settings_section_analytics)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_month_start), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_month_start_value, DateFormat.number(s.monthStartDay, digits)))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (day in AppSettings.MONTH_START_DAYS) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_month_start_value, DateFormat.number(day, digits))) },
                        onClick = {
                            open = false
                            viewModel.setMonthStartDay(day)
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_count_cash), modifier = Modifier.weight(1f))
            Switch(checked = s.includeCashWithdrawals, onCheckedChange = viewModel::setCountCash)
        }
    }
}
