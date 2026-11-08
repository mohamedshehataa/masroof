package eg.masroof.ask

import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.MetalRepository
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AskLinkedGoldTest : AskEngineFixture() {
    private var n = 0
    private val ids =
        object : IdFactory {
            override fun next() = "g-${++n}"
        }

    @Test
    fun `REQ-EXP-063 an entry linked to a gold lot leaves an Ask spend answer exactly as it leaves Insights`() =
        runTest {
            put("e1", 12_000_000L, "2026-10-10T09:00:00Z")
            put("e2", 50_000L, "2026-10-12T09:00:00Z")
            val metal = MetalRepository(db, ids, clock, EraseLock(), WealthRepository(db, clock))
            assertEquals(12_050_000L, (spend("how much did I spend this month") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(12_050_000L, current().categoryTotal)

            val lot = LotDraft("BUY", 30_000L, 12_000_000L, Instant.parse("2026-10-10T09:00:00Z").toEpochMilli(), "e1")
            val id = (metal.createHolding(HoldingDraft("GOLD", "Set", 21), lot) as MetalOutcome.Saved).id
            assertEquals(50_000L, (spend("how much did I spend this month") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(50_000L, current().categoryTotal)

            val lotId =
                db
                    .assetLots()
                    .forAsset(id)
                    .single()
                    .id
            metal.restoreEntry(lotId, null)
            assertEquals(12_050_000L, (spend("how much did I spend this month") as AskAnswer.SpendTotal).totalMinor)
            assertEquals(12_050_000L, current().categoryTotal)
        }
}
