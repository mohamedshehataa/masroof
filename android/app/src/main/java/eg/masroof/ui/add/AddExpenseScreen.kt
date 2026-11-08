package eg.masroof.ui.add

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.ui.AppLanguage
import eg.masroof.ui.components.SecureWindow
import eg.masroof.ui.edit.PDF_MIME
import eg.masroof.ui.edit.Thumbnail
import eg.masroof.ui.receipts.ReceiptOnlySection
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.AddExpenseViewModel
import eg.masroof.ui.viewmodel.AddField
import eg.masroof.ui.viewmodel.AddUiState
import eg.masroof.ui.viewmodel.MicPermission
import eg.masroof.ui.viewmodel.PendingReceiptViewModel
import eg.masroof.ui.viewmodel.VoiceUi

@Composable
fun AddExpenseRoute(
    onDone: () -> Unit,
    onOpenReceipts: () -> Unit = {},
    viewModel: AddExpenseViewModel = hiltViewModel(),
    pending: PendingReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val waiting by pending.receipt.collectAsStateWithLifecycle()
    SecureWindow()
    // A receipt the form was opened from is attached in the same write as the entry; a cancel leaves it waiting (REQ-RCPT-023).
    LaunchedEffect(pending.receiptId) { viewModel.setReceipt(pending.receiptId) }
    LaunchedEffect(state.form.saved) { if (state.form.saved) onDone() }
    val quick = rememberQuickActions(viewModel, state.form.voice)
    // Back while the listening panel is up closes it first; a second Back leaves the form and nothing was saved.
    BackHandler(enabled = state.form.voice != VoiceUi.Idle) { quick.voice.onClose() }
    AddExpenseContent(
        state = state,
        onAmount = viewModel::onAmount,
        onCategory = viewModel::onCategory,
        onAccount = viewModel::onAccount,
        onDate = viewModel::onDate,
        onNotes = viewModel::onNotes,
        onSave = viewModel::save,
        onPaidBy = viewModel::onPaidBy,
        quick = quick,
        extra = {
            val receipt = waiting
            if (receipt != null) {
                Text(stringResource(R.string.rcpt_waiting_title), style = MaterialTheme.typography.titleSmall)
                Thumbnail(
                    pending.fileOf(receipt),
                    receipt.mimeType == PDF_MIME,
                    stringResource(R.string.rcpt_waiting_desc),
                    onClick = {},
                )
            } else if (pending.receiptId == null) {
                ReceiptOnlySection(onStored = onOpenReceipts)
            }
        },
    )
}

private const val NOTICE_PREFS = "masroof_ui"
private const val NOTICE_KEY = "voice_online_notice_seen"

private fun noticeSeen(context: Context) = context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE).getBoolean(NOTICE_KEY, false)

private fun markNoticeSeen(context: Context) =
    context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE).edit {
        putBoolean(NOTICE_KEY, true)
    }

private fun Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * The mic flow's glue (REQ-AND-144): the view model keeps the state, this runs the recognizer and the permission dialog.
 * RECORD_AUDIO is asked only when the mic is tapped; the recognizer is dropped when the screen goes away (no audio is kept).
 */
@Composable
private fun rememberQuickActions(
    viewModel: AddExpenseViewModel,
    voiceState: VoiceUi,
): AddQuickActions {
    val context = LocalContext.current
    val input: VoiceInput = remember { AndroidVoiceInput(context) }
    val micAvailable = remember { input.isAvailable() }
    DisposableEffect(input) { onDispose { input.cancel() } }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val activity = context.findActivity()
            val canAskAgain =
                activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            viewModel.onPermissionResult(granted, canAskAgain)
        }
    val listening = voiceState is VoiceUi.Listening
    // Listening starts only from a tap on this screen instance: after a rotation or process restore the state is dropped.
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        if (listening && !armed.value) {
            viewModel.dismissVoice()
        } else if (listening) {
            val tag = if (AppLanguage.current(context) == AppLanguage.ARABIC) "ar-EG" else "en-US"
            input.start(tag) { event ->
                when (event) {
                    is VoiceEvent.Level -> viewModel.onVoiceLevel(event.percent)
                    is VoiceEvent.Text -> viewModel.onVoiceText(event.text)
                    VoiceEvent.NoSpeech, VoiceEvent.Failed -> viewModel.onVoiceError()
                }
            }
        }
    }
    // Leaving the screen (home button, another app, rotation) stops listening.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, input) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    input.cancel()
                    viewModel.dismissVoice()
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(voiceState == VoiceUi.Requesting) {
        if (voiceState == VoiceUi.Requesting) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    return AddQuickActions(
        onType = viewModel::onType,
        onCurrency = viewModel::onCurrency,
        onMerchant = viewModel::onMerchant,
        onQuickText = viewModel::onQuickText,
        onQuickDone = viewModel::applyQuickText,
        voice =
            AddVoiceActions(
                onMic = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val activity = context.findActivity()
                    val rationale =
                        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                    armed.value = true
                    val notice = !input.isOnDevice() && !noticeSeen(context)
                    viewModel.onMicTapped(
                        when {
                            granted -> MicPermission.GRANTED
                            rationale -> MicPermission.SHOW_RATIONALE
                            else -> MicPermission.ASK
                        },
                        needsOnlineNotice = notice,
                    )
                },
                micAvailable = micAvailable,
                onStop = {
                    input.stop()
                    viewModel.onStopTapped()
                },
                onClose = {
                    input.cancel()
                    viewModel.dismissVoice()
                },
                onRationaleContinue = viewModel::onRationaleContinue,
                onNoticeContinue = {
                    markNoticeSeen(context)
                    viewModel.onNoticeContinue()
                },
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    context.startActivity(intent)
                    viewModel.dismissVoice()
                },
            ),
    )
}

@Composable
fun AddExpenseContent(
    state: AddUiState,
    onAmount: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onAccount: (String?) -> Unit,
    onDate: (String) -> Unit,
    onNotes: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onPaidBy: (String?) -> Unit = {},
    /** The Say it parts (quick line, mic, Expense/Income, currency, merchant). Null = the plain form, as before STORY-200. */
    quick: AddQuickActions? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val arabic = LocalMoneyStyle.current.arabic
    val form = state.form
    // Save lives in a bar pinned below the scrolling form and above the keyboard (imePadding), so it never scrolls
    // away or hides behind the IME however many categories there are.
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AddFields(state, arabic, AddFieldActions(onAmount, onCategory, onAccount, onDate, onNotes, onPaidBy, quick))
            extra()
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSave,
                enabled = !form.saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
            ) { Text(stringResource(R.string.add_save)) }
        }
    }
}

private class AddFieldActions(
    val onAmount: (String) -> Unit,
    val onCategory: (String?) -> Unit,
    val onAccount: (String?) -> Unit,
    val onDate: (String) -> Unit,
    val onNotes: (String) -> Unit,
    val onPaidBy: (String?) -> Unit,
    val quick: AddQuickActions?,
)

@Composable
private fun ColumnScope.AddFields(
    state: AddUiState,
    arabic: Boolean,
    actions: AddFieldActions,
) {
    val form = state.form
    val onAmount = actions.onAmount
    val onCategory = actions.onCategory
    val onAccount = actions.onAccount
    val onDate = actions.onDate
    val onNotes = actions.onNotes
    run {
        Text(
            stringResource(R.string.add_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val quick = actions.quick
        if (quick != null) {
            QuickLine(form, quick)
            TypeSwitch(form.income, quick.onType)
            CurrencyChips(form.currency, quick.onCurrency)
        }
        val inEgp = form.currency == Money.EGP
        OutlinedTextField(
            value = form.amountText,
            onValueChange = onAmount,
            label = { Text(stringResource(if (inEgp) R.string.add_amount else R.string.voice_amount_label)) },
            prefix = { Text(MoneyFormat.symbol(form.currency, arabic)) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            isError = form.amountInvalid || form.amountTooLarge || form.amountMissing,
            supportingText = {
                if (form.amountInvalid) Text(stringResource(R.string.add_amount_error))
                if (form.amountTooLarge) Text(stringResource(R.string.add_amount_too_large))
                if (form.amountMissing) Text(stringResource(R.string.voice_say_amount))
                if (!inEgp) Text(stringResource(R.string.voice_not_in_totals))
            },
            colors = highlightColors(AddField.AMOUNT in form.highlight),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("amount"),
        )
        OutlinedTextField(
            value = form.dateText,
            onValueChange = onDate,
            label = { Text(stringResource(R.string.add_date)) },
            isError = form.dateInvalid,
            supportingText = { if (form.dateInvalid) Text(stringResource(R.string.add_date_error)) },
            colors = highlightColors(AddField.DATE in form.highlight),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (quick != null) {
            OutlinedTextField(
                value = form.merchant,
                onValueChange = quick.onMerchant,
                label = { Text(stringResource(R.string.voice_merchant)) },
                colors = highlightColors(AddField.MERCHANT in form.highlight),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("merchant"),
            )
        }
        // Income never counts as spending, so it has no category (REQ-EXP-052).
        if (!form.income) {
            OptionGroup(
                title = stringResource(R.string.add_category),
                options = state.categories.map { it.id to (if (arabic) it.nameAr else it.nameEn) },
                selected = form.categoryId,
                onSelect = onCategory,
                tinted = true,
            )
        }
        OptionGroup(
            title = stringResource(R.string.add_account),
            options = state.accounts.map { it.id to it.label },
            selected = form.accountId,
            onSelect = onAccount,
        )
        // Only with two or more members; one member means nothing to choose (REQ-EXP-047).
        if (state.members.size >= 2) {
            OptionGroup(
                title = stringResource(R.string.paid_by),
                options = state.members.map { it.id to it.displayName },
                selected = form.paidByMemberId,
                onSelect = actions.onPaidBy,
                noneLabel = stringResource(R.string.paid_by_not_set),
            )
        }
        OutlinedTextField(
            value = form.notes,
            onValueChange = onNotes,
            label = { Text(stringResource(R.string.add_note)) },
            colors = highlightColors(AddField.NOTES in form.highlight),
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.saveFailed) {
            Text(
                stringResource(R.string.add_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Compact wrapping chips (48dp targets) instead of a tall radio list. Names come from the locale-matched field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    tinted: Boolean = false,
    noneLabel: String = stringResource(R.string.add_none),
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        val all = listOf<Pair<String?, String>>(null to noneLabel) + options
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            all.forEach { (id, label) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    label = { Text(label) },
                    leadingIcon =
                        if (tinted && id != null) {
                            {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(CategoryPalette.colorFor(id)),
                                )
                            }
                        } else {
                            null
                        },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}
