package eg.masroof.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.viewmodel.SmsCaptureViewModel

/**
 * Rationale, runtime permission request and one-tap approval of suggested senders (REQ-SMS-041/042); the notification
 * toggle with its explanation (REQ-NTF-001); and the entry to the local review list (REQ-SMS-014).
 */
@Composable
fun SmsCaptureSection(
    onOpenReview: () -> Unit = {},
    viewModel: SmsCaptureViewModel = hiltViewModel(),
) {
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
    val reviewCount by viewModel.reviewCount.collectAsStateWithLifecycle()
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
        NotificationCaptureSection(viewModel)
        OutlinedButton(onClick = onOpenReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.review_open, reviewCount))
        }
    }
}

@Composable
private fun NotificationCaptureSection(viewModel: SmsCaptureViewModel) {
    val context = LocalContext.current
    val on by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val packages by viewModel.packages.collectAsStateWithLifecycle()
    var newPackage by remember { mutableStateOf("") }
    val accessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val openAccessSettings = {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ntf_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ntf_rationale), style = MaterialTheme.typography.bodySmall)
        if (on) {
            Text(stringResource(R.string.ntf_on), color = MaterialTheme.colorScheme.primary)
            if (!accessGranted) {
                Text(stringResource(R.string.ntf_needs_access), style = MaterialTheme.typography.bodySmall)
                Button(onClick = openAccessSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ntf_open_settings))
                }
            }
            Text(stringResource(R.string.ntf_packages_title), style = MaterialTheme.typography.titleSmall)
            packages.forEach { pkg ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pkg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removePackage(pkg) }) { Text(stringResource(R.string.ntf_remove_package)) }
                }
            }
            OutlinedTextField(
                value = newPackage,
                onValueChange = { newPackage = it },
                label = { Text(stringResource(R.string.ntf_package_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    viewModel.addPackage(newPackage)
                    newPackage = ""
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ntf_add_package)) }
            TextButton(onClick = { viewModel.setNotifications(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ntf_disable))
            }
        } else {
            Button(
                onClick = {
                    viewModel.setNotifications(true)
                    if (!accessGranted) openAccessSettings()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ntf_enable)) }
        }
    }
}
