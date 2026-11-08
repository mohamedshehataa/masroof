package eg.masroof.core

import java.time.DateTimeException
import java.time.LocalDate

/**
 * The one parser of a calendar date a user types in any form (Add expense, goals, plans, subscriptions, debts, assets, balances, metal
 * prices). Only a plain `yyyy-mm-dd` with a four-digit year is a date: no sign (`+10000-03-01`, `-0001-03-01`), no other layout. So every
 * date the app can store lies in years 0000..9999, which is exactly the range a backup restore accepts (RestoreValidator).
 */
object UserDate {
    private val PLAIN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** [text] in Western or Arabic-Indic digits; null when it is not a real date in that layout. */
    fun parse(text: String): LocalDate? {
        val western = text.trim().map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
        if (!PLAIN.matches(western)) return null
        return try {
            LocalDate.parse(western)
        } catch (_: DateTimeException) {
            null
        }
    }
}
