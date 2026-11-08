package eg.masroof.ask

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.FakeSettings
import eg.masroof.core.DigitStyle
import eg.masroof.core.analytics.PeriodAnalytics
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ui.ask.AskTexts
import eg.masroof.ui.theme.MoneyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

internal const val GROCERIES = "cat-groceries"
internal const val DINING = "cat-dining"
internal const val CAR = "cat-car"
internal const val FUEL = "cat-car.fuel"
internal const val RENT = "cat-rent"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class AskEngineFixture {
    protected lateinit var db: AppDatabase
    protected val settings = FakeSettings()
    protected var now: Instant = Instant.parse("2026-10-20T09:00:00Z")
    protected val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    protected fun ask() = RoomAskEngine(db, settings, clock, Dispatchers.Unconfined)

    protected fun insights() = RoomAnalyticsEngine(db, settings, clock, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = db.close()

    protected suspend fun seedCategories() {
        db.lookups().upsertCategories(
            listOf(
                CategoryEntity(GROCERIES, "Groceries", "بقالة", 10, color = "#16A34A", system = true),
                CategoryEntity(DINING, "Dining out", "مطاعم", 20, color = "#F97316", system = true),
                CategoryEntity(CAR, "Car", "السيارة", 30, color = "#F59E0B", system = true),
                CategoryEntity(FUEL, "Fuel", "بنزين", 1, parentId = CAR, color = "#F59E0B", system = true),
                CategoryEntity(RENT, "Rent & housing", "إيجار وسكن", 60, color = "#8B5CF6", system = true),
                CategoryEntity("cat-health", "Health", "صحة", 70, system = true),
                CategoryEntity("cat-kids", "Kids", "أطفال", 90, system = true),
                CategoryEntity("cat-clothing", "Clothing", "ملابس", 100, system = true),
                CategoryEntity("cat-travel", "Travel", "سفر", 160, system = true),
            ),
        )
    }

    @Suppress("LongParameterList")
    protected suspend fun put(
        id: String,
        amount: Long,
        at: String,
        type: String = "EXPENSE",
        currency: String = "EGP",
        category: String? = null,
        merchant: String? = null,
        deleted: Boolean = false,
        status: String = "CONFIRMED",
    ) = db.expenses().insert(
        ExpenseEntity(
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
        ),
    )

    /** October 2026: groceries 4,020 net, a soft-deleted 500, a refund, a transfer, cash, USD, fuel, dining and 14 Talabat orders. */
    protected suspend fun seedOctober() {
        seedCategories()
        put("g1", 160_000, "2026-10-03T10:00:00Z", category = GROCERIES, merchant = "Carrefour")
        put("g2", 252_000, "2026-10-10T10:00:00Z", category = GROCERIES, merchant = "Spinneys")
        put("g3", 50_000, "2026-10-11T10:00:00Z", category = GROCERIES, merchant = "Spinneys", deleted = true)
        put("g4", 10_000, "2026-10-12T10:00:00Z", type = "REFUND", category = GROCERIES, merchant = "Carrefour")
        put("g5", 2_000, "2026-10-13T10:00:00Z", currency = "USD", category = GROCERIES, merchant = "Amazon")
        put("t1", 500_000, "2026-10-05T10:00:00Z", type = "TRANSFER")
        put("c1", 100_000, "2026-10-06T10:00:00Z", type = "CASH_WITHDRAWAL")
        put("f1", 30_000, "2026-10-07T10:00:00Z", category = FUEL, merchant = "Wataniya")
        put("m1", 80_000, "2026-10-08T10:00:00Z", category = CAR, merchant = "Garage")
        for (i in 1..13) {
            put("tal$i", 22_900, String.format(Locale.ROOT, "2026-10-%02dT10:00:00Z", 1 + i % 9), category = DINING, merchant = "Talabat")
        }
        put("tal14", 23_300, "2026-10-15T20:00:00Z", category = DINING, merchant = "TALABAT")
    }

    protected suspend fun spend(question: String) =
        ask().answer(
            QuestionParser.parse(
                question,
                java.time.LocalDate
                    .ofInstant(now, java.time.ZoneId.of("Africa/Cairo"))
                    .year,
            ),
        )

    protected suspend fun current(): PeriodAnalytics = insights().compute("current")

    protected fun en(): AskTexts =
        AskTexts(
            ApplicationProvider.getApplicationContext<Context>().resources,
            MoneyStyle(false, DigitStyle.WESTERN),
        )

    protected fun ar(): AskTexts {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) }
        val res: Resources = ctx.createConfigurationContext(config).resources
        return AskTexts(res, MoneyStyle(true, DigitStyle.ARABIC_INDIC))
    }
}
