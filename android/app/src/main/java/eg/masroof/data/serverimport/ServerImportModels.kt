package eg.masroof.data.serverimport

/**
 * Typed fields of what the v0.1.x server sent, after the generated client decoded it (STORY-182, ADR-0009 D6). Nothing here is
 * trusted yet: [ServerImporter] validates every value before it reaches Room. The data classes print ids only.
 */
data class ServerExpense(
    val id: String,
    val type: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val createdAtMillis: Long,
    val categoryId: String?,
    val accountId: String?,
    val paidByMemberId: String?,
    val merchant: String?,
    val notes: String?,
    val paymentMethod: String?,
    /** The server soft-deleted it: never imported. */
    val deleted: Boolean = false,
) {
    override fun toString() = "ServerExpense(id=$id)"
}

/** One entry of a downloaded page: a decoded row, or one the generated model could not decode (non-integer amount, unknown enum). */
sealed interface ServerExpenseRow {
    data class Valid(
        val expense: ServerExpense,
    ) : ServerExpenseRow

    data object Invalid : ServerExpenseRow
}

data class ExpensesBatch(
    val rows: List<ServerExpenseRow>,
    /** null on the last page. */
    val nextCursor: String?,
)

data class ServerCategory(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val sortOrder: Int,
    val parentId: String?,
    val color: String?,
    val archived: Boolean,
) {
    override fun toString() = "ServerCategory(id=$id)"
}

data class ServerAccount(
    val id: String,
    val label: String,
    val currency: String,
    val kind: String,
    val institution: String?,
    val last4: String?,
) {
    override fun toString() = "ServerAccount(id=$id)"
}

data class ServerMember(
    val id: String,
    val displayName: String,
) {
    override fun toString() = "ServerMember(id=$id)"
}

data class ServerBudget(
    val id: String,
    val categoryId: String?,
    val period: String,
    val amountMinor: Long,
    val currency: String,
    val rollover: Boolean,
    val alertThresholds: List<Int>,
) {
    override fun toString() = "ServerBudget(id=$id)"
}

/** The two household settings the phone copies; every other server setting is ignored. */
data class ServerSettings(
    val monthStartDay: Int,
    val countAtmWithdrawalsAsSpending: Boolean,
)

/** Why a run stopped. Never carries a message: a server text could hold a value that must not be logged. */
enum class ImportFailureKind {
    /** No connection, a timeout or a dropped connection. */
    NETWORK,

    /** The server answered 5xx (or another status that is not a session problem). */
    SERVER,

    /** The session is gone or the server rejected the refresh token: sign in again. */
    SESSION,

    /** The response broke the contract (oversized string, a page larger than asked, not the documented shape). */
    UNEXPECTED_DATA,

    /** The phone could not write the rows. */
    STORAGE,
}

class ImportException(
    val kind: ImportFailureKind,
) : Exception(kind.name)

/** The read-only calls of the import. Nothing else may call the network (ADR-0009 D6). */
interface ServerImportSource {
    suspend fun expenses(
        cursor: String?,
        limit: Int,
    ): ExpensesBatch

    suspend fun categories(): List<ServerCategory>

    suspend fun accounts(): List<ServerAccount>

    suspend fun members(): List<ServerMember>

    suspend fun budgets(): List<ServerBudget>

    suspend fun settings(): ServerSettings
}

/** Largest page the contract allows (`Limit` parameter). */
const val IMPORT_PAGE_LIMIT = 200
