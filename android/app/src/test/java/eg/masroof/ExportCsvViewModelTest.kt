package eg.masroof

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.export.CsvExporter
import eg.masroof.ui.settings.ExportChoice
import eg.masroof.ui.settings.ExportCsvContent
import eg.masroof.ui.settings.ExportCsvViewModel
import eg.masroof.ui.settings.ExportStatus
import eg.masroof.ui.settings.ExportUiState
import eg.masroof.ui.theme.MasroofTheme
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExportCsvViewModelTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var settings: FakeSettings
    private lateinit var vm: ExportCsvViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = FakeSettings()
        // 10 Nov 2026 noon Cairo.
        val clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC)
        vm = ExportCsvViewModel(settings, CsvExporter(db, Dispatchers.Unconfined), clock)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Combined flows and Room run on their own threads: wait (real time) until the state satisfies [cond]. */
    private suspend fun await(cond: (ExportUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { cond(it) } } }

    private fun row(
        id: String,
        utc: String,
    ) = ExpenseEntity(id, 100, "EGP", Instant.parse(utc).toEpochMilli(), "m-$id", null, null, null, pending = false)

    @Test
    fun `REQ-EXP-048 This month honours the month start day and offers masroof-YYYY-MM as the file name`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            val plain = await { it.fileName == "masroof-2026-11.csv" }
            assertEquals(LocalDate.of(2026, 11, 1), plain.from)
            assertEquals(LocalDate.of(2026, 11, 30), plain.to)
            settings.update { it.copy(monthStartDay = 25) }
            val shifted = await { it.fileName == "masroof-2026-10.csv" }
            assertEquals(LocalDate.of(2026, 10, 25), shifted.from)
            assertEquals(LocalDate.of(2026, 11, 24), shifted.to)
        }

    @Test
    fun `REQ-EXP-048 Last month This year and Custom resolve their ranges and file names`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            vm.choose(ExportChoice.LAST_MONTH)
            val last = await { it.choice == ExportChoice.LAST_MONTH }
            assertEquals("masroof-2026-10.csv", last.fileName)
            assertEquals(LocalDate.of(2026, 10, 31), last.to)
            vm.choose(ExportChoice.THIS_YEAR)
            val year = await { it.choice == ExportChoice.THIS_YEAR }
            assertEquals(LocalDate.of(2026, 1, 1), year.from)
            assertEquals(LocalDate.of(2026, 12, 31), year.to)
            vm.choose(ExportChoice.CUSTOM)
            vm.setCustomFrom(LocalDate.of(2026, 3, 1))
            vm.setCustomTo(LocalDate.of(2026, 3, 15))
            val custom = await { it.choice == ExportChoice.CUSTOM && it.to == LocalDate.of(2026, 3, 15) }
            assertEquals("masroof-2026-03-01_2026-03-15.csv", custom.fileName)
            assertTrue(custom.canExport)
        }

    @Test
    fun `REQ-EXP-048 an end date before the start disables Export and exporting writes nothing`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            vm.choose(ExportChoice.CUSTOM)
            vm.setCustomFrom(LocalDate.of(2026, 10, 20))
            vm.setCustomTo(LocalDate.of(2026, 10, 10))
            val bad = await { it.endBeforeStart }
            assertFalse(bad.canExport)
            var opened = false
            vm.export(
                open = {
                    opened = true
                    ByteArrayOutputStream()
                },
            )
            assertFalse(opened)
            assertEquals(ExportStatus.Idle, await { it.endBeforeStart }.status)
        }

    @Test
    fun `REQ-EXP-048 a successful export reports the number of entries and writes the file`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            db.expenses().insert(row("a", "2026-11-05T07:00:00Z"))
            db.expenses().insert(row("b", "2026-11-06T07:00:00Z"))
            val out = ByteArrayOutputStream()
            vm.export(open = { out })
            val done = await { it.status is ExportStatus.Done }
            assertEquals(ExportStatus.Done(2), done.status)
            assertTrue(String(out.toByteArray(), Charsets.UTF_8).contains("m-b"))
            vm.dismissResult()
            assertEquals(ExportStatus.Idle, await { it.status == ExportStatus.Idle }.status)
        }

    @Test
    fun `REQ-EXP-048 an empty range ends as Done with 0 entries`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            vm.export(open = { ByteArrayOutputStream() })
            assertEquals(ExportStatus.Done(0), await { it.status is ExportStatus.Done }.status)
        }

    @Test
    fun `REQ-EXP-048 a provider failure mid-write shows Failed, never Done, discards the file and leaves data unchanged`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            for (i in 0 until 30) db.expenses().insert(row("e$i", "2026-11-05T07:00:00Z"))
            var discarded = false
            val failing =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("gone")

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = throw IOException("gone")
                }
            vm.export(open = { failing }, discard = { discarded = true })
            val failed = await { it.status == ExportStatus.Failed }
            assertTrue(discarded)
            assertFalse(failed.status is ExportStatus.Done)
            assertEquals(
                30,
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-048 a picker that gives no stream is a failure and a throwing discard does not hide it`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            vm.export(open = { null }, discard = { throw IOException("cannot delete") })
            assertEquals(ExportStatus.Failed, await { it.status == ExportStatus.Failed }.status)
        }

    private fun uiState(
        endBeforeStart: Boolean = false,
        status: ExportStatus = ExportStatus.Idle,
        choice: ExportChoice = ExportChoice.THIS_MONTH,
    ) = ExportUiState(choice, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "masroof-2026-10.csv", endBeforeStart, status)

    @Test
    fun `REQ-EXP-048 the section shows the ranges, an enabled Export button of at least 48dp and fires the export`() {
        var exported = 0
        compose.setContent {
            MasroofTheme { ExportCsvContent(uiState(), {}, {}, {}, { exported++ }, {}) }
        }
        compose.onNodeWithText("Export CSV").assertIsDisplayed()
        compose.onNodeWithText("This month").assertIsDisplayed()
        compose.onNodeWithText("Last month").assertIsDisplayed()
        compose.onNodeWithText("This year").assertIsDisplayed()
        compose.onNodeWithText("Custom").assertIsDisplayed()
        compose
            .onNodeWithText("Export")
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, exported)
    }

    @Test
    fun `REQ-EXP-048 end before start shows the message and disables Export`() {
        compose.setContent {
            MasroofTheme { ExportCsvContent(uiState(endBeforeStart = true, choice = ExportChoice.CUSTOM), {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("End date is before start").assertIsDisplayed()
        compose.onNodeWithText("Export").assertIsNotEnabled()
    }

    @Test
    fun `REQ-EXP-048 the result line says how many entries were exported, 0 included, or that it failed`() {
        val status = mutableStateOf<ExportStatus>(ExportStatus.Done(1240))
        compose.setContent { MasroofTheme { ExportCsvContent(uiState(status = status.value), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("1,240 entries exported").assertIsDisplayed()
        status.value = ExportStatus.Done(0)
        compose.onNodeWithText("0 entries exported").assertIsDisplayed()
        status.value = ExportStatus.Done(1)
        compose.onNodeWithText("1 entry exported").assertIsDisplayed()
        status.value = ExportStatus.Failed
        compose.onNodeWithText("Export failed").assertIsDisplayed()
        compose.onNodeWithText("entries exported", substring = true).assertDoesNotExist()
    }
}
