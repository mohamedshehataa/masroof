package eg.masroof

import eg.masroof.core.CairoTime
import eg.masroof.data.wealth.MetalInput
import eg.masroof.data.wealth.MetalMath
import eg.masroof.data.wealth.MetalMath.BUY
import eg.masroof.data.wealth.MetalMath.Lot
import eg.masroof.data.wealth.MetalMath.PriceState
import eg.masroof.data.wealth.MetalMath.SELL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MetalMathTest {
    private val today = LocalDate.of(2026, 11, 8)

    private fun at(
        y: Int,
        m: Int,
        d: Int,
    ) = CairoTime.noonOf(LocalDate.of(y, m, d)).toEpochMilli()

    private fun lot(
        id: String,
        side: String,
        grams: Long,
        egp: Long,
        atMillis: Long,
    ) = Lot(id, side, grams * 1000, egp * 100, atMillis)

    private val worked =
        listOf(
            lot("a", BUY, 30, 120_000, at(2026, 5, 9)),
            lot("b", BUY, 10, 45_000, at(2026, 6, 1)),
            lot("c", SELL, 5, 24_000, at(2026, 7, 1)),
        )

    @Test
    fun `REQ-AN-173 the worked example - 35 g, value 168000, average 4125, cost 144375, gain 23625 (1636 bp), realised 3375`() {
        val v = MetalMath.valuation(worked, 480_000L, at(2026, 11, 8), today, CairoTime::dateOf)
        assertEquals(35_000L, v.position.heldMilli)
        assertEquals(16_800_000L, v.valueMinor)
        assertEquals(412_500L, v.position.averagePerGramMinor)
        assertEquals(14_437_500L, v.position.costMinor)
        assertEquals(2_362_500L, v.gainMinor)
        assertEquals(1636, v.gainBp)
        assertEquals(337_500L, v.position.realisedMinor)
        assertEquals(PriceState.FRESH, v.priceState)
    }

    @Test
    fun `REQ-AN-173 a gain of exactly half a basis point rounds away from zero both ways`() {
        // cost 20000 minor, gain +1 -> 0.5 bp -> 1; gain -1 -> -1.
        assertEquals(1, MetalMath.gainBp(1L, 20_000L))
        assertEquals(-1, MetalMath.gainBp(-1L, 20_000L))
        assertEquals(0, MetalMath.gainBp(0L, 20_000L))
        assertNull(MetalMath.gainBp(5L, 0L))
    }

    @Test
    fun `REQ-AN-173 no price falls back to the cost and says so`() {
        val v = MetalMath.valuation(worked, null, null, today, CairoTime::dateOf)
        assertEquals(PriceState.NONE, v.priceState)
        assertEquals(14_437_500L, v.valueMinor)
        assertEquals(0L, v.gainMinor)
        assertNull(v.gainBp)
    }

    @Test
    fun `REQ-AN-173 a price older than 7 days is stale, exactly 7 days is not`() {
        val seven = MetalMath.valuation(worked, 480_000L, at(2026, 11, 1), today, CairoTime::dateOf)
        assertEquals(PriceState.FRESH, seven.priceState)
        val eight = MetalMath.valuation(worked, 480_000L, at(2026, 10, 31), today, CairoTime::dateOf)
        assertEquals(PriceState.STALE, eight.priceState)
        assertEquals(16_800_000L, eight.valueMinor)
    }

    @Test
    fun `REQ-AN-173 zero lots and fully sold holdings are zero, with no average`() {
        val none = MetalMath.valuation(emptyList(), 480_000L, at(2026, 11, 8), today, CairoTime::dateOf)
        assertEquals(0L, none.valueMinor)
        assertNull(none.position.averagePerGramMinor)
        assertNull(none.gainBp)
        val sold = MetalMath.position(listOf(lot("a", BUY, 10, 40_000, 1L), lot("b", SELL, 10, 50_000, 2L)))
        assertEquals(0L, sold.heldMilli)
        assertEquals(0L, sold.costMinor)
        assertEquals(1_000_000L, sold.realisedMinor)
    }

    @Test
    fun `REQ-AN-173 sales are replayed in time order so a later purchase does not change an earlier sale`() {
        val lots =
            listOf(
                lot("buy2", BUY, 10, 50_000, 3L),
                lot("sell", SELL, 5, 25_000, 2L),
                lot("buy1", BUY, 10, 40_000, 1L),
            )
        val p = MetalMath.position(lots)
        // sale at avg 4000 -> realised 5000 EGP; held 15 g, cost 20000 + 50000 = 70000 EGP.
        assertEquals(500_000L, p.realisedMinor)
        assertEquals(15_000L, p.heldMilli)
        assertEquals(7_000_000L, p.costMinor)
    }

    @Test
    fun `REQ-AN-173 fractional grams stay in milligrams and the value rounds half away from zero`() {
        // 0.125 g at 4,800.50 EGP/g = 600.0625 EGP -> 60006 minor.
        assertEquals(60_006L, MetalMath.valueOf(125L, 480_050L))
        // 0.001 g at 0.50 EGP = 0.0005 EGP -> 0.05 minor -> 0.
        assertEquals(0L, MetalMath.valueOf(1L, 50L))
        assertEquals(1L, MetalMath.valueOf(10L, 50L)) // 0.5 minor rounds up
    }

    @Test
    fun `REQ-EXP-063 a sale of more than is held is found, with the grams held at that moment`() {
        assertNull(MetalMath.firstOversell(worked))
        val over = worked + lot("d", SELL, 40, 1, at(2026, 8, 1))
        assertEquals(35_000L, MetalMath.firstOversell(over))
        // A buy deleted from under a later sale.
        assertEquals(30_000L, MetalMath.firstOversell(listOf(worked[0], lot("x", SELL, 31, 1, at(2026, 9, 1)))))
    }

    @Test
    fun `REQ-EXP-063 grams typed in either digit set parse to milligrams, anything else is refused`() {
        assertEquals(30_000L, MetalInput.parseGrams("30"))
        assertEquals(7_500L, MetalInput.parseGrams("7.5"))
        assertEquals(125L, MetalInput.parseGrams("0.125"))
        assertEquals(7_500L, MetalInput.parseGrams("٧٫٥"))
        assertEquals(1_500L, MetalInput.parseGrams("1,5"))
        assertNull(MetalInput.parseGrams(""))
        assertNull(MetalInput.parseGrams("0"))
        assertNull(MetalInput.parseGrams("-3"))
        assertNull(MetalInput.parseGrams("1.2345"))
        assertNull(MetalInput.parseGrams("abc"))
        assertNull(MetalInput.parseGrams("."))
    }

    @Test
    fun `REQ-EXP-063 the lot form flags empty zero and negative grams or amount and a future date`() {
        val bad = MetalInput.lot(BUY, "", "0", "2026-12-01", today)
        assertEquals(setOf(MetalInput.Field.GRAMS, MetalInput.Field.AMOUNT, MetalInput.Field.DATE), bad.errors)
        val neg = MetalInput.lot(BUY, "-1", "-5", "", today)
        assertEquals(setOf(MetalInput.Field.GRAMS, MetalInput.Field.AMOUNT), neg.errors)
        val ok = MetalInput.lot(BUY, "30", "120,000", "2026-05-09", today)
        assertEquals(30_000L, ok.value!!.quantityMilli)
        assertEquals(12_000_000L, ok.value.totalMinor)
    }

    @Test
    fun `REQ-EXP-063 purities are gold 24 22 21 18 and silver 999 925`() {
        assertEquals(listOf(24, 22, 21, 18), MetalMath.karatsOf("GOLD"))
        assertEquals(listOf(999, 925), MetalMath.karatsOf("SILVER"))
    }
}
