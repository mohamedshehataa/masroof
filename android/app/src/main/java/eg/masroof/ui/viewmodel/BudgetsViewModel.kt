package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.BudgetsRepository
import eg.masroof.data.budgets.MutationOutcome
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.budgets.BudgetRowUi
import eg.masroof.ui.budgets.toRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val HOME_BUDGETS = 3

/** Why a save or delete did not go through; shown in the form. */
enum class BudgetError { INVALID_AMOUNT, INVALID, DUPLICATE, OFFLINE, SIGN_IN, FAILED }

private fun MutationOutcome.toError(): BudgetError? =
    when (this) {
        MutationOutcome.Ok -> null
        MutationOutcome.Invalid -> BudgetError.INVALID
        MutationOutcome.Duplicate -> BudgetError.DUPLICATE
        MutationOutcome.Offline -> BudgetError.OFFLINE
        MutationOutcome.SignIn -> BudgetError.SIGN_IN
        MutationOutcome.NotFound, MutationOutcome.Failed -> BudgetError.FAILED
    }

data class BudgetsState(
    /** From the on-device cache: the last good answer, empty before the first successful refresh. */
    val rows: List<BudgetRowUi> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val status: LoadStatus = LoadStatus.LOADING,
    val saving: Boolean = false,
    val error: BudgetError? = null,
)

@HiltViewModel
class BudgetsViewModel
    @Inject
    constructor(
        private val repository: BudgetsRepository,
        expenses: ExpenseRepository,
    ) : ViewModel() {
        private val status = MutableStateFlow(LoadStatus.LOADING)
        private val form = MutableStateFlow(Form())

        private data class Form(
            val saving: Boolean = false,
            val error: BudgetError? = null,
        )

        val state: StateFlow<BudgetsState> =
            combine(repository.observe(), expenses.observeCategories(), status, form) { snapshot, categories, st, f ->
                BudgetsState(snapshot?.toRows(categories).orEmpty(), categories, st, f.saving, f.error)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BudgetsState())

        init {
            refresh()
        }

        fun refresh() {
            status.value = LoadStatus.LOADING
            viewModelScope.launch { status.value = repository.refresh().toStatus() }
        }

        fun clearError() {
            form.value = form.value.copy(error = null)
        }

        /** Creates (when [editingId] is null) or updates a budget; [onSaved] runs only after the server accepted it. */
        fun save(
            editingId: String?,
            categoryId: String?,
            amountText: String,
            rollover: Boolean,
            onSaved: () -> Unit,
        ) {
            val minor = MoneyFormat.parseMinor(amountText)
            if (minor == null) {
                form.value = Form(error = BudgetError.INVALID_AMOUNT)
                return
            }
            form.value = Form(saving = true)
            viewModelScope.launch {
                val draft = BudgetDraft(categoryId, minor, Money.EGP, rollover)
                val outcome = if (editingId == null) repository.create(draft) else repository.update(editingId, draft)
                form.value = Form(error = outcome.toError())
                if (outcome == MutationOutcome.Ok) onSaved()
            }
        }

        fun delete(
            id: String,
            onDeleted: () -> Unit,
        ) {
            form.value = Form(saving = true)
            viewModelScope.launch {
                val outcome = repository.delete(id)
                form.value = Form(error = outcome.toError())
                // A budget already gone elsewhere is as good as deleted.
                if (outcome == MutationOutcome.Ok || outcome == MutationOutcome.NotFound) onDeleted()
            }
        }
    }

/** Home card: the budgets closest to their limit, from the cache whatever the network does. */
@HiltViewModel
class HomeBudgetsViewModel
    @Inject
    constructor(
        private val repository: BudgetsRepository,
        expenses: ExpenseRepository,
    ) : ViewModel() {
        val state: StateFlow<List<BudgetRowUi>> =
            combine(repository.observe(), expenses.observeCategories()) { snapshot, categories ->
                snapshot?.toRows(categories).orEmpty().take(HOME_BUDGETS)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch { repository.refresh() }
        }
    }
