package eg.masroof.ui.answers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerRepository
import eg.masroof.data.answers.AnswerUndo
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.PurchaseOption
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.wealth.AccountDraft
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** "Pick another": the purchases a refund may reverse, shown in a sheet. */
data class RefundPick(
    val refundId: String,
    val options: List<PurchaseOption>,
)

/** The Wallet "Needs an answer" row: cards from Room, one answer at a time, each with an Undo that restores what was there. */
@HiltViewModel
class AnswerCardsViewModel
    @Inject
    constructor(
        private val repository: AnswerRepository,
        private val wealth: WealthRepository,
        db: AppDatabase,
    ) : ViewModel() {
        val cards: StateFlow<List<AnswerCard>> =
            repository.observeCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        val accounts: StateFlow<List<AccountEntity>> =
            db.accountBalances().observeActiveAccounts().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                emptyList(),
            )

        private val lastUndo = MutableStateFlow<AnswerUndo?>(null)
        val undo: StateFlow<AnswerUndo?> = lastUndo.asStateFlow()

        private val picking = MutableStateFlow<RefundPick?>(null)
        val pick: StateFlow<RefundPick?> = picking.asStateFlow()

        private fun run(block: suspend () -> AnswerUndo?) {
            viewModelScope.launch { lastUndo.value = block() ?: lastUndo.value }
        }

        fun linkRefund(
            refundId: String,
            purchaseId: String,
        ) = run {
            picking.value = null
            repository.linkRefund(refundId, purchaseId)
        }

        fun pickAnother(refundId: String) {
            viewModelScope.launch { picking.value = RefundPick(refundId, repository.pickList(refundId)) }
        }

        fun dismissPick() {
            picking.value = null
        }

        fun notRefund(entryId: String) = run { repository.notRefund(entryId) }

        fun transferToOwn(
            entryId: String,
            accountId: String,
        ) = run { repository.transferToOwn(entryId, accountId) }

        fun transferToOther(entryId: String) = run { repository.transferToOther(entryId) }

        fun ignoreCard(last4: String) = run { repository.ignoreCard(last4) }

        fun linkCard(
            last4: String,
            accountId: String,
        ) = run { repository.linkCard(last4, accountId) }

        fun addAccount(draft: AccountDraft) {
            viewModelScope.launch { wealth.addAccount(draft) }
        }

        fun trackCash(entryId: String) = run { repository.trackCash(entryId) }

        fun countCash(entryId: String) = run { repository.countCash(entryId) }

        fun undoLast() {
            val token = lastUndo.value ?: return
            lastUndo.value = null
            viewModelScope.launch { repository.undo(token) }
        }

        fun dismissUndo() {
            lastUndo.value = null
        }
    }

/** Settings -> "Needs an answer": the ATM choice and the ignored cards. */
@HiltViewModel
class AnswersSettingsViewModel
    @Inject
    constructor(
        private val repository: AnswerRepository,
    ) : ViewModel() {
        val atmChoice: StateFlow<AtmChoice?> =
            repository.atmChoice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        val ignored: StateFlow<List<String>> =
            repository.ignoredCards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        fun setAtmChoice(choice: AtmChoice?) {
            viewModelScope.launch { repository.setAtmChoice(choice) }
        }

        fun restore(last4: String) {
            viewModelScope.launch { repository.restoreIgnored(last4) }
        }
    }
