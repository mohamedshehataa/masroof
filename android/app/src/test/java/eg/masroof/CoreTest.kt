package eg.masroof

import eg.masroof.core.CairoTime
import eg.masroof.core.DigitStyle
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CoreTest {
    @Test
    fun `REQ-AND-004 formats english with western digits`() {
        assertEquals("1,250 EGP", MoneyFormat.format(Money(125_000), arabic = false, digits = DigitStyle.WESTERN))
        assertEquals("1,250.50 EGP", MoneyFormat.format(Money(125_050), arabic = false, digits = DigitStyle.WESTERN))
        assertEquals("0.05 EGP", MoneyFormat.format(Money(5), arabic = false, digits = DigitStyle.WESTERN))
        assertEquals("-3 EGP", MoneyFormat.format(Money(-300), arabic = false, digits = DigitStyle.WESTERN))
    }

    @Test
    fun `REQ-AND-004 formats arabic with arabic-indic digits and currency symbol`() {
        assertEquals("١٬٢٥٠ ج.م", MoneyFormat.format(Money(125_000), arabic = true, digits = DigitStyle.ARABIC_INDIC))
        assertEquals("١٬٢٥٠ ج.م", MoneyFormat.format(Money(125_000), arabic = true, digits = DigitStyle.ARABIC_INDIC))
    }

    @Test
    fun `REQ-AND-004 arabic ui can still show western digits`() {
        assertEquals("1,250 ج.م", MoneyFormat.format(Money(125_000), arabic = true, digits = DigitStyle.WESTERN))
    }

    @Test
    fun `REQ-AND-004 large amounts group every three digits without overflow`() {
        assertEquals(
            "92,233,720,368,547,758.07 EGP",
            MoneyFormat.format(Money(Long.MAX_VALUE), false, DigitStyle.WESTERN),
        )
    }

    @Test
    fun `REQ-AND-004 parses input into minor units without floating point`() {
        assertEquals(12_550L, MoneyFormat.parseMinor("125.5"))
        assertEquals(12_500L, MoneyFormat.parseMinor("125"))
        assertEquals(1_250L, MoneyFormat.parseMinor("12,5"))
        assertEquals(12_550L, MoneyFormat.parseMinor("١٢٥٫٥٠"))
        assertEquals(5L, MoneyFormat.parseMinor(".05"))
    }

    @Test
    fun `REQ-AND-004 rejects invalid or non positive input`() {
        listOf("", "abc", "0", "0.00", "1.234", "1.2.3", "-5", ".", "99999999999999999999").forEach {
            assertNull("input '$it'", MoneyFormat.parseMinor(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `REQ-AND-004 mixed currencies cannot be added`() {
        Money(1, "EGP") + Money(1, "USD")
    }

    @Test
    fun `REQ-AND-013 month window uses Cairo calendar not UTC`() {
        // 2026-03-31T22:30Z is already 2026-04-01 00:30 in Cairo (UTC+2).
        val now = Instant.parse("2026-03-31T22:30:00Z")
        val (start, end) = CairoTime.monthRangeMillis(now)
        assertEquals(LocalDate.of(2026, 4, 1), CairoTime.dateOf(start))
        assertEquals(LocalDate.of(2026, 5, 1), CairoTime.dateOf(end))
    }

    @Test
    fun `REQ-AND-013 noon of a date stays on that date`() {
        val date = LocalDate.of(2026, 7, 1)
        assertEquals(date, CairoTime.dateOf(CairoTime.noonOf(date).toEpochMilli()))
    }

    @Test
    fun `REQ-AND-017 server url normalisation`() {
        assertEquals("http://10.0.2.2/", normalizeServerUrl(" http://10.0.2.2 "))
        assertEquals("https://masroof.example/", normalizeServerUrl("https://masroof.example/"))
        assertNull(normalizeServerUrl("masroof.example"))
        assertNull(normalizeServerUrl("http://"))
    }
}
