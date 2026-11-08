package eg.masroof.data.wealth

import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.MetalPriceEntity
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Gold and silver kept by the gram (REQ-AN-173). Pure: quantities in milligrams, money in minor units, all integers. */
object MetalMath {
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val GOLD = "GOLD"
    const val SILVER = "SILVER"
    const val STALE_AFTER_DAYS = 7L
    const val MILLI_PER_GRAM = 1_000L
    private const val BPS = 10_000L

    val GOLD_KARATS = listOf(24, 22, 21, 18)
    val SILVER_KARATS = listOf(999, 925)

    fun karatsOf(metal: String): List<Int> = if (metal == SILVER) SILVER_KARATS else GOLD_KARATS

    fun isMetal(kind: String): Boolean = kind == GOLD || kind == SILVER

    /** One buy or sale: [quantityMilli] grams x 1000 and the [totalMinor] paid or received. */
    data class Lot(
        val id: String,
        val side: String,
        val quantityMilli: Long,
        val totalMinor: Long,
        val atMillis: Long,
    )

    /**
     * What is held after replaying the lots oldest first with the average-cost method: a sale removes cost in proportion to
     * the grams sold. [costMinor] is the cost of what is still held.
     */
    data class Position(
        val heldMilli: Long,
        val costMinor: Long,
        val realisedMinor: Long,
    ) {
        /** Average paid per gram in minor units; null when nothing is held. */
        val averagePerGramMinor: Long?
            get() = if (heldMilli <= 0L) null else divRound(big(costMinor) * big(MILLI_PER_GRAM), big(heldMilli)).clamp()
    }

    enum class PriceState { NONE, FRESH, STALE }

    data class Valuation(
        val position: Position,
        val priceMinor: Long?,
        val priceAtMillis: Long?,
        val priceState: PriceState,
        /** Held grams x today's price; the cost of what is held while no price was typed. */
        val valueMinor: Long,
        val gainMinor: Long,
        /** Gain as basis points of the cost, half away from zero; null without a price or without a cost. */
        val gainBp: Int?,
    )

    private fun chronological(lots: List<Lot>): List<Lot> =
        lots.sortedWith(compareBy<Lot> { it.atMillis }.thenBy { if (it.side == BUY) 0 else 1 }.thenBy { it.id })

    @Suppress("NestedBlockDepth")
    fun position(lots: List<Lot>): Position {
        var held = BigInteger.ZERO
        var cost = BigInteger.ZERO
        var realised = BigInteger.ZERO
        chronological(lots).forEach { lot ->
            val qty = big(lot.quantityMilli)
            val total = big(lot.totalMinor)
            if (lot.side == BUY) {
                held += qty
                cost += total
            } else {
                val sold = qty.min(held)
                if (sold.signum() > 0) {
                    val removed = divRound(cost * sold, held)
                    val proceeds = if (sold == qty) total else divRound(total * sold, qty)
                    realised += proceeds - removed
                    held -= sold
                    cost -= removed
                    if (held.signum() == 0) cost = BigInteger.ZERO
                }
            }
        }
        return Position(held.clamp(), cost.clamp(), realised.clamp())
    }

    /** The grams held just before the first sale that sells more than is held; null when no sale oversells. */
    fun firstOversell(lots: List<Lot>): Long? {
        var held = 0L
        chronological(lots).forEach { lot ->
            if (lot.side == BUY) {
                held += lot.quantityMilli
            } else {
                if (lot.quantityMilli > held) return held
                held -= lot.quantityMilli
            }
        }
        return null
    }

    /** Grams x price per gram, half away from zero. */
    fun valueOf(
        heldMilli: Long,
        pricePerGramMinor: Long,
    ): Long = divRound(big(heldMilli) * big(pricePerGramMinor), big(MILLI_PER_GRAM)).clamp()

    fun gainBp(
        gainMinor: Long,
        costMinor: Long,
    ): Int? =
        if (costMinor <= 0L) {
            null
        } else {
            divRound(big(gainMinor) * big(BPS), big(costMinor)).clamp().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }

    /** [priceAtMillis] null means no price was typed; [dateOf] turns millis into the Cairo day. */
    fun valuation(
        lots: List<Lot>,
        priceMinor: Long?,
        priceAtMillis: Long?,
        today: LocalDate,
        dateOf: (Long) -> LocalDate,
    ): Valuation {
        val pos = position(lots)
        if (priceMinor == null || priceAtMillis == null) {
            return Valuation(pos, null, null, PriceState.NONE, pos.costMinor, 0L, null)
        }
        val value = valueOf(pos.heldMilli, priceMinor)
        val gain = (big(value) - big(pos.costMinor)).clamp()
        val stale = ChronoUnit.DAYS.between(dateOf(priceAtMillis), today) > STALE_AFTER_DAYS
        return Valuation(
            pos,
            priceMinor,
            priceAtMillis,
            if (stale) PriceState.STALE else PriceState.FRESH,
            value,
            gain,
            gainBp(gain, pos.costMinor),
        )
    }

    fun latestPrice(
        prices: List<MetalPriceEntity>,
        metal: String,
        karat: Int,
    ): MetalPriceEntity? =
        prices.filter { it.metal == metal && it.karat == karat }.maxWithOrNull(compareBy<MetalPriceEntity> { it.atMillis }.thenBy { it.id })

    fun lotOf(e: AssetLotEntity): Lot = Lot(e.id, e.side, e.quantityMilli, e.totalMinor, e.atMillis)

    /** The valuation of a gold or silver holding from its stored lots and the typed prices. */
    fun valuationOf(
        asset: AssetEntity,
        lots: List<AssetLotEntity>,
        prices: List<MetalPriceEntity>,
        today: LocalDate,
        dateOf: (Long) -> LocalDate,
    ): Valuation {
        val price = asset.karat?.let { latestPrice(prices, asset.kind, it) }
        return valuation(lots.filter { it.assetId == asset.id }.map(::lotOf), price?.pricePerGramMinor, price?.atMillis, today, dateOf)
    }

    private fun big(v: Long): BigInteger = BigInteger.valueOf(v)

    private fun BigInteger.clamp(): Long = max(big(Long.MIN_VALUE)).min(big(Long.MAX_VALUE)).toLong()

    private fun divRound(
        numerator: BigInteger,
        divisor: BigInteger,
    ): BigInteger {
        if (divisor.signum() == 0) return BigInteger.ZERO
        val two = BigInteger.valueOf(2)
        val q = (numerator.abs() * two + divisor.abs()) / (divisor.abs() * two)
        return if (numerator.signum() * divisor.signum() < 0) q.negate() else q
    }
}
