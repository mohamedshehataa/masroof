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
    private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** A free-text cell: guarded against formula injection, then quoted if needed. */
    fun text(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val guarded = value[0] in FORMULA_STARTS
        val body = if (guarded) "'$value" else value
        return if (guarded || needsQuotes(body)) quote(body) else body
    }

    /** A cell built by the app itself (dates, codes, numbers): RFC 4180 quoting only. */
    fun plain(value: String): String = if (needsQuotes(value)) quote(value) else value

    /** `1450.00` from 145000 minor units; the sign is dropped (a refund is a positive amount with type REFUND). */
    fun amount(minor: Long): String = BigDecimal(BigInteger.valueOf(minor).abs(), MINOR_SCALE).toPlainString()

    fun record(cells: List<String>): String = cells.joinToString(",") + "\r\n"

    private fun needsQuotes(s: String) = s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
