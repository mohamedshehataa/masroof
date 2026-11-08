package eg.masroof

import android.content.Context
import androidx.room.Room
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.DefaultAnswerRepository
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that moves one second on every read: tests with a fixed clock hid every "updated since" problem. */
internal class TickingClock(
    start: Instant = ANSWER_NOW,
) : Clock() {
    private var t = start.toEpochMilli()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant {
        t += 1000
        return Instant.ofEpochMilli(t)
    }
}

/** The small world of the pair tests: CIB and NBE, in-memory Room, the real repository and editor, a ticking clock. */
internal class AnswerWorld(
    context: Context,
) {
    val clock = TickingClock()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val prefs = FakeAnswerPrefs()
    val settings = FakeSettings()
    private var counter = 0
    val repo: DefaultAnswerRepository
    val editor: DefaultExpenseEditor

    init {
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        repo =
            DefaultAnswerRepository(
                db,
                prefs,
                settings,
                clock,
                object : IdFactory {
                    override fun next() = "gen-${counter++}"
                },
                LocalDataSignal(),
                EraseLock(),
                { "Cash" },
            )
        editor = DefaultExpenseEditor(db, LocalDataSignal(), clock, EraseLock(), AnswerPairLookup(repo))
    }

    fun close() = db.close()

    suspend fun twoBanks() {
        db.lookups().upsertAccounts(listOf(answerAccount("cib", "CIB", last4 = "1111"), answerAccount("nbe", "NBE", last4 = "2222")))
        db.accountBalances().insert(AccountBalanceEntity("c1", "cib", 2_000_000, "EGP", ago(20), "MANUAL", ago(20)))
        db.accountBalances().insert(AccountBalanceEntity("c2", "nbe", 1_000_000, "EGP", ago(20), "MANUAL", ago(20)))
    }

    suspend fun put(vararg e: ExpenseEntity) = e.forEach { db.expenses().insert(it) }

    /** Both banks announced the same 5,000 (the send first, the arrival an hour later). */
    suspend fun announcedTransfer() {
        twoBanks()
        put(
            answerEntry("out", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "INSTAPAY", at = ago(1)),
            answerEntry("in", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", at = ago(0, 23)),
        )
    }

    suspend fun worth(): Long =
        WealthRepository(db, clock)
            .observeAccountViews()
            .first()
            .sumOf { it.balanceMinor ?: 0L }

    suspend fun type(id: String) = db.expenses().find(id)!!.type
}
