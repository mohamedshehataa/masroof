package eg.masroof.ui

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
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.normalizeServerUrl
import eg.masroof.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** Where the last server refresh stands. Lists always render from Room regardless of this. */
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
)

private const val STOP_TIMEOUT_MS = 5_000L
private const val RECENT_LIMIT = 5
private const val MAX_NOTES = 2000

private fun toRows(
    expenses: List<ExpenseEntity>,
    categories: List<CategoryEntity>,
): List<ExpenseRow> {
    val byId = categories.associateBy { it.id }
    return expenses.map {
        ExpenseRow(
            id = it.id,
            merchant = it.merchant,
            money = Money(it.amountMinor, it.currency),
            date = CairoTime.dateOf(it.occurredAtMillis),
            pending = it.pending && !it.failed,
            failed = it.failed,
            category = it.categoryId?.let(byId::get),
            notes = it.notes,
        )
    }
}

abstract class RefreshingViewModel(
    private val repository: ExpenseRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    protected val status = MutableStateFlow(LoadStatus.LOADING)

    /** "Retry" on a row the server rejected: re-queue it and let WorkManager upload it. */
    fun retryEntry(id: String) {
        viewModelScope.launch {
            repository.retryFailed(id)
            scheduler.schedule()
        }
    }

    /** "Delete" on a row that could not be synced. */
    fun deleteEntry(id: String) {
        viewModelScope.launch { repository.deleteFailed(id) }
    }

    fun refresh() {
        status.value = LoadStatus.LOADING
        viewModelScope.launch {
            status.value =
                when (repository.refresh()) {
                    RefreshOutcome.Ok -> LoadStatus.READY
                    RefreshOutcome.Offline -> LoadStatus.OFFLINE
                    RefreshOutcome.Failed -> LoadStatus.ERROR
                    RefreshOutcome.SignIn -> LoadStatus.SIGN_IN
                }
        }
    }
}

data class HomeUiState(
    val total: Money = Money.zero(),
    val recent: List<ExpenseRow> = emptyList(),
    val pendingCount: Int = 0,
    val status: LoadStatus = LoadStatus.LOADING,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        repository: ExpenseRepository,
        scheduler: SyncScheduler,
    ) : RefreshingViewModel(repository, scheduler) {
        val state: StateFlow<HomeUiState> =
            combine(
                repository.observeMonthTotal(),
                repository.observeRecent(RECENT_LIMIT),
                repository.observeCategories(),
                repository.observePendingCount(),
                status,
            ) { total, recent, categories, pending, st ->
                HomeUiState(total, toRows(recent, categories), pending, st)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

        init {
            refresh()
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
        scheduler: SyncScheduler,
    ) : RefreshingViewModel(repository, scheduler) {
        val state: StateFlow<TransactionsUiState> =
            combine(
                repository.observeAll(),
                repository.observeCategories(),
                status,
            ) { all, categories, st ->
                TransactionsUiState(toRows(all, categories), st)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransactionsUiState())

        init {
            refresh()
        }
    }

data class AddFormState(
    val amountText: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val dateText: String = "",
    val notes: String = "",
    val amountInvalid: Boolean = false,
    val dateInvalid: Boolean = false,
    val saved: Boolean = false,
)

data class AddUiState(
    val form: AddFormState = AddFormState(),
    val categories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

@HiltViewModel
class AddExpenseViewModel
    @Inject
    constructor(
        private val repository: ExpenseRepository,
        private val scheduler: SyncScheduler,
        private val clock: java.time.Clock,
    ) : ViewModel() {
        private var saving = false
        private val form = MutableStateFlow(AddFormState(dateText = CairoTime.today(clock.instant()).toString()))

        val state: StateFlow<AddUiState> =
            combine(
                form,
                repository.observeCategories(),
                repository.observeAccounts(),
            ) { f, c, a -> AddUiState(f, c, a) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddUiState(form.value))

        fun onAmount(text: String) = form.update { it.copy(amountText = text, amountInvalid = false) }

        fun onCategory(id: String?) = form.update { it.copy(categoryId = id) }

        fun onAccount(id: String?) = form.update { it.copy(accountId = id) }

        fun onDate(text: String) = form.update { it.copy(dateText = text, dateInvalid = false) }

        // The contract caps notes at 2000 characters; the form cannot exceed it.
        fun onNotes(text: String) = form.update { it.copy(notes = text.take(MAX_NOTES)) }

        /** Validates, writes to Room + outbox, then asks WorkManager to sync. Never touches the network itself. */
        fun save() {
            val f = form.value
            if (saving || f.saved) return // double tap: one entry only
            val minor = MoneyFormat.parseMinor(f.amountText)
            val date =
                try {
                    LocalDate.parse(f.dateText.trim())
                } catch (_: DateTimeParseException) {
                    null
                }
            if (minor == null || date == null) {
                form.update { it.copy(amountInvalid = minor == null, dateInvalid = date == null) }
                return
            }
            saving = true
            viewModelScope.launch {
                try {
                    repository.add(
                        NewExpense(
                            amount = Money(minor, Money.EGP),
                            occurredAtMillis = CairoTime.noonOf(date).toEpochMilli(),
                            categoryId = f.categoryId,
                            accountId = f.accountId,
                            notes = f.notes.trim().ifEmpty { null },
                        ),
                    )
                    scheduler.schedule()
                    form.update { it.copy(saved = true) }
                } finally {
                    saving = false
                }
            }
        }
    }

data class SettingsUiState(
    val serverUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val accessToken: String = "",
    val digitStyle: DigitStyle? = null,
    val urlInvalid: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val draft = MutableStateFlow<SettingsUiState?>(null)

        val state: StateFlow<SettingsUiState> =
            combine(settings.settings, draft) { stored, d ->
                d ?: SettingsUiState(stored.serverUrl, stored.accessToken, stored.digitStyle)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        private fun edit(block: (SettingsUiState) -> SettingsUiState) {
            draft.value = block(state.value).copy(saved = false)
        }

        fun onUrl(text: String) = edit { it.copy(serverUrl = text, urlInvalid = false) }

        fun onToken(text: String) = edit { it.copy(accessToken = text) }

        fun onDigits(style: DigitStyle) = edit { it.copy(digitStyle = style) }

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
                        accessToken = s.accessToken.trim(),
                        digitStyle = s.digitStyle,
                    )
                }
                draft.value = s.copy(serverUrl = url, saved = true)
            }
        }
    }
