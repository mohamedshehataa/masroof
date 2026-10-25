package eg.masroof.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.viewmodel.SmsCaptureViewModel

/** Rationale, runtime permission request and one-tap approval of suggested senders (REQ-SMS-041/042). */
@Composable
fun SmsCaptureSection(viewModel: SmsCaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current

    fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    var enabled by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            enabled = ok
            denied = !ok
            viewModel.refresh()
        }
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sms_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sms_rationale), style = MaterialTheme.typography.bodySmall)
        if (enabled) {
            Text(stringResource(R.string.sms_on), color = MaterialTheme.colorScheme.primary)
        } else {
            Button(
                onClick = { launcher.launch(Manifest.permission.RECEIVE_SMS) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sms_enable)) }
        }
        if (denied) Text(stringResource(R.string.sms_denied), color = MaterialTheme.colorScheme.error)
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.sms_suggested_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sms_suggested_body), style = MaterialTheme.typography.bodySmall)
            suggestions.forEach { sender ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sender)
                    TextButton(onClick = { viewModel.add(sender) }) { Text(stringResource(R.string.sms_add_sender)) }
                }
            }
        }
    }
}
