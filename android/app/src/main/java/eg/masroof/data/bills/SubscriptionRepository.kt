package eg.masroof.data.bills

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.data.db.seededCategoryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The built-in service list: names and a generic emoji per kind only, no brand logos. [merchant] is the entry key it matches. */
enum class SubscriptionService(
    val key: String,
    val emoji: String,
    val kind: String,
    val categoryKey: String,
    val merchant: String?,
    /** Extra raw merchant spellings that also pay this service (normalised with [SubscriptionMatcher.keyOf]). */
    val aliases: List<String> = emptyList(),
) {
    NETFLIX("netflix", "🎬", KIND_SUBSCRIPTION, "subscriptions", "netflix"),
    SHAHID("shahid", "📺", KIND_SUBSCRIPTION, "subscriptions", "shahid"),
    WATCH_IT("watch_it", "🎬", KIND_SUBSCRIPTION, "subscriptions", "watch it"),
    OSN("osn", "📺", KIND_SUBSCRIPTION, "subscriptions", "osn+"),
    SPOTIFY("spotify", "🎧", KIND_SUBSCRIPTION, "subscriptions", "spotify"),
    ANGHAMI("anghami", "🎧", KIND_SUBSCRIPTION, "subscriptions", "anghami"),
    YOUTUBE("youtube", "▶️", KIND_SUBSCRIPTION, "subscriptions", "youtube premium"),
    ICLOUD("icloud", "☁️", KIND_SUBSCRIPTION, "subscriptions", "icloud", listOf("apple.com/bill", "icloud")),
    GOOGLE_ONE("google_one", "☁️", KIND_SUBSCRIPTION, "subscriptions", "google one"),
    CHATGPT("chatgpt", "🤖", KIND_SUBSCRIPTION, "subscriptions", "chatgpt"),
    WE("we", "🌐", KIND_BILL, "bills.internet", "we", listOf("we internet", "telecom egypt")),
    VODAFONE("vodafone", "📱", KIND_BILL, "bills.mobile", "vodafone"),
    ORANGE("orange", "📱", KIND_BILL, "bills.mobile", "orange"),
    ETISALAT("etisalat", "📱", KIND_BILL, "bills.mobile", "etisalat", listOf("e and", "e&")),
    GYM("gym", "💪", KIND_SUBSCRIPTION, "subscriptions", null),
    OTHER("other", "🔁", KIND_SUBSCRIPTION, "subscriptions", null),
    ;

    val categoryId: String get() = seededCategoryId(categoryKey)

    companion object {
        fun of(key: String?): SubscriptionService? = entries.firstOrNull { it.key == key }

        /** The normalised alias keys of the built-in service whose merchant key is [rowKey] (none for a typed service). */
        fun aliasKeysFor(rowKey: String): List<String> =
            entries
                .firstOrNull { SubscriptionMatcher.keyOf(it.merchant) == rowKey }
                ?.aliases
                .orEmpty()
                .mapNotNull { SubscriptionMatcher.keyOf(it) }
    }
}

/** The recurring-income presets (the kind INCOME form offers these instead of the service list). */
enum class IncomePreset(
    val key: String,
) {
    SALARY("salary"),
    CERTIFICATE("certificate"),
    OTHER("income_other"),
}

/** Everything needed to store or change a subscription row. */
data class NewSubscription(
    val name: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val cadence: Cadence,
    val nextRenewal: LocalDate,
    val categoryId: String? = null,
    val accountId: String? = null,
    val merchantKey: String? = null,
    val reminderDaysBefore: Int = DEFAULT_SUBSCRIPTION_REMINDER_DAYS,
)

const val DEFAULT_SUBSCRIPTION_REMINDER_DAYS = 1

/** "Netflix went up from 199 to 229": shown until dismissed, so it appears once. */
data class PriceNotice(
    val subscriptionId: String,
    val name: String,
    val fromMinor: Long,
    val toMinor: Long,
    val currency: String,
    /** The entry that paid and the renewal day it paid, so "Not this one" can undo it. */
    val entryId: String = "",
    val renewalEpochDay: Long = 0L,
)

/** What the Bills screen reads: the rows, the recent entries to match and suggest from, and the remembered choices. */
data class SubscriptionsData(
    val rows: List<SubscriptionEntity>,
    val entries: List<EntryFact>,
    val dismissed: Set<String>,
    val notices: List<PriceNotice>,
    /** The real day of month each row renews on (a row without one falls back to [RenewalMath.anchorOf]). */
    val anchors: Map<String, Int> = emptyMap(),
)

/** Dismissed suggestion keys and price-change notices; DataStore in the app. */
interface SubscriptionPrefs {
    val dismissedKeys: Flow<Set<String>>
    val notices: Flow<List<PriceNotice>>

    /** Subscription id to its anchor day of month; the schema has no column for it, so it lives here (include in the backup). */
    val anchors: Flow<Map<String, Int>>

    suspend fun dismissKey(key: String)

    suspend fun setAnchor(
        id: String,
        day: Int,
    )

    suspend fun removeAnchor(id: String)

    /** "Erase all data": forgets dismissed keys, notices and anchors. */
    suspend fun clear()

    suspend fun addNotice(notice: PriceNotice)

    suspend fun clearNotice(subscriptionId: String)
}

/** For code paths that have no subscription prefs. */
object NoSubscriptionPrefs : SubscriptionPrefs {
    override val dismissedKeys: Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())
    override val notices: Flow<List<PriceNotice>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override val anchors: Flow<Map<String, Int>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    override suspend fun dismissKey(key: String) = Unit

    override suspend fun addNotice(notice: PriceNotice) = Unit

    override suspend fun clearNotice(subscriptionId: String) = Unit

    override suspend fun setAnchor(
        id: String,
        day: Int,
    ) = Unit

    override suspend fun removeAnchor(id: String) = Unit

    override suspend fun clear() = Unit
}

private val Context.subscriptionStore: DataStore<Preferences> by preferencesDataStore(name = "subscriptions")

@Singleton
class DataStoreSubscriptionPrefs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SubscriptionPrefs {
        private val dismissedKey = stringSetPreferencesKey("dismissed_suggestions")
        private val noticesKey = stringSetPreferencesKey("price_notices")

        override val dismissedKeys: Flow<Set<String>> = context.subscriptionStore.data.map { it[dismissedKey].orEmpty() }

        override val notices: Flow<List<PriceNotice>> =
            context.subscriptionStore.data.map { p -> p[noticesKey].orEmpty().mapNotNull(::decode).sortedBy { it.subscriptionId } }

        private val anchorsKey = stringSetPreferencesKey("anchor_days")

        override val anchors: Flow<Map<String, Int>> =
            context.subscriptionStore.data.map { p ->
                p[anchorsKey]
                    .orEmpty()
                    .mapNotNull { e ->
                        e.substringAfterLast(':').toIntOrNull()?.let { e.substringBeforeLast(':') to it }
                    }.toMap()
            }

        override suspend fun setAnchor(
            id: String,
            day: Int,
        ) {
            context.subscriptionStore.edit { p ->
                p[anchorsKey] = p[anchorsKey].orEmpty().filterNot { it.substringBeforeLast(':') == id }.toSet() + "$id:$day"
            }
        }

        override suspend fun removeAnchor(id: String) {
            context.subscriptionStore.edit { p ->
                p[anchorsKey] = p[anchorsKey].orEmpty().filterNot { it.substringBeforeLast(':') == id }.toSet()
            }
        }

        override suspend fun clear() {
            context.subscriptionStore.edit { it.clear() }
        }

        override suspend fun dismissKey(key: String) {
            context.subscriptionStore.edit { it[dismissedKey] = it[dismissedKey].orEmpty() + key }
        }

        override suspend fun addNotice(notice: PriceNotice) {
            context.subscriptionStore.edit { p ->
                val kept = p[noticesKey].orEmpty().filterNot { decode(it)?.subscriptionId == notice.subscriptionId }
                p[noticesKey] = (kept + encode(notice)).toSet()
            }
        }

        override suspend fun clearNotice(subscriptionId: String) {
            context.subscriptionStore.edit { p ->
                p[noticesKey] = p[noticesKey].orEmpty().filterNot { decode(it)?.subscriptionId == subscriptionId }.toSet()
            }
        }

        private fun encode(n: PriceNotice) =
            listOf(n.subscriptionId, n.name.replace(SEP, ' '), n.fromMinor, n.toMinor, n.currency, n.entryId, n.renewalEpochDay)
                .joinToString(SEP.toString())

        private fun decode(text: String): PriceNotice? {
            val p = text.split(SEP)
            if (p.size != NOTICE_FIELDS) return null
            val from = p[2].toLongOrNull() ?: return null
            val to = p[3].toLongOrNull() ?: return null
            return PriceNotice(p[0], p[1], from, to, p[4], p[5], p[6].toLongOrNull() ?: 0L)
        }

        private companion object {
            const val SEP = '\t'
            const val NOTICE_FIELDS = 7
        }
    }

interface SubscriptionRepository {
    fun observeAll(): Flow<List<SubscriptionEntity>>

    fun observe(id: String): Flow<SubscriptionEntity?>

    /** The rows with the entries of the last 150 days and the remembered choices. */
    fun observeData(): Flow<SubscriptionsData>

    suspend fun create(new: NewSubscription): String

    suspend fun update(
        id: String,
        new: NewSubscription,
    ): Boolean

    suspend fun setActive(
        id: String,
        active: Boolean,
    )

    suspend fun delete(id: String)

    /** Marks renewals paid by matching entries, advances their dates and rolls past renewals forward. */
    suspend fun settle(today: LocalDate)

    suspend fun dismissSuggestion(key: String)

    suspend fun dismissNotice(subscriptionId: String)

    /** "Not this one": restores the amount and renewal before the match and never matches that entry again. */
    suspend fun rejectNotice(subscriptionId: String)
}

@Singleton
class DefaultSubscriptionRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val prefs: SubscriptionPrefs,
        private val settings: SettingsRepository,
    ) : SubscriptionRepository,
        PaydayProvider {
        private val dao get() = db.subscriptions()

        override fun observeAll() = dao.observeAll()

        override fun observe(id: String) = dao.observeById(id)

        override fun observeData(): Flow<SubscriptionsData> {
            val since = sinceMillis(CairoTime.today(clock.instant()))
            return combine(dao.observeAll(), dao.observeEntriesSince(since), prefs.dismissedKeys, prefs.notices, prefs.anchors) {
                rows,
                entries,
                dismissed,
                notices,
                anchors,
                ->
                val live = rows.map { it.id }.toSet()
                SubscriptionsData(rows, entries.mapNotNull(::factOf), dismissed, notices.filter { it.subscriptionId in live }, anchors)
            }
        }

        private fun todayCairo() = CairoTime.today(clock.instant())

        override suspend fun create(new: NewSubscription): String {
            val id = ids.next()
            dao.insert(new.toEntity(id, clock.instant().toEpochMilli()))
            prefs.setAnchor(id, new.nextRenewal.dayOfMonth)
            settle(todayCairo())
            return id
        }

        override suspend fun update(
            id: String,
            new: NewSubscription,
        ): Boolean {
            val old = dao.find(id) ?: return false
            dao.update(new.toEntity(id, old.createdAtMillis).copy(active = old.active, notes = old.notes))
            // The anchor changes only when the date was edited: an untouched, clamped date (28 Feb) must not replace the real day (30).
            if (CairoTime.dateOf(old.nextRenewalAtMillis) != new.nextRenewal) prefs.setAnchor(id, new.nextRenewal.dayOfMonth)
            settle(todayCairo())
            return true
        }

        override suspend fun setActive(
            id: String,
            active: Boolean,
        ) {
            val row = dao.find(id) ?: return
            if (!active) {
                dao.update(row.copy(active = false))
                return
            }
            // Resumed: skip the renewals missed while paused, and match only entries from now on.
            val today = todayCairo()
            val date = CairoTime.dateOf(row.nextRenewalAtMillis)
            val anchor = prefs.anchors.first()[id] ?: RenewalMath.anchorOf(date)
            val next = RenewalMath.rollTo(date, Cadence.of(row.cadence), today, anchor)
            dao.update(row.copy(active = true, nextRenewalAtMillis = CairoTime.noonOf(next).toEpochMilli()))
            // Entries from before the resume never pay it, also not in a later pass (kept in the anchors store as "since:<id>").
            prefs.setAnchor("$SINCE_PREFIX$id", Math.toIntExact(today.toEpochDay()))
            settleRows(today, id)
        }

        override suspend fun delete(id: String) {
            dao.find(id)?.let { dao.delete(it) }
            prefs.clearNotice(id)
            prefs.removeAnchor(id)
            prefs.removeAnchor("$SINCE_PREFIX$id")
        }

        override suspend fun settle(today: LocalDate) = settleRows(today, null)

        private suspend fun settleRows(
            today: LocalDate,
            onlyId: String?,
        ) {
            val changes = mutableListOf<PriceNotice>()
            val anchors = prefs.anchors.first()
            db.withTransaction {
                val rows = dao.all().filter { it.active && (onlyId == null || it.id == onlyId) }
                val earliest = rows.minOfOrNull { CairoTime.dateOf(it.nextRenewalAtMillis) } ?: return@withTransaction
                val ignored = prefs.dismissedKeys.first()
                val entries =
                    dao
                        .entriesSince(sinceMillis(minOf(earliest, today)))
                        .mapNotNull(::factOf)
                        .filter { "$IGNORED_ENTRY_PREFIX${it.id}" !in ignored }
                settleAll(rows, entries, today, anchors, changes)
            }
            changes.forEach { prefs.addNotice(it) }
        }

        private class RowState(
            var row: SubscriptionEntity,
            var date: LocalDate,
            val anchor: Int,
            val pool: List<EntryFact>,
        )

        /** Matches every row against the entries in one global pass order (see [SubscriptionMatcher.bestMatch]), then rolls dates. */
        private suspend fun settleAll(
            rows: List<SubscriptionEntity>,
            entries: List<EntryFact>,
            today: LocalDate,
            anchors: Map<String, Int>,
            notices: MutableList<PriceNotice>,
        ) {
            val states =
                rows.associate { row ->
                    val date = CairoTime.dateOf(row.nextRenewalAtMillis)
                    val cutoff = anchors["$SINCE_PREFIX${row.id}"]?.let { LocalDate.ofEpochDay(it.toLong()) }
                    val pool = entries.filter { it.income == (row.kind == KIND_INCOME) && (cutoff == null || !it.date.isBefore(cutoff)) }
                    row.id to RowState(row, date, anchors[row.id] ?: RenewalMath.anchorOf(date), pool)
                }
            val used = mutableSetOf<String>()
            var guard = 0
            while (guard++ < MAX_MATCHES * rows.size.coerceAtLeast(1)) {
                val facts =
                    states.values.mapNotNull { st ->
                        val key = SubscriptionMatcher.keyOfRow(st.row.merchantKey, st.row.name) ?: return@mapNotNull null
                        SubscriptionMatcher.RowFacts(
                            st.row.id,
                            key,
                            st.row.amountMinor,
                            st.row.currency,
                            st.date,
                            st.pool,
                            SubscriptionService.aliasKeysFor(key),
                        )
                    }
                val (id, hit) = SubscriptionMatcher.bestMatch(facts, used) ?: break
                val st = states.getValue(id)
                used += hit.id
                if (hit.amountMinor != st.row.amountMinor) {
                    notices +=
                        PriceNotice(
                            st.row.id,
                            st.row.name,
                            st.row.amountMinor,
                            hit.amountMinor,
                            st.row.currency,
                            hit.id,
                            st.date.toEpochDay(),
                        )
                }
                st.date = RenewalMath.nextAfter(st.date, Cadence.of(st.row.cadence), st.anchor)
                st.row =
                    st.row.copy(
                        amountMinor = hit.amountMinor,
                        merchantKey =
                            st.row.merchantKey ?: SubscriptionMatcher.keyOfRow(null, st.row.name),
                    )
            }
            for ((id, st) in states) {
                val rolled = RenewalMath.rollPastGrace(st.date, Cadence.of(st.row.cadence), today, st.anchor)
                val settled = st.row.copy(nextRenewalAtMillis = CairoTime.noonOf(rolled).toEpochMilli())
                if (settled != rows.first { it.id == id }) dao.update(settled)
            }
        }

        override suspend fun dismissSuggestion(key: String) = prefs.dismissKey(key)

        override suspend fun dismissNotice(subscriptionId: String) = prefs.clearNotice(subscriptionId)

        override suspend fun rejectNotice(subscriptionId: String) {
            val notice = prefs.notices.first().firstOrNull { it.subscriptionId == subscriptionId } ?: return
            val row = dao.find(subscriptionId)
            if (row != null && notice.entryId.isNotEmpty()) {
                prefs.dismissKey("$IGNORED_ENTRY_PREFIX${notice.entryId}")
                val back = CairoTime.noonOf(LocalDate.ofEpochDay(notice.renewalEpochDay)).toEpochMilli()
                dao.update(row.copy(amountMinor = notice.fromMinor, nextRenewalAtMillis = back))
            }
            prefs.clearNotice(subscriptionId)
            settle(todayCairo())
        }

        override suspend fun daysToPayday(today: LocalDate): Payday =
            PaydayMath.next(today, dao.all(), settings.current().monthStartDay, CairoTime::dateOf, prefs.anchors.first())

        private fun sinceMillis(today: LocalDate): Long =
            today
                .minusDays(HISTORY_DAYS)
                .atStartOfDay(CairoTime.zone)
                .toInstant()
                .toEpochMilli()

        private fun factOf(e: ExpenseEntity): EntryFact? {
            val key = SubscriptionMatcher.keyOf(e.merchant) ?: return null
            return EntryFact(
                e.id,
                key,
                e.merchant.orEmpty(),
                e.amountMinor,
                e.currency,
                CairoTime.dateOf(e.occurredAtMillis),
                e.type == "INCOME",
            )
        }

        private fun NewSubscription.toEntity(
            id: String,
            createdAt: Long,
        ) = SubscriptionEntity(
            id = id,
            kind = kind,
            name = name.trim(),
            amountMinor = amountMinor,
            currency = currency,
            cadence = cadence.key,
            nextRenewalAtMillis = CairoTime.noonOf(nextRenewal).toEpochMilli(),
            categoryId = categoryId,
            accountId = accountId,
            merchantKey = merchantKey,
            reminderDaysBefore = reminderDaysBefore,
            active = true,
            notes = null,
            createdAtMillis = createdAt,
        )

        private companion object {
            const val SINCE_PREFIX = "since:"
            const val IGNORED_ENTRY_PREFIX = "entry:"
            const val HISTORY_DAYS = 150L
            const val MAX_MATCHES = 60
        }
    }
