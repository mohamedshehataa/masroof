package eg.masroof.data.export

import eg.masroof.core.analytics.BusinessCalendar
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExportRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

/** Entries of these Cairo days, both ends inclusive. */
data class ExportRange(
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * Writes the not-deleted entries of a Cairo date range as CSV (UTF-8 with BOM, CRLF), page by page so memory stays bounded.
 * Only typed entry fields are read: never SMS or notification text, review items, receipt content or a full account number.
 */
class CsvExporter internal constructor(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(db: AppDatabase) : this(db, Dispatchers.IO)

    /** Writes the file to [out] (not closed here) and returns the number of entries; throws on a write failure. */
    suspend fun export(
        range: ExportRange,
        out: OutputStream,
    ): Int =
        withContext(dispatcher) {
            val lookups = db.lookups()
            val categories = lookups.observeEveryCategory().first().associateBy { it.id }
            val accounts = lookups.observeAccounts().first().associate { it.id to it.label }
            val members = lookups.observeMembers().first().associate { it.id to it.displayName }
            val from = BusinessCalendar.startOfDay(range.from).toEpochMilli()
            val to = BusinessCalendar.endOfDayExclusive(range.to).toEpochMilli()
            val writer = out.bufferedWriter(Charsets.UTF_8)
            writer.write(BOM)
            writer.write(CsvFormat.record(CsvFormat.HEADER))
            var count = 0
            var afterMillis = Long.MIN_VALUE
            var afterId = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = db.expenses().exportPage(from, to, afterMillis, afterId, PAGE)
                for (row in page) writer.write(CsvFormat.record(cells(row, categories, accounts, members)))
                count += page.size
                if (page.size < PAGE) break
                afterMillis = page.last().expense.occurredAtMillis
                afterId = page.last().expense.id
            }
            writer.flush()
            count
        }

    private fun cells(
        row: ExportRow,
        categories: Map<String, CategoryEntity>,
        accounts: Map<String, String>,
        members: Map<String, String>,
    ): List<String> {
        val e = row.expense
        val at = Instant.ofEpochMilli(e.occurredAtMillis)
        val cairo = at.atZone(BusinessCalendar.CAIRO)
        val category = e.categoryId?.let { categories[it] }
        val parent = category?.parentId?.let { categories[it] }
        return listOf(
            CsvFormat.plain(DATE.format(cairo)),
            if (e.timeKnown) CsvFormat.plain(TIME.format(cairo)) else "",
            CsvFormat.plain(at.truncatedTo(ChronoUnit.SECONDS).toString()),
            CsvFormat.text(e.type),
            CsvFormat.text(e.status),
            CsvFormat.amount(e.amountMinor),
            CsvFormat.text(e.currency),
            CsvFormat.text(category?.let(::name)),
            CsvFormat.text(parent?.let(::name)),
            CsvFormat.text(e.merchant),
            CsvFormat.text(e.accountId?.let { accounts[it] }),
            CsvFormat.text(e.paidByMemberId?.let { members[it] }),
            CsvFormat.text(e.notes),
            CsvFormat.text(channels(row.channels)),
            row.receiptCount.toString(),
        )
    }

    private fun channels(joined: String?): String? =
        joined
            ?.split(',')
            .orEmpty()
            .distinct()
            .joinToString(";")
            .ifEmpty { null }

    /** The English name keeps the file stable for tools; a custom category typed only in Arabic falls back to it. */
    private fun name(c: CategoryEntity) = c.nameEn.ifBlank { c.nameAr }

    private companion object {
        const val PAGE = 200
        val BOM = 0xFEFF.toChar().toString()
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}
