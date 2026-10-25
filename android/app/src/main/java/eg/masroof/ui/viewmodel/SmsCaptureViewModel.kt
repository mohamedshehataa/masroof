package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.ingest.SenderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SmsCaptureViewModel
    @Inject
    constructor(
        private val senders: SenderSettings,
    ) : ViewModel() {
        private val mutableSuggestions = MutableStateFlow(senders.suggestions())
        val suggestions: StateFlow<List<String>> = mutableSuggestions

        fun refresh() {
            mutableSuggestions.value = senders.suggestions()
        }

        fun add(sender: String) {
            senders.addSender(sender)
            refresh()
        }
    }
