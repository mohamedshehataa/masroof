package eg.masroof.data.wealth

import eg.masroof.core.Money
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import java.math.BigInteger
import java.time.LocalDate

const val KIND_CREDIT_CARD = "CREDIT_CARD"

/** An account with its latest check and the balance (or card outstanding) computed from it; null balance = never confirmed. */
data class AccountView(
    val account: AccountEntity,
    val check: AccountBalanceEntity?,
    val balanceMinor: Long?,
    val card: CardStatus?,
) {
    val isCreditCard: Boolean get() = account.kind == KIND_CREDIT_CARD
}

data class CardStatus(
    val outstandingMinor: Long,
    val availableMinor: Long?,
    val spentSinceStatementMinor: Long,
    val nextDue: LocalDate?,
)

data class AssetView(
    val asset: AssetEntity,
    val valueMinor: Long?,
)

enum class AssetBucket { SPENDABLE, CERTIFICATE, FUND, PROPERTY, VEHICLE, OTHER }

enum class DebtSource { DEBT, CARD, OVERDRAWN, INSTALLMENTS }

data class DebtLine(
    val id: String?,
    val source: DebtSource,
    val name: String?,
    val amountMinor: Long,
)

data class BucketShare(
    val bucket: AssetBucket,
    val amountMinor: Long,
    val shareBps: Int,
)

data class NetWorth(
    val assetsMinor: Long,
    val debtsMinor: Long,
    val buckets: List<BucketShare>,
    val debtLines: List<DebtLine>,
    /** Accounts left out because no balance was ever confirmed. */
    val uncountedAccounts: Int,
    /** Items left out because they are not in the base currency. */
    val otherCurrencyItems: Int,
    /** True when at least one thing was counted: an empty wealth is not a zero. */
    val hasData: Boolean,
) {
    val netMinor: Long get() = saturatingAdd(assetsMinor, -debtsMinor)

    /** `KIND=minor;KIND=minor` for the snapshot row (asset buckets, then DEBTS). */
    fun breakdown(): String = (buckets.map { "${it.bucket.name}=${it.amountMinor}" } + "DEBTS=$debtsMinor").joinToString(";")

    companion object {
        val EMPTY = NetWorth(0, 0, emptyList(), emptyList(), 0, 0, false)
    }
}

object NetWorthMath {
    private const val BASE = Money.EGP

    private fun bucketOf(kind: String): AssetBucket =
        when (kind) {
            "CERTIFICATE" -> AssetBucket.CERTIFICATE
            "FUND" -> AssetBucket.FUND
            "PROPERTY" -> AssetBucket.PROPERTY
            "VEHICLE" -> AssetBucket.VEHICLE
            else -> AssetBucket.OTHER
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun compute(
        accounts: List<AccountView>,
        assets: List<AssetView>,
        debts: List<DebtEntity>,
        installmentsLeftMinor: Long,
    ): NetWorth {
        val amounts = AssetBucket.entries.associateWith { 0L }.toMutableMap()
        val lines = mutableListOf<DebtLine>()
        val liveAccounts = accounts.filter { !it.account.archived }
        val liveAssets = assets.filter { !it.asset.archived && !it.asset.hiddenFromTotals }
        val openDebts = debts.filter { it.settledAtMillis == null }
        val (baseAccounts, foreignAccounts) = liveAccounts.partition { it.account.currency == BASE }
        val (baseAssets, foreignAssets) = liveAssets.partition { it.asset.currency == BASE }
        val (baseDebts, foreignDebts) = openDebts.partition { it.currency == BASE }
        val uncounted = baseAccounts.count { it.balanceMinor == null }
        val otherCurrency = foreignAccounts.size + foreignAssets.size + foreignDebts.size
        var counted = 0

        baseAccounts.forEach { a ->
            val balance = a.balanceMinor ?: return@forEach
            counted++
            when {
                a.isCreditCard && balance > 0 -> lines += DebtLine(a.account.id, DebtSource.CARD, a.account.label, balance)
                a.isCreditCard -> amounts.merge(AssetBucket.SPENDABLE, -balance, ::saturatingAdd)
                balance < 0 -> lines += DebtLine(a.account.id, DebtSource.OVERDRAWN, a.account.label, -balance)
                else -> amounts.merge(AssetBucket.SPENDABLE, balance, ::saturatingAdd)
            }
        }
        baseAssets.forEach { v ->
            val value = v.valueMinor ?: return@forEach
            counted++
            amounts.merge(bucketOf(v.asset.kind), value, ::saturatingAdd)
        }
        baseDebts.forEach { d ->
            counted++
            if (d.direction == "OWED_TO_ME") {
                amounts.merge(AssetBucket.OTHER, d.amountMinor, ::saturatingAdd)
            } else {
                lines += DebtLine(d.id, DebtSource.DEBT, d.name, d.amountMinor)
            }
        }
        if (installmentsLeftMinor > 0) {
            counted++
            lines += DebtLine(null, DebtSource.INSTALLMENTS, null, installmentsLeftMinor)
        }
        val assetsTotal = amounts.values.fold(0L, ::saturatingAdd)
        val shares = largestRemainderShares(amounts)
        val debtsTotal = lines.fold(0L) { acc, l -> saturatingAdd(acc, l.amountMinor) }
        return NetWorth(assetsTotal, debtsTotal, shares, lines, uncounted, otherCurrency, counted > 0)
    }

    /** Basis-point shares of the positive buckets that always add up to exactly 10,000 (largest remainder, ties by order). */
    internal fun largestRemainderShares(amounts: Map<AssetBucket, Long>): List<BucketShare> {
        val positive = AssetBucket.entries.associateWith { maxOf((amounts[it] ?: 0L), 0L).toBigInteger() }
        val total = positive.values.fold(BigInteger.ZERO, BigInteger::add)
        if (total.signum() == 0) return AssetBucket.entries.map { BucketShare(it, (amounts[it] ?: 0L), 0) }
        val scaled = positive.mapValues { it.value * BigInteger.valueOf(BPS) }
        val floors = scaled.mapValues { (it.value / total).toInt() }.toMutableMap()
        val leftover = BPS.toInt() - floors.values.sum()
        AssetBucket.entries
            .filter { positive.getValue(it).signum() > 0 }
            .sortedByDescending { scaled.getValue(it).mod(total) }
            .take(leftover)
            .forEach { floors[it] = floors.getValue(it) + 1 }
        return AssetBucket.entries.map { BucketShare(it, (amounts[it] ?: 0L), floors.getValue(it)) }
    }

    private const val BPS = 10_000L
}

enum class ChartRange(
    val months: Int?,
) {
    MONTH_1(1),
    MONTH_3(3),
    YEAR_1(12),
    ALL(null),
}

data class YearChange(
    val absoluteMinor: Long,
    /** Basis points; null unless the first value was above 0 and the current value is not negative. */
    val percentBps: Int?,
    /** True when the real percent is beyond the cap: show ">999%" instead of a huge number. */
    val percentCapped: Boolean = false,
)

private const val PERCENT_CAP_BPS = 99_900

object NetWorthHistory {
    fun points(
        snapshots: List<NetWorthSnapshotEntity>,
        range: ChartRange,
        today: LocalDate,
    ): List<NetWorthSnapshotEntity> {
        val from = range.months?.let { today.minusMonths(it.toLong()).toString() }
        return snapshots.filter { from == null || it.cairoDate >= from }.sortedBy { it.cairoDate }
    }

    /** Change since the first snapshot of the Cairo year; null when there is none. */
    fun yearChange(
        snapshots: List<NetWorthSnapshotEntity>,
        currentMinor: Long,
        today: LocalDate,
    ): YearChange? {
        val first = snapshots.filter { it.cairoDate >= "${today.year}-01-01" }.minByOrNull { it.cairoDate } ?: return null
        val diff = BigInteger.valueOf(currentMinor) - BigInteger.valueOf(first.netMinor)
        val absolute = diff.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        if (first.netMinor <= 0 || currentMinor < 0) return YearChange(absolute, null)
        val first0 = BigInteger.valueOf(first.netMinor)
        val twice = BigInteger.valueOf(2)
        val bps = (diff.abs() * BigInteger.valueOf(10_000) * twice + first0) / (first0 * twice) * BigInteger.valueOf(diff.signum().toLong())
        val capped = bps.abs() > BigInteger.valueOf(PERCENT_CAP_BPS.toLong())
        val shown = if (capped) PERCENT_CAP_BPS * diff.signum() else bps.toInt()
        return YearChange(absolute, shown, capped)
    }
}
