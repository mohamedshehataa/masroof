package eg.masroof

import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AccountFlowRow
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.DebtEntity
import eg.masroof.data.db.NetWorthSnapshotEntity
import eg.masroof.data.wealth.AccountField
import eg.masroof.data.wealth.AccountForm
import eg.masroof.data.wealth.AccountKinds
import eg.masroof.data.wealth.AccountView
import eg.masroof.data.wealth.AssetBucket
import eg.masroof.data.wealth.AssetField
import eg.masroof.data.wealth.AssetForm
import eg.masroof.data.wealth.AssetValuation
import eg.masroof.data.wealth.AssetView
import eg.masroof.data.wealth.BalanceMath
import eg.masroof.data.wealth.CardCycle
import eg.masroof.data.wealth.ChartRange
import eg.masroof.data.wealth.DebtField
import eg.masroof.data.wealth.DebtForm
import eg.masroof.data.wealth.DebtSource
import eg.masroof.data.wealth.NetWorthHistory
import eg.masroof.data.wealth.NetWorthMath
import eg.masroof.data.wealth.WealthValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WealthMathTest {
    private val checkAt = 1_000L

    private fun check(
        minor: Long,
        currency: String = "EGP",
    ) = AccountBalanceEntity("b", "acc", minor, currency, checkAt, "MANUAL", checkAt)

    private fun flow(
        type: String,
        minor: Long,
        at: Long = checkAt + 1,
        status: String = "CONFIRMED",
        currency: String = "EGP",
    ) = AccountFlowRow("acc", type, status, minor, currency, at)

    private val story =
        listOf(
            flow("EXPENSE", 120_000),
            flow("REFUND", 20_000),
            flow("INCOME", 500_000),
            flow("CASH_WITHDRAWAL", 100_000),
            flow("TRANSFER", 50_000),
            flow("EXPENSE", 70_000, at = checkAt - 1),
        )

    @Test
    fun `REQ-AN-168 worked example gives exactly 12,500 EGP`() {
        assertEquals(1_250_000L, BalanceMath.balance(check(1_000_000), story, creditCard = false))
    }

    @Test
    fun `REQ-AN-168 pending review counts, other statuses, failed currency and the check itself do not`() {
        val rows =
            listOf(
                flow("EXPENSE", 100, status = "PENDING_REVIEW"),
                flow("EXPENSE", 1_000, status = "REJECTED"),
                flow("EXPENSE", 10_000, currency = "USD"),
                flow("EXPENSE", 100_000, at = checkAt),
            )
        assertEquals(900L, BalanceMath.balance(check(1_000), rows, creditCard = false))
    }

    @Test
    fun `REQ-AN-168 credit card sign inversion gives the outstanding amount`() {
        // owed 18,300; a 1,200 purchase raises it, a 200 refund and a 5,000 payment lower it
        val rows = listOf(flow("EXPENSE", 120_000), flow("REFUND", 20_000), flow("INCOME", 500_000))
        assertEquals(1_830_000L + 120_000 - 20_000 - 500_000, BalanceMath.balance(check(1_830_000), rows, creditCard = true))
    }

    @Test
    fun `REQ-AN-168 the latest check wins`() {
        val a = check(5).copy(id = "a", atMillis = 10)
        val b = check(7).copy(id = "b", atMillis = 20)
        assertEquals("b", BalanceMath.latest(listOf(b, a))?.id)
    }

    private fun account(
        id: String = "acc",
        kind: String = AccountKinds.DEBIT,
        currency: String = "EGP",
        archived: Boolean = false,
    ) = AccountEntity(id, id, currency, kind, archived = archived)

    private fun view(
        balance: Long?,
        kind: String = AccountKinds.DEBIT,
        id: String = "acc",
        currency: String = "EGP",
    ) = AccountView(account(id, kind, currency), null, balance, null)

    private fun asset(
        kind: String,
        value: Long?,
        hidden: Boolean = false,
        currency: String = "EGP",
    ) = AssetEntity("a-$kind", kind, kind, currency, value, null, null, null, null, null, null, hidden, false, 0, 0)

    private fun debt(
        amount: Long,
        direction: String = "I_OWE",
        settled: Long? = null,
        currency: String = "EGP",
    ) = DebtEntity("d$amount", "Ahmed", "LOAN", direction, amount, currency, null, settled, null, 0)

    @Test
    fun `REQ-AN-168 accounts without a balance are left out and never become a negative`() {
        val w =
            NetWorthMath.compute(
                listOf(view(null, id = "x"), view(null, id = "y"), view(500_00, id = "z")),
                emptyList(),
                emptyList(),
                0,
            )
        assertEquals(2, w.uncountedAccounts)
        assertEquals(500_00L, w.netMinor)
        assertTrue(w.netMinor >= 0)
    }

    @Test
    fun `REQ-AN-168 only unconfirmed accounts leave nothing to show`() {
        val w = NetWorthMath.compute(listOf(view(null)), emptyList(), emptyList(), 0)
        assertFalse(w.hasData)
        assertEquals(0L, w.netMinor)
    }

    @Test
    fun `REQ-AN-169 net worth is accounts plus assets plus owed to me minus debts, cards and installments`() {
        val accounts =
            listOf(
                view(1_000_00, id = "bank"),
                view(-200_00, id = "over"),
                view(300_00, AccountKinds.CREDIT_CARD, id = "card"),
                view(50_00, AccountKinds.CASH, id = "cash"),
            )
        val assets = listOf(AssetView(asset("PROPERTY", 5_000_00), 5_000_00), AssetView(asset("VEHICLE", 900_00, hidden = true), 900_00))
        val debts = listOf(debt(2_000_00), debt(100_00, "OWED_TO_ME"), debt(777_00, settled = 1))
        val w = NetWorthMath.compute(accounts, assets, debts, 400_00)
        assertEquals(1_000_00 + 50_00 + 5_000_00 + 100_00L, w.assetsMinor)
        assertEquals(200_00 + 300_00 + 2_000_00 + 400_00L, w.debtsMinor)
        assertEquals(w.assetsMinor - w.debtsMinor, w.netMinor)
        val sources = w.debtLines.map { it.source }
        assertTrue(DebtSource.CARD in sources && DebtSource.OVERDRAWN in sources && DebtSource.INSTALLMENTS in sources)
    }

    @Test
    fun `REQ-AN-169 shares are integer basis points and the stacked bar buckets by kind`() {
        val w =
            NetWorthMath.compute(
                listOf(view(7_500_00)),
                listOf(AssetView(asset("PROPERTY", 2_500_00), 2_500_00)),
                emptyList(),
                0,
            )
        assertEquals(7_500, w.buckets.first { it.bucket == AssetBucket.SPENDABLE }.shareBps)
        assertEquals(2_500, w.buckets.first { it.bucket == AssetBucket.PROPERTY }.shareBps)
        assertTrue(w.breakdown().startsWith("SPENDABLE=750000;"))
        assertTrue(w.breakdown().endsWith("DEBTS=0"))
    }

    @Test
    fun `REQ-AN-169 other currency items are excluded with a count`() {
        val w =
            NetWorthMath.compute(
                listOf(view(10, currency = "USD")),
                listOf(AssetView(asset("OTHER", 5, currency = "USD"), 5)),
                listOf(debt(9, currency = "USD")),
                0,
            )
        assertEquals(3, w.otherCurrencyItems)
        assertFalse(w.hasData)
    }

    private fun snap(
        date: String,
        net: Long,
    ) = NetWorthSnapshotEntity("s$date", date, net, 0, net, "EGP", "", 0)

    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun `REQ-AN-169 year change shows a percent only for a positive start and a non negative end`() {
        val s = listOf(snap("2026-02-01", 100_000), snap("2026-01-15", 200_000), snap("2025-12-31", 1))
        // the first snapshot of the Cairo year is 2026-01-15 (200,000)
        assertEquals(50_000L, NetWorthHistory.yearChange(s, 250_000, today)?.absoluteMinor)
        assertEquals(2_500, NetWorthHistory.yearChange(s, 250_000, today)?.percentBps)
        assertNull(NetWorthHistory.yearChange(s, -5, today)?.percentBps)
        assertEquals(-5L - 200_000, NetWorthHistory.yearChange(s, -5, today)?.absoluteMinor)
    }

    @Test
    fun `REQ-AN-169 a first value of zero or below never gives a percent, the minus 480 percent defect`() {
        assertNull(NetWorthHistory.yearChange(listOf(snap("2026-01-02", -95_874_00)), 100, today)?.percentBps)
        assertNull(NetWorthHistory.yearChange(listOf(snap("2026-01-02", 0)), 100, today)?.percentBps)
        assertNull(NetWorthHistory.yearChange(emptyList(), 100, today))
        assertNull(NetWorthHistory.yearChange(listOf(snap("2025-12-31", 5)), 100, today))
    }

    @Test
    fun `REQ-AN-169 chart ranges cut by Cairo date`() {
        val s = listOf(snap("2026-11-08", 3), snap("2026-10-20", 2), snap("2026-08-01", 1), snap("2025-01-01", 0))
        assertEquals(listOf("2026-10-20", "2026-11-08"), NetWorthHistory.points(s, ChartRange.MONTH_1, today).map { it.cairoDate })
        assertEquals(2, NetWorthHistory.points(s, ChartRange.MONTH_3, today).size)
        assertEquals(3, NetWorthHistory.points(s, ChartRange.YEAR_1, today).size)
        assertEquals(4, NetWorthHistory.points(s, ChartRange.ALL, today).size)
        assertEquals(1, NetWorthHistory.points(s.take(1), ChartRange.ALL, today).size)
    }

    @Test
    fun `REQ-EXP-058 card cycle table incl day 31 clamping and leap years`() {
        // statement 20, due 5, today 25 Nov -> latest statement 20 Nov, next due 5 Dec
        assertEquals(LocalDate.of(2026, 11, 20), CardCycle.lastStatement(LocalDate.of(2026, 11, 25), 20))
        assertEquals(LocalDate.of(2026, 12, 5), CardCycle.nextDue(LocalDate.of(2026, 11, 25), 20, 5))
        // before the statement day this month the statement is last month's
        assertEquals(LocalDate.of(2026, 10, 20), CardCycle.lastStatement(LocalDate.of(2026, 11, 8), 20))
        assertEquals(LocalDate.of(2026, 11, 5), CardCycle.nextDue(LocalDate.of(2026, 11, 8), 20, 5))
        // due day after statement day lands in the same month
        assertEquals(LocalDate.of(2026, 11, 28), CardCycle.nextDue(LocalDate.of(2026, 11, 21), 10, 28))
        // day 31 clamps
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.lastStatement(LocalDate.of(2026, 3, 1), 31))
        assertEquals(LocalDate.of(2026, 4, 30), CardCycle.lastStatement(LocalDate.of(2026, 4, 30), 31))
        assertEquals(LocalDate.of(2028, 2, 29), CardCycle.lastStatement(LocalDate.of(2028, 3, 5), 31))
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.clamp(31, java.time.YearMonth.of(2026, 2)))
        // statement 31, due 31: statement Jan 31, due is the first due after it -> Feb 28
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.nextDue(LocalDate.of(2026, 2, 10), 31, 31))
        // year boundary
        assertEquals(LocalDate.of(2027, 1, 5), CardCycle.nextDue(LocalDate.of(2026, 12, 25), 20, 5))
    }

    @Test
    fun `REQ-EXP-059 simple interest is integer, half away from zero and leap year aware`() {
        // 100,000 EGP at 18% for a plain 365 days earns 18,000 EGP
        assertEquals(10_000_000L + 1_800_000, AssetValuation.simpleValue(10_000_000, 1_800, 365))
        // 2024 has 366 days: 10,000,000 * 1800 * 366 / 3,650,000 = 1,804,931.5 -> 1,804,932
        val leap = AssetValuation.fund(10_000_000, 1_800, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))
        assertEquals(10_000_000L + 1_804_932, leap)
        // 1000 * 10000 * 1 / 3,650,000 = 2.74 -> 3 ; 100 * 5000 * 1 / 3,650,000 = 0.137 -> 0
        assertEquals(1_003L, AssetValuation.simpleValue(1_000, 10_000, 1))
        assertEquals(100L, AssetValuation.simpleValue(100, 5_000, 1))
        // zero or negative days and a zero rate leave the principal
        assertEquals(500L, AssetValuation.simpleValue(500, 1_000, 0))
        assertEquals(500L, AssetValuation.simpleValue(500, 0, 400))
    }

    @Test
    fun `REQ-EXP-059 half way interest rounds up and a certificate stops at maturity`() {
        // 73,000 * 5000 * 1 / 3,650,000 = 100 exactly; 36,500 * 5000 * 1 / 3,650,000 = 50 ; use 365 * 5000 * 1 / 3,650,000 = 0.5 -> 1
        assertEquals(366L, AssetValuation.simpleValue(365, 5_000, 1))
        val start = LocalDate.of(2026, 1, 1)
        val maturity = LocalDate.of(2026, 7, 1)
        val atMaturity = AssetValuation.certificate(10_000_000, 1_800, start, maturity, maturity)
        assertEquals(atMaturity, AssetValuation.certificate(10_000_000, 1_800, start, maturity, LocalDate.of(2027, 5, 5)))
        assertEquals(10_000_000L, AssetValuation.certificate(10_000_000, 1_800, start, maturity, LocalDate.of(2025, 12, 1)))
    }

    @Test
    fun `REQ-EXP-057 account validation errors and the no full number rule`() {
        val ok = AccountForm(label = "CIB debit", last4 = "1234")
        assertNotNull(WealthValidation.account(ok, emptyList()).value)
        assertEquals(setOf(AccountField.LABEL), WealthValidation.account(ok.copy(label = " "), emptyList()).errors)
        assertEquals(setOf(AccountField.LAST4), WealthValidation.account(ok.copy(last4 = "123"), emptyList()).errors)
        assertEquals(setOf(AccountField.LAST4), WealthValidation.account(ok.copy(last4 = "1234567890123456"), emptyList()).errors)
        assertEquals(setOf(AccountField.LAST4), WealthValidation.account(ok.copy(last4 = "12a4"), emptyList()).errors)
        assertEquals(setOf(AccountField.LAST4), WealthValidation.account(ok.copy(last4 = ""), emptyList()).errors)
        assertNotNull(WealthValidation.account(ok.copy(kind = AccountKinds.CASH, last4 = ""), emptyList()).value)
        assertEquals("1234", WealthValidation.account(ok.copy(last4 = "١٢٣٤"), emptyList()).value?.last4)
    }

    @Test
    fun `REQ-EXP-057 a second active account with the same kind and last four is rejected`() {
        val existing = listOf(AccountEntity("e1", "Old", "EGP", AccountKinds.DEBIT, last4 = "1234"))
        val form = AccountForm(label = "New", last4 = "1234")
        assertEquals(setOf(AccountField.DUPLICATE), WealthValidation.account(form, existing).errors)
        assertNotNull(WealthValidation.account(form, existing, editingId = "e1").value)
        assertNotNull(WealthValidation.account(form.copy(kind = AccountKinds.WALLET), existing).value)
        assertNotNull(WealthValidation.account(form, listOf(existing[0].copy(archived = true))).value)
    }

    @Test
    fun `REQ-EXP-058 card field validation`() {
        val card =
            AccountForm(label = "Visa", kind = AccountKinds.CREDIT_CARD, last4 = "4321", limit = "50000", statementDay = "20", dueDay = "5")
        val draft = WealthValidation.account(card, emptyList()).value
        assertEquals(5_000_000L, draft?.limitMinor)
        assertEquals(20, draft?.statementDay)
        assertEquals(setOf(AccountField.LIMIT), WealthValidation.account(card.copy(limit = "0"), emptyList()).errors)
        assertEquals(setOf(AccountField.STATEMENT_DAY), WealthValidation.account(card.copy(statementDay = "32"), emptyList()).errors)
        assertEquals(setOf(AccountField.DUE_DAY), WealthValidation.account(card.copy(dueDay = "0"), emptyList()).errors)
    }

    @Test
    fun `REQ-EXP-057 balance parsing allows zero and negative and rejects text`() {
        assertEquals(1_250_050L, WealthValidation.parseBalance("12,500.50"))
        assertEquals(1_250_050L, WealthValidation.parseBalance("١٢٬٥٠٠٫٥٠"))
        assertEquals(0L, WealthValidation.parseBalance("0"))
        assertEquals(-30_000L, WealthValidation.parseBalance("-300"))
        assertNull(WealthValidation.parseBalance("abc"))
        assertNull(WealthValidation.parseBalance(""))
    }

    @Test
    fun `REQ-EXP-059 asset and debt validation`() {
        assertNotNull(WealthValidation.asset(AssetForm("PROPERTY", "Flat", value = "1500000")).value)
        assertEquals(setOf(AssetField.NAME, AssetField.VALUE), WealthValidation.asset(AssetForm("VEHICLE", "", value = "0")).errors)
        val cert = AssetForm("CERTIFICATE", "NBE", principal = "100000", rate = "18.5", start = "2026-01-01", maturity = "2027-01-01")
        val draft = WealthValidation.asset(cert).value
        assertEquals(1_850, draft?.rateBp)
        assertEquals(setOf(AssetField.MATURITY), WealthValidation.asset(cert.copy(maturity = "2025-12-31")).errors)
        assertEquals(setOf(AssetField.RATE), WealthValidation.asset(cert.copy(rate = "1000.01")).errors)
        assertEquals(setOf(AssetField.PRINCIPAL), WealthValidation.asset(cert.copy(principal = "0")).errors)
        assertNotNull(WealthValidation.asset(cert.copy(rate = "0")).value)
        assertNotNull(WealthValidation.asset(AssetForm("FUND", "Fund", principal = "1000", rate = "12", start = "2026-03-01")).value)
        val d = WealthValidation.debt(DebtForm("Loan from Ahmed", amount = "20000", due = "2027-01-01")).value
        assertEquals(2_000_000L, d?.amountMinor)
        assertEquals(setOf(DebtField.NAME, DebtField.AMOUNT), WealthValidation.debt(DebtForm("", amount = "-1")).errors)
        assertEquals(setOf(DebtField.DUE), WealthValidation.debt(DebtForm("x", amount = "1", due = "tomorrow")).errors)
    }
}
