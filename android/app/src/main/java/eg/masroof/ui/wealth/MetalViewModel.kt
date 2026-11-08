package eg.masroof.ui.wealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SavingsGoalEntity
import eg.masroof.data.wealth.HoldingDetail
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.MetalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** A gold or silver holding's detail screen. [holding] is null once loaded when the holding does not exist (deleted). */
data class MetalUiState(
    val loaded: Boolean = false,
    val holding: HoldingDetail? = null,
    val goals: List<SavingsGoalEntity> = emptyList(),
    val candidates: List<ExpenseEntity> = emptyList(),
    /** The lot whose linked entry was just excluded from spending: offers Undo. */
    val undoLotId: String? = null,
)

@Suppress("LongParameterList")
class MetalActions(
    val onBack: () -> Unit = {},
    val onSaveLot: (lotId: String?, draft: LotDraft, done: (MetalOutcome) -> Unit) -> Unit = { _, _, _ -> },
    val onDeleteLot: (lotId: String, done: (MetalOutcome) -> Unit) -> Unit = { _, _ -> },
    val onSavePrices: (Map<Int, Long>) -> Unit = {},
    val onLoadCandidates: () -> Unit = {},
    val onLinkGoal: (goalId: String, done: (MetalOutcome) -> Unit) -> Unit = { _, _ -> },
    val onUnlinkGoal: () -> Unit = {},
    val onUndoEntry: (lotId: String) -> Unit = {},
    val onDismissUndo: () -> Unit = {},
    val onHide: (Boolean) -> Unit = {},
    val onDeleteHolding: () -> Unit = {},
)

@HiltViewModel
class MetalViewModel
    @Inject
    constructor(
        private val repository: MetalRepository,
        private val wealth: eg.masroof.data.wealth.WealthRepository,
    ) : ViewModel() {
        private val assetId = MutableStateFlow<String?>(null)
        private val candidates = MutableStateFlow(emptyList<ExpenseEntity>())
        private val undo = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<MetalUiState> =
            combine(
                assetId.flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeHolding(id) },
                repository.observeLinkableGoals(),
                candidates,
                undo,
            ) { holding, goals, entries, undoId ->
                MetalUiState(loaded = assetId.value != null, holding = holding, goals = goals, candidates = entries, undoLotId = undoId)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MetalUiState())

        fun open(id: String) {
            assetId.value = id
            undo.value = null
        }

        fun create(
            draft: HoldingDraft,
            lot: LotDraft,
            done: (MetalOutcome) -> Unit,
        ) {
            viewModelScope.launch { done(repository.createHolding(draft, lot)) }
        }

        fun saveLot(
            lotId: String?,
            draft: LotDraft,
            done: (MetalOutcome) -> Unit,
        ) {
            val id = assetId.value ?: return
            viewModelScope.launch {
                val out = if (lotId == null) repository.addLot(id, draft) else repository.updateLot(lotId, draft)
                val before =
                    lotId?.let { l ->
                        state.value.holding
                            ?.lots
                            ?.firstOrNull { it.id == l }
                            ?.expenseId
                    }
                // Undo is offered only when this save really linked an entry (one that counted until now).
                val newLink = draft.expenseId != null && draft.expenseId != before
                if (out is MetalOutcome.Saved && newLink && repository.entryOf(out.id) != null) undo.value = out.id
                done(out)
            }
        }

        fun deleteLot(
            lotId: String,
            done: (MetalOutcome) -> Unit,
        ) {
            viewModelScope.launch { done(repository.deleteLot(lotId)) }
        }

        fun savePrices(prices: Map<Int, Long>) {
            val metal =
                state.value.holding
                    ?.asset
                    ?.kind ?: return
            viewModelScope.launch { prices.forEach { (karat, price) -> repository.setPrice(metal, karat, price) } }
        }

        fun loadCandidates() {
            viewModelScope.launch { candidates.value = repository.entryCandidates() }
        }

        fun link(
            goalId: String,
            done: (MetalOutcome) -> Unit,
        ) {
            val id = assetId.value ?: return
            viewModelScope.launch { done(repository.linkGoal(id, goalId)) }
        }

        fun unlink() {
            val id = assetId.value ?: return
            viewModelScope.launch { repository.unlinkGoal(id) }
        }

        fun undoEntry(lotId: String) {
            undo.value = null
            viewModelScope.launch { repository.detachEntry(lotId) }
        }

        fun dismissUndo() {
            undo.value = null
        }

        fun hide(hidden: Boolean) {
            val asset = state.value.holding?.asset ?: return
            viewModelScope.launch { wealth.setAssetHidden(asset, hidden) }
        }

        fun deleteHolding() {
            val asset = state.value.holding?.asset ?: return
            viewModelScope.launch { repository.deleteHolding(asset.id) }
        }
    }
