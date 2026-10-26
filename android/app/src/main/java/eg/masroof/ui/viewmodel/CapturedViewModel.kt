package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.ingest.CapturedActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT = 5_000L

/**
 * The "Captured" chip and its Undo (round 18): which expenses the phone recorded by itself in the last 7 days, and what Undo does.
 * [byExpenseId] maps a server expense id to the captured record it came from.
 */
@HiltViewModel
class CapturedViewModel
    @Inject
    constructor(
        private val actions: CapturedActions,
    ) : ViewModel() {
        val byExpenseId: StateFlow<Map<String, String>> =
            actions
                .observeRecent()
                .map { list -> list.mapNotNull { c -> c.expenseId?.let { it to c.localId } }.toMap() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

        private val mutableStopLearning = MutableStateFlow<String?>(null)

        /** The learned format to ask about after an Undo of a record it made ("Stop learning this format?"). */
        val stopLearning: StateFlow<String?> = mutableStopLearning

        fun undo(localId: String) {
            viewModelScope.launch {
                val result = actions.undo(localId)
                if (result.removed) mutableStopLearning.value = result.templateId
            }
        }

        fun answerStopLearning(yes: Boolean) {
            val templateId = mutableStopLearning.value ?: return
            mutableStopLearning.value = null
            if (yes) viewModelScope.launch { actions.stopLearning(templateId) }
        }
    }
