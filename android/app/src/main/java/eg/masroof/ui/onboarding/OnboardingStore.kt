package eg.masroof.ui.onboarding

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the first-run guide stands on this phone. */
enum class SetupMode {
    /** Not looked at yet (a fresh start of an install that never ran the guide). */
    UNDECIDED,

    /** The guide is showing, at [OnboardingProgress.step]. */
    GUIDE,

    /** A phone that already had data when this version arrived: not forced, a dismissible "Finish setup" card on Wallet instead. */
    CARD,

    /** Finished or dismissed: never shown again on its own; Settings > Setup guide reopens it. */
    DONE,
}

data class OnboardingProgress(
    val mode: SetupMode = SetupMode.UNDECIDED,
    /** The step reached, 0-based (see [OnboardingSteps]). */
    val step: Int = 0,
)

/** The seven steps of the guide, in order. */
object OnboardingSteps {
    const val LANGUAGE = 0
    const val STAYS_ON_PHONE = 1
    const val SMS_CAPTURE = 2
    const val PAST_SMS = 3
    const val NOTIFICATIONS = 4
    const val MEMBER = 5
    const val DONE = 6
    const val COUNT = 7
}

/**
 * The guide's progress, in its own small preferences file because the language step recreates the activity and a kill must resume at
 * the same step. It is read synchronously so a finished phone never flashes the guide. Nothing here is user data: per-install UI state.
 */
class OnboardingStore(
    private val prefs: SharedPreferences,
) {
    private val mutable = MutableStateFlow(read())
    val progress: StateFlow<OnboardingProgress> = mutable

    private fun read(): OnboardingProgress {
        val mode = prefs.getString("mode", null)?.let { name -> SetupMode.entries.firstOrNull { it.name == name } }
        val step = prefs.getInt("step", 0).coerceIn(0, OnboardingSteps.COUNT - 1)
        return OnboardingProgress(mode ?: SetupMode.UNDECIDED, step)
    }

    fun save(next: OnboardingProgress) {
        val safe = next.copy(step = next.step.coerceIn(0, OnboardingSteps.COUNT - 1))
        prefs.edit {
            putString("mode", safe.mode.name)
            putInt("step", safe.step)
        }
        mutable.value = safe
    }
}
