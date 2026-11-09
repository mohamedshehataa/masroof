package eg.masroof.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.NotificationSettings
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The longest second-member name the guide accepts. */
const val MAX_MEMBER_NAME = 40

/** The past-SMS range the guide starts with: the same 90 days as Settings > Read past SMS. */
private const val DEFAULT_RANGE_DAYS = 90

/** The name to store, or null when it is empty, only spaces, or longer than [MAX_MEMBER_NAME] characters. Arabic names are fine. */
fun validMemberName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_MEMBER_NAME }

/**
 * The first-run guide (REQ-AND-139..141). The progress is saved at every step, so a kill or the language step's activity recreation
 * resumes at the same step. Everything runs on the phone: no account, no network.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val store: OnboardingStore,
        private val db: AppDatabase,
        private val ids: IdFactory,
        private val backfill: BackfillController,
        private val senders: SenderSettings,
        private val notifications: NotificationSettings,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        val progress: StateFlow<OnboardingProgress> = store.progress

        private val mutableNameError = MutableStateFlow(false)

        /** "Enter a name up to 40 characters" is showing for the last name tried. */
        val nameError: StateFlow<Boolean> = mutableNameError

        private val mutableRange = MutableStateFlow(DEFAULT_RANGE_DAYS)

        /** The past-SMS range in days: 30, 90 (default) or 180. */
        val rangeDays: StateFlow<Int> = mutableRange

        init {
            decide()
        }

        /** A phone that already holds entries (an update) is not forced into the guide: it gets the card on Wallet. */
        private fun decide() {
            if (store.progress.value.mode != SetupMode.UNDECIDED) return
            viewModelScope.launch {
                val hasData = withContext(io) { db.expenses().count() > 0 }
                if (store.progress.value.mode == SetupMode.UNDECIDED) {
                    store.save(OnboardingProgress(if (hasData) SetupMode.CARD else SetupMode.GUIDE, 0))
                }
            }
        }

        private fun goTo(step: Int) {
            mutableNameError.value = false
            if (step >= OnboardingSteps.COUNT) finish() else store.save(OnboardingProgress(SetupMode.GUIDE, step))
        }

        /** Continue and Skip both move on: nothing is ever blocked by a denial or an empty answer. */
        fun next() = goTo(store.progress.value.step + 1)

        fun finish() = store.save(OnboardingProgress(SetupMode.DONE, 0))

        /** Settings > Setup guide, or the card's button: the guide again from its first step. */
        fun reopen() = store.save(OnboardingProgress(SetupMode.GUIDE, 0))

        /** The card's Dismiss. */
        fun dismissCard() = finish()

        fun chooseRange(days: Int) {
            if (days in BackfillRange.CHOICES) mutableRange.value = days
        }

        /** READ_SMS is granted: starts the backfill in the background (WorkManager) and never waits for it. */
        fun startBackfill() = backfill.start(BackfillRange.Days(mutableRange.value))

        /** RECEIVE_SMS was granted here: capture is on. */
        fun smsCaptureGranted() = senders.setSmsCaptureEnabled(true)

        /** The user went to the notification-access screen: bank-app capture is wanted. */
        fun notificationCaptureWanted() = notifications.setEnabled(true)

        fun nameChanged() {
            mutableNameError.value = false
        }

        /** Adds the second member and moves on; an invalid name shows the error and stays. */
        fun addMember(raw: String) {
            val name = validMemberName(raw)
            if (name == null) {
                mutableNameError.value = true
                return
            }
            // a second tap while the first is still saving must not skip the next step: move on only from the step that was tapped
            val from = store.progress.value.step
            viewModelScope.launch {
                withContext(io) { db.lookups().addMember(ids.next(), name) }
                if (store.progress.value.mode == SetupMode.GUIDE && store.progress.value.step == from) goTo(from + 1)
            }
        }
    }
