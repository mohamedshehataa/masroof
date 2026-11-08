package eg.masroof.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.MoneyFormat
import eg.masroof.data.SettingsRepository
import eg.masroof.data.goals.GoalDraft
import eg.masroof.data.goals.GoalInput
import eg.masroof.data.goals.GoalOutcome
import eg.masroof.data.goals.GoalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

@HiltViewModel
class GoalsViewModel
    @Inject
    constructor(
        repository: GoalRepository,
        settings: SettingsRepository,
        clock: Clock,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<GoalsListState> =
            attempt
                .flatMapLatest {
                    combine(repository.observeGoals(), settings.settings.map { it.monthStartDay }) { goals, day ->
                        val rows = cards(goals, CairoTime.today(clock.instant()), day)
                        GoalsListState(loading = false, cards = rows, totals = totals(rows))
                    }.catch { emit(GoalsListState(loading = false, error = true)) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GoalsListState())

        fun retry() {
            attempt.update { it + 1 }
        }
    }

enum class MoneyError { AMOUNT, TOO_LARGE, DATE, ONLY_HAVE }

/** The Add money / Take out dialog. [onlyHaveMinor] is meaningful for [MoneyError.ONLY_HAVE]. */
data class MoneyDialogState(
    val take: Boolean,
    val error: MoneyError? = null,
    val onlyHaveMinor: Long = 0L,
)

@HiltViewModel
class GoalDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: GoalRepository,
        settings: SettingsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val id: String = savedState.get<String>("id").orEmpty()
        private val dialogState = MutableStateFlow<MoneyDialogState?>(null)
        private val celebrating = MutableStateFlow(false)

        val state: StateFlow<GoalDetailState> =
            combine(repository.observeGoal(id), settings.settings.map { it.monthStartDay }) { detail, day ->
                detailState(detail, CairoTime.today(clock.instant()), day)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GoalDetailState())

        val dialog: StateFlow<MoneyDialogState?> = dialogState
        val celebration: StateFlow<Boolean> = celebrating

        fun openDialog(take: Boolean) {
            dialogState.value = MoneyDialogState(take)
        }

        fun dismissDialog() {
            dialogState.value = null
        }

        fun dismissCelebration() {
            celebrating.value = false
        }

        /** Stores one goal movement (+ for Add money, - for Take out), or reports what is wrong with the input. */
        fun submit(
            amountText: String,
            note: String,
            dateText: String,
        ) {
            val take = dialogState.value?.take ?: return
            val date = if (dateText.isBlank()) null else GoalInput.parseDate(dateText)
            val parsed = MoneyFormat.parseEntry(amountText)
            val error =
                when {
                    parsed is MoneyFormat.EntryAmount.Invalid -> MoneyError.AMOUNT
                    parsed is MoneyFormat.EntryAmount.TooLarge -> MoneyError.TOO_LARGE
                    dateText.isNotBlank() && date == null -> MoneyError.DATE
                    else -> null
                }
            if (error != null || parsed !is MoneyFormat.EntryAmount.Valid) {
                dialogState.value = MoneyDialogState(take, error)
                return
            }
            viewModelScope.launch {
                val delta = if (take) -parsed.minor else parsed.minor
                val atMillis = date?.let { CairoTime.noonOf(it).toEpochMilli() }
                when (val out = repository.record(id, delta, note, atMillis)) {
                    is GoalOutcome.Saved -> {
                        dialogState.value = null
                        if (out.reachedNow) celebrating.value = true
                    }
                    is GoalOutcome.OnlyHave -> dialogState.value = MoneyDialogState(take, MoneyError.ONLY_HAVE, out.savedMinor)
                    GoalOutcome.TooLarge -> dialogState.value = MoneyDialogState(take, MoneyError.TOO_LARGE)
                    else -> dialogState.value = MoneyDialogState(take, MoneyError.AMOUNT)
                }
            }
        }

        fun archive(onDone: () -> Unit) {
            viewModelScope.launch {
                repository.archive(id)
                onDone()
            }
        }

        fun delete(onDone: () -> Unit) {
            viewModelScope.launch {
                repository.delete(id)
                onDone()
            }
        }
    }

data class GoalFormState(
    val editing: Boolean = false,
    val name: String = "",
    val emoji: String = "🎯",
    val targetText: String = "",
    val dateText: String = "",
    val nameError: Boolean = false,
    val targetError: MoneyError? = null,
    val dateError: Boolean = false,
    val saving: Boolean = false,
)

@HiltViewModel
class GoalFormViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: GoalRepository,
    ) : ViewModel() {
        private val id: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }
        private val form = MutableStateFlow(GoalFormState(editing = id != null))
        val state: StateFlow<GoalFormState> = form

        init {
            if (id != null) {
                viewModelScope.launch {
                    repository.find(id)?.let { g ->
                        form.update {
                            it.copy(
                                name = g.name,
                                emoji = g.emoji ?: it.emoji,
                                targetText = minorToText(g.targetMinor),
                                dateText = g.targetAtMillis?.let { ms -> CairoTime.dateOf(ms).toString() }.orEmpty(),
                            )
                        }
                    }
                }
            }
        }

        fun onName(text: String) = form.update { it.copy(name = text, nameError = false) }

        fun onEmoji(emoji: String) = form.update { it.copy(emoji = emoji) }

        fun onTarget(text: String) = form.update { it.copy(targetText = text, targetError = null) }

        fun onDate(text: String) = form.update { it.copy(dateText = text, dateError = false) }

        fun save(onDone: () -> Unit) {
            val f = form.value
            val target = MoneyFormat.parseEntry(f.targetText)
            val date = if (f.dateText.isBlank()) null else GoalInput.parseDate(f.dateText)
            val nameBad = f.name.isBlank()
            val targetBad =
                when (target) {
                    is MoneyFormat.EntryAmount.Invalid -> MoneyError.AMOUNT
                    is MoneyFormat.EntryAmount.TooLarge -> MoneyError.TOO_LARGE
                    is MoneyFormat.EntryAmount.Valid -> null
                }
            val dateBad = f.dateText.isNotBlank() && date == null
            val fieldBad = nameBad || dateBad || targetBad != null
            if (target !is MoneyFormat.EntryAmount.Valid || fieldBad) {
                form.update { it.copy(nameError = nameBad, targetError = targetBad, dateError = dateBad) }
                return
            }
            val draft = GoalDraft(f.name, f.emoji, target.minor, targetAtMillis = date?.let { CairoTime.noonOf(it).toEpochMilli() })
            form.update { it.copy(saving = true) }
            viewModelScope.launch {
                val out = if (id == null) repository.create(draft) else repository.update(id, draft)
                form.update { it.copy(saving = false) }
                if (out is GoalOutcome.Saved) onDone()
            }
        }

        // A stored target as editable text: whole pounds, with piasters only when present.
        private fun minorToText(minor: Long): String =
            if (minor % MINOR == 0L) (minor / MINOR).toString() else "${minor / MINOR}.${(minor % MINOR).toString().padStart(2, '0')}"

        private companion object {
            const val MINOR = 100L
        }
    }
