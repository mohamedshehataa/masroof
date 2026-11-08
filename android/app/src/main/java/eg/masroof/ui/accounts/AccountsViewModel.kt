package eg.masroof.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.wealth.AccountDraft
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class AccountsUiState(
    val accounts: List<AccountView> = emptyList(),
    val loaded: Boolean = false,
)

class AccountsActions(
    val onSave: (AccountDraft, String?) -> Unit = { _, _ -> },
    val onSetBalance: (AccountEntity, Long, Long?) -> Unit = { _, _, _ -> },
    val onArchive: (String) -> Unit = {},
)

@HiltViewModel
class AccountsViewModel
    @Inject
    constructor(
        private val repository: WealthRepository,
    ) : ViewModel() {
        val state: StateFlow<AccountsUiState> =
            repository
                .observeAccountViews()
                .map { list -> AccountsUiState(list.filter { !it.account.archived }, loaded = true) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountsUiState())

        fun save(
            draft: AccountDraft,
            editingId: String?,
        ) = viewModelScope.launch {
            if (editingId == null) repository.addAccount(draft) else repository.updateAccount(editingId, draft)
        }

        fun setBalance(
            account: AccountEntity,
            balanceMinor: Long,
            atMillis: Long?,
        ) = viewModelScope.launch { repository.setBalance(account, balanceMinor, atMillis) }

        fun archive(id: String) = viewModelScope.launch { repository.archiveAccount(id, true) }
    }
