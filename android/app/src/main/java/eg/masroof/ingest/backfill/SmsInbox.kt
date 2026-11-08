package eg.masroof.ingest.backfill

import android.content.ContentResolver
import android.provider.Telephony
import javax.inject.Inject
import javax.inject.Singleton

/** One row of the system SMS inbox. Never prints the body. */
data class InboxSms(
    val id: Long,
    val address: String,
    val body: String,
    /** Received time (`date`), the column the range is cut on and the paging order. */
    val dateMillis: Long,
    /** Sent time (`date_sent`, the service-centre stamp live capture sees); 0 when the provider has none. */
    val dateSentMillis: Long,
) {
    override fun toString() = "InboxSms(id=$id, ts=$dateMillis)"

    /** The second live capture would have used for the same message (`SmsMessage.getTimestampMillis` is the sent stamp). */
    val stampMillis: Long get() = if (dateSentMillis > 0) dateSentMillis else dateMillis
}

/** Where the next page starts: after this (date, id). Null = from the start of the range. */
data class InboxPosition(
    val dateMillis: Long,
    val id: Long,
)

/**
 * The inbox as the backfill sees it. Both calls throw [SecurityException] when `READ_SMS` is not (or no longer) granted; the engine
 * turns that into a "stopped, permission needed" state.
 */
interface SmsInbox {
    /** Calls [visit] with the sender of every inbox row received in [fromMillis, toMillis); streams, keeps nothing (no bodies). */
    fun forEachAddress(
        fromMillis: Long,
        toMillis: Long,
        visit: (String) -> Unit,
    )

    /**
     * At most [limit] rows of the range from exactly these [addresses], after [after], oldest first (date, then id). Rows of any other
     * sender are never asked for, so their text is never read. Memory is bounded by [limit] (times the address chunks, a handful).
     */
    fun page(
        fromMillis: Long,
        toMillis: Long,
        addresses: Set<String>,
        after: InboxPosition?,
        limit: Int,
    ): List<InboxSms>
}

/** `Telephony.Sms.Inbox` through the content resolver, with cursor paging. */
@Singleton
class ContentResolverSmsInbox
    @Inject
    constructor(
        private val resolver: ContentResolver,
    ) : SmsInbox {
        override fun forEachAddress(
            fromMillis: Long,
            toMillis: Long,
            visit: (String) -> Unit,
        ) {
            val cursor =
                resolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS),
                    "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?",
                    arrayOf(fromMillis.toString(), toMillis.toString()),
                    null,
                ) ?: return
            cursor.use { c -> while (c.moveToNext()) visit(c.getString(0).orEmpty()) }
        }

        override fun page(
            fromMillis: Long,
            toMillis: Long,
            addresses: Set<String>,
            after: InboxPosition?,
            limit: Int,
        ): List<InboxSms> {
            // each chunk's first [limit] rows after the position: the best [limit] of all chunks together are the global next page
            val merged = ArrayList<InboxSms>()
            for (chunk in addresses.toList().chunked(ADDRESSES_PER_QUERY)) merged += pageOf(fromMillis, toMillis, chunk, after, limit)
            return merged.sortedWith(compareBy({ it.dateMillis }, { it.id })).take(limit)
        }

        private fun pageOf(
            fromMillis: Long,
            toMillis: Long,
            addresses: List<String>,
            after: InboxPosition?,
            limit: Int,
        ): List<InboxSms> {
            // only "?" placeholders are built into the text; every value is a bound argument
            val marks = addresses.joinToString(",") { "?" }
            val args = mutableListOf(fromMillis.toString(), toMillis.toString())
            args += addresses
            var selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ? AND ${Telephony.Sms.ADDRESS} IN ($marks)"
            if (after != null) {
                selection += " AND (${Telephony.Sms.DATE} > ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} > ?))"
                args += listOf(after.dateMillis.toString(), after.dateMillis.toString(), after.id.toString())
            }
            val cursor =
                resolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.DATE_SENT,
                    ),
                    selection,
                    args.toTypedArray(),
                    "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC LIMIT $limit",
                ) ?: return emptyList()
            val rows = ArrayList<InboxSms>(limit)
            // the read stops at [limit] even if a provider ignores the LIMIT clause
            cursor.use { c ->
                while (rows.size < limit && c.moveToNext()) {
                    rows += InboxSms(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4))
                }
            }
            return rows
        }

        private companion object {
            /** SQLite allows 999 bound arguments per statement; 5 are the range and the position. */
            const val ADDRESSES_PER_QUERY = 500
        }
    }
