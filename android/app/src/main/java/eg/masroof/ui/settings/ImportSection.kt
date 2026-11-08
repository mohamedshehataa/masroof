package eg.masroof.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.DateFormat
import eg.masroof.core.DigitStyle
import eg.masroof.data.auth.SignedOutNotice
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ImportStage
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.login.LoginRoute
import eg.masroof.ui.theme.LocalMoneyStyle
import java.util.Locale

private const val TARGET_DP = 48

/** A count in the chosen digits with thousands separators: `1,240` or `١٬٢٤٠`. */
internal fun importCount(
    n: Int,
    digits: DigitStyle,
): String {
    val grouped = String.format(Locale.ENGLISH, "%,d", n)
    return if (digits == DigitStyle.ARABIC_INDIC) DateFormat.digitsAs(grouped, digits).replace(',', '٬') else grouped
}

/** Settings -> "Import from server" (STORY-182). Shown only while a server session exists; never reads the network by itself. */
@Composable
fun ImportSection(
    onToggleLanguage: () -> Unit = {},
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImportContent(
        state = state,
        onImport = viewModel::start,
        onCancel = viewModel::cancel,
        onDismiss = viewModel::dismissResult,
        onSignIn = viewModel::openSignIn,
    )
    if (state.signInOpen) {
        // The existing login screen, reached only from this card; closing it changes nothing on the phone.
        Dialog(onDismissRequest = viewModel::closeSignIn, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    TextButton(onClick = viewModel::closeSignIn, modifier = Modifier.heightIn(min = TARGET_DP.dp).padding(8.dp)) {
                        Text(stringResource(R.string.import_cancel))
                    }
                    LoginRoute(notice = SignedOutNotice.SESSION_EXPIRED, onToggleLanguage = onToggleLanguage)
                }
            }
        }
    }
}

@Composable
fun ImportContent(
    state: ImportUiState,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
) {
    if (!state.visible) return
    val digits = LocalMoneyStyle.current.digits
    SectionCard(stringResource(R.string.import_title)) {
        val phase = state.phase
        if (phase !is ImportPhase.Done) {
            Text(
                stringResource(R.string.import_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (phase) {
            ImportPhase.Idle -> Unit
            is ImportPhase.Running -> Progress(phase, digits)
            is ImportPhase.Done -> Result(phase, digits, onDismiss)
            is ImportPhase.Failed -> Failure(phase.kind)
        }
        Actions(state, onImport, onCancel, onSignIn)
        if (state.offline && !state.running && state.phase !is ImportPhase.Done) {
            Text(
                stringResource(R.string.import_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Actions(
    state: ImportUiState,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
) {
    val needsSignIn = (state.phase as? ImportPhase.Failed)?.kind == ImportFailureKind.SESSION
    when {
        state.running ->
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) {
                Text(stringResource(R.string.import_cancel))
            }
        needsSignIn ->
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) {
                Text(stringResource(R.string.import_sign_in))
            }
        state.phase is ImportPhase.Done -> Unit
        else ->
            Button(onClick = onImport, enabled = state.canImport, modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp)) {
                Text(stringResource(R.string.import_action))
            }
    }
}

@Composable
private fun Progress(
    p: ImportPhase.Running,
    digits: DigitStyle,
) {
    val text =
        if (p.stage == ImportStage.SAVING && p.total != null) {
            stringResource(R.string.import_saving, importCount(p.done, digits), importCount(p.total, digits))
        } else {
            stringResource(R.string.import_downloading, importCount(p.done, digits))
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Result(
    d: ImportPhase.Done,
    digits: DigitStyle,
    onDismiss: () -> Unit,
) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(R.plurals.import_done, d.imported, importCount(d.imported, digits)),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        )
        if (d.skipped > 0) {
            Text(
                pluralStringResource(R.plurals.import_skipped, d.skipped, importCount(d.skipped, digits)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
        Text(stringResource(R.string.import_dismiss))
    }
}

@Composable
private fun Failure(kind: ImportFailureKind) {
    val text =
        when (kind) {
            ImportFailureKind.NETWORK -> R.string.import_error_network
            ImportFailureKind.SERVER -> R.string.import_error_server
            ImportFailureKind.SESSION -> R.string.import_sign_in_again
            ImportFailureKind.UNEXPECTED_DATA -> R.string.import_error_data
            ImportFailureKind.STORAGE -> R.string.import_error_storage
        }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
