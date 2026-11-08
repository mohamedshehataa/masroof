package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.DigitStyle
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.AppSettings
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.NewExpense
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.DataEraser
import eg.masroof.data.auth.EraseCounts
import eg.masroof.data.auth.PartialEraseException
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MemberEntity
import eg.masroof.data.db.seededCategoryId
import eg.masroof.data.normalizeServerUrl
import eg.masroof.ingest.quick.QuickEntryParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** Where a list stands. Expense lists read only Room, so they are always READY; Insights and Budgets still use the rest. */
enum class LoadStatus { LOADING, READY, OFFLINE, ERROR, SIGN_IN }

data class ExpenseRow(
    val id: String,
    val merchant: String?,
    val money: Money,
    val date: LocalDate,
    val pending: Boolean,
    val failed: Boolean,
    val category: CategoryEntity?,
    val notes: String?,
    val source: String? = null,
    val income: Boolean = false,
    val reviewPending: Boolean = false,
    val transfer: Boolean = false,
    /** Null for an entry made on this phone: it has no server copy, so it does not open the server-based edit screen. */
    val serverId: String? = null,
    /** Display name of the member who paid; null = not set or the member no longer exists (no chip). */
    val paidBy: String? = null,
    /** "Don't count in totals" (REQ-AN-175): listed with a marker, never part of spending. */
    val excluded: Boolean = false,
)

private const val STOP_TIMEOUT_MS = 5_000L
private const val MAX_NOTES = 500
private const val MAX_MERCHANT = QuickEntryParser.MAX_MERCHANT_LENGTH
private const val MAX_QUICK_LINE = 400
private const val HIGHLIGHT_MS = 2_000L

internal fun toRows(
    expenses: List<ExpenseEntity>,
    categories: List<CategoryEntity>,
    members: List<MemberEntity> = emptyList(),
): List<ExpenseRow> {
    val byId = categories.associateBy { it.id }
    val memberNames = members.associate { it.id to it.displayName }
    return expenses.map {
        ExpenseRow(
            id = it.id,
            merchant = it.merchant,
            money = Money(it.amountMinor, it.currency),
            date = CairoTime.dateOf(it.occurredAtMillis),
            // Nothing waits for an upload any more (ADR-0009): no "Waiting to sync", "Couldn't sync" or Retry on a row.
            pending = false,
            failed = false,
            category = it.categoryId?.let(byId::get),
            notes = it.notes,
            source = it.source,
            income = it.type == "INCOME",
            transfer =
                eg.masroof.data.answers.ExpenseTypes
                    .isTransfer(it.type),
            reviewPending = it.status == "PENDING_REVIEW",
            serverId = it.serverId,
            paidBy = it.paidByMemberId?.let(memberNames::get),
            excluded = it.excludedFromTotals,
        )
    }
}

data class TransactionsUiState(
    val rows: List<ExpenseRow> = emptyList(),
    val status: LoadStatus = LoadStatus.LOADING,
)

@HiltViewModel
class TransactionsViewModel
    @Inject
    constructor(
        repository: ExpenseRepository,
    ) : ViewModel() {
        val state: StateFlow<TransactionsUiState> =
            combine(
                repository.observeAll(),
                repository.observeLabelCategories(),
                repository.observeMembers(),
            ) { all, categories, members ->
                TransactionsUiState(toRows(all, categories, members), LoadStatus.READY)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransactionsUiState())
    }

data class AddFormState(
    val amountText: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val paidByMemberId: String? = null,
    val dateText: String = "",
    val notes: String = "",
    /** "Enter an amount": empty, zero, negative or not a number. */
    val amountInvalid: Boolean = false,
    /** "Amount is too large": above 1,000,000,000,000 minor units. */
    val amountTooLarge: Boolean = false,
    val dateInvalid: Boolean = false,
    /** The waiting receipt this form was opened from. */
    val receiptId: String? = null,
    /** The write is running: Save is disabled. */
    val saving: Boolean = false,
    /** The write threw: the sheet stays open with the input kept and "Couldn't save, try again". */
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
    /** The Expense / Income switch (REQ-EXP-052): true saves `type` INCOME. */
    val income: Boolean = false,
    val currency: String = Money.EGP,
    val merchant: String = "",
    /** The one-line "Type it: 150 fuel" field; parsed on Done. */
    val quickText: String = "",
    /** "Say or type an amount": the last line had no usable number and the amount is still empty. */
    val amountMissing: Boolean = false,
    /** Fields the last line filled, highlighted for two seconds. */
    val highlight: Set<AddField> = emptySet(),
    val voice: VoiceUi = VoiceUi.Idle,
)

private fun AddFormState.listeningOrWorking() = voice is VoiceUi.Listening || voice == VoiceUi.Processing

data class AddUiState(
    val form: AddFormState = AddFormState(),
    val categories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val members: List<MemberEntity> = emptyList(),
)

@HiltViewModel
class AddExpenseViewModel
    @Inject
    constructor(
        private val repository: ExpenseRepository,
        private val clock: java.time.Clock,
    ) : ViewModel() {
        private val form = MutableStateFlow(AddFormState(dateText = CairoTime.today(clock.instant()).toString()))

        val state: StateFlow<AddUiState> =
            combine(
                form,
                repository.observePickerCategories(),
                repository.observeAccounts(),
                repository.observeMembers(),
            ) { f, c, a, m -> AddUiState(f, c, a, m) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddUiState(form.value))

        private var highlightJob: Job? = null

        fun onAmount(text: String) =
            form.update { it.copy(amountText = text, amountInvalid = false, amountTooLarge = false, amountMissing = false) }

        /** Expense / Income switch. Income has no category (it never counts as spending). */
        fun onType(income: Boolean) = form.update { it.copy(income = income, categoryId = if (income) null else it.categoryId) }

        fun onCurrency(code: String) = form.update { if (code in ADD_CURRENCIES) it.copy(currency = code) else it }

        fun onMerchant(text: String) = form.update { it.copy(merchant = text.take(MAX_MERCHANT)) }

        // While the mic is listening the line is the recognizer's: typing then would be lost or overwritten.
        fun onQuickText(text: String) = form.update { if (it.listeningOrWorking()) it else it.copy(quickText = text.take(MAX_QUICK_LINE)) }

        /** Done on the quick-text field: the same parser as the mic. Only fills the form; nothing is saved. */
        fun applyQuickText() {
            val line = form.value.quickText
            if (line.isNotBlank() && !form.value.listeningOrWorking()) fill(line)
        }

        // ---- the mic flow (REQ-AND-144): the screen runs the recognizer and the permission dialog, this keeps the state ----

        /** The mic was tapped. Ignored while listening or working. */
        fun onMicTapped(
            permission: MicPermission,
            needsOnlineNotice: Boolean = false,
        ) {
            if (form.value.listeningOrWorking()) return
            if (needsOnlineNotice) {
                form.update { it.copy(voice = VoiceUi.OnlineNotice(permission)) }
                return
            }
            proceed(permission)
        }

        private fun proceed(permission: MicPermission) {
            val next =
                when (permission) {
                    MicPermission.GRANTED -> VoiceUi.Listening()
                    MicPermission.SHOW_RATIONALE -> VoiceUi.Rationale
                    MicPermission.ASK -> VoiceUi.Requesting
                }
            form.update { it.copy(voice = next) }
        }

        /** The on-device recognizer has no model for the language: the default one may be online, so the notice comes first. */
        fun onFallbackNotice() =
            form.update {
                if (it.listeningOrWorking()) it.copy(voice = VoiceUi.OnlineNotice(MicPermission.GRANTED)) else it
            }

        /** "Continue" on the one-time online notice: on to the permission step the tap would have taken. */
        fun onNoticeContinue() {
            val v = form.value.voice
            if (v is VoiceUi.OnlineNotice) proceed(v.then)
        }

        /** "Continue" under the reason text: now the system dialog. */
        fun onRationaleContinue() = form.update { if (it.voice == VoiceUi.Rationale) it.copy(voice = VoiceUi.Requesting) else it }

        /** The system dialog answered. [canAskAgain] is false after "don't ask again": only Settings can help then. */
        fun onPermissionResult(
            granted: Boolean,
            canAskAgain: Boolean,
        ) = form.update {
            when {
                it.voice != VoiceUi.Requesting -> it
                granted -> it.copy(voice = VoiceUi.Listening())
                canAskAgain -> it.copy(voice = VoiceUi.Denied)
                else -> it.copy(voice = VoiceUi.DeniedForever)
            }
        }

        fun onVoiceLevel(percent: Int) =
            form.update {
                if (it.voice is VoiceUi.Listening) {
                    it.copy(
                        voice = VoiceUi.Listening(percent),
                    )
                } else {
                    it
                }
            }

        /** Stop was tapped: the recognizer delivers what it heard, or an error. */
        fun onStopTapped() = form.update { if (it.voice is VoiceUi.Listening) it.copy(voice = VoiceUi.Processing) else it }

        /** The recognizer's final text: fills the quick-text line's fields like Done would. A blank text is "didn't catch that". */
        fun onVoiceText(text: String) {
            val voice = form.value.voice
            if (voice !is VoiceUi.Listening && voice != VoiceUi.Processing) return
            if (text.isBlank()) {
                onVoiceError()
                return
            }
            form.update { it.copy(voice = VoiceUi.Idle) }
            fill(text)
        }

        /** Silence, no match or a recognizer failure: nothing is filled. */
        fun onVoiceError() =
            form.update {
                if (it.voice is VoiceUi.Listening ||
                    it.voice == VoiceUi.Processing
                ) {
                    it.copy(voice = VoiceUi.Error)
                } else {
                    it
                }
            }

        /** Close, Back or leaving while the panel is up. */
        fun dismissVoice() = form.update { it.copy(voice = VoiceUi.Idle) }

        private fun fill(line: String) {
            val today = CairoTime.today(clock.instant())
            val draft = QuickEntryParser.parse(line, today)
            viewModelScope.launch {
                val rows = repository.observePickerCategories().first()
                // The seeded id for the reference key, only when that category is on this phone (never a hard-coded row).
                val categoryId = draft.categoryKey?.let(::seededCategoryId)?.takeIf { id -> rows.any { it.id == id } }
                form.update { QuickFill.apply(it, line, draft, categoryId, today) }
                highlightJob?.cancel()
                highlightJob =
                    launch {
                        delay(HIGHLIGHT_MS)
                        form.update { it.copy(highlight = emptySet()) }
                    }
            }
        }

        fun onCategory(id: String?) = form.update { it.copy(categoryId = id) }

        fun onAccount(id: String?) = form.update { it.copy(accountId = id) }

        fun onPaidBy(id: String?) = form.update { it.copy(paidByMemberId = id) }

        fun onDate(text: String) = form.update { it.copy(dateText = text, dateInvalid = false) }

        /** The form was opened from a waiting receipt: Save attaches it in the same write as the entry (REQ-RCPT-023). */
        fun setReceipt(id: String?) = form.update { it.copy(receiptId = id) }

        /** Prefill from a review item (REQ-SMS-014): amount and note only; the user still reviews and saves. */
        fun prefill(
            amountText: String,
            notes: String,
        ) = form.update { it.copy(amountText = amountText, notes = notes.take(MAX_NOTES)) }

        // The note is capped at 500 characters (ADR-0007); the form cannot exceed it.
        fun onNotes(text: String) = form.update { it.copy(notes = text.take(MAX_NOTES)) }

        /** Validates, then writes to Room in one transaction. Never touches the network. */
        fun save() {
            val f = form.value
            if (f.saving || f.saved) return // double tap: one entry only
            val amount = MoneyFormat.parseEntry(f.amountText)
            val date =
                try {
                    LocalDate.parse(f.dateText.trim())
                } catch (_: DateTimeParseException) {
                    null
                }
            if (amount !is MoneyFormat.EntryAmount.Valid || date == null) {
                form.update {
                    it.copy(
                        amountInvalid = amount == MoneyFormat.EntryAmount.Invalid,
                        amountTooLarge = amount == MoneyFormat.EntryAmount.TooLarge,
                        dateInvalid = date == null,
                        saveFailed = false,
                    )
                }
                return
            }
            form.update { it.copy(saving = true, saveFailed = false) }
            viewModelScope.launch {
                val result =
                    runCatching {
                        repository.add(
                            NewExpense(
                                amount = Money(amount.minor, f.currency),
                                date = date,
                                // Income never counts as spending, so it carries no category (REQ-EXP-052).
                                categoryId = if (f.income) null else f.categoryId,
                                accountId = f.accountId,
                                paidByMemberId = f.paidByMemberId,
                                receiptId = f.receiptId,
                                notes = f.notes.trim().ifEmpty { null },
                                type = if (f.income) TYPE_INCOME else TYPE_EXPENSE,
                                merchant = f.merchant.trim().ifEmpty { null },
                            ),
                        )
                    }
                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                // On failure the input is kept and nothing was written (the repository's transaction rolled back). Nothing is logged.
                form.update {
                    it.copy(saving = false, saved = result.isSuccess, saveFailed = result.isFailure)
                }
            }
        }
    }

/** The "Erase all data" flow in Settings. */
sealed interface EraseState {
    data object Idle : EraseState

    /** The dialog is open with what would be deleted. Nothing is deleted until [SettingsViewModel.confirmErase]. */
    data class Confirm(
        val counts: EraseCounts,
    ) : EraseState

    data object Erasing : EraseState

    /** The erase transaction rolled back: nothing was changed. */
    data object Failed : EraseState

    /** The data is erased but a clean-up step failed; it is finished at the next start. */
    data object Incomplete : EraseState
}

data class SettingsUiState(
    val serverUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val digitStyle: DigitStyle? = null,
    val urlInvalid: Boolean = false,
    val saved: Boolean = false,
    val loggingOut: Boolean = false,
    val erase: EraseState = EraseState.Idle,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val session: SessionManager,
        private val eraser: DataEraser,
    ) : ViewModel() {
        private val draft = MutableStateFlow<SettingsUiState?>(null)

        val state: StateFlow<SettingsUiState> =
            combine(settings.settings, draft) { stored, d ->
                d ?: SettingsUiState(stored.serverUrl, stored.digitStyle)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        private fun edit(block: (SettingsUiState) -> SettingsUiState) {
            draft.value = block(state.value).copy(saved = false)
        }

        fun onUrl(text: String) = edit { it.copy(serverUrl = text, urlInvalid = false) }

        fun onDigits(style: DigitStyle) = edit { it.copy(digitStyle = style) }

        /** Log out ends the session only. Entries, categories, receipts and the rest stay on the phone (REQ-AND-131). */
        fun logout() {
            if (state.value.loggingOut) return
            viewModelScope.launch {
                draft.value = state.value.copy(loggingOut = true)
                session.logout()
                draft.value = null
            }
        }

        /** "Erase all data": shows what would be deleted and waits for the second, explicit confirmation. */
        fun askErase() {
            viewModelScope.launch { draft.value = state.value.copy(erase = EraseState.Confirm(eraser.counts())) }
        }

        /** Cancel or Back in the dialog: nothing is deleted. */
        fun dismissErase() {
            draft.value = state.value.copy(erase = EraseState.Idle)
        }

        fun confirmErase() {
            if (state.value.erase !is EraseState.Confirm) return
            draft.value = state.value.copy(erase = EraseState.Erasing)
            viewModelScope.launch {
                val result = runCatching { eraser.erase() }
                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                val outcome =
                    when (result.exceptionOrNull()) {
                        null -> EraseState.Idle
                        is PartialEraseException -> EraseState.Incomplete
                        else -> EraseState.Failed
                    }
                draft.value = state.value.copy(erase = outcome)
            }
        }

        fun save() {
            val s = state.value
            // blank = no server (after the one-time import the URL is deleted): saving digits must still work
            val url = if (s.serverUrl.isBlank()) "" else normalizeServerUrl(s.serverUrl)
            if (url == null) {
                draft.value = s.copy(urlInvalid = true, saved = false)
                return
            }
            viewModelScope.launch {
                settings.update {
                    it.copy(
                        serverUrl = url,
                        digitStyle = s.digitStyle,
                    )
                }
                draft.value = s.copy(serverUrl = url, saved = true)
            }
        }
    }
