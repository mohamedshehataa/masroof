package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.DigitStyle
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.PeriodAnalytics
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
import eg.masroof.data.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
)

private const val STOP_TIMEOUT_MS = 5_000L
private const val RECENT_LIMIT = 5
private const val MAX_NOTES = 500

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
            transfer = it.type == "TRANSFER",
            reviewPending = it.status == "PENDING_REVIEW",
            serverId = it.serverId,
            paidBy = it.paidByMemberId?.let(memberNames::get),
        )
    }
}

data class HomeUiState(
    val total: Money = Money.zero(),
    val recent: List<ExpenseRow> = emptyList(),
    val status: LoadStatus = LoadStatus.LOADING,
    /** False when the engine failed: Home then shows the list without the summary card (REQ-AN-147). */
    val summaryAvailable: Boolean = true,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        repository: ExpenseRepository,
        engine: AnalyticsEngine,
    ) : ViewModel() {
        // One spending rule for Home and Insights: the engine's total of the current household period.
        private val total: Flow<Money?> =
            engine
                .observe("current")
                .map<PeriodAnalytics, Money?> { Money(it.summary.total, Money.EGP) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(null)
                }

        val state: StateFlow<HomeUiState> =
            combine(
                total,
                repository.observeRecent(RECENT_LIMIT),
                repository.observeLabelCategories(),
                repository.observeMembers(),
            ) { total, recent, categories, members ->
                HomeUiState(total ?: Money.zero(), toRows(recent, categories, members), LoadStatus.READY, summaryAvailable = total != null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())
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
    /** The id of the entry just written (a receipt waiting for an entry is attached to it). */
    val savedId: String? = null,
    /** The write is running: Save is disabled. */
    val saving: Boolean = false,
    /** The write threw: the sheet stays open with the input kept and "Couldn't save, try again". */
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
)

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

        fun onAmount(text: String) = form.update { it.copy(amountText = text, amountInvalid = false, amountTooLarge = false) }

        fun onCategory(id: String?) = form.update { it.copy(categoryId = id) }

        fun onAccount(id: String?) = form.update { it.copy(accountId = id) }

        fun onPaidBy(id: String?) = form.update { it.copy(paidByMemberId = id) }

        fun onDate(text: String) = form.update { it.copy(dateText = text, dateInvalid = false) }

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
                                amount = Money(amount.minor, Money.EGP),
                                date = date,
                                categoryId = f.categoryId,
                                accountId = f.accountId,
                                paidByMemberId = f.paidByMemberId,
                                notes = f.notes.trim().ifEmpty { null },
                            ),
                        )
                    }
                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                // On failure the input is kept and nothing was written (the repository's transaction rolled back). Nothing is logged.
                form.update {
                    it.copy(saving = false, saved = result.isSuccess, savedId = result.getOrNull(), saveFailed = result.isFailure)
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
            val url = normalizeServerUrl(s.serverUrl)
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
