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

    /** "Didn't catch that": silence, an error or an empty result. Nothing was filled. */
    data object Error : VoiceUi
}

/** What the screen knows about RECORD_AUDIO when the mic is tapped. */
enum class MicPermission { GRANTED, SHOW_RATIONALE, ASK }

/** The currencies the Add form offers (REQ-EXP-052), EGP first. */
val ADD_CURRENCIES = listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP", "KWD", "QAR")

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

    fun apply(
        form: AddFormState,
        draft: EntryDraft,
        categoryId: String?,
        today: LocalDate,
    ): AddFormState {
        val income = draft.type == EntryType.INCOME
        val minor = draft.amountMinor
        val changed = mutableSetOf<AddField>()
        if (minor != null) changed += AddField.AMOUNT
        if (income) changed += AddField.TYPE
        if (draft.currencyStated) changed += AddField.CURRENCY
        if (draft.dateStated) changed += AddField.DATE
        if (categoryId != null) changed += AddField.CATEGORY
        if (draft.merchant != null) changed += AddField.MERCHANT
        if (draft.note != null) changed += AddField.NOTES
        val amountText = if (minor != null) amountText(minor) else form.amountText
        return form.copy(
            amountText = amountText,
            amountInvalid = false,
            amountTooLarge = false,
            amountMissing = minor == null && amountText.isBlank(),
            income = form.income || income,
            currency = if (draft.currencyStated) draft.currency else form.currency,
            dateText = if (draft.dateStated) draft.date.toString() else form.dateText.ifBlank { today.toString() },
            dateInvalid = false,
            categoryId = if (income || form.income) null else categoryId ?: form.categoryId,
            merchant = draft.merchant ?: form.merchant,
            notes = draft.note?.take(MAX_QUICK_NOTES) ?: form.notes,
            quickText = "",
            highlight = changed,
        )
    }
}
