package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.analytics.AnalyticsEngine
import eg.masroof.core.analytics.CategoryLine
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.LocalInsightsRepository
import eg.masroof.data.validMonthStartDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun parityFile(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val f = File(dir, "fixtures/analytics/parity-v1.json")
        if (f.isFile) return f
        dir = dir.parentFile
    }
    error("fixtures/analytics/parity-v1.json not found")
}

private fun JsonElement.obj() = jsonObject

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.lng(k: String) = getValue(k).jsonPrimitive.long

private fun JsonObject.intOrNull(k: String): Int? = get(k)?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalyticsEngineTest {
    private lateinit var db: AppDatabase
    private val settings = FakeSettings()
    private var now: Instant = Instant.parse("2026-10-27T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }

    private fun engine(): AnalyticsEngine = RoomAnalyticsEngine(db, settings, clock, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Suppress("LongParameterList")
    private fun e(
        id: String,
        amount: Long,
        at: String,
        type: String = "EXPENSE",
        status: String = "CONFIRMED",
        currency: String = "EGP",
        category: String? = null,
        deleted: Boolean = false,
        merchant: String? = null,
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = currency,
        occurredAtMillis = Instant.parse(at).toEpochMilli(),
        merchant = merchant,
        categoryId = category,
        accountId = null,
        notes = null,
        pending = false,
        type = type,
        status = status,
        deletedAtMillis = if (deleted) 1L else null,
    )

    private fun cat(
        id: String,
        parent: String? = null,
        en: String = id,
        archived: Boolean = false,
    ) = CategoryEntity(id, en, "$en-ar", 0, parent, "#112233", archived)

    private fun line(
        k: String,
        l: CategoryLine,
        exp: JsonObject,
    ) {
        assertEquals("$k key", exp.str("categoryKey"), l.categoryId)
        assertEquals("$k nameEn", exp.str("nameEn"), l.nameEn)
        assertEquals("$k nameAr", exp.str("nameAr"), l.nameAr)
        assertEquals("$k color", exp.str("color"), l.color)
        assertEquals("$k total", exp.lng("total"), l.total)
        assertEquals("$k previousTotal", exp.lng("previousTotal"), l.previousTotal)
        assertEquals("$k shareBps", exp.getValue("shareBps").jsonPrimitive.int, l.shareBps)
        assertEquals("$k count", exp.getValue("count").jsonPrimitive.int, l.count)
        assertEquals("$k deltaBps", exp.intOrNull("deltaBps"), l.deltaBps)
    }

    private fun check(
        k: String,
        out: PeriodAnalytics,
        summary: JsonObject,
        forecast: JsonObject,
        byCategory: JsonObject,
    ) {
        val s = out.summary
        assertEquals("$k from", summary.getValue("period").obj().str("from"), s.period.from.toString())
        assertEquals("$k to", summary.getValue("period").obj().str("to"), s.period.to.toString())
        assertEquals("$k label", summary.getValue("period").obj().str("label"), s.period.label)
        assertEquals("$k total", summary.lng("total"), s.total)
        // The golden file has no savings rate; the server formula is (income - spent) / income in basis points, half away from zero.
        val expectedSavings =
            summary.lng("income").takeIf { it > 0 }?.let { inc ->
                java.math
                    .BigDecimal(inc - summary.lng("total"))
                    .multiply(java.math.BigDecimal(10_000))
                    .divide(java.math.BigDecimal(inc), 0, java.math.RoundingMode.HALF_UP)
                    .toInt()
            }
        assertEquals("$k savingsRateBps", expectedSavings, s.savingsRateBps)
        assertEquals("$k previousTotal", summary.lng("previousTotal"), s.previousTotal)
        assertEquals("$k deltaBps", summary.intOrNull("deltaBps"), s.deltaBps)
        assertEquals("$k dailyAverage", summary.lng("dailyAverage"), s.dailyAverage)
        assertEquals("$k projectedTotal", summary.lng("projectedTotal"), s.projectedTotal)
        assertEquals("$k projectedWeekdayAware", summary.lng("projectedWeekdayAware"), s.projectedWeekdayAware)
        assertEquals("$k income", summary.lng("income"), s.income)
        assertEquals("$k daysLeft", summary.getValue("daysLeft").jsonPrimitive.int, s.daysLeft)
        assertEquals("$k pending", summary.getValue("pendingReviewCount").jsonPrimitive.int, s.pendingReviewCount)
        assertNull("$k remainingBudget", s.remainingBudget)
        val top = summary.getValue("topCategories").jsonArray
        assertEquals("$k top size", top.size, s.topCategories.size)
        top.forEachIndexed { i, t -> line("$k top$i", s.topCategories[i], t.obj()) }
        assertEquals("$k spentSoFar", forecast.lng("spentSoFar"), out.forecast.spentSoFar)
        assertEquals("$k linear", forecast.lng("linear"), out.forecast.linear)
        assertEquals("$k weekdayAware", forecast.lng("weekdayAware"), out.forecast.weekdayAware)
        assertEquals("$k category total", byCategory.lng("total"), out.categoryTotal)
        val items = byCategory.getValue("items").jsonArray
        assertEquals("$k items size", items.size, out.categories.size)
        items.forEachIndexed { i, t -> line("$k item$i", out.categories[i], t.obj()) }
    }

    @Test
    fun `REQ-AN-145 REQ-AN-146 summary forecast and by-category equal the golden file for both cash settings`() =
        runTest {
            val root = Json.parseToJsonElement(parityFile().readText()).obj()
            root.getValue("categories").jsonArray.forEach {
                val c = it.obj()
                db.lookups().upsertCategories(
                    listOf(CategoryEntity(c.str("key")!!, c.str("nameEn")!!, c.str("nameAr")!!, 0, c.str("parentKey"), c.str("color"))),
                )
            }
            root.getValue("expenses").jsonArray.forEach {
                val x = it.obj()
                db.expenses().insert(
                    e(
                        x.str("id")!!,
                        x.lng("amountMinor"),
                        x.str("occurredAt")!!,
                        x.str("type")!!,
                        x.str("status")!!,
                        x.str("currency")!!,
                        x.str("categoryKey"),
                        x.getValue("deleted").jsonPrimitive.boolean,
                        x.str("merchant"),
                    ),
                )
            }
            var checked = 0
            for (sc in root.getValue("scenarios").jsonArray.map { it.obj() }) {
                val today = LocalDate.parse(sc.str("today")!!)
                // The server clock is that day at 12:00 Cairo.
                now = today.atTime(12, 0).atZone(eg.masroof.core.CairoTime.zone).toInstant()
                val out = sc.getValue("outputs").obj()
                val monthStartDay = sc.getValue("monthStartDay").jsonPrimitive.int
                for (cash in listOf(false, true)) {
                    settings.update { it.copy(monthStartDay = monthStartDay, includeCashWithdrawals = cash) }
                    val suffix = if (cash) "WithCash" else ""
                    check(
                        "d$monthStartDay $today cash=$cash",
                        engine().compute("current"),
                        out.getValue("summary$suffix").obj(),
                        out.getValue("forecast").obj(),
                        out.getValue("byCategory$suffix").obj(),
                    )
                    checked++
                }
            }
            assertEquals(24, checked)
        }

    private fun checkLines(
        k: String,
        actual: List<CategoryLine>,
        expected: kotlinx.serialization.json.JsonArray,
    ) {
        assertEquals("$k size", expected.size, actual.size)
        expected.forEachIndexed { i, t -> line("$k[$i]", actual[i], t.obj()) }
    }

    private fun checkExtras(
        k: String,
        out: PeriodAnalytics,
        merchants: JsonObject,
        heatmap: JsonObject,
        trend6: kotlinx.serialization.json.JsonArray,
        trend12: kotlinx.serialization.json.JsonArray,
    ) {
        val items = merchants.getValue("items").jsonArray
        assertEquals("$k merchants size", items.size, out.merchants.size)
        items.forEachIndexed { i, t ->
            val x = t.obj()
            val m = out.merchants[i]
            assertEquals("$k m$i name", x.str("name"), m.name)
            assertEquals("$k m$i total", x.lng("total"), m.total)
            assertEquals("$k m$i visits", x.getValue("visits").jsonPrimitive.int, m.visits)
            assertEquals("$k m$i avg", x.lng("averageTicket"), m.averageTicket)
            assertEquals("$k m$i category", x.str("categoryKey"), m.categoryId)
        }
        val days = heatmap.getValue("days").jsonArray
        assertEquals("$k days size", days.size, out.heatmap.days.size)
        days.forEachIndexed { i, t ->
            val x = t.obj()
            assertEquals(
                "$k day$i",
                x.str("date"),
                out.heatmap.days[i]
                    .date
                    .toString(),
            )
            assertEquals("$k day$i total", x.lng("total"), out.heatmap.days[i].total)
            assertEquals("$k day$i count", x.getValue("count").jsonPrimitive.int, out.heatmap.days[i].count)
        }
        val wh = heatmap.getValue("weekdayHour").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.long } }
        assertEquals("$k weekdayHour", wh, out.heatmap.weekdayHour)
        for ((n, expected) in listOf(6 to trend6, 12 to trend12)) {
            val actual = out.trend.takeLast(n)
            assertEquals("$k trend$n size", expected.size, actual.size)
            expected.forEachIndexed { i, t ->
                val x = t.obj()
                val p = actual[i]
                assertEquals("$k trend$n[$i] label", x.getValue("period").obj().str("label"), p.period.label)
                assertEquals("$k trend$n[$i] from", x.getValue("period").obj().str("from"), p.period.from.toString())
                assertEquals("$k trend$n[$i] total", x.lng("total"), p.total)
                assertEquals("$k trend$n[$i] income", x.lng("income"), p.income)
                assertEquals("$k trend$n[$i] savings", x.intOrNull("savingsRateBps"), p.savingsRateBps)
                checkLines("$k trend$n[$i] cat", p.byCategory, x.getValue("byCategory").jsonArray)
            }
        }
    }

    @Test
    fun `REQ-AN-150 REQ-AN-151 REQ-AN-152 merchants heatmap and trend equal the golden file for both cash settings`() =
        runTest {
            val root = Json.parseToJsonElement(parityFile().readText()).obj()
            root.getValue("categories").jsonArray.forEach {
                val c = it.obj()
                db.lookups().upsertCategories(
                    listOf(CategoryEntity(c.str("key")!!, c.str("nameEn")!!, c.str("nameAr")!!, 0, c.str("parentKey"), c.str("color"))),
                )
            }
            root.getValue("expenses").jsonArray.forEach {
                val x = it.obj()
                db.expenses().insert(
                    e(
                        x.str("id")!!,
                        x.lng("amountMinor"),
                        x.str("occurredAt")!!,
                        x.str("type")!!,
                        x.str("status")!!,
                        x.str("currency")!!,
                        x.str("categoryKey"),
                        x.getValue("deleted").jsonPrimitive.boolean,
                        x.str("merchant"),
                    ),
                )
            }
            var checked = 0
            for (sc in root.getValue("scenarios").jsonArray.map { it.obj() }) {
                val today = LocalDate.parse(sc.str("today")!!)
                now = today.atTime(12, 0).atZone(eg.masroof.core.CairoTime.zone).toInstant()
                val out = sc.getValue("outputs").obj()
                val startDay = sc.getValue("monthStartDay").jsonPrimitive.int
                for (cash in listOf(false, true)) {
                    settings.update { it.copy(monthStartDay = startDay, includeCashWithdrawals = cash) }
                    val s = if (cash) "WithCash" else ""
                    checkExtras(
                        "d$startDay $today cash=$cash",
                        engine().compute("current"),
                        out.getValue("merchants").obj(),
                        out.getValue("heatmap$s").obj(),
                        out.getValue("trend6$s").jsonArray,
                        out.getValue("trend12$s").jsonArray,
                    )
                    checked++
                }
            }
            assertEquals(24, checked)
        }

    @Test
    fun `REQ-AN-152 twelve periods on a phone with two months of data are present and the older ones are zero`() =
        runTest {
            now = Instant.parse("2026-10-27T10:00:00Z")
            db.expenses().insert(e("a", 5_000, "2026-10-05T10:00:00Z", merchant = " Shop "))
            db.expenses().insert(e("b", 7_000, "2026-09-05T10:00:00Z", merchant = "shop"))
            db.expenses().insert(e("c", 7_000, "2026-09-06T10:00:00Z", type = "REFUND", merchant = "SHOP"))
            val out = engine().compute("current")
            assertEquals(12, out.trend.size)
            assertEquals(5_000L, out.trend[11].total)
            assertTrue(out.trend.take(10).all { it.total == 0L && it.byCategory.isEmpty() && it.savingsRateBps == null })
            assertEquals(1, out.merchants.size)
            assertEquals("Shop", out.merchants[0].name)
            assertEquals(5_000L, out.merchants[0].total)
        }

    @Test
    fun `REQ-AN-145 an empty period is zero with no delta and a USD-only period counts the entry as not included`() =
        runTest {
            val empty = engine().compute("current").summary
            assertEquals(0L, empty.total)
            assertNull(empty.deltaBps)
            db.expenses().insert(e("usd", 9_900, "2026-10-20T10:00:00Z", currency = "USD"))
            val usd = engine().compute("current").summary
            assertEquals(0L, usd.total)
            assertEquals(1, usd.otherCurrencyCount)
        }

    @Test
    fun `REQ-AN-146 archived and missing categories fall under the parent or Uncategorised and the total keeps every amount`() =
        runTest {
            db.lookups().upsertCategories(
                listOf(cat("car", en = "Car"), cat("fuel", "car", "Fuel", archived = true), cat("old", en = "Old", archived = true)),
            )
            db.expenses().insert(e("a", 1_000, "2026-10-10T10:00:00Z", category = "fuel"))
            db.expenses().insert(e("b", 2_000, "2026-10-11T10:00:00Z", category = "old"))
            db.expenses().insert(e("c", 4_000, "2026-10-12T10:00:00Z", category = "gone"))
            val out = engine().compute("current")
            assertEquals(7_000L, out.categoryTotal)
            assertEquals(listOf(null to 6_000L, "car" to 1_000L), out.categories.map { it.categoryId to it.total })
            assertEquals("Uncategorised", out.categories.first().nameEn)
        }

    @Test
    fun `REQ-AN-148 a month starting on the 25th covers 25 Oct to 24 Nov and 00_30 Cairo on the 25th belongs to it`() =
        runTest {
            settings.update { it.copy(monthStartDay = 25) }
            db.expenses().insert(e("edge", 5_000, "2026-10-24T21:30:00Z"))
            db.expenses().insert(e("before", 700, "2026-10-24T20:30:00Z"))
            val s = engine().compute("current").summary
            assertEquals(LocalDate.parse("2026-10-25"), s.period.from)
            assertEquals(LocalDate.parse("2026-11-24"), s.period.to)
            assertEquals(5_000L, s.total)
            assertEquals(700L, s.previousTotal)
        }

    @Test
    fun `REQ-AN-148 a stored month start day outside 1 to 28 falls back to 1`() {
        assertEquals(1, validMonthStartDay(0))
        assertEquals(1, validMonthStartDay(29))
        assertEquals(1, validMonthStartDay(null))
        assertEquals(28, validMonthStartDay(28))
    }

    @Test
    fun `REQ-AN-147 adding an expense updates the observed figures without a refresh`() =
        runTest {
            db.lookups().upsertCategories(listOf(cat("car", en = "Car")))
            val repo = LocalInsightsRepository(engine(), clock)
            assertEquals(
                0L,
                repo
                    .observe(InsightsPeriod.CURRENT)
                    .first()!!
                    .summary.total.amountMinor,
            )
            db.expenses().insert(e("fuel", 20_000, "2026-10-27T08:00:00Z", category = "car"))
            val snap = repo.observe(InsightsPeriod.CURRENT).first()!!
            assertEquals(20_000L, snap.summary.total.amountMinor)
            assertEquals(
                20_000L,
                snap.categories.items
                    .single()
                    .total.amountMinor,
            )
            assertEquals(RefreshOutcome.Ok, repo.refresh(InsightsPeriod.CURRENT))
        }

    @Test
    fun `REQ-AN-147 an engine failure is a Failed refresh and no snapshot, not a crash`() =
        runTest {
            val broken =
                object : AnalyticsEngine {
                    override fun observe(spec: String): Flow<PeriodAnalytics> = flow { error("boom") }

                    override suspend fun compute(spec: String): PeriodAnalytics = error("boom")
                }
            val repo = LocalInsightsRepository(broken, clock)
            assertNull(repo.observe(InsightsPeriod.CURRENT).first())
            assertEquals(RefreshOutcome.Failed, repo.refresh(InsightsPeriod.CURRENT))
        }

    @Test
    fun `REQ-AN-147 the old insights cache files are deleted and the budgets cache is kept`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "insights-test-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "insights-current.json").writeText("{}")
        File(dir, "insights-previous.json").writeText("{}")
        File(dir, "insights-budgets.json").writeText("{}")
        eg.masroof.data.insights
            .deleteLegacyInsightsCache(dir)
        assertEquals(listOf("insights-budgets.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    private suspend fun seedYear(perMonth: Int) {
        val start = Instant.parse("2025-11-06T08:00:00Z").toEpochMilli()
        val rows =
            (0 until 12 * perMonth).map { i ->
                val month = i / perMonth
                val within = i % perMonth
                ExpenseEntity(
                    id = "r$i",
                    amountMinor = 1_000L + i % 977,
                    currency = "EGP",
                    occurredAtMillis = start + month * 30L * 86_400_000L + within * 40_000L,
                    merchant = "m${i % 40}",
                    categoryId = "c${i % 8}",
                    accountId = null,
                    notes = null,
                    pending = false,
                )
            }
        db.withTransaction { rows.forEach { db.expenses().insert(it) } }
    }

    @Test
    fun `REQ-AN-149 summary, by-category, merchants, heatmap and trend over 12 x 600 rows take under 200 ms at p95`() =
        runTest {
            now = Instant.parse("2026-10-27T10:00:00Z")
            seedYear(600)
            val engine = engine()
            engine.compute("current") // warm-up: class loading and the statement cache
            val times =
                (1..20)
                    .map {
                        val t = System.nanoTime()
                        val out = engine.compute("current")
                        assertTrue(out.summary.total > 0 && out.merchants.isNotEmpty() && out.trend.size == 12 && out.trend[11].total > 0)
                        (System.nanoTime() - t) / 1_000_000
                    }.sorted()
            val p95 = times[18]
            assertTrue("p95 was $p95 ms", p95 < 200)
        }

    @Test
    fun `REQ-AN-149 72000 rows still compute without running out of memory`() =
        runTest {
            seedYear(6_000)
            val out = engine().compute("current")
            assertTrue(out.summary.total > 0)
        }

    @Test
    fun `REQ-AN-147 Home spent equals the engine summary for month start 25 and an engine failure hides the summary card`() =
        runTest {
            settings.update { it.copy(monthStartDay = 25) }
            db.expenses().insert(e("in", 5_000, "2026-10-26T08:00:00Z"))
            db.expenses().insert(e("out", 9_000, "2026-10-20T08:00:00Z"))
            val repo =
                eg.masroof.data.DefaultExpenseRepository(
                    db,
                    clock,
                    eg.masroof.data.UuidIdFactory(),
                    eg.masroof.data.LocalDataSignal(),
                )
            val home =
                eg.masroof.ui.viewmodel
                    .HomeViewModel(repo, engine())
            val job = backgroundScope.launch(Dispatchers.Unconfined) { home.state.collect {} }
            val state = home.state.first { it.status == eg.masroof.ui.viewmodel.LoadStatus.READY }
            assertEquals(engine().compute("current").summary.total, state.total.amountMinor)
            assertEquals(5_000L, state.total.amountMinor)
            assertTrue(state.summaryAvailable)
            job.cancel()
            val broken =
                object : AnalyticsEngine {
                    override fun observe(spec: String): Flow<PeriodAnalytics> = flow { error("boom") }

                    override suspend fun compute(spec: String): PeriodAnalytics = error("boom")
                }
            val failed =
                eg.masroof.ui.viewmodel
                    .HomeViewModel(repo, broken)
            val job2 = backgroundScope.launch(Dispatchers.Unconfined) { failed.state.collect {} }
            val fs = failed.state.first { it.status == eg.masroof.ui.viewmodel.LoadStatus.READY }
            assertTrue(!fs.summaryAvailable)
            assertEquals(2, fs.recent.size)
            job2.cancel()
        }

    @Test
    fun `REQ-AN-148 an open screen moves to the next month after Cairo midnight`() =
        runTest {
            val t0 = System.nanoTime()
            val start = Instant.parse("2026-10-31T21:59:59.700Z")
            val moving =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId?) = this

                    override fun instant(): Instant = start.plusNanos(System.nanoTime() - t0)
                }
            val labels =
                RoomAnalyticsEngine(db, settings, moving, Dispatchers.Unconfined).observe("current").take(2).toList().map {
                    it.summary.period.label
                }
            assertEquals(listOf("2026-10", "2026-11"), labels)
        }
}
