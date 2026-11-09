package eg.masroof.data.serverimport

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What the one-time import remembers between runs (STORY-182): which phone category / member each server id was mapped to (decided
 * on the FIRST run, never re-derived from a name the user may have changed since), whether an import is still pending (so the card
 * stays while the session is gone), and whether forgetting the server was started but not finished. Ids and flags only.
 */
interface ImportStore {
    fun categoryOf(serverId: String): String?

    fun mapCategory(
        serverId: String,
        localId: String,
    )

    fun memberOf(serverId: String): String?

    fun mapMember(
        serverId: String,
        localId: String,
    )

    /** An import was offered (a session existed) and has not completed: the card stays until it does. */
    var pending: Boolean

    /** The run completed and forgetting the server has started: it is retried until the credentials and the URL are gone. */
    var forgetPending: Boolean

    /** How many entries the completed run imported, kept while the forgetting is unfinished so the card can still say so. */
    var importedCount: Int

    /** A completed import, or "Erase all data": the mappings and flags go. */
    fun clear()
}

/** For code paths that have no import state (previews and older tests). */
object NoImportStore : ImportStore {
    override fun categoryOf(serverId: String): String? = null

    override fun mapCategory(
        serverId: String,
        localId: String,
    ) = Unit

    override fun memberOf(serverId: String): String? = null

    override fun mapMember(
        serverId: String,
        localId: String,
    ) = Unit

    override var pending: Boolean = false
    override var forgetPending: Boolean = false
    override var importedCount: Int = 0

    override fun clear() = Unit
}

/**
 * SharedPreferences-backed [ImportStore] (file `masroof_server_import`): ids and flags only, in app-private storage.
 * The writes use commit(): `forgetPending` must be on disk before the forget starts, and a mapping before the rows that use it.
 */
@SuppressLint("ApplySharedPref") // KDoc above: commit() is deliberate (synchronous, durable before the next step)
class PrefsImportStore(
    private val prefs: SharedPreferences,
) : ImportStore {
    override fun categoryOf(serverId: String): String? = prefs.getString(CATEGORY + serverId, null)

    override fun mapCategory(
        serverId: String,
        localId: String,
    ) {
        prefs.edit(commit = true) { putString(CATEGORY + serverId, localId) }
    }

    override fun memberOf(serverId: String): String? = prefs.getString(MEMBER + serverId, null)

    override fun mapMember(
        serverId: String,
        localId: String,
    ) {
        prefs.edit(commit = true) { putString(MEMBER + serverId, localId) }
    }

    override var pending: Boolean
        get() = prefs.getBoolean(PENDING, false)
        set(value) {
            prefs.edit(commit = true) { putBoolean(PENDING, value) }
        }

    override var forgetPending: Boolean
        get() = prefs.getBoolean(FORGET, false)
        set(value) {
            prefs.edit(commit = true) { putBoolean(FORGET, value) }
        }

    override var importedCount: Int
        get() = prefs.getInt(IMPORTED, 0)
        set(value) {
            prefs.edit(commit = true) { putInt(IMPORTED, value) }
        }

    override fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private companion object {
        const val CATEGORY = "cat:"
        const val MEMBER = "mem:"
        const val PENDING = "pending"
        const val FORGET = "forget"
        const val IMPORTED = "imported"
    }
}
