package eg.masroof.data.serverimport

import eg.masroof.data.answers.DuplicateMatcher
import eg.masroof.data.answers.EntrySources
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.edit.DUPLICATE_LOOKBACK_DAYS

/** What the run knows when it writes a chunk of expenses. */
internal class ImportContext(
    /** server category id to the local category id (a default category, or the server's own id for a custom one). */
    val categories: Map<String, String>,
    val accountIds: Set<String>,
    /** server member id to the local member id (the signed-in member is the phone's own "Me" row). */
    val members: Map<String, String>,
    /** When this run started: the `receivedAt` of the import source rows it makes and the `updatedAt` of the rows it inserts. */
    val stampMillis: Long,
    val nowMillis: Long,
    /** Entries of a refund or transfer link, or that a gold lot or an installment payment points at (STORY-208): never touched. */
    val blocked: Set<String>,
)

internal enum class WriteOutcome {
    /** A new row and its `SERVER_IMPORT` source. */
    INSERTED,

    /** A row this import made earlier that nobody changed on the phone: refreshed from the server. */
    REFRESHED,

    /** Already on the phone and left exactly as it is (changed on the phone since, linked, or not made by the import). */
    KEPT,

    /** The id is taken by a row that is not this server expense: nothing is written. */
    CLASH,
}

private const val MS_PER_DAY = 24L * 60 * 60 * 1000
private const val PENDING_REVIEW = "PENDING_REVIEW"
private const val UNKNOWN_METHOD = "UNKNOWN"

/**
 * The ONE place the server import writes `expense` rows (listed in `ExpenseWritePathTest`). It only ever INSERTs a new row for a
 * server id, or UPDATEs the row that server id already made, and only while nothing changed on the phone and no link owns it, so
 * it can never touch a linked local row, a typed entry or a capture. Callers run it inside one Room transaction.
 *
 * "Changed on the phone" is exact: the row has no `SERVER_IMPORT` source stamp, it is soft-deleted, or its `updatedAtMillis` is
 * later than the stamp (`receivedAtMillis` of its import source), which is the time the run that made it started. Every editor,
 * merge, answer and delete on the phone writes `updatedAtMillis` with the clock, so they all move it past the stamp.
 */
internal class ImportWriter(
    private val db: AppDatabase,
) {
    suspend fun write(
        e: ServerExpense,
        ctx: ImportContext,
    ): WriteOutcome {
        val existing = db.expenses().findByServerId(e.id)
        return if (existing == null) insertNew(e, ctx) else refresh(existing, e, ctx)
    }

    private suspend fun insertNew(
        e: ServerExpense,
        ctx: ImportContext,
    ): WriteOutcome {
        // the local id of an imported row is the server id: a row that already has it, without that server id, is not ours
        if (db.expenses().find(e.id) != null) return WriteOutcome.CLASH
        val plain = entity(e, ctx, statusOf(e))
        val row = if (hasLocalTwin(plain, ctx)) plain.copy(status = PENDING_REVIEW) else plain
        db.expenses().insert(row)
        db.sources().insert(
            ExpenseSourceEntity("import:${e.id}", row.id, DuplicateMatcher.SERVER_IMPORT, "server:${e.id}", ctx.stampMillis, null, null),
        )
        return WriteOutcome.INSERTED
    }

    private suspend fun refresh(
        existing: ExpenseEntity,
        e: ServerExpense,
        ctx: ImportContext,
    ): WriteOutcome {
        val stamp =
            db
                .sources()
                .forExpense(existing.id)
                .firstOrNull { it.channel == DuplicateMatcher.SERVER_IMPORT }
                ?.receivedAtMillis
        val changed = stamp == null || existing.deletedAtMillis != null || existing.updatedAtMillis > stamp
        if (changed || existing.id in ctx.blocked) return WriteOutcome.KEPT
        // the status stays: the household's decision about a possible duplicate is not the server's to undo
        val fresh =
            entity(e, ctx, existing.status).copy(
                createdAtMillis = existing.createdAtMillis,
                updatedAtMillis = existing.updatedAtMillis,
                timeKnown = existing.timeKnown,
                excludedFromTotals = existing.excludedFromTotals,
            )
        if (fresh != existing) db.expenses().update(fresh)
        return WriteOutcome.REFRESHED
    }

    /** REQ-DEDUP-066: the same rule the "Possible duplicate" card uses; a linked local row never counts. */
    private suspend fun hasLocalTwin(
        server: ExpenseEntity,
        ctx: ImportContext,
    ): Boolean {
        if (server.occurredAtMillis < ctx.nowMillis - DUPLICATE_LOOKBACK_DAYS * MS_PER_DAY) return false
        val asPending = server.copy(status = PENDING_REVIEW)
        val serverSources = EntrySources(setOf(DuplicateMatcher.SERVER_IMPORT))
        return db
            .expenses()
            .serverTwinCandidates(
                server.amountMinor,
                server.currency,
                server.type,
                server.occurredAtMillis - DuplicateMatcher.SERVER_MAX_GAP_MS,
                server.occurredAtMillis + DuplicateMatcher.SERVER_MAX_GAP_MS,
            ).any { local ->
                val sources = db.sources().forExpense(local.id)
                val localSources = EntrySources(sources.mapTo(HashSet()) { it.channel }, sources.firstNotNullOfOrNull { it.senderId })
                DuplicateMatcher.matchesServer(local, localSources, asPending, serverSources, ctx.blocked)
            }
    }

    private fun statusOf(e: ServerExpense): String = if (e.status == "CONFIRMED") "CONFIRMED" else PENDING_REVIEW

    private fun entity(
        e: ServerExpense,
        ctx: ImportContext,
        status: String,
    ) = ExpenseEntity(
        id = e.id,
        amountMinor = e.amountMinor,
        currency = e.currency,
        occurredAtMillis = e.occurredAtMillis,
        merchant = e.merchant,
        categoryId = e.categoryId?.let { ctx.categories[it] },
        accountId = e.accountId?.takeIf { it in ctx.accountIds },
        notes = e.notes,
        pending = false,
        failed = false,
        source = DuplicateMatcher.SERVER_IMPORT,
        type = e.type,
        status = status,
        createdAtMillis = e.createdAtMillis.takeIf { it > 0 } ?: e.occurredAtMillis,
        updatedAtMillis = ctx.stampMillis,
        deletedAtMillis = null,
        timeKnown = true,
        paymentMethod = e.paymentMethod?.takeIf { it != UNKNOWN_METHOD },
        instrumentLast4 = null,
        paidByMemberId = e.paidByMemberId?.let { ctx.members[it] },
        serverId = e.id,
        excludedFromTotals = false,
    )
}
