package eg.masroof.ui.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.bills.BillMath
import eg.masroof.data.bills.BillsOverview
import eg.masroof.data.bills.CalendarItem
import eg.masroof.data.bills.InstallmentRepository
import eg.masroof.data.bills.PLAN_ACTIVE
import eg.masroof.data.bills.PaymentChoice
import eg.masroof.data.bills.PlanField
import eg.masroof.data.bills.PlanProgress
import eg.masroof.data.bills.UndoToken
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ingest.NotificationPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class BillsUiState(
    val loaded: Boolean = false,
    val overview: BillsOverview? = null,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val calendarMonth: YearMonth = YearMonth.of(1970, 1),
    val calendar: List<CalendarItem> = emptyList(),
    val notificationsAllowed: Boolean = true,
)

@HiltViewModel
class BillsViewModel
    @Inject
    constructor(
        repository: InstallmentRepository,
        private val permission: NotificationPermission,
        private val clock: Clock,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.from(CairoTime.today(clock.instant())))
        private val allowed = MutableStateFlow(permission.granted())

        val state: StateFlow<BillsUiState> =
            combine(repository.observePlans(), repository.observePayments(), month, allowed) { plans, payments, m, ok ->
                val today = CairoTime.today(clock.instant())
                BillsUiState(
                    loaded = true,
                    overview = BillMath.overview(plans, payments, today),
                    today = today,
                    calendarMonth = m,
                    calendar = BillMath.calendar(plans.filter { it.status == PLAN_ACTIVE }, payments, m),
                    notificationsAllowed = ok,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BillsUiState())

        fun shiftMonth(by: Int) = month.update { it.plusMonths(by.toLong()) }

        /** Called when the screen comes back to the front: the user may have just allowed notifications. */
        fun refreshPermission() {
            allowed.value = permission.granted()
        }
    }

data class PlanDetailState(
    val loaded: Boolean = false,
    val progress: PlanProgress? = null,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    /** Non-null while the Record payment dialog is open: the entries that could already be this payment. */
    val candidates: List<ExpenseEntity>? = null,
    val undo: UndoToken? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class PlanDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: InstallmentRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val planId: String = savedState.get<String>("id").orEmpty()
        private val extra = MutableStateFlow(Extra())

        private data class Extra(
            val candidates: List<ExpenseEntity>? = null,
            val undo: UndoToken? = null,
            val deleted: Boolean = false,
        )

        val state: StateFlow<PlanDetailState> =
            combine(repository.observePlan(planId), repository.observePaymentsOf(planId), extra) { plan, payments, e ->
                PlanDetailState(
                    loaded = true,
                    progress = plan?.let { BillMath.progressOf(it, payments) },
                    today = CairoTime.today(clock.instant()),
                    candidates = e.candidates,
                    undo = e.undo,
                    deleted = e.deleted,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlanDetailState())

        /** Opens the Record payment dialog for the next unpaid payment with its link candidates. */
        fun startRecord() {
            viewModelScope.launch {
                val next = state.value.progress?.nextDue ?: return@launch
                extra.update { it.copy(candidates = repository.linkCandidates(next.id)) }
            }
        }

        fun dismissRecord() = extra.update { it.copy(candidates = null) }

        fun record(choice: PaymentChoice) {
            viewModelScope.launch {
                val next = state.value.progress?.nextDue
                val token = next?.let { repository.recordPayment(it.id, choice) }
                extra.update { it.copy(candidates = null, undo = token) }
            }
        }

        fun collect() {
            viewModelScope.launch { extra.update { it.copy(undo = repository.recordCollection(planId)) } }
        }

        fun undo() {
            viewModelScope.launch {
                extra.value.undo?.let { repository.undo(it) }
                extra.update { it.copy(undo = null) }
            }
        }

        fun dismissUndo() = extra.update { it.copy(undo = null) }

        fun complete() {
            viewModelScope.launch {
                repository.complete(planId)
                extra.update { it.copy(undo = null) }
            }
        }

        fun delete() {
            viewModelScope.launch {
                repository.delete(planId)
                extra.update { it.copy(deleted = true) }
            }
        }
    }

data class AddPlanState(
    val form: AddPlanForm = AddPlanForm(),
    val errors: Set<PlanField> = emptySet(),
    val saving: Boolean = false,
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val categories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val saved: String? = null,
) {
    /** The "How many are already paid?" question appears only when the first payment is in the past. */
    val askAlreadyPaid: Boolean get() = form.dueBefore(today) > 0
}

@HiltViewModel
class AddPlanViewModel
    @Inject
    constructor(
        private val repository: InstallmentRepository,
        expenses: ExpenseRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val today = CairoTime.today(clock.instant())
        private val inner = MutableStateFlow(AddPlanState(form = AddPlanForm(firstDate = today.toString()), today = today))

        val state: StateFlow<AddPlanState> =
            combine(inner, expenses.observePickerCategories(), expenses.observeAccounts()) { s, categories, accounts ->
                s.copy(categories = categories, accounts = accounts)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), inner.value)

        fun edit(change: (AddPlanForm) -> AddPlanForm) = inner.update { it.copy(form = change(it.form), errors = emptySet()) }

        fun save() {
            val current = inner.value
            if (current.saving) return
            when (val r = current.form.parse(CairoTime.today(clock.instant()))) {
                is FormResult.Errors -> inner.update { it.copy(errors = r.fields) }
                is FormResult.Ok -> {
                    inner.update { it.copy(saving = true, errors = emptySet()) }
                    viewModelScope.launch {
                        val id = repository.create(r.plan)
                        inner.update { it.copy(saving = false, saved = id) }
                    }
                }
            }
        }
    }
