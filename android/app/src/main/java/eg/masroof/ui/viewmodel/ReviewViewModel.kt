package eg.masroof.ui.viewmodel

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.MoneyFormat
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.db.reviewCutoff
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.CaptureRows
import eg.masroof.ingest.ChannelPairing
import eg.masroof.ingest.NoCapturedNotifier
import eg.masroof.ingest.TemplateLearning
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val STOP_TIMEOUT = 5_000L

/** What one tap on Save did. */
enum class ReviewSaveResult { SAVED, ALREADY_RECORDED, AMOUNT_INVALID, FAILED, NOTHING }

/** The engine's suggestion as a typed record; null when it is not complete enough to save with one tap. */
private fun ReviewItemEntity.toRecord(): ParsedRecord? {
    val kind = sugKind?.let { runCatching { TxKind.valueOf(it) }.getOrNull() } ?: return null
    return ParsedRecord(
        localId = id,
        source = runCatching { CaptureSource.valueOf(source) }.getOrDefault(CaptureSource.SMS),
        kind = kind,
        amountMinor = sugAmountMinor ?: return null,
        currency = sugCurrency ?: "EGP",
        occurredAtMillis = sugOccurredAtMillis ?: receivedAtMillis,
        instrumentLast4 = sugLast4,
        senderId = sugSenderId ?: return null,
        institution = sugInstitution,
        paymentMethod = sugPaymentMethod,
        merchant = sugMerchant,
    )
}

/** What the manual form is prefilled with when the user turns a review item into an expense. Never uploaded as text. */
data class ReviewPrefill(
    val amountText: String,
    val notes: String,
)

/**
 * The local "Needs review" list (REQ-SMS-014). Everything here lives in Room on this phone: the text is shown to the
 * user, and only an expense the user saves leaves it (through the normal manual-entry outbox).
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val db: AppDatabase,
        @IoDispatcher private val io: CoroutineDispatcher,
        private val clock: java.time.Clock,
        private val ids: IdFactory = UuidIdFactory(),
        private val signal: LocalDataSignal = LocalDataSignal(),
        private val eraseLock: EraseLock = EraseLock(),
    ) : ViewModel() {
        private val pairing = ChannelPairing(db, clock, NoCapturedNotifier)
        private val _results = MutableSharedFlow<ReviewSaveResult>(extraBufferCapacity = 4)

        /** What a Save did, for the snackbar (already recorded, could not save, amount out of range). */
        val results: SharedFlow<ReviewSaveResult> = _results

        val items: StateFlow<List<ReviewItemEntity>?> =
            db
                .reviews()
                .observeAll(reviewCutoff(clock.millis()))
                .map<List<ReviewItemEntity>, List<ReviewItemEntity>?> { it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

        /**
         * One tap: the engine's suggestion becomes a local entry with its source (the same rows as an auto-recorded message), the
         * format is learned, and the item is gone. Nothing is queued or uploaded (ADR-0009 D3).
         */
        fun save(id: String) {
            viewModelScope.launch { saveNow(id) }
        }

        internal suspend fun saveNow(id: String): ReviewSaveResult {
            val result =
                withContext(io) {
                    val item = db.reviews().find(id) ?: return@withContext ReviewSaveResult.NOTHING
                    val base = item.toRecord() ?: return@withContext ReviewSaveResult.NOTHING
                    if (base.amountMinor !in 1..MoneyFormat.MAX_ENTRY_MINOR) return@withContext ReviewSaveResult.AMOUNT_INVALID
                    val record = base.copy(timeKnown = timeKnownOf(item, base))
                    try {
                        // like every other write of an entry: after a running erase, in one transaction (a failure rolls back, item kept)
                        eraseLock.mutex.withLock { db.withTransaction { write(item, record) } }
                    } catch (
                        @Suppress("SwallowedException") _: SQLiteException,
                    ) {
                        ReviewSaveResult.FAILED
                    }
                }
            if (result == ReviewSaveResult.SAVED || result == ReviewSaveResult.ALREADY_RECORDED) signal.changed()
            if (result != ReviewSaveResult.NOTHING) _results.tryEmit(result)
            return result
        }

        private suspend fun write(
            item: ReviewItemEntity,
            record: ParsedRecord,
        ): ReviewSaveResult {
            val id = item.id
            // the same message may already be an entry (a live capture, a backfill): then only the item goes
            if (db.sources().countByLocalSourceId(id) > 0 || db.captured().knows(id) > 0) {
                db.reviews().delete(id)
                return ReviewSaveResult.ALREADY_RECORDED
            }
            val now = clock.millis()
            // the other channel already recorded this transfer: this message joins that entry as a second source
            val twin = pairing.claimTwin(record, id, item.receivedAtMillis)
            if (twin != null) {
                db.sources().insert(CaptureRows.source(ids.next(), twin.expense.id, record, id, item.receivedAtMillis))
                if (twin.captured.instrumentLast4 == null && record.instrumentLast4 != null) {
                    db.expenses().enrichFromTwin(twin.expense.id, record.instrumentLast4, CaptureRows.label(record))
                }
            } else {
                val expenseId = ids.next()
                db.expenses().insert(CaptureRows.expense(expenseId, record, now, confirmedByUser = true))
                db.sources().insert(CaptureRows.source(ids.next(), expenseId, record, id, item.receivedAtMillis))
            }
            TemplateLearning.learn(db, item, now)
            db.reviews().delete(id)
            return ReviewSaveResult.SAVED
        }

        /**
         * The suggestion does not store whether the message gave a time. The text is still here until Save, so the parser is asked
         * again; a date-only message is Cairo noon with the time unknown. When the answer does not match what was stored, the
         * time is taken as known.
         */
        private fun timeKnownOf(
            item: ReviewItemEntity,
            record: ParsedRecord,
        ): Boolean {
            val source = runCatching { CaptureSource.valueOf(item.source) }.getOrDefault(CaptureSource.SMS)
            val again =
                when (val o = MessageParser.parse(IncomingMessage(source, item.sender, item.text, item.receivedAtMillis))) {
                    is ParseOutcome.Review -> o.suggestion
                    is ParseOutcome.Record -> o.record
                    else -> null
                }
            return again?.takeIf { it.occurredAtMillis == record.occurredAtMillis }?.timeKnown ?: true
        }

        /** The user saved the expense by hand from this item (maybe with another amount): learn its format, then drop it. */
        fun confirmEdited(id: String) {
            viewModelScope.launch { confirmEditedNow(id) }
        }

        internal suspend fun confirmEditedNow(id: String) {
            // Only the one-tap Save of an UNCHANGED suggestion teaches a format: an edit means the engine read the message wrongly,
            // and what the user typed is not a format. The item (and its slots) is dropped either way.
            withContext(io) { db.reviews().delete(id) }
        }

        fun dismiss(id: String) {
            viewModelScope.launch { withContext(io) { db.reviews().delete(id) } }
        }

        /** Amount and a short note for the add form; the note carries the sender only, never the message text. */
        suspend fun prefill(id: String): ReviewPrefill? =
            withContext(io) {
                val item = db.reviews().find(id) ?: return@withContext null
                val minor = item.sugAmountMinor ?: MessageParser.suggestAmountMinor(item.text)
                ReviewPrefill(minor?.let { "%d.%02d".format(it / 100, it % 100) }.orEmpty(), item.sender.take(40))
            }
    }
