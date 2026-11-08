package eg.masroof.data.categories

import androidx.room.withTransaction
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What a category edit answered; nothing is written unless it is [Ok]. */
sealed interface CategoryResult {
    data class Ok(
        val id: String,
    ) : CategoryResult

    data object EmptyName : CategoryResult

    /** A sibling (same parent) already has this name in English or Arabic. */
    data object Duplicate : CategoryResult

    /** Entries, sub-categories, budgets or rules still use it: archive instead of delete. */
    data object InUse : CategoryResult

    /** Built-in categories can be renamed and archived but never deleted. */
    data object SystemProtected : CategoryResult

    data object NotFound : CategoryResult
}

/** Settings -> Categories on Room only. Archived categories keep their history and still roll up. */
interface CategoryRepository {
    /** Every category, archived ones too. */
    fun observe(): Flow<List<CategoryEntity>>

    /** [nameEn] or [nameAr] may be blank (the other is used for both), not both. */
    suspend fun add(
        nameEn: String,
        nameAr: String,
        emoji: String?,
        parentId: String?,
    ): CategoryResult

    suspend fun edit(
        id: String,
        nameEn: String,
        nameAr: String,
        emoji: String?,
    ): CategoryResult

    suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): CategoryResult

    /** Moves the category one place up or down among its siblings. */
    suspend fun move(
        id: String,
        up: Boolean,
    ): CategoryResult

    suspend fun delete(id: String): CategoryResult
}

/** Ids of categories the user made start with this; they are never dropped from a picker. */
const val CUSTOM_ID_PREFIX = "custom-"

/** Longest category name, in code points. */
const val MAX_NAME = 40
private const val ORDER_STEP = 10
private const val PINNED_LAST = 900

@Singleton
class DefaultCategoryRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
        private val eraseLock: EraseLock,
    ) : CategoryRepository {
        private val dao get() = db.categoryRules()

        override fun observe() = db.lookups().observeLabelCategories()

        override suspend fun add(
            nameEn: String,
            nameAr: String,
            emoji: String?,
            parentId: String?,
        ): CategoryResult {
            val names = names(nameEn, nameAr) ?: return CategoryResult.EmptyName
            val result =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        when {
                            parentId != null && dao.findCategory(parentId) == null -> CategoryResult.NotFound
                            isDuplicate(names, parentId, null) -> CategoryResult.Duplicate
                            else -> {
                                val id = "$CUSTOM_ID_PREFIX${ids.next()}"
                                dao.insertCategory(
                                    CategoryEntity(
                                        id = id,
                                        nameEn = names.first,
                                        nameAr = names.second,
                                        sortOrder = nextOrder(parentId),
                                        parentId = parentId,
                                        color = parentId?.let { dao.findCategory(it)?.color },
                                        emoji = emoji?.trim()?.takeIf { it.isNotEmpty() },
                                    ),
                                )
                                CategoryResult.Ok(id)
                            }
                        }
                    }
                }
            if (result is CategoryResult.Ok) signal.changed()
            return result
        }

        override suspend fun edit(
            id: String,
            nameEn: String,
            nameAr: String,
            emoji: String?,
        ): CategoryResult {
            val names = names(nameEn, nameAr) ?: return CategoryResult.EmptyName
            val result =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val old = dao.findCategory(id)
                        when {
                            old == null -> CategoryResult.NotFound
                            isDuplicate(names, old.parentId, id) -> CategoryResult.Duplicate
                            else -> {
                                dao.updateDetails(id, names.first, names.second, emoji?.trim()?.takeIf { it.isNotEmpty() })
                                CategoryResult.Ok(id)
                            }
                        }
                    }
                }
            if (result is CategoryResult.Ok) signal.changed()
            return result
        }

        override suspend fun setArchived(
            id: String,
            archived: Boolean,
        ): CategoryResult {
            val changed = eraseLock.mutex.withLock { dao.setArchived(id, archived) }
            if (changed == 0) return CategoryResult.NotFound
            signal.changed()
            return CategoryResult.Ok(id)
        }

        override suspend fun move(
            id: String,
            up: Boolean,
        ): CategoryResult {
            val result =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val me = dao.findCategory(id)
                        if (me == null) {
                            CategoryResult.NotFound
                        } else {
                            val siblings =
                                dao
                                    .allCategories()
                                    .filter { it.parentId == me.parentId }
                                    .sortedWith(compareBy({ it.sortOrder }, { it.nameEn }))
                                    .toMutableList()
                            val from = siblings.indexOfFirst { it.id == id }
                            val to = if (up) from - 1 else from + 1
                            if (to in siblings.indices) {
                                siblings.add(to, siblings.removeAt(from))
                                siblings.forEachIndexed { i, c -> dao.setSortOrder(c.id, (i + 1) * ORDER_STEP) }
                            }
                            CategoryResult.Ok(id)
                        }
                    }
                }
            if (result is CategoryResult.Ok) signal.changed()
            return result
        }

        override suspend fun delete(id: String): CategoryResult {
            val result =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val c = dao.findCategory(id)
                        when {
                            c == null -> CategoryResult.NotFound
                            c.system -> CategoryResult.SystemProtected
                            dao.usageCount(id) > 0 -> CategoryResult.InUse
                            else -> {
                                dao.deleteCustom(id)
                                CategoryResult.Ok(id)
                            }
                        }
                    }
                }
            if (result is CategoryResult.Ok) signal.changed()
            return result
        }

        /** (English, Arabic): a blank side takes the other, so a category always has both. */
        private fun names(
            en: String,
            ar: String,
        ): Pair<String, String>? {
            val e = en.trim().capCodePoints(MAX_NAME)
            val a = ar.trim().capCodePoints(MAX_NAME)
            if (e.isEmpty() && a.isEmpty()) return null
            return (e.ifEmpty { a }) to (a.ifEmpty { e })
        }

        private suspend fun isDuplicate(
            names: Pair<String, String>,
            parentId: String?,
            selfId: String?,
        ): Boolean {
            val mine = setOf(CategoryNameKey.of(names.first), CategoryNameKey.of(names.second))
            return dao.allCategories().any { c ->
                c.parentId == parentId &&
                    c.id != selfId &&
                    (CategoryNameKey.of(c.nameEn) in mine || CategoryNameKey.of(c.nameAr) in mine)
            }
        }

        private suspend fun nextOrder(parentId: String?): Int {
            val siblings = dao.allCategories().filter { it.parentId == parentId }
            val max = siblings.map { it.sortOrder }.filter { it < PINNED_LAST || parentId != null }.maxOrNull() ?: 0
            return max + ORDER_STEP
        }
    }
