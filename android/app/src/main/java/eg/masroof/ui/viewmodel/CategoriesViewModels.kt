package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.categories.CategoryRepository
import eg.masroof.data.categories.CategoryResult
import eg.masroof.data.categories.MerchantRuleRepository
import eg.masroof.data.categories.PastChange
import eg.masroof.data.categories.RuleResult
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.MerchantRuleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** A top-level category with its sub-categories, in display order. */
data class CategoryGroup(
    val parent: CategoryEntity,
    val children: List<CategoryEntity>,
)

data class CategoriesUiState(
    val loaded: Boolean = false,
    val groups: List<CategoryGroup> = emptyList(),
    /** The last delete answer that needs a message (in use / built-in), cleared by [CategoriesViewModel.clearMessage]. */
    val deleteBlocked: CategoryResult? = null,
)

/** Groups categories by parent, ordered by sort order then English name; a child whose parent is missing is shown top level. */
fun groupCategories(all: List<CategoryEntity>): List<CategoryGroup> {
    val order = compareBy<CategoryEntity>({ it.sortOrder }, { it.nameEn })
    val ids = all.mapTo(HashSet()) { it.id }
    val tops = all.filter { it.parentId == null || it.parentId !in ids }.sortedWith(order)
    return tops.map { p -> CategoryGroup(p, all.filter { it.parentId == p.id }.sortedWith(order)) }
}

@HiltViewModel
class CategoriesViewModel
    @Inject
    constructor(
        private val repository: CategoryRepository,
    ) : ViewModel() {
        private val blocked = MutableStateFlow<CategoryResult?>(null)

        val state: StateFlow<CategoriesUiState> =
            combine(repository.observe(), blocked) { all, b ->
                CategoriesUiState(loaded = true, groups = groupCategories(all), deleteBlocked = b)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CategoriesUiState())

        /** Adds (when [editingId] is null) or edits; [onResult] gets the answer so the sheet can show a field error. */
        @Suppress("LongParameterList")
        fun save(
            editingId: String?,
            parentId: String?,
            nameEn: String,
            nameAr: String,
            emoji: String?,
            onResult: (CategoryResult) -> Unit,
        ) {
            viewModelScope.launch {
                val r =
                    if (editingId == null) {
                        repository.add(nameEn, nameAr, emoji, parentId)
                    } else {
                        repository.edit(editingId, nameEn, nameAr, emoji)
                    }
                onResult(r)
            }
        }

        fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            viewModelScope.launch { repository.setArchived(id, archived) }
        }

        fun move(
            id: String,
            up: Boolean,
        ) {
            viewModelScope.launch { repository.move(id, up) }
        }

        fun delete(id: String) {
            viewModelScope.launch {
                val r = repository.delete(id)
                if (r !is CategoryResult.Ok) blocked.value = r
            }
        }

        fun clearMessage() {
            blocked.value = null
        }
    }

/** One rule with the category it points at. */
data class RuleRow(
    val rule: MerchantRuleEntity,
    val category: CategoryEntity?,
)

data class MerchantRulesUiState(
    val loaded: Boolean = false,
    val rules: List<RuleRow> = emptyList(),
    /** Active categories for the "File under" choice, in display order. */
    val categories: List<CategoryEntity> = emptyList(),
    val emptyKeyError: Boolean = false,
)

@HiltViewModel
class MerchantRulesViewModel
    @Inject
    constructor(
        private val rules: MerchantRuleRepository,
        categories: CategoryRepository,
    ) : ViewModel() {
        private val error = MutableStateFlow(false)

        val state: StateFlow<MerchantRulesUiState> =
            combine(rules.observe(), categories.observe(), error) { r, c, e ->
                val byId = c.associateBy { it.id }
                MerchantRulesUiState(
                    loaded = true,
                    rules = r.map { RuleRow(it, byId[it.categoryId]) },
                    categories = c.filter { !it.archived }.sortedWith(compareBy({ it.sortOrder }, { it.nameEn })),
                    emptyKeyError = e,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MerchantRulesUiState())

        fun add(
            merchant: String,
            categoryId: String?,
            onSaved: () -> Unit,
        ) {
            if (categoryId == null) return
            viewModelScope.launch {
                when (rules.addRule(merchant, categoryId)) {
                    is RuleResult.Saved -> {
                        error.value = false
                        onSaved()
                    }
                    RuleResult.EmptyKey -> error.value = true
                    RuleResult.NoCategory -> Unit
                }
            }
        }

        fun clearError() {
            error.value = false
        }

        fun delete(ruleId: String) {
            viewModelScope.launch { rules.delete(ruleId) }
        }
    }

/** Where the two-step "Always file ...? / Also change the N past ...?" prompt is. */
sealed interface RulePromptStage {
    data object Idle : RulePromptStage

    data object AskRule : RulePromptStage

    data class AskPast(
        val merchantKey: String,
        val count: Int,
    ) : RulePromptStage

    /** The past entries were changed; Undo is still possible. */
    data class Changed(
        val count: Int,
    ) : RulePromptStage
}

@HiltViewModel
class RulePromptViewModel
    @Inject
    constructor(
        private val rules: MerchantRuleRepository,
        categories: CategoryRepository,
    ) : ViewModel() {
        private val mutableStage = MutableStateFlow<RulePromptStage>(RulePromptStage.Idle)
        val stage: StateFlow<RulePromptStage> = mutableStage

        private val mutableFinished = MutableStateFlow(false)

        /** True when the prompt has nothing more to ask: the edit screen may close. */
        val finished: StateFlow<Boolean> = mutableFinished

        private var change: PastChange? = null
        private var begun = false
        private var offer: RuleOffer? = null

        val categoryNames: StateFlow<Map<String, CategoryEntity>> =
            categories
                .observe()
                .map { all -> all.associateBy { it.id } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

        /** Starts once per screen: no prompt when the rule already exists. */
        fun begin(offered: RuleOffer) {
            if (begun) return
            begun = true
            offer = offered
            viewModelScope.launch {
                if (rules.hasRule(offered.merchant, offered.categoryId)) {
                    mutableFinished.value = true
                } else {
                    mutableStage.value = RulePromptStage.AskRule
                }
            }
        }

        fun yes() {
            val o = offer ?: return
            when (val s = mutableStage.value) {
                RulePromptStage.AskRule -> viewModelScope.launch { saveRule(o) }
                is RulePromptStage.AskPast ->
                    viewModelScope.launch {
                        val c = rules.applyToPast(s.merchantKey, o.categoryId)
                        change = c
                        if (c.count > 0) {
                            mutableStage.value = RulePromptStage.Changed(c.count)
                        } else {
                            finish()
                        }
                    }
                else -> Unit
            }
        }

        private suspend fun saveRule(o: RuleOffer) {
            val r = rules.addRule(o.merchant, o.categoryId)
            if (r is RuleResult.Saved && r.pastEntries > 0) {
                mutableStage.value = RulePromptStage.AskPast(r.merchantKey, r.pastEntries)
            } else {
                finish()
            }
        }

        /** "Not now" / "Leave them" / "Done". */
        fun no() = finish()

        fun undo() {
            val c = change ?: return
            viewModelScope.launch {
                rules.undo(c)
                change = null
                finish()
            }
        }

        private fun finish() {
            mutableStage.value = RulePromptStage.Idle
            mutableFinished.value = true
        }
    }
