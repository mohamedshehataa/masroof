package eg.masroof.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.components.SectionCard
import eg.masroof.ui.viewmodel.MembersError
import eg.masroof.ui.viewmodel.MembersUiState
import eg.masroof.ui.viewmodel.MembersViewModel

@Composable
fun MembersRoute(viewModel: MembersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.members_share_title)
    MembersSection(
        state,
        MembersActions(
            onName = viewModel::onName,
            onEmail = viewModel::onEmail,
            onCreate = viewModel::create,
            onDone = viewModel::dismissCreated,
            onRevoke = viewModel::revoke,
            onRetry = viewModel::refresh,
            onShare = { link ->
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, shareTitle))
            },
        ),
    )
}

class MembersActions(
    val onName: (String) -> Unit,
    val onEmail: (String) -> Unit,
    val onCreate: () -> Unit,
    val onDone: () -> Unit,
    val onRevoke: (String) -> Unit,
    val onRetry: () -> Unit,
    val onShare: (String) -> Unit,
)

@Composable
fun MembersSection(
    state: MembersUiState,
    actions: MembersActions,
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.members_title), modifier) {
        when {
            state.loading && state.members.isEmpty() -> Text(stringResource(R.string.loading))
            state.loadFailed ->
                Column {
                    Text(stringResource(R.string.members_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.retry))
                    }
                }
            else -> {
                state.members.forEach { m ->
                    Text(
                        stringResource(
                            if (m.isAdmin) R.string.members_row_admin else R.string.members_row_member,
                            m.displayName,
                        ),
                    )
                }
                if (state.isAdmin) AdminPart(state, actions)
            }
        }
    }
}

@Composable
private fun AdminPart(
    state: MembersUiState,
    actions: MembersActions,
) {
    Text(stringResource(R.string.members_pending), style = MaterialTheme.typography.titleSmall)
    if (state.invites.isEmpty()) Text(stringResource(R.string.members_no_pending))
    state.invites.forEach { invite ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(invite.displayName, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onRevoke(invite.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.members_revoke))
            }
        }
    }
    val created = state.created
    val link = state.link
    if (created != null && link != null) {
        Text(stringResource(R.string.members_created, created.displayName), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.members_shown_once))
        Button(onClick = { actions.onShare(link) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.members_share))
        }
        OutlinedButton(onClick = actions.onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.members_done))
        }
    } else {
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.members_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = actions.onEmail,
            label = { Text(stringResource(R.string.members_email_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Text(
                stringResource(
                    when (it) {
                        MembersError.CONFLICT -> R.string.members_error_conflict
                        MembersError.INVALID -> R.string.members_error_invalid
                        MembersError.OFFLINE -> R.string.members_error_offline
                        MembersError.OTHER -> R.string.members_error_other
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = actions.onCreate,
            enabled = !state.creating && state.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.members_invite))
        }
    }
}
