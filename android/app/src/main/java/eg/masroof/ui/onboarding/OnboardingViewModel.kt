package eg.masroof.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ME_MEMBER_ID
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

/** Direction marks, embeddings, isolates, zero-width characters and the byte-order mark: invisible, so never part of a name. */
private fun Char.isInvisible() = code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0xFEFF

/**
 * The name to store, or null when it is empty or only spaces and invisible characters, holds a line break or other control
 * character, or is longer than [MAX_MEMBER_NAME] characters. Invisible characters are removed; Arabic names are fine.
 */
fun validMemberName(raw: String): String? {
    val visible = raw.filterNot { it.isInvisible() }
    if (visible.any { it.isISOControl() }) return null
    return visible.trim().takeIf { it.isNotEmpty() && it.length <= MAX_MEMBER_NAME }
}

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

        /** A phone that already holds data of any kind (an update) is not forced into the guide: it gets the card on Wallet. */
        private fun decide() {
            if (store.progress.value.mode != SetupMode.UNDECIDED) return
            viewModelScope.launch {
                val hasData = withContext(io) { db.lookups().hasUserData(ME_MEMBER_ID) }
                if (store.progress.value.mode == SetupMode.UNDECIDED) {
                    store.save(OnboardingProgress(if (hasData) SetupMode.CARD else SetupMode.GUIDE, 0))
                }
            }
        }

        private fun goTo(step: Int) {
            mutableNameError.value = false
            val now = store.progress.value
            if (step >= OnboardingSteps.COUNT) finish() else store.save(now.copy(mode = SetupMode.GUIDE, step = step))
        }

        /** Continue and Skip both move on: nothing is ever blocked by a denial or an empty answer. */
        fun next() = goTo(store.progress.value.step + 1)

        /** System Back inside the guide: the previous step, and on the first step of a reopened guide it closes the guide. */
        fun back() {
            val now = store.progress.value
            when {
                now.step > 0 -> goTo(now.step - 1)
                now.reopened -> finish()
            }
        }

        fun finish() = store.save(OnboardingProgress(SetupMode.DONE, 0))

        /** Settings > Setup guide, or the card's button: the guide again from its first step. */
        fun reopen() = store.save(OnboardingProgress(SetupMode.GUIDE, 0, reopened = true))

        /** The card's Dismiss. */
        fun dismissCard() = finish()

        fun chooseRange(days: Int) {
            if (days in BackfillRange.CHOICES) mutableRange.value = days
        }

        /** READ_SMS is granted: starts the backfill in the background (WorkManager) and never waits for it. */
        fun startBackfill() = backfill.start(BackfillRange.Days(mutableRange.value))

        /** The app's own "SMS capture" switch, the same one Settings shows (capture is on only with the permission too). */
        fun smsCaptureWanted(): Boolean = senders.smsCaptureEnabled()

        /** The user asked for SMS capture here and the permission is granted: the switch goes on. */
        fun smsCaptureGranted() = senders.setSmsCaptureEnabled(true)

        /** Notification access is granted in the system: bank-app capture goes on (never before). */
        fun notificationAccessGranted() = notifications.setEnabled(true)

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
