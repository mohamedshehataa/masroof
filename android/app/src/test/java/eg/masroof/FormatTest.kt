package eg.masroof

import eg.masroof.core.DateFormat
import eg.masroof.core.DigitStyle
import eg.masroof.core.PercentFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class FormatTest {
    private fun en(bps: Int, signed: Boolean = false) = PercentFormat.format(bps, false, DigitStyle.WESTERN, signed)

    @Test
    fun `REQ-AND-070 percent shows one decimal only when needed`() {
        assertEquals("12%", en(1_200))
        assertEquals("12.5%", en(1_250))
        assertEquals("0%", en(0))
        assertEquals("100%", en(10_000))
        assertEquals("33.3%", en(3_333))
        assertEquals("66.7%", en(6_667))
    }

    @Test
    fun `REQ-AND-070 percent rounds half up to a tenth and keeps the sign`() {
        assertEquals("0.1%", en(5))
        assertEquals("0%", en(4))
        assertEquals("-12.5%", en(-1_250))
        assertEquals("+12.5%", en(1_250, signed = true))
        assertEquals("0%", en(0, signed = true))
        assertEquals("0%", en(-4))
    }

    @Test
    fun `REQ-AND-070 Arabic percent uses Arabic-Indic digits and the Arabic percent sign`() {
        assertEquals("١٢٫٥٪", PercentFormat.format(1_250, true, DigitStyle.ARABIC_INDIC))
        assertEquals("١٢٪", PercentFormat.format(1_200, true, DigitStyle.ARABIC_INDIC))
        assertEquals("12٪", PercentFormat.format(1_200, true, DigitStyle.WESTERN))
        assertEquals("12.5%", PercentFormat.format(1_250, false, DigitStyle.WESTERN))
    }

    @Test
    fun `REQ-AND-070 dates follow the language and the digit setting`() {
        val d = LocalDate.of(2026, 10, 5)
        assertEquals("5 Oct", DateFormat.shortDate(d, false, DigitStyle.WESTERN))
        assertEquals("٥ أكتوبر", DateFormat.shortDate(d, true, DigitStyle.ARABIC_INDIC))
        assertEquals("5 أكتوبر", DateFormat.shortDate(d, true, DigitStyle.WESTERN))
        assertEquals("Oct", DateFormat.monthShort(d, false))
        assertEquals("٥", DateFormat.dayNumber(d, DigitStyle.ARABIC_INDIC))
        assertEquals("5", DateFormat.dayNumber(d, DigitStyle.WESTERN))
        assertEquals("١٢", DateFormat.number(12, DigitStyle.ARABIC_INDIC))
    }

    @Test
    fun `REQ-AND-070 weekday names exist in both languages`() {
        assertEquals("Sat", DateFormat.weekday(DayOfWeek.SATURDAY, false, narrow = false))
        assertEquals("S", DateFormat.weekday(DayOfWeek.SATURDAY, false, narrow = true))
        assertEquals("السبت", DateFormat.weekday(DayOfWeek.SATURDAY, true, narrow = false))
    }
}
