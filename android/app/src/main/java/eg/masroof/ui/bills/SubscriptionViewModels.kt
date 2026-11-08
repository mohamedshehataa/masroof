package eg.masroof.ui.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.SettingsRepository
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.CalendarItem
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.Payday
import eg.masroof.data.bills.PaydayMath
import eg.masroof.data.bills.PriceNotice
import eg.masroof.data.bills.RenewalMath
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.bills.SubscriptionRepository
import eg.masroof.data.bills.SubscriptionTotals
import eg.masroof.data.bills.SubscriptionsData
import eg.masroof.data.bills.Suggestion
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.ui.viewmodel.plainAmount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** One row of the list: the stored row, when it was last paid (from matching entries) and the days until its renewal. */
data class SubItem(
    val row: SubscriptionEntity,
    val date: LocalDate,
    val daysUntil: Int,
    val lastPaid: LocalDate?,
)

data class SubscriptionsUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val totals: SubscriptionTotals = SubscriptionTotals(0L, 0L, 0, false),
    /** Active first, by next renewal; paused ones last. */
    val subscriptions: List<SubItem> = emptyList(),
    val income: List<SubItem> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val notices: List<PriceNotice> = emptyList(),
    val payday: Payday? = null,
    val calendarMonth: YearMonth = YearMonth.of(1970, 1),
    val calendar: List<CalendarItem> = emptyList(),
)

/** Builds the screen state from the stored rows; pure, so the screen tests use it too. */
fun subscriptionsState(
    data: SubscriptionsData,
    today: LocalDate,
    month: YearMonth,
    monthStartDay: Int,
): SubscriptionsUiState {
    val items =
        data.rows
            .map { row ->
                val date = CairoTime.dateOf(row.nextRenewalAtMillis)
                val key = SubscriptionMatcher.keyOfRow(row.merchantKey, row.name).orEmpty()
                val paid = SubscriptionMatcher.lastPaid(key, row.amountMinor, row.currency, row.kind == KIND_INCOME, data.entries, today)
                SubItem(row, date, RenewalMath.daysUntil(today, date), paid)
            }.sortedWith(compareBy({ !it.row.active }, { it.date }, { it.row.id }))
    val existingKeys = data.rows.mapNotNull { SubscriptionMatcher.keyOfRow(it.merchantKey, it.name) }.toSet()
    val active = data.rows.filter { it.active }
    return SubscriptionsUiState(
        loaded = true,
        today = today,
        totals = RenewalMath.totals(data.rows),
        subscriptions = items.filter { it.row.kind != KIND_INCOME },
        income = items.filter { it.row.kind == KIND_INCOME },
        suggestions = SubscriptionMatcher.suggest(data.entries, existingKeys, data.dismissed, today),
        notices = data.notices,
        payday = PaydayMath.next(today, data.rows, monthStartDay, CairoTime::dateOf, data.anchors),
        calendarMonth = month,
        calendar =
            active
                .flatMap { row ->
                    RenewalMath
                        .occurrencesIn(
                            CairoTime.dateOf(row.nextRenewalAtMillis),
                            Cadence.of(row.cadence),
                            month,
                            data.anchors[row.id] ?: RenewalMath.anchorOf(CairoTime.dateOf(row.nextRenewalAtMillis)),
                        ).map { CalendarItem(it, row.id, row.name, "NONE", row.amountMinor, row.currency, false, row.kind == KIND_INCOME) }
                }.sortedWith(compareBy({ it.date }, { it.planId })),
    )
}

@HiltViewModel
class SubscriptionsViewModel
    @Inject
    constructor(
        private val repository: SubscriptionRepository,
        settings: SettingsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.from(CairoTime.today(clock.instant())))

        init {
            // Match entries to renewals and roll dates forward whenever the rows or the entries change.
            viewModelScope.launch {
                repository
                    .observeData()
                    .map { d -> d.rows.size to d.entries.map { it.id } }
                    .distinctUntilChanged()
                    .collect { repository.settle(CairoTime.today(clock.instant())) }
            }
        }

        val state: StateFlow<SubscriptionsUiState> =
            combine(repository.observeData(), month, settings.settings) { data, m, s ->
                subscriptionsState(data, CairoTime.today(clock.instant()), m, s.monthStartDay)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState())

        fun shiftMonth(by: Int) = month.update { it.plusMonths(by.toLong()) }

        fun dismissSuggestion(key: String) {
            viewModelScope.launch { repository.dismissSuggestion(key) }
        }

        fun dismissNotice(id: String) {
            viewModelScope.launch { repository.dismissNotice(id) }
        }
    }

class SubscriptionDetailActions(
    val onEdit: () -> Unit,
    val onSetActive: (Boolean) -> Unit,
    val onDelete: () -> Unit,
)

data class SubscriptionDetailState(
    val loaded: Boolean = false,
    val item: SubItem? = null,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val deleted: Boolean = false,
)

@HiltViewModel
class SubscriptionDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: SubscriptionRepository,
        settings: SettingsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val id: String = savedState.get<String>("id").orEmpty()
        private val deleted = MutableStateFlow(false)

        val state: StateFlow<SubscriptionDetailState> =
            combine(repository.observeData(), settings.settings, deleted) { data, s, gone ->
                val today = CairoTime.today(clock.instant())
                val ui = subscriptionsState(data, today, YearMonth.from(today), s.monthStartDay)
                SubscriptionDetailState(
                    loaded = true,
                    item = (ui.subscriptions + ui.income).firstOrNull { it.row.id == id },
                    today = today,
                    deleted = gone,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionDetailState())

        fun setActive(active: Boolean) {
            viewModelScope.launch { repository.setActive(id, active) }
        }

        fun delete() {
            viewModelScope.launch {
                repository.delete(id)
                deleted.value = true
            }
        }
    }

data class SubscriptionFormState(
    val form: SubscriptionForm = SubscriptionForm(),
    val errors: Set<SubField> = emptySet(),
    val editing: Boolean = false,
    val saving: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val saved: String? = null,
)

@HiltViewModel
class SubscriptionFormViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: SubscriptionRepository,
        expenses: ExpenseRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val id: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }
        private val inner = MutableStateFlow(SubscriptionFormState(form = prefill(savedState), editing = id != null))

        val state: StateFlow<SubscriptionFormState> =
            combine(inner, expenses.observeAccounts()) { s, accounts -> s.copy(accounts = accounts) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), inner.value)

        init {
            if (id != null) {
                viewModelScope.launch {
                    repository.observe(id).first()?.let { row -> inner.update { it.copy(form = SubscriptionForm.of(row)) } }
                }
            }
        }

        private fun prefill(saved: SavedStateHandle): SubscriptionForm {
            val minor = saved.get<Long>("amount")?.takeIf { it > 0 }
            val today = CairoTime.today(clock.instant())
            return SubscriptionForm(
                name = saved.get<String>("name").orEmpty(),
                amount = minor?.let { plainAmount(it) }.orEmpty(),
                currency = saved.get<String>("cur")?.takeIf { it.isNotBlank() } ?: SubscriptionForm().currency,
                date = saved.get<String>("date")?.takeIf { it.isNotBlank() } ?: today.toString(),
                merchant = saved.get<String>("merchant").orEmpty(),
            )
        }

        fun edit(change: (SubscriptionForm) -> SubscriptionForm) = inner.update { it.copy(form = change(it.form), errors = emptySet()) }

        fun save() {
            val current = inner.value
            if (current.saving) return
            when (val r = current.form.parse()) {
                is SubFormResult.Errors -> inner.update { it.copy(errors = r.fields) }
                is SubFormResult.Ok -> {
                    inner.update { it.copy(saving = true, errors = emptySet()) }
                    viewModelScope.launch {
                        val savedId =
                            if (id == null) {
                                repository.create(r.value)
                            } else {
                                repository.update(id, r.value)
                                id
                            }
                        inner.update { it.copy(saving = false, saved = savedId) }
                    }
                }
            }
        }
    }
