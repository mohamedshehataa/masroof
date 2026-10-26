package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.db.reviewCutoff
import eg.masroof.di.IoDispatcher
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
        clock: java.time.Clock,
    ) : ViewModel() {
        val items: StateFlow<List<ReviewItemEntity>?> =
            db
                .reviews()
                .observeAll(reviewCutoff(clock.millis()))
                .map<List<ReviewItemEntity>, List<ReviewItemEntity>?> { it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

        fun dismiss(id: String) {
            viewModelScope.launch { withContext(io) { db.reviews().delete(id) } }
        }

        /** Amount and a short note for the add form; the note carries the sender only, never the message text. */
        suspend fun prefill(id: String): ReviewPrefill? =
            withContext(io) {
                val item = db.reviews().find(id) ?: return@withContext null
                val minor = MessageParser.suggestAmountMinor(item.text)
                ReviewPrefill(minor?.let { "%d.%02d".format(it / 100, it % 100) }.orEmpty(), item.sender.take(40))
            }
    }
