package eg.masroof

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.InsightsRemote
import eg.masroof.data.insights.InsightsRepository
import eg.masroof.data.insights.InsightsSnapshot
import eg.masroof.data.remote.apiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** The analytics wire format as the server sends it (October 2026, day 25 of 31), decoded by the real client models. */
object InsightsFixtures {
    const val GROCERIES = "7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60"
    const val CAR = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"

    private fun money(minor: Long) = """{"amountMinor":$minor,"currency":"EGP"}"""

    private fun period(
        label: String,
        from: String,
        to: String,
    ) = """{"label":"$label","from":"$from","to":"$to"}"""

    private fun category(
        id: String?,
        en: String,
        ar: String,
        total: Long,
        share: Int,
        delta: Int?,
        count: Int,
    ) = """{"categoryId":${id?.let { "\"$it\"" } ?: "null"},"nameEn":"$en","nameAr":"$ar","color":"#16A34A",""" +
        """"total":${money(total)},"previousTotal":${money(0)},"shareBps":$share,"count":$count,"deltaPercentBps":${delta ?: "null"}}"""

    private fun categories() =
        listOf(
            category(GROCERIES, "Groceries", "بقالة", 75_000, 6_000, 1_000, 6),
            category(CAR, "Car", "السيارة", 50_000, 4_000, null, 2),
        ).joinToString(",")

    private fun trend(): String {
        val totals = listOf(90_000L, 110_000L, 0L, 100_000L, 100_000L, 125_000L)
        val months = listOf("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10")
        val points =
            months.zip(totals).joinToString(",") { (m, t) ->
                val from = "$m-01"
                val to = "$m-28"
                """{"period":${period(m, from, to)},"total":${money(t)},"byCategory":[]}"""
            }
        return """{"points":[$points]}"""
    }

    private fun heatmap(): String {
        val days =
            (1..31).joinToString(",") { d ->
                val (total, count) =
                    when (d) {
                        3 -> 40_000L to 2
                        15 -> 85_000L to 1
                        else -> 0L to 0
                    }
                val date = "2026-10-" + d.toString().padStart(2, '0')
                """{"date":"$date","total":${money(total)},"count":$count}"""
            }
        // Saturday (index 0) 14:00 and Thursday (index 5) 10:00.
        val matrix =
            (0 until 7).joinToString(",") { row ->
                (0 until 24).joinToString(",", "[", "]") { hour ->
                    when {
                        row == 0 && hour == 14 -> money(40_000)
                        row == 5 && hour == 10 -> money(85_000)
                        else -> money(0)
                    }
                }
            }
        return """{"period":${period("2026-10", "2026-10-01", "2026-10-31")},"days":[$days],"weekdayHour":[$matrix]}"""
    }

    fun json(
        total: Long = 125_000,
        deltaBps: Int? = 2_500,
        daysLeft: Int = 6,
        pending: Int = 2,
        withCategories: Boolean = true,
        withTrend: Boolean = true,
    ): String {
        val summary =
            """{"period":${period("2026-10", "2026-10-01", "2026-10-31")},"total":${money(total)},"previousTotal":${money(100_000)},""" +
                """"deltaPercentBps":${deltaBps ?: "null"},"dailyAverage":${money(5_000)},"projectedTotal":${money(155_000)},""" +
                """"daysLeft":$daysLeft,"pendingReviewCount":$pending,"topCategories":[${if (withCategories) categories() else ""}]}"""
        val breakdown =
            """{"period":${period("2026-10", "2026-10-01", "2026-10-31")},"total":${money(total)},""" +
                """"items":[${if (withCategories) categories() else ""}]}"""
        val trend = if (withTrend) trend() else """{"points":[]}"""
        return """{"summary":$summary,"categories":$breakdown,"trend":$trend,"heatmap":${heatmap()},"fetchedAtMillis":1760000000000}"""
    }

    fun snapshot(
        total: Long = 125_000,
        deltaBps: Int? = 2_500,
        daysLeft: Int = 6,
        pending: Int = 2,
        withCategories: Boolean = true,
        withTrend: Boolean = true,
    ): InsightsSnapshot =
        apiJson.decodeFromString(InsightsSnapshot.serializer(), json(total, deltaBps, daysLeft, pending, withCategories, withTrend))

    /** No spending at all: the "not enough data yet" case. */
    fun emptySnapshot(): InsightsSnapshot = snapshot(total = 0, deltaBps = null, withCategories = false, withTrend = false)
}

class FakeInsightsCache : InsightsCache {
    val files = mutableMapOf<String, String>()
    var clears = 0

    override fun read(key: String): String? = files[key]

    override fun write(
        key: String,
        json: String,
    ) {
        files[key] = json
    }

    override fun clear() {
        clears++
        files.clear()
    }
}

class FakeInsightsRemote : InsightsRemote {
    var snapshot: InsightsSnapshot = InsightsFixtures.snapshot()
    var failure: Exception? = null
    val fetched = mutableListOf<InsightsPeriod>()

    override suspend fun fetch(
        period: InsightsPeriod,
        nowMillis: Long,
    ): InsightsSnapshot {
        fetched += period
        failure?.let { throw it }
        return snapshot.copy(fetchedAtMillis = nowMillis)
    }
}

class FakeInsightsRepository : InsightsRepository {
    val snapshots = MutableStateFlow<Map<InsightsPeriod, InsightsSnapshot?>>(emptyMap())
    var outcome: RefreshOutcome = RefreshOutcome.Ok
    val refreshed = mutableListOf<InsightsPeriod>()
    var cleared = 0

    override fun observe(period: InsightsPeriod): Flow<InsightsSnapshot?> =
        kotlinx.coroutines.flow.flow {
            snapshots.collect { emit(it[period]) }
        }

    override suspend fun refresh(period: InsightsPeriod): RefreshOutcome {
        refreshed += period
        return outcome
    }

    override fun clear() {
        cleared++
    }
}

fun ioFailure() = IOException("offline")
