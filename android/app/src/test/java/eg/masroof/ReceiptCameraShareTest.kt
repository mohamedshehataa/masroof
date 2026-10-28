package eg.masroof

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.camera.CameraFiles
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.receipts.PdfProbe
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.ShareIntentParser
import eg.masroof.ui.viewmodel.PendingReceiptViewModel
import eg.masroof.ui.viewmodel.ReceiptListViewModel
import eg.masroof.ui.viewmodel.ShareOutcome
import eg.masroof.ui.viewmodel.ShareReceiptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptCameraShareTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var store: ReceiptStore
    private val now = Instant.parse("2026-10-28T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val pdf = "%PDF-1.4 body".toByteArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(ctx.filesDir, "receipts-share-${System.nanoTime()}")
        store = ReceiptStore(db, dir, PdfProbe { 1 }, clock, EraseLock(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
        CameraFiles.clean(ctx)
        Dispatchers.resetMain()
    }

    private fun send(
        action: String,
        stream: Any? = null,
    ) = Intent(action).apply {
        type = "application/pdf"
        when (stream) {
            is Uri -> putExtra(Intent.EXTRA_STREAM, stream)
            is List<*> -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(stream.filterIsInstance<Uri>()))
        }
    }

    private fun foreign(n: Int) = Uri.parse("content://com.other.app.files/doc/$n")

    // ---- manifest (REQ-RCPT-021, REQ-RCPT-022) ----

    @Test
    fun `REQ-RCPT-021 declares no camera or storage permission and the camera provider is not exported`() {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        val perms = info.requestedPermissions.orEmpty().toSet()
        assertFalse(perms.any { it.endsWith(".CAMERA") })
        assertFalse(perms.any { it.contains("STORAGE") || it.contains("READ_MEDIA") })
        val provider = info.providers.orEmpty().single { it.authority == CameraFiles.authority(ctx) }
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun `REQ-RCPT-022 the share target is exported and handles only SEND and SEND_MULTIPLE`() {
        val pm = ctx.packageManager
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").setPackage(ctx.packageName)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").setPackage(ctx.packageName)
        val view = Intent(Intent.ACTION_VIEW).setType("application/pdf").setPackage(ctx.packageName)
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(ctx.packageName)
        assertEquals(1, pm.queryIntentActivities(send, 0).size)
        assertEquals(1, pm.queryIntentActivities(multiple, 0).size)
        assertTrue(pm.queryIntentActivities(view, 0).isEmpty())
        assertTrue(pm.queryIntentActivities(text, 0).isEmpty())
        assertTrue(
            pm
                .queryIntentActivities(send, 0)
                .single()
                .activityInfo.exported,
        )
    }

    // ---- camera ----

    // Robolectric offers no non-deprecated way to register an intent resolution
    @Suppress("DEPRECATION")
    @Test
    fun `REQ-RCPT-021 camera option is hidden when no activity resolves image capture`() {
        assertFalse(CameraFiles.available(ctx))
        val resolve =
            android.content.pm.ResolveInfo().apply {
                activityInfo =
                    android.content.pm.ActivityInfo().apply {
                        packageName = "com.cam"
                        name = "Cam"
                    }
            }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), resolve)
        assertTrue(CameraFiles.available(ctx))
    }

    @Test
    fun `REQ-RCPT-021 camera target is a cache file behind the non-exported provider and a backed-out camera leaves nothing`() {
        val file = CameraFiles.newTarget(ctx)
        assertTrue(file.path.startsWith(File(ctx.cacheDir, CameraFiles.DIR).path))
        // FileProvider.getUriForFile is not exercised here: its canonical-root check fails on Windows temp paths under Robolectric.
        assertEquals("${ctx.packageName}.receipts.camera", CameraFiles.authority(ctx))
        assertNotNull(CameraFiles.fileNamed(ctx, file.name))
        assertNull(CameraFiles.fileNamed(ctx, "../x.jpg"))
        CameraFiles.clean(ctx)
        assertFalse(file.exists())
    }

    @Test
    fun `REQ-RCPT-021 a camera photo from Receipt only is stored unattached`() =
        runTest {
            val result = store.attach(null) { ByteArrayInputStream(pdf) }
            assertTrue(result is eg.masroof.data.receipts.AttachResult.Added)
            assertNull(db.receipts().find((result as eg.masroof.data.receipts.AttachResult.Added).receiptId)!!.expenseId)
        }

    // ---- share intent parsing (REQ-RCPT-022) ----

    @Test
    fun `REQ-RCPT-022 file scheme, schemeless and own authority are refused`() {
        val own = Uri.parse("content://${ctx.packageName}.receipts.camera/camera/a.jpg")
        val plan =
            ShareIntentParser.parse(
                send(Intent.ACTION_SEND_MULTIPLE, listOf(Uri.parse("file:///sdcard/a.pdf"), Uri.parse("/sdcard/a.pdf"), own, foreign(1))),
                ctx.packageName,
            )
        assertEquals(listOf(false, false, false, true), plan.items.map { it != null })
        assertFalse(plan.overflow)
    }

    @Test
    fun `REQ-RCPT-022 a single shared item is accepted and text only or another action yields nothing`() {
        assertEquals(1, ShareIntentParser.parse(send(Intent.ACTION_SEND, foreign(1)), ctx.packageName).items.size)
        val textOnly = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_TEXT, "hello")
        assertTrue(ShareIntentParser.parse(textOnly, ctx.packageName).items.isEmpty())
        assertTrue(ShareIntentParser.parse(send(Intent.ACTION_VIEW, foreign(1)), ctx.packageName).items.isEmpty())
    }

    @Test
    fun `REQ-RCPT-022 eleven items take the first ten and flag the overflow`() {
        val plan = ShareIntentParser.parse(send(Intent.ACTION_SEND_MULTIPLE, (1..11).map { foreign(it) }), ctx.packageName)
        assertEquals(10, plan.items.size)
        assertTrue(plan.overflow)
    }

    @Test
    fun `REQ-RCPT-022 mixed valid and invalid items store the good ones and name the failing reasons`() =
        runTest {
            val good = foreign(1)
            val bad = foreign(2)
            val resolver = ctx.contentResolver
            shadowOf(resolver).registerInputStream(good, ByteArrayInputStream(pdf))
            shadowOf(resolver).registerInputStream(bad, ByteArrayInputStream("not a receipt at all".toByteArray()))
            val vm = ShareReceiptViewModel(store)
            val intent = send(Intent.ACTION_SEND_MULTIPLE, listOf(good, Uri.parse("file:///x.pdf"), bad))
            vm.start(intent, resolver, ctx.packageName)
            val done = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.finished } } }
            assertEquals(ShareOutcome.Stored, done.outcomes[0])
            assertEquals(ShareOutcome.CantShare, done.outcomes[1])
            assertTrue(done.outcomes[2] is ShareOutcome.Refused)
            assertFalse(done.allGood)
            assertEquals(1, db.receipts().count())
            assertNull(
                db
                    .receipts()
                    .observeUnattached()
                    .first()
                    .single()
                    .expenseId,
            )
        }

    // ---- Receipts list (REQ-RCPT-023) ----

    private suspend fun addAt(
        text: String,
        millis: Long,
    ): String {
        val clockAt = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
        val s = ReceiptStore(db, dir, PdfProbe { 1 }, clockAt, EraseLock(), Dispatchers.Unconfined)
        return (
            s.attach(
                null,
            ) { ByteArrayInputStream("%PDF-1.4 $text".toByteArray()) } as eg.masroof.data.receipts.AttachResult.Added
        ).receiptId
    }

    private fun entry(
        id: String,
        at: Long,
    ) = ExpenseEntity(id, 12_500, "EGP", at, "Spinneys", null, null, null, pending = false, createdAtMillis = at, updatedAtMillis = at)

    @Test
    fun `REQ-RCPT-023 unattached receipts list newest first with a stale hint over 90 days`() =
        runTest {
            val old = addAt("old", now.toEpochMilli() - TimeUnit.DAYS.toMillis(100))
            val fresh = addAt("fresh", now.toEpochMilli() - 1000)
            val vm = ReceiptListViewModel(store, db, clock)
            val s = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.loaded && it.receipts.size == 2 } } }
            assertEquals(listOf(fresh, old), s.receipts.map { it.id })
            assertTrue(s.receipts[1].createdAtMillis < s.staleBeforeMillis)
            assertFalse(s.receipts[0].createdAtMillis < s.staleBeforeMillis)
        }

    @Test
    fun `REQ-RCPT-023 old unattached receipts are kept by the sweeper`() =
        runTest {
            val id = addAt("ancient", now.toEpochMilli() - TimeUnit.DAYS.toMillis(400))
            store.sweep()
            val row = db.receipts().find(id)!!
            assertTrue(store.fileOf(row).isFile)
        }

    @Test
    fun `REQ-RCPT-023 attach to entry picks only entries of the last 30 days`() =
        runTest {
            val id = addAt("a", now.toEpochMilli())
            db.expenses().insert(entry("recent", now.toEpochMilli() - TimeUnit.DAYS.toMillis(3)))
            db.expenses().insert(entry("ancient", now.toEpochMilli() - TimeUnit.DAYS.toMillis(45)))
            val vm = ReceiptListViewModel(store, db, clock)
            val s = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.loaded && it.receipts.isNotEmpty() } } }
            assertEquals(listOf("recent"), s.choices.map { it.id })
            vm.askAttach(s.receipts.single())
            vm.attachTo("recent")
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.receipts.isEmpty() } } }
            assertEquals("recent", db.receipts().find(id)!!.expenseId)
        }

    @Test
    fun `REQ-RCPT-023 delete removes the row and the file`() =
        runTest {
            val id = addAt("gone", now.toEpochMilli())
            val file = store.fileOf(db.receipts().find(id)!!)
            val vm = ReceiptListViewModel(store, db, clock)
            val s = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.receipts.isNotEmpty() } } }
            vm.askRemove(s.receipts.single())
            vm.confirmRemove()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.receipts.isEmpty() } } }
            assertNull(db.receipts().find(id))
            withContext(Dispatchers.Default) { withTimeout(10_000) { while (file.exists()) kotlinx.coroutines.delay(10) } }
        }

    @Test
    fun `REQ-RCPT-023 new entry from a receipt attaches on save and a cancel leaves it waiting`() =
        runTest {
            val id = addAt("n", now.toEpochMilli())
            db.expenses().insert(entry("saved", now.toEpochMilli()))
            val cancelled = PendingReceiptViewModel(store, db, SavedStateHandle(mapOf("receipt" to id)))
            assertEquals(id, cancelled.receiptId)
            assertNull(db.receipts().find(id)!!.expenseId) // nothing happens until a save
            val vm = PendingReceiptViewModel(store, db, SavedStateHandle(mapOf("receipt" to id)))
            vm.attachTo("saved")
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (db.receipts().find(id)!!.expenseId ==
                        null
                    ) {
                        kotlinx.coroutines.delay(10)
                    }
                }
            }
            assertEquals("saved", db.receipts().find(id)!!.expenseId)
            assertNull(PendingReceiptViewModel(store, db, SavedStateHandle()).receiptId)
        }
}
