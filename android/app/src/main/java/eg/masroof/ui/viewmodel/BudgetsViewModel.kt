package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.MoneyFormat
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.BudgetPeriodKind
import eg.masroof.core.analytics.BudgetStatus
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.BudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.budgets.toSpec
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.buildBudgetRows
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val HOME_BUDGETS = 4

/** Why a save or delete did not go through; shown in the form. */
enum class BudgetError { INVALID_AMOUNT, AMOUNT_TOO_LARGE, INVALID, CATEGORY_GONE, DUPLICATE, FAILED }

private fun MutationOutcome.toError(): BudgetError? =
    when (this) {
        MutationOutcome.Ok -> null
        MutationOutcome.Invalid -> BudgetError.INVALID
        MutationOutcome.UnknownCategory -> BudgetError.CATEGORY_GONE
        MutationOutcome.Duplicate -> BudgetError.DUPLICATE
        MutationOutcome.NotFound, MutationOutcome.Failed -> BudgetError.FAILED
    }

data class BudgetsState(
    /** Every stored budget from Room, with its progress from the on-device engine when that worked. */
    val rows: List<BudgetRowUi> = emptyList(),
    /** The categories a new budget can be set on (the same list the Add sheet offers). */
    val categories: List<CategoryEntity> = emptyList(),
    val status: LoadStatus = LoadStatus.LOADING,
    val saving: Boolean = false,
    val error: BudgetError? = null,
    /** The engine could not compute progress: the budgets are listed without it, with a Retry. */
    val progressFailed: Boolean = false,
)

/** Rows plus whether their progress is missing because the engine failed. */
internal data class BudgetRows(
    val rows: List<BudgetRowUi>,
    val progressFailed: Boolean,
    /** The engine has not answered yet (the first frame): no rows are shown, so none flashes up without its progress. */
    val loading: Boolean = false,
)

private sealed interface Progress {
    data object Pending : Progress

    data class Ready(
        val byBudget: Map<String, BudgetStatus>,
    ) : Progress

    data object Failed : Progress
}

/**
 * The budgets screen and the Wallet tile read this one source: stored budgets and categories from Room, progress from the
 * engine. Every Room change (an entry, a budget, a category) re-emits, so nothing needs a refresh. [retry] restarts the
 * engine after a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun budgetRows(
    repository: BudgetsRepository,
    engine: AnalyticsEngine,
    retry: Flow<Int>,
): Flow<BudgetRows> {
    val progress: Flow<Progress> =
        retry.flatMapLatest {
            engine
                .observeBudgets()
                .map<List<BudgetStatus>, Progress> { list -> Progress.Ready(list.associateBy { it.budget.id }) }
                .onStart { emit(Progress.Pending) }
                .catch { emit(Progress.Failed) }
        }
    return combine(repository.observe(), repository.observeCategories(), progress) { budgets, categories, p ->
        if (p is Progress.Pending) return@combine BudgetRows(emptyList(), progressFailed = false, loading = true)
        val statuses = (p as? Progress.Ready)?.byBudget.orEmpty()
        // a budget saved a moment ago has no status until the engine catches up: it appears with its progress, never without
        val hidden =
            if (p is Progress.Ready) {
                budgets
                    .filter {
                        it.toSpec() != null && it.id !in statuses
                    }.map { it.id }
                    .toSet()
            } else {
                emptySet()
            }
        BudgetRows(buildBudgetRows(budgets.filter { it.id !in hidden }, categories, statuses), p is Progress.Failed)
    }
}

@HiltViewModel
class BudgetsViewModel
    @Inject
    constructor(
        private val repository: BudgetsRepository,
        engine: AnalyticsEngine,
        expenses: ExpenseRepository,
    ) : ViewModel() {
        private val retry = MutableStateFlow(0)
        private val form = MutableStateFlow(Form())

        private data class Form(
            val saving: Boolean = false,
            val error: BudgetError? = null,
        )

        val state: StateFlow<BudgetsState> =
            combine(budgetRows(repository, engine, retry), expenses.observePickerCategories(), form) { b, choices, f ->
                BudgetsState(b.rows, choices, if (b.loading) LoadStatus.LOADING else LoadStatus.READY, f.saving, f.error, b.progressFailed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BudgetsState())

        /** Computes the progress again after a failure. */
        fun retryProgress() {
            retry.update { it + 1 }
        }

        fun clearError() {
            form.value = form.value.copy(error = null)
        }

        /**
         * Creates a budget when [editingId] is null, otherwise changes the amount and rollover of that one (the category and
         * period are fixed after create). [onSaved] runs only after the row is stored.
         */
        fun save(
            editingId: String?,
            categoryId: String?,
            period: BudgetPeriodKind,
            amountText: String,
            rollover: Boolean,
            onSaved: () -> Unit,
        ) {
            if (form.value.saving) return // a second tap while a save runs is ignored
            val minor =
                when (val amount = MoneyFormat.parseEntry(amountText)) {
                    is MoneyFormat.EntryAmount.Valid -> amount.minor
                    MoneyFormat.EntryAmount.Invalid -> return fail(BudgetError.INVALID_AMOUNT)
                    MoneyFormat.EntryAmount.TooLarge -> return fail(BudgetError.AMOUNT_TOO_LARGE)
                }
            form.value = Form(saving = true)
            viewModelScope.launch {
                val outcome =
                    if (editingId == null) {
                        repository.create(BudgetDraft(categoryId, minor, rollover, period))
                    } else {
                        repository.update(editingId, minor, rollover)
                    }
                if (outcome == MutationOutcome.Ok) onSaved()
                form.value = Form(error = outcome.toError())
            }
        }

        private fun fail(error: BudgetError) {
            form.value = Form(error = error)
        }

        fun delete(
            id: String,
            onDeleted: () -> Unit,
        ) {
            if (form.value.saving) return
            form.value = Form(saving = true)
            viewModelScope.launch {
                val outcome = repository.delete(id)
                // A budget already gone is as good as deleted.
                if (outcome == MutationOutcome.Ok || outcome == MutationOutcome.NotFound) onDeleted()
                form.value = Form(error = outcome.toError())
            }
        }
    }

/** Wallet's budget card and tile: the budgets closest to their limit, live from Room, no refresh and no network. */
@HiltViewModel
class HomeBudgetsViewModel
    @Inject
    constructor(
        repository: BudgetsRepository,
        engine: AnalyticsEngine,
    ) : ViewModel() {
        private val rows = budgetRows(repository, engine, MutableStateFlow(0)).map { it.rows.filter { r -> r.hasProgress } }

        val state: StateFlow<List<BudgetRowUi>> =
            rows.map { it.take(HOME_BUDGETS) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** The monthly "All spending" budget (the oldest if a restore left two), whatever its rank: Home shows its remaining amount. */
        val total: StateFlow<BudgetRowUi?> =
            rows
                .map { list -> list.firstOrNull { it.categoryId == null && !it.weekly && !it.duplicate && !it.unsupported } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    }
