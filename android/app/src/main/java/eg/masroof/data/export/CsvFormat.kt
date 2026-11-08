package eg.masroof.data.export

import java.math.BigDecimal
import java.math.BigInteger

/**
 * CSV cell rules of the export (REQ-EXP-048, REQ-SEC-069): RFC 4180 quoting, and a formula guard for every text cell so a
 * label that came from an SMS can never run as a spreadsheet formula. Money is built from minor units with no floating point.
 */
object CsvFormat {
    val HEADER =
        listOf(
            "date_cairo",
            "time_cairo",
            "occurred_at_utc",
            "type",
            "status",
            "amount",
            "currency",
            "category",
            "parent_category",
            "merchant",
            "account",
            "paid_by",
            "notes",
            "sources",
            "receipts",
        )

    private const val MINOR_SCALE = 2
    private const val FULL_WIDTH_EQUALS = 0xFF1D
    private const val FULL_WIDTH_PLUS = 0xFF0B
    private const val FULL_WIDTH_MINUS = 0xFF0D
    private const val FULL_WIDTH_AT = 0xFF20
    private const val NBSP = 0xA0
    private const val ZERO_WIDTH_FROM = 0x200B
    private const val DIRECTION_MARK_TO = 0x200F
    private const val BOM_CHAR = 0xFEFF

    /** Characters a spreadsheet reads as the start of a formula, full-width forms included. */
    private val FORMULA_CODES =
        setOf('='.code, '+'.code, '-'.code, '@'.code, FULL_WIDTH_EQUALS, FULL_WIDTH_PLUS, FULL_WIDTH_MINUS, FULL_WIDTH_AT)

    /** Characters after which a spreadsheet may start a new cell: a list separator (; or ,), a tab, or a line break. */
    private val CELL_BREAKS = charArrayOf(';', ',', '\t', '\r', '\n')

    private val LEADING_CONTROLS = charArrayOf('\t', '\r')

    /**
     * A free-text cell: every place where a cell could start (the beginning, and after a separator or line break) that is followed,
     * past spaces and invisible characters, by a formula character gets a leading quote (apostrophe); then it is quoted if needed.
     */
    fun text(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val out = StringBuilder(value.length + 2)
        var guarded = false
        for (i in value.indices) {
            if (needsGuard(value, i)) {
                out.append('\'')
                guarded = true
            }
            out.append(value[i])
        }
        val body = out.toString()
        return if (guarded || needsQuotes(body)) quote(body) else body
    }

    private fun needsGuard(
        s: String,
        i: Int,
    ): Boolean {
        if (i == 0 && s[0] in LEADING_CONTROLS) return true // a leading tab or carriage return is guarded whatever follows
        return (i == 0 || s[i - 1] in CELL_BREAKS) && formulaFollows(s, i)
    }

    private fun formulaFollows(
        s: String,
        from: Int,
    ): Boolean {
        var j = from
        while (j < s.length && skippable(s[j])) j++
        return j < s.length && s[j].code in FORMULA_CODES
    }

    private fun skippable(c: Char) =
        c.isWhitespace() || c.code == NBSP || c.code in ZERO_WIDTH_FROM..DIRECTION_MARK_TO || c.code == BOM_CHAR

    /** A cell built by the app itself (dates, codes, numbers): RFC 4180 quoting only. */
    fun plain(value: String): String = if (needsQuotes(value)) quote(value) else value

    /** `1450.00` from 145000 minor units; the sign is dropped (a refund is a positive amount with type REFUND). */
    fun amount(minor: Long): String = BigDecimal(BigInteger.valueOf(minor).abs(), MINOR_SCALE).toPlainString()

    fun record(cells: List<String>): String = cells.joinToString(",") + "\r\n"

    private fun needsQuotes(s: String) = s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
