package eg.masroof.data.answers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the user chose for ATM withdrawals: keep track of the cash, or count them as spending. */
enum class AtmChoice { TRACK, COUNT }

enum class LinkKind(
    val code: String,
) {
    /** [EntryLink.first] is the refund, [EntryLink.second] the purchase it reverses. */
    REFUND("R"),

    /** [EntryLink.first] is the money out (TRANSFER), [EntryLink.second] the money in (TRANSFER_IN). */
    TRANSFER("T"),
}

/**
 * One answered pair. [inserted] is the id of the row the app itself added (a TRANSFER_IN made by an answer): only such a row is ever
 * deleted together with its partner. [converted] is a real captured entry that an answer changed; [origType] and [origAccountId]
 * are what it was before, so releasing the pair puts it back exactly (a real entry is never deleted because of a pair).
 */
data class EntryLink(
    val kind: LinkKind,
    val first: String,
    val second: String,
    val inserted: String = "",
    val converted: String = "",
    val origType: String = "",
    val origAccountId: String = "",
    /** A Cash account that "Track it" created for this pair: removed again when the capture is undone and nothing else uses it. */
    val newAccountId: String = "",
    /** What the [converted] entry is while the pair is whole: its type and account (the rest of the record is the "orig" side). */
    val pairType: String = "",
    val pairAccountId: String = "",
    val origStatus: String = "",
    val origCategoryId: String = "",
    /** Makes every link unique: an Undo token applies to the very link its answer made, not to an equal one made later. */
    val nonce: String = "",
) {
    /** The row that goes away together with [id]: an inserted transfer side, or the refund of a purchase. Null for a real entry. */
    fun deletablePartnerOf(id: String): String? =
        when (kind) {
            LinkKind.TRANSFER -> inserted.takeIf { it.isNotEmpty() && it != id && involves(id) }
            LinkKind.REFUND -> first.takeIf { id == second }
        }

    fun involves(id: String) = id == first || id == second
}

/**
 * Everything STORY-208 remembers outside Room (no schema change; see DECISIONS). Only ids and four-digit card tails are kept;
 * never a message, a merchant or an amount.
 */
data class AnswerState(
    /** Entries whose card was answered or dismissed: the card never returns for them. */
    val answered: Set<String> = emptySet(),
    val links: Set<EntryLink> = emptySet(),
    /** Last fours the user chose to ignore: later captures from them go to the review list. */
    val ignoredCards: Set<String> = emptySet(),
    /** Last four to account id: "Link to an existing account". */
    val aliases: Map<String, String> = emptyMap(),
    val atmChoice: AtmChoice? = null,
) {
    fun linkedIds(): Set<String> = links.flatMapTo(HashSet()) { listOf(it.first, it.second) }

    /**
     * Entries whose card must not ask again: answered or linked, except the two sides of a link that lost a side ([liveIds] are the
     * live entries): the survivor may ask again, and a new answer replaces the old link.
     */
    fun settledIds(liveIds: Set<String>): Set<String> {
        val (whole, broken) = links.partition { it.first in liveIds && it.second in liveIds }
        val brokenIds = broken.flatMapTo(HashSet()) { listOf(it.first, it.second) }
        return (answered - brokenIds) + whole.flatMap { listOf(it.first, it.second) }
    }

    fun linksOf(id: String): List<EntryLink> = links.filter { it.involves(id) }
}

private const val LAST4_LENGTH = 4

/** A card tail is exactly four digits; anything else is never stored. */
internal fun isLast4(value: String): Boolean = value.length == LAST4_LENGTH && value.all { it in '0'..'9' }

interface AnswerPrefs {
    val state: Flow<AnswerState>

    suspend fun current(): AnswerState = state.first()

    /** Counts the erases this process has seen: an Undo token from before an "Erase all data" never applies again. */
    val epoch: Long get() = 0L

    /** One atomic read-modify-write. */
    suspend fun edit(transform: (AnswerState) -> AnswerState)

    /** "Erase all data": forgets every answer, link, ignored card and the ATM choice. */
    suspend fun clear()
}

/** For code paths that have no answers (previews and older tests). */
object NoAnswerPrefs : AnswerPrefs {
    override val state: Flow<AnswerState> = flowOf(AnswerState())

    override suspend fun edit(transform: (AnswerState) -> AnswerState) = Unit

    override suspend fun clear() = Unit
}

internal val Context.answerStore: DataStore<Preferences> by preferencesDataStore(name = "answers")

private val answeredKey = stringSetPreferencesKey("answered_entries")
private val linksKey = stringSetPreferencesKey("entry_links")
private val ignoredKey = stringSetPreferencesKey("ignored_cards")
private val aliasKey = stringSetPreferencesKey("card_aliases")
private val atmKey = stringPreferencesKey("atm_choice")

private const val LINK_FIELDS = 13

private fun EntryLink.encode() =
    listOf(
        kind.code,
        first,
        second,
        inserted,
        converted,
        origType,
        origAccountId,
        pairType,
        pairAccountId,
        origStatus,
        origCategoryId,
        newAccountId,
        nonce,
    ).joinToString("|")

private fun decodeLink(raw: String): EntryLink? {
    val parts = raw.split('|')
    val kind = LinkKind.entries.firstOrNull { it.code == parts.getOrNull(0) } ?: return null
    if (parts.size != LINK_FIELDS) return null
    return EntryLink(
        kind,
        parts[1],
        parts[2],
        inserted = parts[3],
        converted = parts[4],
        origType = parts[5],
        origAccountId = parts[6],
        pairType = parts[7],
        pairAccountId = parts[8],
        origStatus = parts[9],
        origCategoryId = parts[10],
        newAccountId = parts[11],
        nonce = parts[12],
    )
}

private fun decode(p: Preferences) =
    AnswerState(
        answered = p[answeredKey].orEmpty(),
        links = p[linksKey].orEmpty().mapNotNull(::decodeLink).toSet(),
        ignoredCards = p[ignoredKey].orEmpty().filter(::isLast4).toSet(),
        aliases =
            p[aliasKey]
                .orEmpty()
                .mapNotNull { e -> e.substringBefore(':').takeIf(::isLast4)?.let { it to e.substringAfter(':') } }
                .toMap(),
        atmChoice = p[atmKey]?.let { runCatching { AtmChoice.valueOf(it) }.getOrNull() },
    )

private fun encode(
    p: MutablePreferences,
    s: AnswerState,
) {
    p[answeredKey] = s.answered
    p[linksKey] = s.links.map { it.encode() }.toSet()
    p[ignoredKey] = s.ignoredCards.filter(::isLast4).toSet()
    p[aliasKey] =
        s.aliases
            .filterKeys(::isLast4)
            .map { "${it.key}:${it.value}" }
            .toSet()
    val atm = s.atmChoice
    if (atm == null) p.remove(atmKey) else p[atmKey] = atm.name
}

@Singleton
class DataStoreAnswerPrefs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AnswerPrefs {
        override val state: Flow<AnswerState> = context.answerStore.data.map(::decode)

        override suspend fun edit(transform: (AnswerState) -> AnswerState) {
            context.answerStore.edit { p -> encode(p, transform(decode(p))) }
        }

        private val erases =
            java.util.concurrent.atomic
                .AtomicLong()

        override val epoch: Long get() = erases.get()

        override suspend fun clear() {
            erases.incrementAndGet()
            context.answerStore.edit { it.clear() }
        }
    }
