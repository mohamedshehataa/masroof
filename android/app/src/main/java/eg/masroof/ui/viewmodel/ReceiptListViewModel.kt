package eg.masroof.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.data.receipts.AttachResult
import eg.masroof.data.receipts.ReceiptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ReceiptListState(
    val receipts: List<ReceiptEntity> = emptyList(),
    val loaded: Boolean = false,
    /** Files being copied and re-encoded: one placeholder each. */
    val adding: Int = 0,
    val message: ReceiptMessage? = null,
    val viewing: ReceiptEntity? = null,
    val removing: ReceiptEntity? = null,
    /** The receipt whose "Attach to entry" picker is open, with the entries of the last 30 days. */
    val picking: ReceiptEntity? = null,
    val choices: List<ExpenseEntity> = emptyList(),
    /** Receipts created before this are older than 90 days and get the "Not attached for 3 months" hint. */
    val staleBeforeMillis: Long = 0L,
)

private const val STOP_TIMEOUT_MS = 5_000L
private const val PICK_DAYS = 30L
private const val STALE_DAYS = 90L

/** The Receipts list (More -> Receipts): receipts without an entry, newest first (REQ-RCPT-023), and "Receipt only" intake. */
@HiltViewModel
class ReceiptListViewModel
    @Inject
    constructor(
        private val store: ReceiptStore,
        private val db: AppDatabase,
        private val clock: Clock,
    ) : ViewModel() {
        private val local = MutableStateFlow(ReceiptListState())

        val state: StateFlow<ReceiptListState> =
            combine(
                local,
                db.receipts().observeUnattached(),
                db.expenses().observeSince(clock.millis() - days(PICK_DAYS)),
            ) { l, rows, entries ->
                l.copy(
                    receipts = rows,
                    loaded = true,
                    choices = entries,
                    staleBeforeMillis = clock.millis() - days(STALE_DAYS),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReceiptListState())

        fun fileOf(receipt: ReceiptEntity): File = store.fileOf(receipt)

        /** Stores [open]'s content unattached; [after] runs when done (the camera temp file is deleted there). */
        fun add(
            after: () -> Unit = {},
            open: () -> InputStream?,
        ) {
            local.update { it.copy(adding = it.adding + 1, message = null) }
            viewModelScope.launch {
                val result =
                    try {
                        store.attach(null, open)
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

        fun openDuplicate(receiptId: String) {
            viewModelScope.launch {
                val row = db.receipts().find(receiptId) ?: return@launch
                local.update { it.copy(viewing = row, message = null) }
            }
        }

        fun closeViewer() = local.update { it.copy(viewing = null, removing = null) }

        fun askRemove() = local.update { if (it.viewing == null) it else it.copy(removing = it.viewing) }

        fun askRemove(receipt: ReceiptEntity) = local.update { it.copy(removing = receipt) }

        fun dismissRemove() = local.update { it.copy(removing = null) }

        /** Row first, then the file. */
        fun confirmRemove() {
            val id = local.value.removing?.id ?: return
            local.update { it.copy(viewing = it.viewing?.takeIf { v -> v.id != id }, removing = null) }
            viewModelScope.launch { store.remove(id) }
        }

        fun askAttach(receipt: ReceiptEntity) = local.update { it.copy(picking = receipt) }

        fun dismissAttach() = local.update { it.copy(picking = null) }

        fun attachTo(expenseId: String) {
            val receipt = local.value.picking ?: return
            local.update { it.copy(picking = null) }
            viewModelScope.launch {
                if (!store.attachExisting(receipt.id, expenseId)) local.update { it.copy(message = ReceiptMessage.Error(null)) }
            }
        }

        private fun days(n: Long) = TimeUnit.DAYS.toMillis(n)
    }

/**
 * The Add sheet opened from a receipt ("New entry"): holds the receipt id from the route, and attaches it to the entry once saved.
 * Cancelling never touches the receipt (REQ-RCPT-023).
 */
@HiltViewModel
class PendingReceiptViewModel
    @Inject
    constructor(
        private val store: ReceiptStore,
        private val db: AppDatabase,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        val receiptId: String? = savedState.get<String>("receipt")?.takeIf { it.isNotBlank() }

        val receipt: StateFlow<ReceiptEntity?> =
            kotlinx.coroutines.flow
                .flow { emit(receiptId?.let { db.receipts().find(it) }) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        fun fileOf(receipt: ReceiptEntity): File = store.fileOf(receipt)

        /** Called once with the new entry's id. */
        fun attachTo(expenseId: String) {
            val id = receiptId ?: return
            viewModelScope.launch { store.attachExisting(id, expenseId) }
        }
    }
