package eg.masroof.data.wealth

import eg.masroof.core.Money
import eg.masroof.core.analytics.Projections
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
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
    val netMinor: Long get() = assetsMinor - debtsMinor

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
                a.isCreditCard -> amounts.merge(AssetBucket.SPENDABLE, -balance, Long::plus)
                balance < 0 -> lines += DebtLine(a.account.id, DebtSource.OVERDRAWN, a.account.label, -balance)
                else -> amounts.merge(AssetBucket.SPENDABLE, balance, Long::plus)
            }
        }
        baseAssets.forEach { v ->
            val value = v.valueMinor ?: return@forEach
            counted++
            amounts.merge(bucketOf(v.asset.kind), value, Long::plus)
        }
        baseDebts.forEach { d ->
            counted++
            if (d.direction == "OWED_TO_ME") {
                amounts.merge(AssetBucket.OTHER, d.amountMinor, Long::plus)
            } else {
                lines += DebtLine(d.id, DebtSource.DEBT, d.name, d.amountMinor)
            }
        }
        if (installmentsLeftMinor > 0) {
            counted++
            lines += DebtLine(null, DebtSource.INSTALLMENTS, null, installmentsLeftMinor)
        }
        val assetsTotal = amounts.values.sum()
        val shares =
            AssetBucket.entries.map { b ->
                BucketShare(b, amounts.getValue(b), Projections.shareBps(amounts.getValue(b), assetsTotal))
            }
        return NetWorth(assetsTotal, lines.sumOf { it.amountMinor }, shares, lines, uncounted, otherCurrency, counted > 0)
    }
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
)

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
        val diff = currentMinor - first.netMinor
        val bps =
            if (first.netMinor > 0 && currentMinor >= 0) {
                Math.toIntExact(Projections.divideRounded(Math.multiplyExact(diff, 10_000L), first.netMinor))
            } else {
                null
            }
        return YearChange(diff, bps)
    }
}
