package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.OutboxConverter
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

// The old upload-queue tests lived here; REQ-AND-133 turns the queue into local entries (STORY-169).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queue(
        id: String,
        minor: Long = 12_500,
        currency: String = "EGP",
        failed: Boolean = false,
        at: Long = 1_000,
    ) {
        db.outbox().insert(
            OutboxEntity(id, minor, currency, at, "cat-car.fuel", "acc-1", "lunch", attempts = 2, failed = failed, createdAtMillis = at),
        )
        db.expenses().insert(
            ExpenseEntity(id, minor, currency, at, null, "cat-car.fuel", "acc-1", "lunch", pending = true, failed = failed),
        )
    }

    private fun converter(clock: Clock = this.clock) = OutboxConverter(db, clock, UuidIdFactory())

    @Test
    fun `REQ-AND-133 each queued entry becomes one local expense with a MANUAL source and the queue is emptied`() =
        runTest {
            queue("c1")
            queue("c2", failed = true, at = 2_000)
            assertEquals(2, converter().convertAll())

            assertTrue(db.outbox().all().isEmpty())
            val rows = db.expenses().all()
            assertEquals(2, rows.size)
            assertTrue(rows.none { it.pending || it.failed })
            assertNull("the old pending cache row is gone", db.expenses().find("c1"))
            for (row in rows) {
                assertEquals("CONFIRMED", row.status)
                assertEquals("EXPENSE", row.type)
                assertEquals(12_500L, row.amountMinor)
                assertEquals("EGP", row.currency)
                assertEquals("cat-car.fuel", row.categoryId)
                assertEquals("acc-1", row.accountId)
                assertEquals("lunch", row.notes)
            }
            val sources = db.sources().all()
            assertEquals(setOf("c1", "c2"), sources.map { it.localSourceId }.toSet())
            assertTrue(sources.all { it.channel == "MANUAL" })
            assertEquals(rows.map { it.id }.toSet(), sources.map { it.expenseId }.toSet())
        }

    @Test
    fun `REQ-AND-133 a second run converts nothing and duplicates nothing`() =
        runTest {
            queue("c1")
            assertEquals(1, converter().convertAll())
            assertEquals(0, converter().convertAll())
            assertEquals(1, db.expenses().count())
            assertEquals(1, db.sources().all().size)
        }

    @Test
    fun `REQ-AND-133 a run interrupted between rows is finished by the next start without duplicates`() =
        runTest {
            queue("c1", at = 1_000)
            queue("c2", at = 2_000)
            queue("c3", at = 3_000)
            // the process "dies" while converting the second row: its clock read throws, so its transaction rolls back
            val calls = AtomicInteger()
            val dying =
                object : Clock() {
                    override fun getZone() = clock.zone

                    override fun withZone(zone: java.time.ZoneId?) = this

                    override fun instant(): Instant = if (calls.incrementAndGet() == 2) error("killed") else clock.instant()
                }
            try {
                converter(dying).convertAll()
                fail("the interrupted run should have thrown")
            } catch (expected: IllegalStateException) {
                // process death
            }
            assertEquals(1, db.sources().all().size)
            assertEquals(
                listOf("c2", "c3"),
                db
                    .outbox()
                    .all()
                    .map { it.clientId }
                    .sorted(),
            )

            assertEquals(2, converter().convertAll())
            assertEquals(3, db.expenses().count())
            assertEquals(
                setOf("c1", "c2", "c3"),
                db
                    .sources()
                    .all()
                    .map { it.localSourceId }
                    .toSet(),
            )
            assertTrue(db.outbox().all().isEmpty())
        }

    @Test
    fun `REQ-AND-133 a row already converted but still queued only loses its queue entry`() =
        runTest {
            queue("c1")
            converter().convertAll()
            db.outbox().insert(OutboxEntity("c1", 12_500, "EGP", 1_000, null, null, null, createdAtMillis = 1_000))
            assertEquals(0, converter().convertAll())
            assertTrue(db.outbox().all().isEmpty())
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-AND-133 a corrupt row with a zero amount or a bad currency becomes a review expense with its stored values`() =
        runTest {
            queue("zero", minor = 0, at = 1_000)
            queue("bad", currency = "EG", at = 2_000)
            queue("ok", at = 3_000)
            assertEquals(3, converter().convertAll())
            val bySource = db.sources().all().associate { it.localSourceId to db.expenses().find(it.expenseId)!! }
            assertEquals("PENDING_REVIEW", bySource.getValue("zero").status)
            assertEquals(0L, bySource.getValue("zero").amountMinor)
            assertEquals("PENDING_REVIEW", bySource.getValue("bad").status)
            assertEquals("EG", bySource.getValue("bad").currency)
            assertEquals("CONFIRMED", bySource.getValue("ok").status)
        }

    @Test
    fun `REQ-AND-133 a receipt attached to the old pending row follows the new expense`() =
        runTest {
            queue("c1")
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis)
                VALUES ('r1','c1','f','image/jpeg',1,'h',NULL,1)
                """.trimIndent(),
            )
            converter().convertAll()
            val newId =
                db
                    .sources()
                    .all()
                    .single()
                    .expenseId
            db.openHelper.writableDatabase.query("SELECT expenseId FROM receipt WHERE id = 'r1'").use {
                it.moveToFirst()
                assertEquals(newId, it.getString(0))
            }
        }
}
