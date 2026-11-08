package eg.masroof.data.wealth

import eg.masroof.data.db.AssetEntity
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What an asset is worth today, in integer minor units. Interest is simple, by actual days over 365, half away from zero. */
object AssetValuation {
    private val two: BigInteger = BigInteger.valueOf(2)
    private const val BPS = 10_000L
    private const val DAYS_PER_YEAR = 365L

    /** Principal plus simple interest for [days] at [rateBp] a year. */
    fun simpleValue(
        principalMinor: Long,
        rateBp: Int,
        days: Long,
    ): Long {
        if (days <= 0 || rateBp <= 0) return principalMinor
        val numerator = BigInteger.valueOf(principalMinor) * BigInteger.valueOf(rateBp.toLong()) * BigInteger.valueOf(days)
        val divisor = BigInteger.valueOf(BPS * DAYS_PER_YEAR)
        val sign = numerator.signum().toLong()
        val interest = (numerator.abs() * two + divisor) / (divisor * two)
        return principalMinor + sign * interest.toLong()
    }

    /** A certificate accrues until its maturity and no further. */
    fun certificate(
        principalMinor: Long,
        rateBp: Int,
        start: LocalDate,
        maturity: LocalDate,
        today: LocalDate,
    ): Long {
        val end = if (today < maturity) today else maturity
        return simpleValue(principalMinor, rateBp, ChronoUnit.DAYS.between(start, end))
    }

    /** A fund grows pro rata by days since its start, with no end date. */
    fun fund(
        principalMinor: Long,
        rateBp: Int,
        start: LocalDate,
        today: LocalDate,
    ): Long = simpleValue(principalMinor, rateBp, ChronoUnit.DAYS.between(start, today))

    /** The value to count, or null when the asset has nothing to count yet (no manual value / no principal). */
    fun valueOf(
        asset: AssetEntity,
        today: LocalDate,
        dateOf: (Long) -> LocalDate,
    ): Long? {
        val principal = asset.principalMinor
        val start = asset.startAtMillis?.let(dateOf)
        return when {
            asset.kind == "CERTIFICATE" && principal != null && start != null ->
                certificate(principal, asset.annualRateBp ?: 0, start, asset.maturityAtMillis?.let(dateOf) ?: today, today)
            asset.kind == "FUND" && principal != null && start != null -> fund(principal, asset.annualRateBp ?: 0, start, today)
            else -> asset.valueMinor ?: principal
        }
    }
}
