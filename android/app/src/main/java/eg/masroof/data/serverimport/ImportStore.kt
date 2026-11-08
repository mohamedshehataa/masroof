package eg.masroof.data.serverimport

import android.annotation.SuppressLint
import android.content.SharedPreferences

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

    /** A completed import, or "Erase all data": the mappings and flags go. */
    fun clear()
}

/** SharedPreferences-backed [ImportStore] (file `masroof_server_import`): ids and flags only, in app-private storage. */
@SuppressLint("ApplySharedPref")
class PrefsImportStore(
    private val prefs: SharedPreferences,
) : ImportStore {
    override fun categoryOf(serverId: String): String? = prefs.getString("$CATEGORY$serverId", null)

    override fun mapCategory(
        serverId: String,
        localId: String,
    ) {
        prefs.edit().putString("$CATEGORY$serverId", localId).commit()
    }

    override fun memberOf(serverId: String): String? = prefs.getString("$MEMBER$serverId", null)

    override fun mapMember(
        serverId: String,
        localId: String,
    ) {
        prefs.edit().putString("$MEMBER$serverId", localId).commit()
    }

    override var pending: Boolean
        get() = prefs.getBoolean(PENDING, false)
        set(value) {
            prefs.edit().putBoolean(PENDING, value).commit()
        }

    override var forgetPending: Boolean
        get() = prefs.getBoolean(FORGET, false)
        set(value) {
            prefs.edit().putBoolean(FORGET, value).commit()
        }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val CATEGORY = "cat:"
        const val MEMBER = "mem:"
        const val PENDING = "pending"
        const val FORGET = "forget"
    }
}
