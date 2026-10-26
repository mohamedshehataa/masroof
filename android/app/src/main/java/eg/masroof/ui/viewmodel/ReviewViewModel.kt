package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.db.reviewCutoff
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.ingest.TemplateLearning
import eg.masroof.ingest.parse.MessageParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val STOP_TIMEOUT = 5_000L

private fun ReviewItemEntity.toOutbox(now: Long): ParsedOutboxEntity? {
    val kind = sugKind ?: return null
    val amount = sugAmountMinor ?: return null
    return ParsedOutboxEntity(
        localId = id,
        source = source,
        kind = kind,
        amountMinor = amount,
        currency = sugCurrency ?: "EGP",
        occurredAtMillis = sugOccurredAtMillis ?: receivedAtMillis,
        instrumentLast4 = sugLast4,
        senderId = sugSenderId ?: return null,
        institution = sugInstitution,
        paymentMethod = sugPaymentMethod,
        merchant = sugMerchant,
        createdAtMillis = now,
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
        private val scheduler: SmsWorkScheduler,
        private val clock: java.time.Clock,
    ) : ViewModel() {
        val items: StateFlow<List<ReviewItemEntity>?> =
            db
                .reviews()
                .observeAll(reviewCutoff(clock.millis()))
                .map<List<ReviewItemEntity>, List<ReviewItemEntity>?> { it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

        /**
         * One tap: the engine's suggestion becomes a normal expense with source SMS, through the typed outbox (the same path as an
         * auto-recorded message), the format is learned, and the item is gone. Nothing but the typed fields leaves the phone.
         */
        fun save(id: String) {
            viewModelScope.launch { saveNow(id) }
        }

        internal suspend fun saveNow(id: String) {
            val saved =
                withContext(io) {
                    val item = db.reviews().find(id) ?: return@withContext false
                    val outbox = item.toOutbox(clock.millis()) ?: return@withContext false
                    db.withTransaction {
                        db.parsedOutbox().insert(outbox)
                        TemplateLearning.learn(db, item, clock.millis())
                        db.reviews().delete(id)
                    }
                    true
                }
            if (saved) scheduler.scheduleUpload()
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
