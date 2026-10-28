package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.receipts.PdfProbe
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.Refusal
import eg.masroof.ui.settings.megabytes
import eg.masroof.ui.viewmodel.ReceiptMessage
import eg.masroof.ui.viewmodel.ReceiptsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var vm: ReceiptsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(ctx.filesDir, "receipts-vm-${System.nanoTime()}")
        val clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC)
        val store = ReceiptStore(db, dir, PdfProbe { 1 }, clock, EraseLock(), Dispatchers.Unconfined)
        vm = ReceiptsViewModel(store, db, SavedStateHandle(mapOf("id" to "e1")))
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private val pdfBytes = "%PDF-1.4 body".toByteArray()

    /** Room runs its suspend queries on its own threads: wait (real time) until the state satisfies [cond]. */
    private suspend fun await(cond: (eg.masroof.ui.viewmodel.ReceiptsUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { cond(it) } } }

    @Test
    fun `REQ-RCPT-020 a duplicate says where it is attached and Open shows that receipt`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            db.expenses().insert(ExpenseEntity("e1", 125_00, "EGP", 1_760_000_000_000, null, null, null, null, pending = false))
            vm.add { ByteArrayInputStream(pdfBytes) }
            await { it.receipts.size == 1 && it.adding == 0 }
            assertNull(vm.state.value.message)
            vm.add { ByteArrayInputStream(pdfBytes) }
            val message = await { it.message != null }.message as ReceiptMessage.Duplicate
            assertEquals(125_00L, message.result.amountMinor)
            vm.openDuplicate(message.result.receiptId)
            await { it.viewing != null }
            assertNull(vm.state.value.message)
        }

    @Test
    fun `REQ-RCPT-016 a refused file shows its reason and stores nothing`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            vm.add { ByteArrayInputStream("not an image".toByteArray()) }
            await { it.message != null }
            assertEquals(ReceiptMessage.Error(Refusal.UNSUPPORTED), vm.state.value.message)
            assertEquals(0, vm.state.value.adding)
            vm.dismissMessage()
            assertNull(vm.state.value.message)
        }

    @Test
    fun `REQ-RCPT-018 and 019 remove asks first, then deletes the row and the file from the viewer`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            db.expenses().insert(ExpenseEntity("e1", 100, "EGP", 1_760_000_000_000, null, null, null, null, pending = false))
            vm.add { ByteArrayInputStream(pdfBytes) }
            val receipt = await { it.receipts.size == 1 && it.adding == 0 }.receipts.single()
            vm.view(receipt)
            vm.askRemove()
            assertTrue(vm.state.value.confirmRemove)
            vm.dismissRemove()
            assertEquals(1, db.receipts().count())
            vm.askRemove()
            vm.confirmRemove()
            await { it.receipts.isEmpty() && it.viewing == null }
            assertEquals(0, db.receipts().count())
            assertFalse(vm.fileOf(receipt).exists())
        }

    @Test
    fun `REQ-RCPT-019 settings sizes show one decimal using integers`() {
        assertEquals("0.0", megabytes(0))
        assertEquals("2.5", megabytes(2_621_440))
        assertEquals("0.1", megabytes(110_000))
    }

    @Test
    fun `REQ-RCPT-018 no receipt code starts another app or grants a URI`() {
        val root = File("src/main/java/eg/masroof")
        val sources =
            listOf("data/receipts", "ui/edit").flatMap {
                File(root, it)
                    .walkTopDown()
                    .filter { f ->
                        f.extension == "kt"
                    }.toList()
            }
        assertTrue(sources.isNotEmpty())
        for (f in sources) {
            val text = f.readText()
            assertFalse("${f.name} must not use ACTION_VIEW", text.contains("ACTION_VIEW"))
            assertFalse("${f.name} must not use FileProvider", text.contains("FileProvider"))
            assertFalse("${f.name} must not grant URI permissions", text.contains("grantUriPermission") || text.contains("FLAG_GRANT"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE") || manifest.contains("READ_MEDIA") || manifest.contains("CAMERA"))
    }
}
