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
) {
    NETFLIX("netflix", "🎬", KIND_SUBSCRIPTION, "subscriptions", "netflix"),
    SHAHID("shahid", "📺", KIND_SUBSCRIPTION, "subscriptions", "shahid"),
    WATCH_IT("watch_it", "🎬", KIND_SUBSCRIPTION, "subscriptions", "watch it"),
    OSN("osn", "📺", KIND_SUBSCRIPTION, "subscriptions", "osn+"),
    SPOTIFY("spotify", "🎧", KIND_SUBSCRIPTION, "subscriptions", "spotify"),
    ANGHAMI("anghami", "🎧", KIND_SUBSCRIPTION, "subscriptions", "anghami"),
    YOUTUBE("youtube", "▶️", KIND_SUBSCRIPTION, "subscriptions", "youtube premium"),
    ICLOUD("icloud", "☁️", KIND_SUBSCRIPTION, "subscriptions", "icloud"),
    GOOGLE_ONE("google_one", "☁️", KIND_SUBSCRIPTION, "subscriptions", "google one"),
    CHATGPT("chatgpt", "🤖", KIND_SUBSCRIPTION, "subscriptions", "chatgpt"),
    WE("we", "🌐", KIND_BILL, "bills.internet", "we"),
    VODAFONE("vodafone", "📱", KIND_BILL, "bills.mobile", "vodafone"),
    ORANGE("orange", "📱", KIND_BILL, "bills.mobile", "orange"),
    ETISALAT("etisalat", "📱", KIND_BILL, "bills.mobile", "e&"),
    GYM("gym", "💪", KIND_SUBSCRIPTION, "subscriptions", null),
    OTHER("other", "🔁", KIND_SUBSCRIPTION, "subscriptions", null),
    ;

    val categoryId: String get() = seededCategoryId(categoryKey)

    companion object {
        fun of(key: String?): SubscriptionService? = entries.firstOrNull { it.key == key }
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
)

/** What the Bills screen reads: the rows, the recent entries to match and suggest from, and the remembered choices. */
data class SubscriptionsData(
    val rows: List<SubscriptionEntity>,
    val entries: List<EntryFact>,
    val dismissed: Set<String>,
    val notices: List<PriceNotice>,
)

/** Dismissed suggestion keys and price-change notices; DataStore in the app. */
interface SubscriptionPrefs {
    val dismissedKeys: Flow<Set<String>>
    val notices: Flow<List<PriceNotice>>

    suspend fun dismissKey(key: String)

    suspend fun addNotice(notice: PriceNotice)

    suspend fun clearNotice(subscriptionId: String)
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
            listOf(n.subscriptionId, n.name.replace(SEP, ' '), n.fromMinor, n.toMinor, n.currency).joinToString(SEP.toString())

        private fun decode(text: String): PriceNotice? {
            val p = text.split(SEP)
            if (p.size != NOTICE_FIELDS) return null
            val from = p[2].toLongOrNull() ?: return null
            val to = p[3].toLongOrNull() ?: return null
            return PriceNotice(p[0], p[1], from, to, p[4])
        }

        private companion object {
            const val SEP = '\t'
            const val NOTICE_FIELDS = 5
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
            return combine(
                dao.observeAll(),
                dao.observeEntriesSince(since),
                prefs.dismissedKeys,
                prefs.notices,
            ) { rows, entries, dismissed, notices ->
                SubscriptionsData(rows, entries.mapNotNull(::factOf), dismissed, notices)
            }
        }

        override suspend fun create(new: NewSubscription): String {
            val id = ids.next()
            dao.insert(new.toEntity(id, clock.instant().toEpochMilli()))
            return id
        }

        override suspend fun update(
            id: String,
            new: NewSubscription,
        ): Boolean {
            val old = dao.find(id) ?: return false
            dao.update(new.toEntity(id, old.createdAtMillis).copy(active = old.active, notes = old.notes))
            return true
        }

        override suspend fun setActive(
            id: String,
            active: Boolean,
        ) {
            dao.find(id)?.let { dao.update(it.copy(active = active)) }
        }

        override suspend fun delete(id: String) {
            dao.find(id)?.let { dao.delete(it) }
            prefs.clearNotice(id)
        }

        override suspend fun settle(today: LocalDate) {
            val changes = mutableListOf<PriceNotice>()
            db.withTransaction {
                val rows = dao.all().filter { it.active }
                val earliest = rows.minOfOrNull { CairoTime.dateOf(it.nextRenewalAtMillis) } ?: return@withTransaction
                val entries = dao.entriesSince(sinceMillis(minOf(earliest, today))).mapNotNull(::factOf)
                val used = mutableSetOf<String>()
                for (row in rows) {
                    val settled = settleOne(row, entries, used, today, changes)
                    if (settled != row) dao.update(settled)
                }
            }
            changes.forEach { prefs.addNotice(it) }
        }

        private fun settleOne(
            row: SubscriptionEntity,
            entries: List<EntryFact>,
            used: MutableSet<String>,
            today: LocalDate,
            notices: MutableList<PriceNotice>,
        ): SubscriptionEntity {
            val cadence = Cadence.of(row.cadence)
            val income = row.kind == KIND_INCOME
            val pool = entries.filter { it.income == income }
            var cur = row
            var date = CairoTime.dateOf(row.nextRenewalAtMillis)
            val anchor = RenewalMath.anchorOf(date)
            var guard = 0
            var hit = findHit(cur, date, pool, used)
            while (hit != null && guard++ < MAX_MATCHES) {
                used += hit.id
                if (hit.amountMinor != cur.amountMinor) {
                    notices += PriceNotice(cur.id, cur.name, cur.amountMinor, hit.amountMinor, cur.currency)
                }
                date = RenewalMath.nextAfter(date, cadence, anchor)
                cur = cur.copy(amountMinor = hit.amountMinor, merchantKey = cur.merchantKey ?: hit.merchantKey)
                hit = findHit(cur, date, pool, used)
            }
            date = RenewalMath.rollPastGrace(date, cadence, today)
            return cur.copy(nextRenewalAtMillis = CairoTime.noonOf(date).toEpochMilli())
        }

        private fun findHit(
            row: SubscriptionEntity,
            renewal: LocalDate,
            pool: List<EntryFact>,
            used: Set<String>,
        ): EntryFact? {
            val key = row.merchantKey ?: SubscriptionMatcher.keyOf(row.name) ?: return null
            return SubscriptionMatcher.match(key, row.amountMinor, row.currency, renewal, pool, used)
        }

        override suspend fun dismissSuggestion(key: String) = prefs.dismissKey(key)

        override suspend fun dismissNotice(subscriptionId: String) = prefs.clearNotice(subscriptionId)

        override suspend fun daysToPayday(today: LocalDate): Payday =
            PaydayMath.next(today, dao.all(), settings.current().monthStartDay, CairoTime::dateOf)

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
            const val HISTORY_DAYS = 150L
            const val MAX_MATCHES = 60
        }
    }
