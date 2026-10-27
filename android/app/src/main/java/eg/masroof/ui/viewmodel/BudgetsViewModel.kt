package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.api.models.BudgetPeriod
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.RefreshOutcome
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val HOME_BUDGETS = 4

/** The household's currency as the server last reported it; EGP before any budget exists (the only supported base). */
internal fun householdCurrency(rows: List<BudgetRowUi>): String = rows.firstOrNull()?.limit?.currency ?: Money.EGP

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

        /** Scopes (category + period) whose last create ended without an answer (offline or timed out). */
        private val unansweredScopes = mutableSetOf<Pair<String?, BudgetPeriod>>()

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
                val rows = state.value.rows
                val period = rows.firstOrNull { it.id == editingId }?.period ?: BudgetPeriod.MONTHLY
                val draft = BudgetDraft(categoryId, minor, householdCurrency(rows), rollover, period)
                val scope = draft.categoryId to draft.period
                var outcome = if (editingId == null) repository.create(draft) else repository.update(editingId, draft)
                if (editingId == null) {
                    // a create that got no answer may still have gone through; a later 409 for that scope is then ours
                    val unanswered = outcome == MutationOutcome.Offline
                    if (outcome == MutationOutcome.Duplicate && scope in unansweredScopes) outcome = adoptExisting(draft)
                    if (unanswered) {
                        unansweredScopes += scope
                    } else if (outcome != MutationOutcome.Duplicate) {
                        unansweredScopes -= scope
                    }
                }
                form.value = Form(error = outcome.toError())
                if (outcome == MutationOutcome.Ok) onSaved()
            }
        }

        /**
         * A create the server refused as a duplicate, for a scope whose earlier create from this app got no answer, most likely
         * did go through. Look at the fresh list: when that budget exists, bring it to what the user entered and call it saved.
         * Any other duplicate is a budget the user did not mean to touch and stays an error.
         */
        private suspend fun adoptExisting(draft: BudgetDraft): MutationOutcome {
            // only a fresh list counts: a stale cached row must never be taken for the server's budget
            if (repository.refresh() != RefreshOutcome.Ok) return MutationOutcome.Duplicate
            val rows =
                repository
                    .observe()
                    .first()
                    ?.toRows(state.value.categories)
                    .orEmpty()
            val existing =
                rows.firstOrNull {
                    it.categoryId == draft.categoryId &&
                        it.period == draft.period &&
                        it.limit.currency == draft.currency
                }
            return when {
                existing == null -> MutationOutcome.Duplicate
                existing.limit.amountMinor == draft.amountMinor && existing.rollover == draft.rollover -> MutationOutcome.Ok
                else -> repository.update(existing.id, draft)
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
