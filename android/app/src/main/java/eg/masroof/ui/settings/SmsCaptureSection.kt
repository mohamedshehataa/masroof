package eg.masroof.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    var permitted by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    // coming back from the system settings page: the permission may have been given or taken away there
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = granted() }
    // the quiet "Recorded ... Undo" notification needs its own permission on Android 13+: asked right when capture is turned on
    val askNotifications = rememberNotificationRequester(viewModel)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            permitted = ok
            denied = !ok
            viewModel.refresh()
            if (ok) askNotifications()
        }
    val wanted by viewModel.smsCaptureEnabled.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val reviewCount by viewModel.reviewCount.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sms_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sms_rationale), style = MaterialTheme.typography.bodySmall)
        SmsCaptureSwitch(
            wanted = wanted,
            permitted = permitted,
            denied = denied,
            onToggle = { on ->
                viewModel.setSmsCapture(on)
                if (on && !permitted) launcher.launch(Manifest.permission.RECEIVE_SMS)
            },
            onOpenSettings = {
                val uri = Uri.fromParts("package", context.packageName, null)
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
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
        CapturedAlertsRow(viewModel)
        val learned by viewModel.learnedCount.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.learned_formats, learned), modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::clearLearned, enabled = learned > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.learned_clear))
            }
        }
        Text(stringResource(R.string.learned_explainer), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.review_open, reviewCount))
        }
    }
}

/**
 * The SMS capture switch (REQ-AND-135). It is on only when the user wants capture AND the system permission is granted. A
 * switch that is wanted but whose permission was taken away in the system settings shows off with "Permission needed" and a
 * button to the app's system settings page.
 */
@Composable
internal fun SmsCaptureSwitch(
    wanted: Boolean,
    permitted: Boolean,
    denied: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val on = wanted && permitted
    val description = stringResource(R.string.sms_switch_description)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (on) R.string.sms_on else R.string.sms_off),
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = on, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = description })
        }
        if (!permitted) {
            Text(stringResource(R.string.sms_permission_needed), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sms_permission_needed_body), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sms_open_settings))
            }
        }
        if (denied) Text(stringResource(R.string.sms_denied), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun NotificationCaptureSection(viewModel: SmsCaptureViewModel) {
    val context = LocalContext.current
    val askNotifications = rememberNotificationRequester(viewModel)
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
                    if (!viewModel.notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications()
                    } else if (!accessGranted) {
                        openAccessSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ntf_enable)) }
        }
    }
}

/** The switch for the quiet "Recorded ... Undo" notification; turning it on asks for the notification permission (Android 13+). */
@Composable
private fun CapturedAlertsRow(viewModel: SmsCaptureViewModel) {
    val on by viewModel.capturedAlertsEnabled.collectAsStateWithLifecycle()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.setCapturedAlerts(granted)
        }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.captured_alerts_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.captured_alerts_body), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = on,
            onCheckedChange = { wanted ->
                if (wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setCapturedAlerts(wanted)
                }
            },
        )
    }
}

/** Asks for the notification permission when the system needs it (Android 13+); the screen re-reads the real state afterwards. */
@Composable
private fun rememberNotificationRequester(viewModel: SmsCaptureViewModel): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onNotificationPermission() }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !viewModel.notificationsGranted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
