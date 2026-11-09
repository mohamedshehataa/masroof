package eg.masroof.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.CountFormat
import eg.masroof.ui.AppLanguage
import eg.masroof.ui.theme.LocalMoneyStyle

/** What the guide shows for the current step; everything the system decides (permissions) comes in from the route. */
internal data class OnboardingUi(
    val step: Int,
    /** The language the UI shows right now (`ar` or `en`). */
    val language: String,
    /** SMS capture is on: the permission is granted AND the app's own capture switch is on (the same rule as Settings). */
    val smsGranted: Boolean = false,
    val smsDenied: Boolean = false,
    /** The system will not ask again ("Don't ask again"): the main button then opens the app's system settings. */
    val smsPermanentlyDenied: Boolean = false,
    val readSmsGranted: Boolean = false,
    val readSmsDenied: Boolean = false,
    val readSmsPermanentlyDenied: Boolean = false,
    val rangeDays: Int = 90,
    /** null until the user has been to the system screen; then whether Masroof is enabled there. */
    val notificationAccess: Boolean? = null,
    val nameError: Boolean = false,
)

@Suppress("LongParameterList") // one callback per user action
internal class OnboardingActions(
    val onLanguage: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onRequestSms: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onRange: (Int) -> Unit = {},
    val onStartPastSms: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onNameChange: () -> Unit = {},
    val onAddMember: (String) -> Unit = {},
    val onFinish: () -> Unit = {},
)

private val RANGES = listOf(30, 90, 180)

/** The guide as a function of its state (so it is testable without Hilt or real permissions). */
@Composable
internal fun OnboardingContent(
    ui: OnboardingUi,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val digits = LocalMoneyStyle.current.digits
    val last = ui.step == OnboardingSteps.DONE
    var name by rememberSaveable { mutableStateOf("") }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.onb_step_of,
                        CountFormat.format(ui.step + 1, digits),
                        CountFormat.format(OnboardingSteps.COUNT, digits),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!last) {
                    TextButton(onClick = actions.onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onb_skip))
                    }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (ui.step) {
                    OnboardingSteps.LANGUAGE -> LanguageStep(ui.language, actions)
                    OnboardingSteps.STAYS_ON_PHONE -> TextStep("🔒", R.string.onb_phone_title, R.string.onb_phone_body)
                    OnboardingSteps.SMS_CAPTURE -> SmsStep(ui, actions)
                    OnboardingSteps.PAST_SMS -> PastSmsStep(ui, actions)
                    OnboardingSteps.NOTIFICATIONS -> NotificationsStep(ui, actions)
                    OnboardingSteps.MEMBER ->
                        MemberStep(ui, name) {
                            name = it
                            actions.onNameChange()
                        }
                    else -> TextStep("✅", R.string.onb_done_title, R.string.onb_done_body)
                }
            }
            Button(
                onClick = {
                    when (ui.step) {
                        OnboardingSteps.MEMBER -> actions.onAddMember(name)
                        OnboardingSteps.DONE -> actions.onFinish()
                        else -> actions.onNext()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (last) R.string.onb_open_wallet else R.string.onb_continue))
            }
        }
    }
}

/** The big emoji of a step: decoration only, so TalkBack skips it. */
@Composable
private fun Illustration(emoji: String) {
    Text(emoji, style = MaterialTheme.typography.displayMedium, modifier = Modifier.clearAndSetSemantics {})
}

@Composable
private fun StepTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun TextStep(
    emoji: String,
    title: Int,
    body: Int,
) {
    Illustration(emoji)
    StepTitle(title)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun LanguageStep(
    language: String,
    actions: OnboardingActions,
) {
    Illustration("🌍")
    StepTitle(R.string.onb_lang_title)
    Text(stringResource(R.string.onb_lang_body), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.selectableGroup()) {
        listOf(AppLanguage.ARABIC to R.string.onb_lang_arabic, AppLanguage.ENGLISH to R.string.onb_lang_english).forEach { (code, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = language == code, role = Role.RadioButton, onClick = { actions.onLanguage(code) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = language == code, onClick = null)
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun SmsStep(
    ui: OnboardingUi,
    actions: OnboardingActions,
) {
    Illustration("💬")
    StepTitle(R.string.onb_sms_title)
    Text(stringResource(R.string.onb_sms_body), style = MaterialTheme.typography.bodyLarge)
    if (ui.smsGranted) {
        Text(stringResource(R.string.onb_sms_on), color = MaterialTheme.colorScheme.primary)
    } else {
        val permanent = ui.smsPermanentlyDenied
        Button(
            onClick = if (permanent) actions.onOpenAppSettings else actions.onRequestSms,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (permanent) R.string.onb_open_app_settings else R.string.onb_sms_turn_on))
        }
    }
    if (ui.smsDenied && !ui.smsGranted) {
        Text(
            stringResource(R.string.onb_sms_denied),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun PastSmsStep(
    ui: OnboardingUi,
    actions: OnboardingActions,
) {
    Illustration("📥")
    StepTitle(R.string.onb_past_title)
    Text(stringResource(R.string.onb_past_body), style = MaterialTheme.typography.bodyLarge)
    val labels = mapOf(30 to R.string.backfill_range_30, 90 to R.string.backfill_range_90, 180 to R.string.backfill_range_180)
    Column(Modifier.selectableGroup()) {
        RANGES.forEach { days ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = ui.rangeDays == days, role = Role.RadioButton, onClick = { actions.onRange(days) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.rangeDays == days, onClick = null)
                Text(stringResource(labels.getValue(days)), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    val permanent = ui.readSmsPermanentlyDenied && !ui.readSmsGranted
    Button(
        onClick = if (permanent) actions.onOpenAppSettings else actions.onStartPastSms,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(
                when {
                    permanent -> R.string.onb_open_app_settings
                    ui.readSmsGranted -> R.string.onb_past_start
                    else -> R.string.onb_past_allow_start
                },
            ),
        )
    }
    if (ui.readSmsDenied && !ui.readSmsGranted) {
        Text(
            stringResource(R.string.onb_past_denied),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun NotificationsStep(
    ui: OnboardingUi,
    actions: OnboardingActions,
) {
    Illustration("🔔")
    StepTitle(R.string.onb_ntf_title)
    Text(stringResource(R.string.onb_ntf_body), style = MaterialTheme.typography.bodyLarge)
    if (ui.notificationAccess != true) {
        Button(onClick = actions.onOpenNotificationAccess, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.onb_ntf_open))
        }
    }
    when (ui.notificationAccess) {
        true -> Text(stringResource(R.string.onb_ntf_enabled), color = MaterialTheme.colorScheme.primary)
        false ->
            Text(
                stringResource(R.string.onb_ntf_not_enabled),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        null -> Unit
    }
}

@Composable
private fun MemberStep(
    ui: OnboardingUi,
    name: String,
    onName: (String) -> Unit,
) {
    Illustration("👥")
    StepTitle(R.string.onb_member_title)
    Text(stringResource(R.string.onb_member_body), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.onb_member_label)) },
        isError = ui.nameError,
        supportingText = {
            if (ui.nameError) {
                // the limit follows the app's digit setting, like every other number on screen
                val limit = CountFormat.format(MAX_MEMBER_NAME, LocalMoneyStyle.current.digits)
                Text(stringResource(R.string.onb_member_error, limit), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.notificationAccessOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** True once the system will no longer show its dialog for [permission] ("Don't ask again"); read only after a denial we asked for. */
private fun Context.permanentlyDenied(permission: String): Boolean =
    findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

/**
 * The permission plumbing around [OnboardingContent]. A permission is asked only after the step's own text explained it, and a denial
 * never blocks: Continue and Skip always work. System Back goes to the previous step (and closes a guide that was reopened).
 */
@Composable
internal fun OnboardingRoute(
    progress: OnboardingProgress,
    viewModel: OnboardingViewModel,
    onSetLanguage: (String) -> Unit,
) {
    val step = progress.step
    val context = LocalContext.current
    var smsPermitted by remember { mutableStateOf(context.granted(Manifest.permission.RECEIVE_SMS)) }
    var smsWanted by remember { mutableStateOf(viewModel.smsCaptureWanted()) }
    var smsDenied by rememberSaveable { mutableStateOf(false) }
    var smsPermanent by rememberSaveable { mutableStateOf(false) }
    var readGranted by remember { mutableStateOf(context.granted(Manifest.permission.READ_SMS)) }
    var readDenied by rememberSaveable { mutableStateOf(false) }
    var readPermanent by rememberSaveable { mutableStateOf(false) }
    var visitedAccess by rememberSaveable { mutableStateOf(false) }
    var access by remember { mutableStateOf(context.notificationAccessOn()) }
    // back from the system settings pages: any of these may have changed there
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        smsPermitted = context.granted(Manifest.permission.RECEIVE_SMS)
        readGranted = context.granted(Manifest.permission.READ_SMS)
        access = context.notificationAccessOn()
        smsWanted = viewModel.smsCaptureWanted()
    }
    // bank-app capture goes on only once the system really lets Masroof read notifications
    LaunchedEffect(step, access) {
        if (step == OnboardingSteps.NOTIFICATIONS && access) viewModel.notificationAccessGranted()
    }
    BackHandler(enabled = step > 0 || progress.reopened, onBack = viewModel::back)
    val smsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            smsPermitted = ok
            smsDenied = !ok
            smsPermanent = !ok && context.permanentlyDenied(Manifest.permission.RECEIVE_SMS)
            if (ok) {
                viewModel.smsCaptureGranted()
                smsWanted = true
            }
        }
    val readLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            readGranted = ok
            readDenied = !ok
            readPermanent = !ok && context.permanentlyDenied(Manifest.permission.READ_SMS)
            // granted: the backfill starts in the background and the guide moves on; denied: nothing starts, the step says why
            if (ok) {
                viewModel.startBackfill()
                viewModel.next()
            }
        }
    val rangeDays by viewModel.rangeDays.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    val language = AppLanguage.current(context).let { if (it == AppLanguage.ARABIC) it else AppLanguage.ENGLISH }
    OnboardingContent(
        ui =
            OnboardingUi(
                step = step,
                language = language,
                smsGranted = smsPermitted && smsWanted,
                smsDenied = smsDenied,
                smsPermanentlyDenied = smsPermanent,
                readSmsGranted = readGranted,
                readSmsDenied = readDenied,
                readSmsPermanentlyDenied = readPermanent,
                rangeDays = rangeDays,
                notificationAccess = if (visitedAccess || access) access else null,
                nameError = nameError,
            ),
        actions =
            OnboardingActions(
                onLanguage = onSetLanguage,
                onNext = viewModel::next,
                onRequestSms = {
                    smsDenied = false
                    if (smsPermitted) {
                        // the permission is there but the app's own switch is off: just turn it on
                        viewModel.smsCaptureGranted()
                        smsWanted = true
                    } else {
                        smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    }
                },
                onOpenAppSettings = { openAppSettings(context) },
                onRange = viewModel::chooseRange,
                onStartPastSms = {
                    readDenied = false
                    if (readGranted) {
                        viewModel.startBackfill()
                        viewModel.next()
                    } else {
                        readLauncher.launch(Manifest.permission.READ_SMS)
                    }
                },
                onOpenNotificationAccess = {
                    visitedAccess = true
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onNameChange = viewModel::nameChanged,
                onAddMember = viewModel::addMember,
                onFinish = viewModel::finish,
            ),
    )
}

/**
 * The first-run guide takes the whole screen while it runs; finished, it hands over to the app (which starts on Wallet). A phone that
 * already had data gets the "Finish setup" card on Wallet instead and is never forced into the guide.
 */
@Composable
internal fun SetupGate(
    progress: OnboardingProgress,
    guide: @Composable () -> Unit,
    app: @Composable (showSetupCard: Boolean) -> Unit,
) {
    when (progress.mode) {
        SetupMode.UNDECIDED -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        SetupMode.GUIDE -> guide()
        SetupMode.CARD -> app(true)
        SetupMode.DONE -> app(false)
    }
}

/** The dismissible "Finish setup" card on Wallet for a phone that already had data when the guide arrived. */
@Composable
fun FinishSetupCard(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onb_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.onb_card_body), style = MaterialTheme.typography.bodyMedium)
            // stacked, so at a large font on a narrow screen neither button is squeezed
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onb_card_open))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onb_card_dismiss))
            }
        }
    }
}

/** Settings > "Setup guide": opens the guide again from its first step. */
@Composable
fun SetupGuideSection(onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onb_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.onb_settings_body), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.onb_settings_open))
        }
    }
}

/** The app's own page in the system settings (where a permission that was denied for good can be given). */
internal fun openAppSettings(context: Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
