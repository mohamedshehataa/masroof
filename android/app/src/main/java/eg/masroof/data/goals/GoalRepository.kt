package eg.masroof.data.goals

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.GoalMovementEntity
import eg.masroof.data.db.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** A goal and the money recorded against it. */
data class GoalSummary(
    val goal: SavingsGoalEntity,
    val savedMinor: Long,
)

data class GoalDetail(
    val goal: SavingsGoalEntity,
    val savedMinor: Long,
    /** Oldest first. */
    val movements: List<GoalMovementEntity>,
)

data class GoalDraft(
    val name: String,
    val emoji: String?,
    val targetMinor: Long,
    val currency: String = Money.EGP,
    val targetAtMillis: Long? = null,
)

sealed interface GoalOutcome {
    /** [reachedNow] is true once, when this write took the goal to its target (the celebration). */
    data class Saved(
        val id: String,
        val reachedNow: Boolean = false,
    ) : GoalOutcome

    data object InvalidName : GoalOutcome

    data object InvalidAmount : GoalOutcome

    data object TooLarge : GoalOutcome

    /** A take-out bigger than what the goal holds. */
    data class OnlyHave(
        val savedMinor: Long,
    ) : GoalOutcome

    /** A movement dated after today or before the goal was created (Cairo days). */
    data object InvalidDate : GoalOutcome

    data object NotFound : GoalOutcome
}

/**
 * Savings goals on Room only (REQ-EXP-056). Money is recorded against a goal: no `expense` row is ever created or changed here, so
 * a goal movement never counts as spending or income. Every write is one transaction and waits for a running erase.
 */
@Singleton
class GoalRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val ids: IdFactory,
        private val clock: Clock,
        private val eraseLock: EraseLock,
    ) {
        /** Active (not archived) goals in list order with what each holds. */
        fun observeGoals(): Flow<List<GoalSummary>> =
            combine(db.savingsGoals().observeAll(), db.goalMovements().observeAll()) { goals, moves ->
                val sums = moves.groupBy { it.goalId }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
                goals.filter { it.archivedAtMillis == null }.map { GoalSummary(it, sums[it.id] ?: 0L) }
            }

        fun observeGoal(id: String): Flow<GoalDetail?> =
            combine(db.savingsGoals().observeById(id), db.goalMovements().observeForGoal(id)) { goal, moves ->
                goal?.let { GoalDetail(it, moves.sumOf { m -> m.amountMinor }, moves) }
            }

        suspend fun find(id: String): SavingsGoalEntity? = db.savingsGoals().find(id)

        suspend fun create(draft: GoalDraft): GoalOutcome =
            eraseLock.mutex.withLock {
                invalid(draft) ?: db.withTransaction {
                    val id = ids.next()
                    db.savingsGoals().insert(
                        SavingsGoalEntity(
                            id = id,
                            name = draft.name.trim(),
                            emoji = draft.emoji?.takeIf { it.isNotBlank() },
                            targetMinor = draft.targetMinor,
                            currency = draft.currency,
                            targetAtMillis = draft.targetAtMillis,
                            sortOrder = db.savingsGoals().nextSortOrder(),
                            createdAtMillis = clock.millis(),
                            completedAtMillis = null,
                            archivedAtMillis = null,
                        ),
                    )
                    GoalOutcome.Saved(id)
                }
            }

        /** Changes name, emoji, target and date; the reached state follows the new target. */
        suspend fun update(
            id: String,
            draft: GoalDraft,
        ): GoalOutcome =
            eraseLock.mutex.withLock {
                invalid(draft) ?: db.withTransaction {
                    val goal = db.savingsGoals().find(id) ?: return@withTransaction GoalOutcome.NotFound
                    val changed =
                        goal.copy(
                            name = draft.name.trim(),
                            emoji = draft.emoji?.takeIf { it.isNotBlank() },
                            targetMinor = draft.targetMinor,
                            targetAtMillis = draft.targetAtMillis,
                        )
                    GoalOutcome.Saved(id, settle(changed, db.goalMovements().total(id)))
                }
            }

        /** [deltaMinor] positive puts money in, negative takes it out. [atMillis] null means now. */
        suspend fun record(
            goalId: String,
            deltaMinor: Long,
            note: String?,
            atMillis: Long? = null,
        ): GoalOutcome =
            eraseLock.mutex.withLock {
                val size = Math.abs(deltaMinor)
                when {
                    deltaMinor == 0L || size == Long.MIN_VALUE -> GoalOutcome.InvalidAmount
                    size > MoneyFormat.MAX_ENTRY_MINOR -> GoalOutcome.TooLarge
                    else ->
                        db.withTransaction {
                            val goal = db.savingsGoals().find(goalId) ?: return@withTransaction GoalOutcome.NotFound
                            val saved = db.goalMovements().total(goalId)
                            val at = atMillis ?: clock.millis()
                            val day = CairoTime.dateOf(at)
                            if (day.isAfter(CairoTime.today(clock.instant())) || day.isBefore(CairoTime.dateOf(goal.createdAtMillis))) {
                                return@withTransaction GoalOutcome.InvalidDate
                            }
                            val existing = db.goalMovements().forGoal(goalId)
                            // The running total must stay at or above zero at every point in time, not only now.
                            val heldAtDate = existing.filter { it.atMillis <= at }.sumOf { it.amountMinor }
                            if (deltaMinor < 0 && !staysNonNegative(existing, at, deltaMinor)) {
                                return@withTransaction GoalOutcome.OnlyHave(heldAtDate)
                            }
                            db.goalMovements().insert(
                                GoalMovementEntity(
                                    id = ids.next(),
                                    goalId = goalId,
                                    amountMinor = deltaMinor,
                                    atMillis = at,
                                    note = note?.trim()?.take(MAX_NOTE)?.ifBlank { null },
                                ),
                            )
                            GoalOutcome.Saved(goalId, settle(goal, saved + deltaMinor))
                        }
                }
            }

        suspend fun archive(id: String) {
            eraseLock.mutex.withLock {
                db.withTransaction {
                    db.savingsGoals().find(id)?.let { db.savingsGoals().update(it.copy(archivedAtMillis = clock.millis())) }
                }
            }
        }

        /** Removes the goal and (by cascade) its movements. Entries are never touched. */
        suspend fun delete(id: String) {
            eraseLock.mutex.withLock {
                db.withTransaction { db.savingsGoals().find(id)?.let { db.savingsGoals().delete(it) } }
            }
        }

        private fun staysNonNegative(
            existing: List<GoalMovementEntity>,
            at: Long,
            delta: Long,
        ): Boolean {
            var sum = 0L
            val merged = existing.map { it.atMillis to it.amountMinor } + (at to delta)
            return merged.sortedBy { it.first }.all {
                sum += it.second
                sum >= 0L
            }
        }

        // Celebrates and stamps completedAtMillis the first time only: a take-out and a re-reach never fire again.
        private suspend fun settle(
            goal: SavingsGoalEntity,
            savedMinor: Long,
        ): Boolean {
            val first = savedMinor >= goal.targetMinor && goal.completedAtMillis == null
            if (first) db.savingsGoals().update(goal.copy(completedAtMillis = clock.millis()))
            return first
        }

        private fun invalid(draft: GoalDraft): GoalOutcome? =
            when {
                draft.name.isBlank() || draft.name.trim().length > MAX_NAME -> GoalOutcome.InvalidName
                draft.targetMinor <= 0L -> GoalOutcome.InvalidAmount
                draft.targetMinor > MoneyFormat.MAX_ENTRY_MINOR -> GoalOutcome.TooLarge
                else -> null
            }

        private companion object {
            const val MAX_NAME = 60
            const val MAX_NOTE = 200
        }
    }

/** What the goal forms make of typed text. */
object GoalInput {
    /** A calendar date typed as `yyyy-mm-dd` in Western or Arabic-Indic digits; null when it is not a real date. */
    fun parseDate(text: String): LocalDate? {
        val western = text.trim().map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
        if (!DATE.matches(western)) return null
        return try {
            LocalDate.parse(western)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
}

/** Emoji for the picker: money, saving, home, travel, school, family. */
object GoalEmoji {
    val ALL: List<String> =
        listOf(
            "💰",
            "💵",
            "💸",
            "🏦",
            "💳",
            "🪙",
            "💎",
            "📈",
            "🎯",
            "🏆",
            "🚗",
            "🚙",
            "🏍️",
            "🚲",
            "🏠",
            "🏡",
            "🏢",
            "🛋️",
            "🔑",
            "🏖️",
            "🌴",
            "✈️",
            "🧳",
            "🕋",
            "🌍",
            "🎓",
            "📚",
            "🏫",
            "👶",
            "👨‍👩‍👧‍👦",
            "💍",
            "👰",
            "🎁",
            "🎂",
            "📱",
            "💻",
            "📷",
            "🎮",
            "🛒",
            "👗",
            "🏥",
            "🦷",
            "🐑",
            "🌙",
            "🎉",
            "🛠️",
            "🏗️",
            "🌱",
            "⚽",
            "🎸",
        )
}
