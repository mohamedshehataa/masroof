package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.NotificationSettings
import eg.masroof.ingest.SenderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val STOP_TIMEOUT = 5_000L

@HiltViewModel
class SmsCaptureViewModel
    @Inject
    constructor(
        private val senders: SenderSettings,
        private val notifications: NotificationSettings,
        db: AppDatabase,
    ) : ViewModel() {
        private val mutableSuggestions = MutableStateFlow(senders.suggestions())
        val suggestions: StateFlow<List<String>> = mutableSuggestions

        private val mutableNotifications = MutableStateFlow(notifications.enabled())
        val notificationsEnabled: StateFlow<Boolean> = mutableNotifications

        private val mutablePackages = MutableStateFlow(notifications.packages().sorted())
        val packages: StateFlow<List<String>> = mutablePackages

        val reviewCount: StateFlow<Int> =
            db.reviews().observeCount().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                0,
            )

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
