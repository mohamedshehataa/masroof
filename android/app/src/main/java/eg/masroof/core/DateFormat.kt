package eg.masroof.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Short, locale-aware dates for lists and charts. Digits follow the app's digit setting, not the JVM locale. */
object DateFormat {
    private val arabicLocale = Locale.forLanguageTag("ar")

    private fun locale(arabic: Boolean) = if (arabic) arabicLocale else Locale.ENGLISH

    /** `15 Oct` / `١٥ أكتوبر`. */
    fun shortDate(
        date: LocalDate,
        arabic: Boolean,
        digits: DigitStyle,
    ): String = digitsAs(DateTimeFormatter.ofPattern("d MMM", locale(arabic)).format(date), digits)

    /** Month name only: `Oct` / `أكتوبر`. */
    fun monthShort(
        date: LocalDate,
        arabic: Boolean,
    ): String = DateTimeFormatter.ofPattern("MMM", locale(arabic)).format(date)

    /** Day of month as a number in the chosen digits. */
    fun dayNumber(
        date: LocalDate,
        digits: DigitStyle,
    ): String = digitsAs(date.dayOfMonth.toString(), digits)

    /** One-letter weekday heading (narrow) or short name. */
    fun weekday(
        day: DayOfWeek,
        arabic: Boolean,
        narrow: Boolean,
    ): String = day.getDisplayName(if (narrow) TextStyle.NARROW else TextStyle.SHORT, locale(arabic))

    /** Formats an integer in the chosen digits (counts, days left). */
    fun number(
        value: Int,
        digits: DigitStyle,
    ): String = digitsAs(value.toString(), digits)

    /** Rewrites every digit (Western or Arabic-Indic) into [digits]. */
    fun digitsAs(
        text: String,
        digits: DigitStyle,
    ): String =
        text
            .map { c ->
                val western = if (c in '٠'..'٩') '0' + (c - '٠') else c
                if (digits == DigitStyle.ARABIC_INDIC && western in '0'..'9') '٠' + (western - '0') else western
            }.joinToString("")
}
