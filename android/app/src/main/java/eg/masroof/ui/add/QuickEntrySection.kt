package eg.masroof.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.ui.viewmodel.ADD_CURRENCIES
import eg.masroof.ui.viewmodel.AddFormState
import eg.masroof.ui.viewmodel.VoiceUi

/** What the Say it parts of the Add form can do (STORY-200). The defaults make each part inert, so old callers keep working. */
class AddQuickActions(
    val onType: (Boolean) -> Unit = {},
    val onCurrency: (String) -> Unit = {},
    val onMerchant: (String) -> Unit = {},
    val onQuickText: (String) -> Unit = {},
    val onQuickDone: () -> Unit = {},
    val voice: AddVoiceActions = AddVoiceActions(),
)

/** The mic and its panel. */
class AddVoiceActions(
    val onMic: () -> Unit = {},
    /** False when the phone has no speech recognizer: the mic is hidden. */
    val micAvailable: Boolean = false,
    val onStop: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onRationaleContinue: () -> Unit = {},
    val onNoticeContinue: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/** Soft tint on a field the last line filled, so the user checks it before Save. */
@Composable
internal fun highlightColors(on: Boolean) =
    if (on) {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

/** The one-line "Type it: 150 fuel" field with the mic button, and the listening panel under it. */
@Composable
internal fun QuickLine(
    form: AddFormState,
    quick: AddQuickActions,
) {
    OutlinedTextField(
        value = form.quickText,
        onValueChange = quick.onQuickText,
        label = { Text(stringResource(R.string.voice_quick_label)) },
        trailingIcon =
            if (quick.voice.micAvailable) {
                {
                    IconButton(onClick = quick.voice.onMic, modifier = Modifier.testTag("mic")) {
                        Icon(painterResource(R.drawable.ic_voice_mic), contentDescription = stringResource(R.string.voice_mic_desc))
                    }
                }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { quick.onQuickDone() }),
        // While listening the line belongs to the recognizer; the typed text is kept.
        readOnly = form.voice is VoiceUi.Listening || form.voice == VoiceUi.Processing,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("quick_text"),
    )
    VoicePanel(form.voice, quick)
    if (form.highlight.isNotEmpty()) {
        Text(
            stringResource(R.string.voice_filled),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun VoicePanel(
    voice: VoiceUi,
    quick: AddQuickActions,
) {
    if (voice == VoiceUi.Idle || voice == VoiceUi.Requesting) return
    Card(Modifier.fillMaxWidth().testTag("voice_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (voice) {
                is VoiceUi.Listening -> ListeningBody(voice, quick)
                VoiceUi.Processing -> {
                    Text(stringResource(R.string.voice_processing), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is VoiceUi.OnlineNotice -> {
                    Message(stringResource(R.string.voice_online_notice))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = quick.voice.onNoticeContinue, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_continue))
                        }
                        TextButton(onClick = quick.voice.onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_type_instead))
                        }
                    }
                }
                VoiceUi.Rationale -> {
                    Message(stringResource(R.string.voice_rationale))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = quick.voice.onRationaleContinue, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_continue))
                        }
                        CloseButton(quick)
                    }
                }
                VoiceUi.Denied -> {
                    Message(stringResource(R.string.voice_denied))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = quick.voice.onMic, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_try_again))
                        }
                        CloseButton(quick)
                    }
                }
                VoiceUi.DeniedForever -> {
                    Message(stringResource(R.string.voice_denied_forever))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = quick.voice.onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_open_settings))
                        }
                        CloseButton(quick)
                    }
                }
                VoiceUi.Error -> {
                    Message(stringResource(R.string.voice_error))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = quick.voice.onMic, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_try_again))
                        }
                        CloseButton(quick)
                    }
                }
                VoiceUi.Idle, VoiceUi.Requesting -> Unit
            }
        }
    }
}

@Composable
private fun ListeningBody(
    voice: VoiceUi.Listening,
    quick: AddQuickActions,
) {
    val levelDesc = stringResource(R.string.voice_level_desc)
    Text(
        stringResource(R.string.voice_listening),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    LinearProgressIndicator(
        progress = { voice.level / PERCENT_MAX },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = levelDesc },
    )
    Button(onClick = quick.voice.onStop, modifier = Modifier.heightIn(min = 48.dp).testTag("voice_stop")) {
        Text(stringResource(R.string.voice_stop))
    }
}

private const val PERCENT_MAX = 100f

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun CloseButton(quick: AddQuickActions) {
    TextButton(onClick = quick.voice.onClose, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.voice_close))
    }
}

/** Expense / Income switch (default Expense) as two chips with 48dp targets (REQ-EXP-052). */
@Composable
internal fun TypeSwitch(
    income: Boolean,
    onType: (Boolean) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.voice_type_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !income,
                onClick = { onType(false) },
                label = { Text(stringResource(R.string.voice_type_expense)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("type_expense"),
            )
            FilterChip(
                selected = income,
                onClick = { onType(true) },
                label = { Text(stringResource(R.string.voice_type_income)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("type_income"),
            )
        }
    }
}

/** The currency chips: EGP by default, then USD, EUR, SAR, AED, GBP, KWD, QAR (REQ-EXP-052). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CurrencyChips(
    selected: String,
    onCurrency: (String) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.voice_currency_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ADD_CURRENCIES.forEach { code ->
                FilterChip(
                    selected = selected == code,
                    onClick = { onCurrency(code) },
                    label = { Text(code) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("currency_$code"),
                )
            }
        }
    }
}
