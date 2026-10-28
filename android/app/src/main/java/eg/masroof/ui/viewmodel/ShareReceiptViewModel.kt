package eg.masroof.ui.viewmodel

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.receipts.AttachResult
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.Refusal
import eg.masroof.data.receipts.ShareIntentParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How one shared item ended. Never carries names, URIs or content. */
sealed interface ShareOutcome {
    data object Stored : ShareOutcome

    data object Duplicate : ShareOutcome

    /** Not a `content://` URI of another app: refused without being read. */
    data object CantShare : ShareOutcome

    data class Refused(
        val reason: Refusal?,
    ) : ShareOutcome
}

data class ShareState(
    val total: Int = 0,
    val outcomes: List<ShareOutcome> = emptyList(),
    val overflow: Boolean = false,
    val started: Boolean = false,
) {
    val finished: Boolean get() = started && outcomes.size == total

    /** Everything went in: the Receipts list opens by itself. */
    val allGood: Boolean get() = finished && total > 0 && !overflow && outcomes.all { it == ShareOutcome.Stored }
}

/** REQ-RCPT-022: copies each shared item at once, one at a time, unattached. */
@HiltViewModel
class ShareReceiptViewModel
    @Inject
    constructor(
        private val store: ReceiptStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ShareState())
        val state: StateFlow<ShareState> = _state

        /** Idempotent: a recreated activity does not process the same intent twice. */
        fun start(
            intent: Intent,
            resolver: ContentResolver,
            ownPackage: String,
        ) {
            if (_state.value.started) return
            val plan = ShareIntentParser.parse(intent, ownPackage)
            _state.value = ShareState(total = plan.items.size, overflow = plan.overflow, started = true)
            viewModelScope.launch {
                plan.items.forEach { uri -> add(outcomeOf(uri, resolver)) }
            }
        }

        private fun add(outcome: ShareOutcome) = _state.update { it.copy(outcomes = it.outcomes + outcome) }

        private suspend fun outcomeOf(
            uri: Uri?,
            resolver: ContentResolver,
        ): ShareOutcome {
            if (uri == null) return ShareOutcome.CantShare
            return when (val result = store.attach(null) { resolver.openInputStream(uri) }) {
                is AttachResult.Added -> ShareOutcome.Stored
                is AttachResult.Duplicate -> ShareOutcome.Duplicate
                is AttachResult.Refused -> ShareOutcome.Refused(result.reason)
                AttachResult.Failed -> ShareOutcome.Refused(null)
            }
        }
    }
