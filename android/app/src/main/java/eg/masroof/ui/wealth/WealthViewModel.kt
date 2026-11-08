package eg.masroof.ui.wealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.data.wealth.AssetDraft
import eg.masroof.data.wealth.AssetView
import eg.masroof.data.wealth.DebtDraft
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.NetWorth
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class WealthUiState(
    val netWorth: NetWorth = NetWorth.EMPTY,
    val assets: List<AssetView> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val snapshots: List<NetWorthSnapshotEntity> = emptyList(),
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val loaded: Boolean = false,
    val unmatchedEntries: Int = 0,
)

class WealthActions(
    val onOpenAccounts: () -> Unit = {},
    val onAddAsset: (AssetDraft) -> Unit = {},
    val onAddDebt: (DebtDraft) -> Unit = {},
    val onSettleDebt: (DebtEntity) -> Unit = {},
    val onDeleteDebt: (DebtEntity) -> Unit = {},
    val onHideAsset: (AssetEntity, Boolean) -> Unit = { _, _ -> },
    val onDeleteAsset: (AssetEntity) -> Unit = {},
    val onAddMetal: (HoldingDraft, LotDraft, (MetalOutcome) -> Unit) -> Unit = { _, _, _ -> },
    val onOpenMetal: (String) -> Unit = {},
)

@HiltViewModel
class WealthViewModel
    @Inject
    constructor(
        private val repository: WealthRepository,
    ) : ViewModel() {
        val state: StateFlow<WealthUiState> =
            combine(
                repository.observeNetWorth(),
                repository.observeAssetViews(),
                repository.observeDebts(),
                repository.observeSnapshots(),
                repository.observeUnmatched(),
            ) { net, assets, debts, snapshots, unmatched ->
                WealthUiState(net, assets, debts, snapshots, repository.today(), loaded = true, unmatchedEntries = unmatched)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WealthUiState())

        init {
            // Opening Wealth records today's point (once per Cairo day), so the chart is never empty after the first look.
            viewModelScope.launch { runCatching { repository.snapshotToday() } }
        }

        fun addAsset(draft: AssetDraft) = viewModelScope.launch { repository.addAsset(draft) }

        fun addDebt(draft: DebtDraft) = viewModelScope.launch { repository.addDebt(draft) }

        fun settle(debt: DebtEntity) = viewModelScope.launch { repository.settleDebt(debt) }

        fun deleteDebt(debt: DebtEntity) = viewModelScope.launch { repository.deleteDebt(debt) }

        fun hide(
            asset: AssetEntity,
            hidden: Boolean,
        ) = viewModelScope.launch { repository.setAssetHidden(asset, hidden) }

        fun deleteAsset(asset: AssetEntity) = viewModelScope.launch { repository.deleteAsset(asset) }
    }
