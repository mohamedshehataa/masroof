package eg.masroof.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.auth.SignedOutNotice
import eg.masroof.ui.viewmodel.LoginError
import eg.masroof.ui.viewmodel.LoginUiState
import eg.masroof.ui.viewmodel.LoginViewModel

@Composable
fun LoginRoute(
    notice: SignedOutNotice?,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(
        modifier = modifier,
        state = state,
        notice = notice,
        onServerUrl = viewModel::onServerUrl,
        onEmail = viewModel::onEmail,
        onPassword = viewModel::onPassword,
        onToggleShow = viewModel::toggleShowPassword,
        onSubmit = viewModel::submit,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    notice: SignedOutNotice?,
    onServerUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onToggleShow: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        notice?.let {
            val text =
                when (it) {
                    SignedOutNotice.SESSION_EXPIRED -> R.string.login_notice_expired
                    SignedOutNotice.KEYSTORE_RESET -> R.string.login_notice_keystore
                }
            Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrl,
            label = { Text(stringResource(R.string.settings_server_url)) },
            isError = state.urlInvalid,
            supportingText = { if (state.urlInvalid) Text(stringResource(R.string.settings_server_url_error)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            label = { Text(stringResource(R.string.login_email)) },
            isError = state.emailMissing,
            supportingText = { if (state.emailMissing) Text(stringResource(R.string.login_email_required)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.login_password)) },
            isError = state.passwordMissing,
            supportingText = { if (state.passwordMissing) Text(stringResource(R.string.login_password_required)) },
            visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            singleLine = true,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onToggleShow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (state.showPassword) R.string.login_hide_password else R.string.login_show_password))
        }
        state.error?.let { error ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(errorText(error, state.retryMinutes), color = MaterialTheme.colorScheme.error)
                if (error == LoginError.NETWORK || error == LoginError.SERVER) {
                    TextButton(onClick = onSubmit, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
        Button(onClick = onSubmit, enabled = !state.loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.login_signing_in))
                }
            } else {
                Text(stringResource(R.string.login_sign_in))
            }
        }
    }
}

@Composable
private fun errorText(
    error: LoginError,
    minutes: Int?,
): String =
    when (error) {
        LoginError.INVALID_CREDENTIALS -> stringResource(R.string.login_error_credentials)
        LoginError.RATE_LIMITED ->
            if (minutes != null) {
                stringResource(R.string.login_error_rate_limited_minutes, minutes)
            } else {
                stringResource(R.string.login_error_rate_limited)
            }
        LoginError.NETWORK -> stringResource(R.string.login_error_network)
        LoginError.SERVER -> stringResource(R.string.login_error_server)
    }
