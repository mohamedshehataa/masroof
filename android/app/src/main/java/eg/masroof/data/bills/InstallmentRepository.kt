package eg.masroof.data.bills

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.CHANNEL_MANUAL
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.InstallmentPaymentEntity
import eg.masroof.data.db.InstallmentPlanEntity
import eg.masroof.data.db.seededCategoryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The providers the form offers, in order. [key] is what `installment_plan.provider` stores; [brand] is the entry's merchant. */
enum class BillProvider(
    val key: String,
    val brand: String?,
) {
    VALU("VALU", "valU"),
    HALAN("HALAN", "Halan"),
    SOUHOOLA("SOUHOOLA", "Souhoola"),
    GAM3EYA(PROVIDER_GAM3EYA, "Gam3eya"),
    MONEY_FELLOWS("MONEY_FELLOWS", "Money Fellows"),
    CONTACT("CONTACT", "Contact"),
    AMAN("AMAN", "Aman"),
    FORSA("FORSA", "Forsa"),
    SYMPL("SYMPL", "Sympl"),
    BANK("BANK", null),
    OTHER_STORE("OTHER_STORE", null),
    NONE("NONE", null),
    ;

    companion object {
        fun of(key: String): BillProvider = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** "What is it for" chips; each gives the plan a default category. */
enum class BillPurpose(
    val key: String,
    val categoryKey: String,
) {
    PHONE("PHONE", "electronics"),
    CAR("CAR", "car"),
    LAPTOP("LAPTOP", "electronics"),
    FURNITURE("FURNITURE", "household"),
    AC("AC", "household"),
    FRIDGE("FRIDGE", "household"),
    TV("TV", "electronics"),
    PERSONAL_LOAN("PERSONAL_LOAN", "household"),
    ;

    val categoryId: String get() = seededCategoryId(categoryKey)
}

const val DEFAULT_CATEGORY_KEY = "household"

/** Which form field a validation message belongs to. */
enum class PlanField { TITLE, AMOUNT, COUNT, END_DATE, FIRST_DATE, TURN, PAYOUT, PURCHASE_PRICE, DOWN_PAYMENT, ALREADY_PAID }

/** What the repository needs to create a plan and its schedule. A gam3eya sets [collectionTurn] (the share is [monthlyMinor]). */
data class NewPlan(
    val title: String,
    val provider: String,
    val monthlyMinor: Long,
    val paymentCount: Int,
    val firstDue: LocalDate,
    val categoryId: String? = null,
    val accountId: String? = null,
    val purchasePriceMinor: Long? = null,
    val downPaymentMinor: Long = 0L,
    val reminderDaysBefore: Int = BillMath.DEFAULT_REMINDER_DAYS,
    val alreadyPaid: Int = 0,
    val notes: String? = null,
    val collectionTurn: Int? = null,
    val payoutMinor: Long? = null,
    val currency: String = Money.EGP,
)

object PlanValidator {
    /** Every field that is wrong; empty means the plan can be stored. */
    fun validate(plan: NewPlan): Set<PlanField> {
        val bad = mutableSetOf<PlanField>()
        if (plan.title.isBlank()) bad += PlanField.TITLE
        if (plan.monthlyMinor !in 1..MoneyFormat.MAX_ENTRY_MINOR) bad += PlanField.AMOUNT
        if (plan.paymentCount !in 1..BillMath.MAX_PAYMENTS) bad += PlanField.COUNT
        if (plan.alreadyPaid !in 0..plan.paymentCount.coerceAtLeast(0)) bad += PlanField.ALREADY_PAID
        if (plan.purchasePriceMinor != null && plan.purchasePriceMinor !in 1..MoneyFormat.MAX_ENTRY_MINOR) bad += PlanField.PURCHASE_PRICE
        if (plan.downPaymentMinor !in 0..MoneyFormat.MAX_ENTRY_MINOR) bad += PlanField.DOWN_PAYMENT
        if (plan.collectionTurn != null && plan.collectionTurn !in 1..plan.paymentCount.coerceAtLeast(1)) bad += PlanField.TURN
        if (plan.payoutMinor != null && plan.payoutMinor !in 1..MoneyFormat.MAX_ENTRY_MINOR) bad += PlanField.PAYOUT
        return bad
    }
}

/** The way a payment is settled in the Record payment dialog. Never both an existing and a new entry. */
sealed interface PaymentChoice {
    data class LinkExisting(
        val expenseId: String,
    ) : PaymentChoice

    data object CreateEntry : PaymentChoice

    data object MarkOnly : PaymentChoice
}

/** What Undo needs to put a recorded payment (or collection) back exactly as it was. */
data class UndoToken(
    val plan: InstallmentPlanEntity,
    val payment: InstallmentPaymentEntity?,
    val createdExpenseId: String?,
)

/** The marker kept in `expense.paymentMethod` for a gam3eya movement: an outgoing share or the incoming collection. */
const val METHOD_GAM3EYA_PAYMENT = "GAM3EYA_PAYMENT"
const val METHOD_GAM3EYA_COLLECTION = "GAM3EYA_COLLECTION"
const val TYPE_EXPENSE = "EXPENSE"
const val TYPE_TRANSFER = "TRANSFER"

interface InstallmentRepository {
    fun observePlans(): Flow<List<InstallmentPlanEntity>>

    fun observePayments(): Flow<List<InstallmentPaymentEntity>>

    fun observePlan(id: String): Flow<InstallmentPlanEntity?>

    fun observePaymentsOf(planId: String): Flow<List<InstallmentPaymentEntity>>

    /** Validates (IllegalArgumentException with nothing written when wrong) and stores the plan and its schedule in one transaction. */
    suspend fun create(plan: NewPlan): String

    /** Entries that could already be this payment (see [eg.masroof.data.db.InstallmentPaymentDao.linkCandidates]). */
    suspend fun linkCandidates(paymentId: String): List<ExpenseEntity>

    /** Marks the payment paid now and links or creates its entry in one transaction; null when it is missing or already paid. */
    suspend fun recordPayment(
        paymentId: String,
        choice: PaymentChoice,
    ): UndoToken?

    /** A gam3eya collection: marks it received now and records the incoming TRANSFER entry. Null when there is none to collect. */
    suspend fun recordCollection(planId: String): UndoToken?

    suspend fun undo(token: UndoToken)

    /** Closes the remaining payments without entries and completes the plan. */
    suspend fun complete(planId: String): Boolean

    /** Removes the plan and its payments; entries that were linked stay. */
    suspend fun delete(planId: String)
}

@Singleton
class DefaultInstallmentRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val clock: Clock,
        private val ids: IdFactory,
        private val signal: LocalDataSignal,
        private val eraseLock: EraseLock,
    ) : InstallmentRepository {
        override fun observePlans() = db.installmentPlans().observeAll()

        override fun observePayments() = db.installmentPayments().observeAll()

        override fun observePlan(id: String) = db.installmentPlans().observeById(id)

        override fun observePaymentsOf(planId: String) = db.installmentPayments().observeByPlan(planId)

        override suspend fun create(plan: NewPlan): String {
            val invalid = PlanValidator.validate(plan)
            require(invalid.isEmpty()) { "Invalid plan: $invalid" }
            val now = clock.millis()
            val planId = ids.next()
            val dates = BillMath.dueDates(plan.firstDue, plan.paymentCount)
            val payout = plan.collectionTurn?.let { turn -> CairoTime.noonOf(BillMath.collectionDate(plan.firstDue, turn)).toEpochMilli() }
            val payoutAmount =
                if (plan.collectionTurn == null) {
                    null
                } else {
                    plan.payoutMinor ?: requireNotNull(BillMath.defaultPayout(plan.monthlyMinor, plan.paymentCount)) { "Payout overflows" }
                }
            val row =
                InstallmentPlanEntity(
                    id = planId,
                    title = plan.title.trim(),
                    provider = plan.provider,
                    categoryId = plan.categoryId,
                    accountId = plan.accountId,
                    currency = plan.currency,
                    purchasePriceMinor = plan.purchasePriceMinor,
                    downPaymentMinor = plan.downPaymentMinor,
                    monthlyAmountMinor = plan.monthlyMinor,
                    paymentCount = plan.paymentCount,
                    firstDueAtMillis = CairoTime.noonOf(plan.firstDue).toEpochMilli(),
                    reminderDaysBefore = plan.reminderDaysBefore,
                    status = PLAN_ACTIVE,
                    notes = plan.notes?.trim()?.ifEmpty { null },
                    createdAtMillis = now,
                    completedAtMillis = null,
                    payoutAtMillis = payout,
                    payoutAmountMinor = payoutAmount,
                    payoutReceivedAtMillis = null,
                )
            val payments =
                dates.mapIndexed { i, date ->
                    val due = CairoTime.noonOf(date).toEpochMilli()
                    InstallmentPaymentEntity(
                        id = ids.next(),
                        planId = planId,
                        seq = i + 1,
                        dueAtMillis = due,
                        amountMinor = plan.monthlyMinor,
                        // Already paid in the past: closed on the day it fell due, with no entry.
                        paidAtMillis = if (i < plan.alreadyPaid) due else null,
                        expenseId = null,
                    )
                }
            // A plan whose every payment is already paid is created completed.
            val finished = plan.alreadyPaid >= plan.paymentCount
            db.withTransaction {
                db.installmentPlans().insert(if (finished) row.copy(status = PLAN_COMPLETED, completedAtMillis = now) else row)
                payments.forEach { db.installmentPayments().insert(it) }
            }
            return planId
        }

        override suspend fun linkCandidates(paymentId: String): List<ExpenseEntity> {
            val payment = db.installmentPayments().find(paymentId) ?: return emptyList()
            val plan = db.installmentPlans().find(payment.planId) ?: return emptyList()
            val due = CairoTime.dateOf(payment.dueAtMillis)
            val from =
                due
                    .minusDays(BillMath.CANDIDATE_WINDOW_DAYS)
                    .atStartOfDay(CairoTime.zone)
                    .toInstant()
                    .toEpochMilli()
            val to =
                due
                    .plusDays(BillMath.CANDIDATE_WINDOW_DAYS + 1)
                    .atStartOfDay(CairoTime.zone)
                    .toInstant()
                    .toEpochMilli()
            return db.installmentPayments().linkCandidates(payment.amountMinor, plan.currency, from, to)
        }

        override suspend fun recordPayment(
            paymentId: String,
            choice: PaymentChoice,
        ): UndoToken? {
            val now = clock.millis()
            val token =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val payment = db.installmentPayments().find(paymentId)?.takeIf { it.paidAtMillis == null }
                        val plan = payment?.let { db.installmentPlans().find(it.planId) }
                        if (payment == null || plan == null) {
                            null
                        } else {
                            val created = settle(plan, payment, choice, now)
                            val linked = (choice as? PaymentChoice.LinkExisting)?.expenseId
                            db.installmentPayments().update(payment.copy(paidAtMillis = now, expenseId = linked ?: created))
                            val open = db.installmentPayments().byPlan(plan.id).count { it.paidAtMillis == null }
                            if (open == 0) db.installmentPlans().update(plan.copy(status = PLAN_COMPLETED, completedAtMillis = now))
                            UndoToken(plan, payment, created)
                        }
                    }
                }
            if (token?.createdExpenseId != null) signal.changed()
            return token
        }

        /** Links (never to an entry another payment already holds) or creates the entry; returns the id of a created one. */
        private suspend fun settle(
            plan: InstallmentPlanEntity,
            payment: InstallmentPaymentEntity,
            choice: PaymentChoice,
            now: Long,
        ): String? =
            when (choice) {
                is PaymentChoice.LinkExisting -> {
                    val entry = db.expenses().find(choice.expenseId)
                    require(
                        entry != null &&
                            entry.deletedAtMillis == null &&
                            entry.type == TYPE_EXPENSE &&
                            entry.amountMinor == payment.amountMinor &&
                            entry.currency == plan.currency,
                    ) { "Entry cannot be linked" }
                    val held = db.installmentPayments().linkCandidates(entry.amountMinor, entry.currency, 0L, Long.MAX_VALUE)
                    require(held.any { it.id == entry.id }) { "Entry is already linked to a payment" }
                    null
                }
                PaymentChoice.CreateEntry -> createEntry(plan, payment, now)
                PaymentChoice.MarkOnly -> null
            }

        private suspend fun createEntry(
            plan: InstallmentPlanEntity,
            payment: InstallmentPaymentEntity,
            now: Long,
        ): String {
            val gam3eya = plan.provider == PROVIDER_GAM3EYA
            return insertEntry(
                plan = plan,
                amountMinor = payment.amountMinor,
                type = if (gam3eya) TYPE_TRANSFER else TYPE_EXPENSE,
                method = if (gam3eya) METHOD_GAM3EYA_PAYMENT else null,
                note = "${plan.title} ${payment.seq}/${plan.paymentCount}",
                now = now,
            )
        }

        private suspend fun insertEntry(
            plan: InstallmentPlanEntity,
            amountMinor: Long,
            type: String,
            method: String?,
            note: String,
            now: Long,
        ): String {
            val id = ids.next()
            db.expenses().insert(
                ExpenseEntity(
                    id = id,
                    amountMinor = amountMinor,
                    currency = plan.currency,
                    occurredAtMillis = now,
                    merchant = BillProvider.of(plan.provider).brand ?: plan.title,
                    categoryId = if (type == TYPE_EXPENSE) plan.categoryId else null,
                    accountId = plan.accountId,
                    notes = note,
                    pending = false,
                    source = CHANNEL_MANUAL,
                    type = type,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    timeKnown = true,
                    paymentMethod = method,
                ),
            )
            db.sources().insert(
                ExpenseSourceEntity(
                    id = ids.next(),
                    expenseId = id,
                    channel = CHANNEL_MANUAL,
                    localSourceId = id,
                    receivedAtMillis = now,
                    senderId = null,
                    instrumentLast4 = null,
                ),
            )
            return id
        }

        override suspend fun recordCollection(planId: String): UndoToken? {
            val now = clock.millis()
            val token =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        val plan =
                            db.installmentPlans().find(planId)?.takeIf {
                                it.payoutAtMillis != null &&
                                    it.payoutReceivedAtMillis == null
                            }
                        val amount = plan?.payoutAmountMinor
                        if (plan == null || amount == null) {
                            null
                        } else {
                            val id = insertEntry(plan, amount, TYPE_TRANSFER, METHOD_GAM3EYA_COLLECTION, "${plan.title} (collection)", now)
                            db.installmentPlans().update(plan.copy(payoutReceivedAtMillis = now))
                            UndoToken(plan, null, id)
                        }
                    }
                }
            if (token != null) signal.changed()
            return token
        }

        override suspend fun undo(token: UndoToken) {
            eraseLock.mutex.withLock {
                db.withTransaction {
                    token.payment?.let { db.installmentPayments().update(it) }
                    token.createdExpenseId?.let { db.expenses().delete(it) }
                    db.installmentPlans().update(token.plan)
                }
            }
            if (token.createdExpenseId != null) signal.changed()
        }

        override suspend fun complete(planId: String): Boolean {
            val now = clock.millis()
            return db.withTransaction {
                val plan = db.installmentPlans().find(planId)
                if (plan == null) {
                    false
                } else {
                    db.installmentPayments().closeOpen(planId, now)
                    db.installmentPlans().update(plan.copy(status = PLAN_COMPLETED, completedAtMillis = now))
                    true
                }
            }
        }

        override suspend fun delete(planId: String) {
            db.withTransaction { db.installmentPlans().find(planId)?.let { db.installmentPlans().delete(it) } }
        }
    }
