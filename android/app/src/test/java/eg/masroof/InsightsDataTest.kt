package eg.masroof

import eg.masroof.api.models.Heatmap
import eg.masroof.core.Money
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.insights.DefaultInsightsRepository
import eg.masroof.data.insights.FileInsightsCache
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.apiJson
import eg.masroof.ui.insights.toUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cache = FakeInsightsCache()
    private val remote = FakeInsightsRemote()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)

    private fun newRepo(dispatcher: CoroutineDispatcher) = DefaultInsightsRepository(cache, remote, clock, dispatcher)

    @Test
    fun `REQ-AND-070 the analytics wire format decodes including the weekday-hour matrix`() {
        val snapshot = InsightsFixtures.snapshot()
        assertEquals(125_000L, snapshot.summary.total.amountMinor)
        assertEquals(2_500, snapshot.summary.deltaPercentBps)
        assertEquals(7, snapshot.heatmap.weekdayHour.size)
        assertEquals(24, snapshot.heatmap.weekdayHour[0].size)
        assertEquals(40_000L, snapshot.heatmap.weekdayHour[0][14].amountMinor)
    }

    @Test
    fun `REQ-AND-070 a null change and a missing optional field decode`() {
        val json =
            InsightsFixtures.json(deltaBps = null).replace("\"previousTotal\":{\"amountMinor\":0,\"currency\":\"EGP\"},", "")
        val snapshot =
            apiJson.decodeFromString(
                eg.masroof.data.insights.InsightsSnapshot
                    .serializer(),
                json,
            )
        assertNull(snapshot.summary.deltaPercentBps)
        assertNull(snapshot.categories.items[0].previousTotal)
    }

    @Test
    fun `REQ-AND-070 heatmap round trips through the JSON cache format`() {
        val heatmap = InsightsFixtures.snapshot().heatmap
        val json = apiJson.encodeToString(Heatmap.serializer(), heatmap)
        assertEquals(heatmap, apiJson.decodeFromString(Heatmap.serializer(), json))
    }

    @Test
    fun `REQ-AND-070 the mapper keeps money in minor units and sums weekdays Saturday first`() {
        val ui = InsightsFixtures.snapshot().toUi()
        assertEquals(Money(125_000, "EGP"), ui.total)
        assertEquals(LocalDate.of(2026, 10, 1), ui.from)
        assertEquals(LocalDate.of(2026, 10, 31), ui.to)
        assertEquals(6, ui.daysLeft)
        assertEquals(2, ui.pendingReview)
        assertEquals(listOf("Groceries", "Car"), ui.categories.map { it.nameEn })
        assertEquals(1_000, ui.categories[0].deltaBps)
        assertNull(ui.categories[1].deltaBps)
        assertEquals(31, ui.calendar.size)
        assertEquals(40_000L, ui.weekday[0].amountMinor)
        assertEquals(85_000L, ui.weekday[5].amountMinor)
        assertEquals(7, ui.weekday.size)
        assertEquals(6, ui.trend.size)
        assertTrue(ui.trend.last().selected)
        assertEquals(1, ui.trend.count { it.selected })
        assertTrue(ui.hasData)
    }

    @Test
    fun `REQ-AND-070 an empty period has no data`() {
        assertFalse(InsightsFixtures.emptySnapshot().toUi().hasData)
    }

    @Test
    fun `REQ-AND-070 refresh stores the answer and observe replays it, per period`() =
        runTest {
            val repo = newRepo(StandardTestDispatcher(testScheduler))
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())

            assertEquals(RefreshOutcome.Ok, repo.refresh(InsightsPeriod.CURRENT))

            assertEquals(
                125_000L,
                repo
                    .observe(InsightsPeriod.CURRENT)
                    .first()
                    ?.summary
                    ?.total
                    ?.amountMinor,
            )
            assertNull(repo.observe(InsightsPeriod.PREVIOUS).first())
            assertEquals(clock.millis(), repo.observe(InsightsPeriod.CURRENT).first()?.fetchedAtMillis)
            assertEquals(listOf(InsightsPeriod.CURRENT), remote.fetched)
        }

    @Test
    fun `REQ-AND-070 offline keeps the last good snapshot and reports offline`() =
        runTest {
            val repo = newRepo(StandardTestDispatcher(testScheduler))
            repo.refresh(InsightsPeriod.CURRENT)
            remote.failure = ioFailure()

            assertEquals(RefreshOutcome.Offline, repo.refresh(InsightsPeriod.CURRENT))

            assertNotNull(repo.observe(InsightsPeriod.CURRENT).first())
        }

    @Test
    fun `REQ-AND-070 http failures map to sign-in and error`() =
        runTest {
            val repo = newRepo(StandardTestDispatcher(testScheduler))
            remote.failure = HttpFailure(401)
            assertEquals(RefreshOutcome.SignIn, repo.refresh(InsightsPeriod.CURRENT))
            remote.failure = HttpFailure(500)
            assertEquals(RefreshOutcome.Failed, repo.refresh(InsightsPeriod.CURRENT))
            remote.failure = SerializationException("bad body")
            assertEquals(RefreshOutcome.Failed, repo.refresh(InsightsPeriod.CURRENT))
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())
        }

    @Test
    fun `REQ-AND-070 an unreadable cached document is treated as not cached`() =
        runTest {
            cache.files[InsightsPeriod.CURRENT.spec] = "{ not json"
            val repo = newRepo(StandardTestDispatcher(testScheduler))
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())
            cache.files[InsightsPeriod.CURRENT.spec] = """{"summary":1}"""
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())
        }

    @Test
    fun `REQ-AND-070 clear removes cached insights so another account never sees them`() =
        runTest {
            val repo = newRepo(StandardTestDispatcher(testScheduler))
            repo.refresh(InsightsPeriod.CURRENT)
            repo.clear()
            assertEquals(1, cache.clears)
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())
        }

    @Test
    fun `REQ-AND-070 file cache writes atomically, replaces and clears`() {
        val dir = File(tmp.root, "insights")
        val files = FileInsightsCache(dir)
        assertNull(files.read("current"))
        files.write("current", "one")
        files.write("current", "two")
        files.write("previous", "p")
        assertEquals("two", files.read("current"))
        assertEquals("p", files.read("previous"))
        assertEquals(setOf("insights-current.json", "insights-previous.json"), dir.list()!!.toSet())
        files.clear()
        assertNull(files.read("current"))
        assertEquals(0, dir.list()!!.size)
    }
}
