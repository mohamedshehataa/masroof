package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillRange
import eg.masroof.ingest.backfill.BackfillState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Read past SMS" on Settings: the range choice and the run state. Everything else is the controller's and the worker's. */
@HiltViewModel
class BackfillViewModel
    @Inject
    constructor(
        private val controller: BackfillController,
    ) : ViewModel() {
        val state: StateFlow<BackfillState> = controller.state

        private val mutableRange = MutableStateFlow(initialRange())
        val range: StateFlow<BackfillRange> = mutableRange

        // a stopped or killed run is continued with the range it started with
        private fun initialRange(): BackfillRange =
            controller.state.value
                .takeIf { it.resumable }
                ?.let { BackfillRange.parse(it.rangeKey) } ?: BackfillRange.DEFAULT

        fun choose(range: BackfillRange) {
            mutableRange.value = range
        }

        /** Starts (or continues, for the same range) the run. The screen calls this only once READ_SMS is granted. */
        fun start() = controller.start(mutableRange.value)

        fun cancel() {
            viewModelScope.launch { controller.cancel() }
        }

        fun dismiss() = controller.dismiss()

        /** Re-reads the run state (coming back to the screen). */
        fun refresh() = controller.refresh()
    }
