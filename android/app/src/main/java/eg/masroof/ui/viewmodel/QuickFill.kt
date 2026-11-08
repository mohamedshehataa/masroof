package eg.masroof.ui.viewmodel

import eg.masroof.ingest.quick.EntryDraft
import eg.masroof.ingest.quick.EntryType
import java.time.LocalDate

/** The form fields a spoken or typed line can fill; they are highlighted for a moment so the user checks them. */
enum class AddField { AMOUNT, DATE, CATEGORY, MERCHANT, NOTES, TYPE, CURRENCY }

/** Where the mic flow stands (REQ-AND-144). Only [Listening] means the recognizer is running. */
sealed interface VoiceUi {
    data object Idle : VoiceUi

    /** Permission was refused before: a short reason first, then the system dialog. */
    data object Rationale : VoiceUi

    /** The system permission dialog is up. */
    data object Requesting : VoiceUi

    class Listening(
        val level: Int = 0,
    ) : VoiceUi

    data object Processing : VoiceUi

    /** Refused; the system dialog can still be shown next time. */
    data object Denied : VoiceUi

    /** Refused with "don't ask again": only Settings can turn the microphone on. */
    data object DeniedForever : VoiceUi

    /** Before the first mic tap on a phone with no on-device recognizer: its speech service may be online. */
    class OnlineNotice(
        val then: MicPermission,
    ) : VoiceUi

    /** "Didn't catch that": silence, an error or an empty result. Nothing was filled. */
    data object Error : VoiceUi
}

/** What the screen knows about RECORD_AUDIO when the mic is tapped. */
enum class MicPermission { GRANTED, SHOW_RATIONALE, ASK }

/** The currencies the Add form offers (REQ-EXP-052), EGP first. */
val ADD_CURRENCIES = listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP", "QAR")

private const val MAX_QUICK_NOTES = 500
private const val MINOR_PER_MAJOR = 100L

/** Turns a parsed line into form fields. Pure: nothing is saved here, the user still reviews and taps Save. */
internal object QuickFill {
    /** `1250` or `85.50`: a plain amount the amount field reads back exactly (Western digits, no grouping). */
    fun amountText(minor: Long): String {
        val major = minor / MINOR_PER_MAJOR
        val rest = minor % MINOR_PER_MAJOR
        return if (rest == 0L) major.toString() else "$major.${rest.toString().padStart(2, '0')}"
    }

    private fun changedFields(
        form: AddFormState,
        draft: EntryDraft,
        categoryId: String?,
        nowIncome: Boolean,
        noteApplied: Boolean,
    ): Set<AddField> =
        buildSet {
            if (draft.amountMinor != null) add(AddField.AMOUNT)
            if (nowIncome != form.income) add(AddField.TYPE)
            if (draft.currencyStated) add(AddField.CURRENCY)
            if (draft.dateStated) add(AddField.DATE)
            if (categoryId != null) add(AddField.CATEGORY)
            if (draft.merchant != null) add(AddField.MERCHANT)
            if (noteApplied) add(AddField.NOTES)
        }

    fun apply(
        form: AddFormState,
        line: String,
        draft: EntryDraft,
        categoryId: String?,
        today: LocalDate,
    ): AddFormState {
        val income = draft.type == EntryType.INCOME
        val minor = draft.amountMinor
        // "paid"/"received" or a category word decides the type; a bare "50 lunch" keeps what the user chose.
        val nowIncome =
            if (income) {
                true
            } else if (draft.categoryKey != null || draft.typeStated) {
                false
            } else {
                form.income
            }
        // The user's own note is never replaced by words from a two-number line.
        val noteApplied = draft.note != null && form.notes.isBlank()
        val changed = changedFields(form, draft, categoryId, nowIncome, noteApplied)
        val amountText = if (minor != null) amountText(minor) else form.amountText
        return form.copy(
            amountText = amountText,
            amountInvalid = false,
            amountTooLarge = false,
            amountMissing = minor == null && amountText.isBlank(),
            income = nowIncome,
            currency = if (draft.currencyStated) draft.currency else form.currency,
            dateText = if (draft.dateStated) draft.date.toString() else form.dateText.ifBlank { today.toString() },
            dateInvalid = false,
            categoryId = if (nowIncome) null else categoryId ?: form.categoryId,
            merchant = draft.merchant ?: form.merchant,
            notes = if (noteApplied) draft.note.orEmpty().take(MAX_QUICK_NOTES) else form.notes,
            // The heard or typed line stays in the field, editable; Done parses it again.
            quickText = line,
            highlight = changed,
        )
    }
}
