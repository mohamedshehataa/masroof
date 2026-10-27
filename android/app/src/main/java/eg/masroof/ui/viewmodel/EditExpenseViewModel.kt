package eg.masroof.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.ExpenseEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Why the last Save / Confirm / Delete did not go through; shown above the buttons. The user's edits stay as typed. */
enum class EditError { OFFLINE, SIGN_IN, INVALID, FAILED }

data class EditFormState(
    val loaded: Boolean = false,
    val type: String = TYPE_EXPENSE,
    val amountText: String = "",
    val currency: String = "EGP",
    val date: LocalDate = LocalDate.of(1970, 1, 1),
    val categoryId: String? = null,
    val merchant: String = "",
    val notes: String = "",
    val source: String? = null,
    val reviewPending: Boolean = false,
    val amountInvalid: Boolean = false,
    val dateInvalid: Boolean = false,
    /** A call is in flight: every button is disabled so a double tap cannot send a second PATCH or DELETE. */
    val busy: Boolean = false,
    val error: EditError? = null,
    /** The server answered 409 and the form now shows its latest version. */
    val changedElsewhere: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Saved, confirmed, deleted or gone: the screen closes. */
    val done: Boolean = false,
)

data class EditUiState(
    val form: EditFormState = EditFormState(),
    val categories: List<CategoryEntity> = emptyList(),
)

const val TYPE_EXPENSE = "EXPENSE"
const val TYPE_INCOME = "INCOME"
const val TYPE_TRANSFER = "TRANSFER"
private const val STATUS_PENDING_REVIEW = "PENDING_REVIEW"
private const val STATUS_CONFIRMED = "CONFIRMED"
private const val MAX_NOTES = 500

/** The server's ceiling for one amount (ExpenseService.MAX_AMOUNT_MINOR), checked before sending. */
internal const val MAX_AMOUNT_MINOR = 10_000_000_000_000L

/** At most [n] Unicode code points: a surrogate pair (emoji) is never split. */
internal fun String.takeCodePoints(n: Int): String =
    if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))

private const val MAX_MERCHANT = 200
private const val STOP_TIMEOUT_MS = 5_000L
private const val MINOR_PER_MAJOR = 100L

/** `125` or `125.50`: what the amount field starts with. Integer arithmetic only. */
internal fun plainAmount(minor: Long): String {
    val major = minor / MINOR_PER_MAJOR
    val rest = (minor % MINOR_PER_MAJOR).toInt()
    return if (rest == 0) major.toString() else "$major.${rest.toString().padStart(2, '0')}"
}

private fun ExpenseEntity.toForm() =
    EditFormState(
        loaded = true,
        type = type,
        amountText = plainAmount(amountMinor),
        currency = currency,
        date = CairoTime.dateOf(occurredAtMillis),
        categoryId = categoryId,
        merchant = merchant.orEmpty(),
        notes = notes.orEmpty(),
        source = source,
        reviewPending = status == STATUS_PENDING_REVIEW,
    )

@HiltViewModel
class EditExpenseViewModel
    @Inject
    constructor(
        private val editor: ExpenseEditor,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val id: String = savedState.get<String>("id").orEmpty()
        private val form = MutableStateFlow(EditFormState())
        private var original: ExpenseEntity? = null
        private var inFlight = false

        val state: StateFlow<EditUiState> =
            combine(form, editor.observeCategories()) { f, c -> EditUiState(f, c) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EditUiState())

        init {
            viewModelScope.launch {
                val found = editor.find(id)
                if (found == null) {
                    form.update { it.copy(done = true) } // not cached (any more): nothing to edit
                } else {
                    original = found
                    form.value = found.toForm()
                }
            }
        }

        private fun edit(block: (EditFormState) -> EditFormState) = form.update { block(it).copy(error = null) }

        fun onType(type: String) = edit { it.copy(type = type) }

        fun onAmount(text: String) = edit { it.copy(amountText = text, amountInvalid = false) }

        fun onDate(date: LocalDate) = edit { it.copy(date = date, dateInvalid = false) }

        fun onCategory(id: String?) = edit { it.copy(categoryId = id) }

        fun onMerchant(text: String) = edit { it.copy(merchant = text.takeCodePoints(MAX_MERCHANT)) }

        fun onNotes(text: String) = edit { it.copy(notes = text.takeCodePoints(MAX_NOTES)) }

        fun askDelete() = form.update { if (it.busy) it else it.copy(confirmDelete = true) }

        fun dismissDelete() = form.update { it.copy(confirmDelete = false) }

        /**
         * One tap on a pending-review row. With no edits it sends status CONFIRMED only; with typed edits it behaves like
         * Save (the edits plus CONFIRMED), so nothing the user typed is dropped.
         */
        fun confirm() {
            val o = original ?: return
            if (o.status != STATUS_PENDING_REVIEW) return
            save()
        }

        /** Sends only what differs from the server's copy; a pending-review row is confirmed by saving it. */
        fun save() {
            val o = original ?: return
            if (inFlight) return
            val f = form.value
            val minor = MoneyFormat.parseMinor(f.amountText)?.takeIf { it <= MAX_AMOUNT_MINOR }
            if (minor == null) {
                form.update { it.copy(amountInvalid = true) }
                return
            }
            val date = f.date
            val changes = changesOf(o, f, minor, date)
            if (changes.isEmpty) {
                form.update { it.copy(done = true) }
                return
            }
            submit { editor.update(id, changes) }
        }

        fun delete() {
            if (original == null) return
            form.update { it.copy(confirmDelete = false) }
            submit { editor.delete(id) }
        }

        private fun changesOf(
            o: ExpenseEntity,
            f: EditFormState,
            minor: Long,
            date: LocalDate,
        ): ExpenseChanges {
            val merchant = f.merchant.trim()
            val notes = f.notes.trim()
            return ExpenseChanges(
                type = f.type.takeIf { it != o.type },
                amountMinor = minor.takeIf { it != o.amountMinor },
                currency = o.currency.takeIf { minor != o.amountMinor },
                occurredAtMillis =
                    CairoTime
                        .noonOf(date)
                        .toEpochMilli()
                        .takeIf { date != CairoTime.dateOf(o.occurredAtMillis) },
                categoryId = f.categoryId.takeIf { it != null && it != o.categoryId },
                merchantRaw = merchant.takeIf { it != o.merchant.orEmpty() },
                notes = notes.takeIf { it != o.notes.orEmpty() },
                status = STATUS_CONFIRMED.takeIf { o.status == STATUS_PENDING_REVIEW },
            )
        }

        /** One call at a time: [inFlight] is checked and set on the main thread before anything is launched. */
        private fun submit(call: suspend () -> EditOutcome) {
            if (inFlight) return
            inFlight = true
            form.update { it.copy(busy = true, error = null, changedElsewhere = false) }
            viewModelScope.launch {
                try {
                    apply(call())
                } finally {
                    inFlight = false
                    form.update { it.copy(busy = false) }
                }
            }
        }

        private fun apply(outcome: EditOutcome) {
            when (outcome) {
                is EditOutcome.Saved, EditOutcome.Deleted, EditOutcome.NotFound -> form.update { it.copy(done = true) }
                EditOutcome.Offline -> form.update { it.copy(error = EditError.OFFLINE) }
                EditOutcome.SignIn -> form.update { it.copy(error = EditError.SIGN_IN) }
                EditOutcome.Failed -> form.update { it.copy(error = EditError.FAILED) }
                is EditOutcome.Conflict -> {
                    val latest = outcome.latest
                    if (latest == null) {
                        form.update { it.copy(error = EditError.FAILED) }
                    } else {
                        original = latest
                        form.value = latest.toForm().copy(changedElsewhere = true, busy = true)
                    }
                }
                is EditOutcome.Invalid ->
                    form.update {
                        it.copy(
                            error = EditError.INVALID,
                            amountInvalid = outcome.field == "amount" || outcome.field?.startsWith("amount.") == true,
                            dateInvalid = outcome.field == "occurredAt",
                        )
                    }
            }
        }
    }
