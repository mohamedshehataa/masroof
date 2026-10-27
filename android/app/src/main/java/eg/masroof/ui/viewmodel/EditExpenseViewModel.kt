package eg.masroof.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.edit.ExpenseEditor
import eg.masroof.data.edit.ItemInput
import eg.masroof.data.receipts.MAX_ITEMS
import eg.masroof.data.receipts.MAX_ITEM_NAME
import eg.masroof.data.receipts.ReceiptRemote
import eg.masroof.data.receipts.canonicalQty
import eg.masroof.data.receipts.cleanItemName
import eg.masroof.data.receipts.parseItemAmount
import eg.masroof.data.receipts.parseQty
import eg.masroof.data.remote.HttpFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
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
    /** The purchased-items section: loaded from the server on open (STORY-168), never cached in Room. */
    val itemsLoad: SectionLoad = SectionLoad.LOADING,
    val items: List<ItemRow> = emptyList(),
    /** Saved, confirmed, deleted or gone: the screen closes. */
    val done: Boolean = false,
)

/** Where a server-backed section (items, attachments) is: spinner, shown, or failed with Retry. */
enum class SectionLoad { LOADING, LOADED, FAILED }

/** One editable line of the Items section; every field is the text as typed, parsed only on Save. [key] is stable across edits. */
data class ItemRow(
    val key: Long,
    val name: String = "",
    val qty: String = "",
    val amount: String = "",
    val invalid: Boolean = false,
) {
    // Item names and amounts are private: never printed.
    override fun toString() = "ItemRow(key=$key)"
}

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

private val ITEM_FIELD = Regex("""^items\[(\d+)]""")

/** The row index a 400 named (`items[2].total` -> 2), or null when it named something else. */
internal fun itemIndexOf(field: String?): Int? =
    field?.let {
        ITEM_FIELD
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

private const val MAX_QTY_TEXT = 12
private const val MAX_AMOUNT_TEXT = 20

/** A form's items as sent: blank rows dropped; keys = the row behind each item, badKeys = rows that do not parse. */
internal class ParsedItems(
    val items: List<ItemInput>,
    val keys: List<Long>,
    val badKeys: Set<Long>,
)

internal fun parseItems(
    rows: List<ItemRow>,
    currency: String,
): ParsedItems {
    val items = mutableListOf<ItemInput>()
    val keys = mutableListOf<Long>()
    val bad = mutableSetOf<Long>()
    for (row in rows) {
        if (row.name.isBlank() && row.qty.isBlank() && row.amount.isBlank()) continue
        val name = cleanItemName(row.name)
        val qty = if (row.qty.isBlank()) null else parseQty(row.qty)
        val amount = if (row.amount.isBlank()) null else parseItemAmount(row.amount)?.takeIf { it <= MAX_AMOUNT_MINOR }
        val qtyBad = row.qty.isNotBlank() && qty == null
        val amountBad = row.amount.isNotBlank() && amount == null
        if (name == null || qtyBad || amountBad) {
            bad += row.key
        } else {
            items += ItemInput(name, qty, amount?.let { Money(it, currency) })
            keys += row.key
        }
    }
    return ParsedItems(items, keys, bad)
}

/** The sum of the item amounts that parse, for the informational "Items total"; saturates rather than overflowing. */
fun itemsTotalMinor(rows: List<ItemRow>): Long =
    rows.fold(0L) { acc, row ->
        val v = if (row.amount.isBlank()) 0L else parseItemAmount(row.amount)?.takeIf { it <= MAX_AMOUNT_MINOR } ?: 0L
        if (acc > Long.MAX_VALUE - v) Long.MAX_VALUE else acc + v
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
        private val receipts: ReceiptRemote,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val id: String = savedState.get<String>("id").orEmpty()
        private val form = MutableStateFlow(EditFormState())
        private var original: ExpenseEntity? = null
        private var inFlight = false
        private var originalItems: List<ItemInput> = emptyList()
        private var nextKey = 0L
        private var sentKeys: List<Long> = emptyList()

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
                    loadItems()
                }
            }
        }

        /** Fetches the expense's items from the server; the rest of the form works meanwhile. Items are only ever sent once loaded. */
        fun loadItems() {
            val o = original ?: return
            form.update { it.copy(itemsLoad = SectionLoad.LOADING) }
            viewModelScope.launch {
                try {
                    val loaded =
                        receipts.items(id).map {
                            ItemInput(
                                it.name,
                                canonicalQty(it.qty),
                                it.totalMinor?.let { m ->
                                    Money(m, o.currency)
                                },
                            )
                        }
                    originalItems = loaded
                    form.update {
                        it.copy(
                            itemsLoad = SectionLoad.LOADED,
                            items = loaded.map { item -> rowOf(item) },
                        )
                    }
                } catch (_: IOException) {
                    form.update { it.copy(itemsLoad = SectionLoad.FAILED) }
                } catch (_: HttpFailure) {
                    form.update { it.copy(itemsLoad = SectionLoad.FAILED) }
                } catch (_: SerializationException) {
                    form.update { it.copy(itemsLoad = SectionLoad.FAILED) }
                }
            }
        }

        private fun rowOf(item: ItemInput) =
            ItemRow(nextKey++, item.name, item.qty.orEmpty(), item.total?.let { plainAmount(it.amountMinor) }.orEmpty())

        fun addItem() =
            edit { f ->
                if (f.itemsLoad != SectionLoad.LOADED || f.items.size >= MAX_ITEMS) f else f.copy(items = f.items + ItemRow(nextKey++))
            }

        fun removeItem(key: Long) = edit { f -> f.copy(items = f.items.filterNot { it.key == key }) }

        fun onItemName(
            key: Long,
            text: String,
        ) = editRow(key) { it.copy(name = text.takeCodePoints(MAX_ITEM_NAME)) }

        fun onItemQty(
            key: Long,
            text: String,
        ) = editRow(key) { it.copy(qty = text.take(MAX_QTY_TEXT)) }

        fun onItemAmount(
            key: Long,
            text: String,
        ) = editRow(key) { it.copy(amount = text.take(MAX_AMOUNT_TEXT)) }

        private fun editRow(
            key: Long,
            block: (ItemRow) -> ItemRow,
        ) = edit { f -> f.copy(items = f.items.map { if (it.key == key) block(it).copy(invalid = false) else it }) }

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
            val parsed = parseItems(f.items, o.currency)
            if (parsed.badKeys.isNotEmpty()) {
                form.update { cur ->
                    cur.copy(error = EditError.INVALID, items = cur.items.map { it.copy(invalid = it.key in parsed.badKeys) })
                }
                return
            }
            val sendItems = parsed.items.takeIf { f.itemsLoad == SectionLoad.LOADED && it != originalItems }
            sentKeys = if (sendItems != null) parsed.keys else emptyList()
            val changes = changesOf(o, f, minor, date).copy(items = sendItems)
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
                        val keep = form.value
                        form.value =
                            latest.toForm().copy(changedElsewhere = true, busy = true, itemsLoad = keep.itemsLoad, items = keep.items)
                    }
                }
                is EditOutcome.Invalid -> {
                    val bad = itemIndexOf(outcome.field)?.let { sentKeys.getOrNull(it) }
                    form.update {
                        it.copy(
                            items =
                                if (bad ==
                                    null
                                ) {
                                    it.items
                                } else {
                                    it.items.map { row -> if (row.key == bad) row.copy(invalid = true) else row }
                                },
                            error = EditError.INVALID,
                            amountInvalid = outcome.field == "amount" || outcome.field?.startsWith("amount.") == true,
                            dateInvalid = outcome.field == "occurredAt",
                        )
                    }
                }
            }
        }
    }
