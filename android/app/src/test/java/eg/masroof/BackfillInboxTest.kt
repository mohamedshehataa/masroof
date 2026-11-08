package eg.masroof

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillRange
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.ContentResolverSmsInbox
import eg.masroof.ingest.backfill.InboxPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** One query the provider received: what was asked for, and about whom. */
class ProviderQuery(
    val projection: List<String>,
    val selection: String?,
    val args: List<String>,
)

/** A stand-in for the system SMS provider: a real SQLite table, so the reader's selection and sort order are really executed. */
class FakeSmsProvider : ContentProvider() {
    private lateinit var table: SQLiteDatabase
    var denied = false
    var lastSort: String? = null
    val queries = mutableListOf<ProviderQuery>()

    override fun onCreate(): Boolean {
        table = SQLiteDatabase.create(null)
        table.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY, address TEXT, body TEXT, date INTEGER, date_sent INTEGER)")
        return true
    }

    fun add(
        id: Long,
        address: String,
        body: String,
        date: Long,
        sent: Long = date,
    ) = table.execSQL("INSERT INTO sms VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(id, address, body, date, sent))

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (denied) throw SecurityException("READ_SMS")
        lastSort = sortOrder
        queries += ProviderQuery(projection?.toList().orEmpty(), selection, selectionArgs?.toList().orEmpty())
        return table.query("sms", projection, selection, selectionArgs, null, null, sortOrder)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

/** STORY-186: the inbox reader (cursor paging through the content resolver) and the start / continue / cancel controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillInboxTest {
    private lateinit var context: Context
    private lateinit var provider: FakeSmsProvider
    private lateinit var inbox: ContentResolverSmsInbox

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms").get()
        inbox = ContentResolverSmsInbox(context.contentResolver)
    }

    @Test
    fun `REQ-SMS-101 the reader returns the range oldest first, in pages, resuming after a position`() {
        // ids are not in date order, and three share a date: (date, id) is the order and the cursor
        provider.add(7, "CIB", "late", 5_000)
        provider.add(3, "CIB", "tie b", 2_000)
        provider.add(2, "CIB", "tie a", 2_000)
        provider.add(9, "CIB", "tie c", 2_000)
        provider.add(1, "CIB", "too old", 500)
        provider.add(8, "CIB", "too new", 9_000)
        val first = inbox.page(1_000, 9_000, setOf("CIB"), null, 2)
        assertEquals(listOf(2L, 3L), first.map { it.id })
        val second = inbox.page(1_000, 9_000, setOf("CIB"), InboxPosition(first.last().dateMillis, first.last().id), 2)
        assertEquals(listOf(9L, 7L), second.map { it.id })
        val third = inbox.page(1_000, 9_000, setOf("CIB"), InboxPosition(second.last().dateMillis, second.last().id), 2)
        assertTrue(third.isEmpty())
        assertTrue("the order is asked of the provider", provider.lastSort.orEmpty().startsWith("date ASC, _id ASC"))
    }

    @Test
    fun `REQ-SMS-101 the address pass reads senders only, in the range`() {
        provider.add(1, "CIB", "a", 2_000)
        provider.add(2, "Pizza", "b", 3_000)
        provider.add(3, "CIB", "c", 99_000)
        val seen = mutableListOf<String>()
        inbox.forEachAddress(1_000, 10_000) { seen += it }
        assertEquals(listOf("CIB", "Pizza"), seen.sorted())
    }

    @Test
    fun `REQ-SMS-101 the sent time is used when the provider has one, else the received time`() {
        provider.add(1, "CIB", "a", date = 2_000, sent = 1_500)
        provider.add(2, "CIB", "b", date = 3_000, sent = 0)
        val rows = inbox.page(0, 10_000, setOf("CIB"), null, 10)
        assertEquals(listOf(1_500L, 3_000L), rows.map { it.stampMillis })
    }

    @Test
    fun `REQ-SMS-101 a message row never prints its body`() {
        provider.add(1, "CIB", "secret body 123456", 2_000)
        val row = inbox.page(0, 10_000, setOf("CIB"), null, 10).single()
        assertFalse(row.toString().contains("secret"))
    }

    @Test
    fun `REQ-SMS-103 a missing READ_SMS surfaces as a SecurityException for the engine to handle`() {
        provider.denied = true
        assertThrows(SecurityException::class.java) { inbox.page(0, 10_000, setOf("CIB"), null, 10) }
        assertThrows(SecurityException::class.java) { inbox.forEachAddress(0, 10_000) {} }
    }

    private fun controller(clock: Clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)): Pair<BackfillController, BackfillStore> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val store = BackfillStore(context.getSharedPreferences("controller-test", Context.MODE_PRIVATE))
        return BackfillController(WorkManager.getInstance(context), store, clock) to store
    }

    @Test
    fun `REQ-SMS-101 Start begins a run for the chosen range in Africa Cairo and queues unique work`() {
        val (controller, store) = controller()
        controller.start(BackfillRange.Days(90))
        val s = store.state.value
        assertEquals(BackfillPhase.RUNNING, s.phase)
        assertEquals("D90", s.rangeKey)
        // 2026-11-08 minus 90 days = 2026-08-10, from the start of that day in Cairo (UTC+2 in August: DST)
        val expected =
            LocalDate
                .of(2026, 8, 10)
                .atStartOfDay(eg.masroof.core.CairoTime.zone)
                .toInstant()
                .toEpochMilli()
        assertEquals(expected, s.fromMillis)
        assertEquals(-1, s.total)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("sms-backfill").get()
        assertEquals(1, work.size)
        // Start twice: still one unit of work
        controller.start(BackfillRange.Days(90))
        assertEquals(
            1,
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork("sms-backfill")
                .get()
                .size,
        )
    }

    @Test
    fun `REQ-SMS-102 Start after a stop with the same range continues, another range starts over`() {
        val (controller, store) = controller()
        store.save(
            BackfillState(
                BackfillPhase.STOPPED,
                "D90",
                10L,
                20L,
                total = 50,
                read = 30,
                entries = 20,
                review = 4,
                lastDateMillis = 15L,
                lastId = 7L,
            ),
        )
        controller.start(BackfillRange.Days(90))
        val continued = store.state.value
        assertEquals(BackfillPhase.RUNNING, continued.phase)
        assertEquals(30, continued.read)
        assertEquals(InboxPosition(15L, 7L), continued.position)
        store.save(continued.copy(phase = BackfillPhase.STOPPED))
        controller.start(BackfillRange.Days(30))
        val fresh = store.state.value
        assertEquals(0, fresh.read)
        assertNull(fresh.position)
        assertEquals("D30", fresh.rangeKey)
    }

    @Test
    fun `REQ-SMS-103 Cancel marks the run stopped at once and keeps its counts`() =
        kotlinx.coroutines.test.runTest {
            val (controller, store) = controller()
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", 10L, 20L, total = 50, read = 30, entries = 20, review = 4))
            controller.cancel()
            val s = store.state.value
            assertEquals(BackfillPhase.STOPPED, s.phase)
            assertEquals(20, s.entries)
        }

    @Test
    fun `REQ-SMS-101 a custom start date and its key round trip, and an unknown key is not a range`() {
        val range = BackfillRange.Since(LocalDate.of(2026, 8, 1))
        assertEquals(range, BackfillRange.parse(range.key))
        assertEquals(BackfillRange.Days(180), BackfillRange.parse("D180"))
        assertNull(BackfillRange.parse(""))
        assertNull(BackfillRange.parse("Cnonsense"))
        val (controller, store) = controller()
        controller.start(range)
        assertEquals(
            LocalDate
                .of(2026, 8, 1)
                .atStartOfDay(eg.masroof.core.CairoTime.zone)
                .toInstant()
                .toEpochMilli(),
            store.state.value.fromMillis,
        )
    }

    @Test
    fun `REQ-SMS-102 the 30 day memory forgets old ids, keeps recent ones, and survives a reload`() {
        val store = BackfillStore(context.getSharedPreferences("memory-test", Context.MODE_PRIVATE))
        store.mark("old", 1_000L)
        store.mark("recent", 9_000L)
        store.forgetBefore(5_000L)
        assertFalse(store.handled("old", 9_000L))
        assertTrue(store.handled("recent", 9_000L))
        val reloaded = BackfillStore(context.getSharedPreferences("memory-test", Context.MODE_PRIVATE))
        assertTrue(reloaded.handled("recent", 9_000L))
        store.save(BackfillState(BackfillPhase.DONE, "D90", 1L, 2L, total = 3, read = 3, entries = 2, review = 1))
        assertEquals(2, BackfillStore(context.getSharedPreferences("memory-test", Context.MODE_PRIVATE)).state.value.entries)
    }

    @Test
    fun `REQ-SMS-103 the manifest asks for READ_SMS next to RECEIVE_SMS and adds no exported component for it`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.READ_SMS"))
        assertTrue(manifest.contains("android.permission.RECEIVE_SMS"))
        // the exported components are exactly the ones that existed before this story
        val exported = Regex("android:exported=\"true\"").findAll(manifest).count()
        assertEquals(4, exported)
    }
}
