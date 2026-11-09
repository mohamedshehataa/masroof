package eg.masroof

import eg.masroof.data.serverimport.ImportStore

/** In-memory [ImportStore] for tests. */
internal class MemoryImportStore : ImportStore {
    private val categories = mutableMapOf<String, String>()
    private val members = mutableMapOf<String, String>()
    override var pending = false
    override var forgetPending = false
    override var importedCount = 0

    override fun categoryOf(serverId: String) = categories[serverId]

    override fun mapCategory(
        serverId: String,
        localId: String,
    ) {
        categories[serverId] = localId
    }

    override fun memberOf(serverId: String) = members[serverId]

    override fun mapMember(
        serverId: String,
        localId: String,
    ) {
        members[serverId] = localId
    }

    override fun clear() {
        categories.clear()
        members.clear()
        pending = false
        importedCount = 0
        forgetPending = false
    }
}
