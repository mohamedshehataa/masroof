package eg.masroof.data.wealth

import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.AccountEntity
import java.time.LocalDate

/** The result of checking a form: the draft when everything is fine, otherwise the fields that are wrong. */
data class Checked<T, F>(
    val value: T?,
    val errors: Set<F>,
)

enum class AccountField { LABEL, LAST4, DUPLICATE, LIMIT, STATEMENT_DAY, DUE_DAY }

enum class AssetField { NAME, VALUE, PRINCIPAL, RATE, START, MATURITY }

enum class DebtField { NAME, AMOUNT, DUE }

enum class BalanceField { AMOUNT, DATE }

object AccountKinds {
    const val DEBIT = "DEBIT"
    const val CREDIT_CARD = KIND_CREDIT_CARD
    const val CASH = "CASH"
    const val WALLET = "E_WALLET"
    val all = listOf(DEBIT, CREDIT_CARD, CASH, WALLET)
}

val MANUAL_ASSET_KINDS = listOf("PROPERTY", "VEHICLE", "BUSINESS", "OTHER")
val ASSET_KINDS = MANUAL_ASSET_KINDS + listOf("CERTIFICATE", "FUND")

data class AccountForm(
    val label: String = "",
    val kind: String = AccountKinds.DEBIT,
    val institution: String = "",
    val last4: String = "",
    val currency: String = Money.EGP,
    val emoji: String = "",
    val limit: String = "",
    val statementDay: String = "",
    val dueDay: String = "",
)

data class AccountDraft(
    val label: String,
    val kind: String,
    val institution: String?,
    val last4: String?,
    val currency: String,
    val emoji: String?,
    val limitMinor: Long?,
    val statementDay: Int?,
    val dueDay: Int?,
)

data class AssetForm(
    val kind: String = "PROPERTY",
    val name: String = "",
    val value: String = "",
    val principal: String = "",
    /** Percent a year, e.g. `18.5`. */
    val rate: String = "",
    val start: String = "",
    val maturity: String = "",
)

data class AssetDraft(
    val kind: String,
    val name: String,
    val valueMinor: Long?,
    val principalMinor: Long?,
    val rateBp: Int?,
    val startAtMillis: Long?,
    val maturityAtMillis: Long?,
)

data class DebtForm(
    val name: String = "",
    val owedToMe: Boolean = false,
    val amount: String = "",
    val due: String = "",
)

data class DebtDraft(
    val name: String,
    val owedToMe: Boolean,
    val amountMinor: Long,
    val dueAtMillis: Long?,
)

object WealthValidation {
    private const val MAX_RATE_BP = 100_000
    private const val LAST4 = 4
    private const val MAX_DAY = 31
    private val RATE = Regex("""\d{1,4}([.,]\d{1,2})?""")

    /** Arabic-Indic digits and separators typed by the user, as Western ones. */
    fun western(text: String): String =
        text
            .trim()
            .map { c ->
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    '٫' -> '.'
                    '٬', '،' -> ','
                    else -> c
                }
            }.joinToString("")

    fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(western(text)) }.getOrNull()

    /** A signed balance: `12,500.50`, `-300`, `0`. Null when it is not an amount. */
    fun parseBalance(text: String): Long? {
        val t = western(text)
        val negative = t.startsWith("-")
        val body = t.removePrefix("-").trim()
        if (isZeroText(body)) return 0L
        val minor = MoneyFormat.parseMinor(body) ?: return null
        return if (negative) -minor else minor
    }

    /** Percent text to basis points: `18.5` is 1850. Null when it is not a percent. */
    fun parseRateBp(text: String): Int? {
        val t = western(text)
        if (!RATE.matches(t)) return null
        val parts = t.replace(',', '.').split('.')
        val bp = parts[0].toInt() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toInt()
        return bp.takeIf { it in 0..MAX_RATE_BP }
    }

    private fun isZeroText(body: String): Boolean = body.isNotEmpty() && body.any { it == '0' } && body.all { it in "0.," }

    private fun dayOrNull(text: String): Int? = western(text).toIntOrNull()?.takeIf { it in 1..MAX_DAY }

    private fun last4Valid(
        last4: String,
        kind: String,
    ): Boolean = (last4.length == LAST4 && last4.all { it in '0'..'9' }) || (last4.isEmpty() && kind == AccountKinds.CASH)

    private fun isDuplicate(
        form: AccountForm,
        last4: String,
        existing: List<AccountEntity>,
        editingId: String?,
    ): Boolean = last4.isNotEmpty() && existing.any { it.id != editingId && !it.archived && it.kind == form.kind && it.last4 == last4 }

    private class CardFields(
        val limit: Long? = null,
        val statement: Int? = null,
        val due: Int? = null,
        val errors: Set<AccountField> = emptySet(),
    )

    /** The optional credit-card fields: a blank one is fine, a filled one must be valid. */
    private fun cardFields(form: AccountForm): CardFields {
        val limit = MoneyFormat.parseMinor(form.limit)
        val statement = dayOrNull(form.statementDay)
        val due = dayOrNull(form.dueDay)
        val errors =
            buildSet {
                if (form.limit.isNotBlank() && limit == null) add(AccountField.LIMIT)
                if (form.statementDay.isNotBlank() && statement == null) add(AccountField.STATEMENT_DAY)
                if (form.dueDay.isNotBlank() && due == null) add(AccountField.DUE_DAY)
            }
        return CardFields(limit, statement, due, errors)
    }

    fun account(
        form: AccountForm,
        existing: List<AccountEntity>,
        editingId: String? = null,
    ): Checked<AccountDraft, AccountField> {
        val errors = mutableSetOf<AccountField>()
        val label = form.label.trim()
        if (label.isEmpty()) errors += AccountField.LABEL
        val last4 = western(form.last4)
        if (!last4Valid(last4, form.kind)) {
            errors += AccountField.LAST4
        } else if (isDuplicate(form, last4, existing, editingId)) {
            errors += AccountField.DUPLICATE
        }
        val card = if (form.kind == AccountKinds.CREDIT_CARD) cardFields(form) else CardFields()
        errors += card.errors
        val (limit, statement, due) = Triple(card.limit, card.statement, card.due)
        if (errors.isNotEmpty()) return Checked(null, errors)
        return Checked(
            AccountDraft(
                label,
                form.kind,
                form.institution.trim().ifEmpty { null },
                last4.ifEmpty { null },
                form.currency
                    .trim()
                    .uppercase()
                    .ifEmpty { Money.EGP },
                form.emoji.trim().ifEmpty { null },
                limit,
                statement,
                due,
            ),
            emptySet(),
        )
    }

    fun asset(form: AssetForm): Checked<AssetDraft, AssetField> {
        val errors = mutableSetOf<AssetField>()
        val name = form.name.trim()
        if (name.isEmpty()) errors += AssetField.NAME
        var value: Long? = null
        var principal: Long? = null
        var rate: Int? = null
        var start: LocalDate? = null
        var maturity: LocalDate? = null
        if (form.kind in MANUAL_ASSET_KINDS) {
            value = MoneyFormat.parseMinor(form.value)
            if (value == null) errors += AssetField.VALUE
        } else {
            principal = MoneyFormat.parseMinor(form.principal)
            if (principal == null) errors += AssetField.PRINCIPAL
            rate = parseRateBp(form.rate)
            if (rate == null) errors += AssetField.RATE
            start = parseDate(form.start)
            if (start == null) errors += AssetField.START
            if (form.kind == "CERTIFICATE") {
                maturity = parseDate(form.maturity)
                if (maturity == null || (start != null && maturity < start)) errors += AssetField.MATURITY
            }
        }
        if (errors.isNotEmpty()) return Checked(null, errors)
        return Checked(
            AssetDraft(
                form.kind,
                name,
                value,
                principal,
                rate,
                start?.let { CairoTime.noonOf(it).toEpochMilli() },
                maturity?.let { CairoTime.noonOf(it).toEpochMilli() },
            ),
            emptySet(),
        )
    }

    fun debt(form: DebtForm): Checked<DebtDraft, DebtField> {
        val errors = mutableSetOf<DebtField>()
        val name = form.name.trim()
        if (name.isEmpty()) errors += DebtField.NAME
        val amount = (MoneyFormat.parseEntry(form.amount) as? MoneyFormat.EntryAmount.Valid)?.minor
        if (amount == null) errors += DebtField.AMOUNT
        val due = if (form.due.isBlank()) null else parseDate(form.due)
        if (form.due.isNotBlank() && due == null) errors += DebtField.DUE
        if (errors.isNotEmpty() || amount == null) return Checked(null, errors)
        return Checked(DebtDraft(name, form.owedToMe, amount, due?.let { CairoTime.noonOf(it).toEpochMilli() }), emptySet())
    }
}
