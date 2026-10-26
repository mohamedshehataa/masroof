package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.reviewCutoff
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.NotificationSettings
import eg.masroof.ingest.SenderSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val STOP_TIMEOUT = 5_000L

@HiltViewModel
class SmsCaptureViewModel
    @Inject
    constructor(
        private val senders: SenderSettings,
        private val notifications: NotificationSettings,
        private val capturedAlerts: CapturedAlertSettings,
        db: AppDatabase,
        clock: java.time.Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableSuggestions = MutableStateFlow(senders.suggestions())
        val suggestions: StateFlow<List<String>> = mutableSuggestions

        private val mutableNotifications = MutableStateFlow(notifications.enabled())
        val notificationsEnabled: StateFlow<Boolean> = mutableNotifications

        private val mutablePackages = MutableStateFlow(notifications.packages().sorted())
        val packages: StateFlow<List<String>> = mutablePackages

        private val mutableCapturedAlerts = MutableStateFlow(capturedAlerts.enabled())

        /** The quiet "Recorded ... Undo" notification for transactions captured by themselves (on by default). */
        val capturedAlertsEnabled: StateFlow<Boolean> = mutableCapturedAlerts

        fun setCapturedAlerts(on: Boolean) {
            capturedAlerts.setEnabled(on)
            mutableCapturedAlerts.value = on
        }

        val reviewCount: StateFlow<Int> =
            db.reviews().observeCount(reviewCutoff(clock.millis())).stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                0,
            )

        val learnedCount: StateFlow<Int> =
            db.learnedTemplates().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

        private val templates = db.learnedTemplates()

        /** Forget every message format the user confirmed; later messages go back to the review list first. */
        fun clearLearned() {
            viewModelScope.launch { withContext(io) { templates.clear() } }
        }

        fun refresh() {
            mutableSuggestions.value = senders.suggestions()
        }

        fun add(sender: String) {
            senders.addSender(sender)
            refresh()
        }

        fun setNotifications(on: Boolean) {
            notifications.setEnabled(on)
            mutableNotifications.value = on
        }

        fun addPackage(pkg: String) {
            notifications.addPackage(pkg)
            mutablePackages.value = notifications.packages().sorted()
        }

        fun removePackage(pkg: String) {
            notifications.removePackage(pkg)
            mutablePackages.value = notifications.packages().sorted()
        }
    }
