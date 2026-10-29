package eg.masroof.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.data.db.ReceiptTotals
import eg.masroof.data.receipts.AttachResult
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.Refusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/** What the snackbar-style line under the receipts says. Never carries file content or names. */
sealed interface ReceiptMessage {
    data class Error(
        val reason: Refusal?,
    ) : ReceiptMessage

    data class Duplicate(
        val result: AttachResult.Duplicate,
    ) : ReceiptMessage
}

data class ReceiptsUiState(
    val receipts: List<ReceiptEntity> = emptyList(),
    /** Files being copied and re-encoded: one progress slot each. Save of the entry is not blocked. */
    val adding: Int = 0,
    val message: ReceiptMessage? = null,
    val viewing: ReceiptEntity? = null,
    /** The receipt the remove dialog is about (from the viewer or from a thumbnail). */
    val removing: ReceiptEntity? = null,
) {
    val confirmRemove: Boolean get() = removing != null
}

private const val STOP_TIMEOUT_MS = 5_000L

@HiltViewModel
class ReceiptsViewModel
    @Inject
    constructor(
        private val store: ReceiptStore,
        private val db: AppDatabase,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val expenseId: String = savedState.get<String>("id").orEmpty()
        private val local = MutableStateFlow(ReceiptsUiState())

        val state: StateFlow<ReceiptsUiState> =
            combine(local, db.receipts().observeFor(expenseId)) { l, rows ->
                l.copy(receipts = rows)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReceiptsUiState())

        fun fileOf(receipt: ReceiptEntity): File = store.fileOf(receipt)

        /** [open] runs on an IO thread; a null stream is a failure. A cancelled picker never gets here. */
        fun add(
            after: () -> Unit = {},
            open: () -> InputStream?,
        ) {
            local.update { it.copy(adding = it.adding + 1, message = null) }
            viewModelScope.launch {
                val result =
                    try {
                        store.attach(expenseId, open)
                    } finally {
                        after()
                    }
                local.update {
                    val message =
                        when (result) {
                            is AttachResult.Added -> null
                            is AttachResult.Duplicate -> ReceiptMessage.Duplicate(result)
                            is AttachResult.Refused -> ReceiptMessage.Error(result.reason)
                            AttachResult.Failed -> ReceiptMessage.Error(null)
                        }
                    it.copy(adding = it.adding - 1, message = message)
                }
            }
        }

        fun dismissMessage() = local.update { it.copy(message = null) }

        fun view(receipt: ReceiptEntity) = local.update { it.copy(viewing = receipt, removing = null) }

        /** The duplicate message's "Open": the existing receipt, wherever it is attached. */
        fun openDuplicate(receiptId: String) {
            viewModelScope.launch {
                val row = db.receipts().find(receiptId) ?: return@launch
                local.update { it.copy(viewing = row, message = null) }
            }
        }

        fun closeViewer() = local.update { it.copy(viewing = null, removing = null) }

        /** From the viewer: the receipt being shown. */
        fun askRemove() = local.update { if (it.viewing == null) it else it.copy(removing = it.viewing) }

        /** From a thumbnail, so a receipt that cannot be shown can still be removed. */
        fun askRemove(receipt: ReceiptEntity) = local.update { it.copy(removing = receipt) }

        fun dismissRemove() = local.update { it.copy(removing = null) }

        fun confirmRemove() {
            val id = local.value.removing?.id ?: return
            local.update { it.copy(viewing = it.viewing?.takeIf { v -> v.id != id }, removing = null) }
            viewModelScope.launch { store.remove(id) }
        }
    }

/** Settings: "Receipts: N files, X MB". */
@HiltViewModel
class ReceiptTotalsViewModel
    @Inject
    constructor(
        db: AppDatabase,
    ) : ViewModel() {
        val totals: StateFlow<ReceiptTotals> =
            db.receipts().observeTotals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReceiptTotals(0, 0))
    }
