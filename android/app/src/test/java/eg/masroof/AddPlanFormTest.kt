package eg.masroof

import eg.masroof.data.bills.BillProvider
import eg.masroof.data.bills.BillPurpose
import eg.masroof.data.bills.PlanField
import eg.masroof.data.db.seededCategoryId
import eg.masroof.ui.bills.AddPlanForm
import eg.masroof.ui.bills.FormResult
import eg.masroof.ui.bills.derived
import eg.masroof.ui.bills.dueBefore
import eg.masroof.ui.bills.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AddPlanFormTest {
    private val today = LocalDate.parse("2026-11-08")

    private fun form(
        monthly: String = "3,250",
        count: String = "18",
        first: String = "2026-03-05",
    ) = AddPlanForm(title = "iPhone 17 Pro", monthly = monthly, count = count, firstDate = first)

    private fun ok(f: AddPlanForm) = (f.parse(today) as FormResult.Ok).plan

    private fun errors(f: AddPlanForm) = (f.parse(today) as FormResult.Errors).fields

    @Test
    fun `REQ-EXP-053 valU 3,250 for 18 months from 2026-03-05 parses to 325000 minor units`() {
        val plan = ok(form())
        assertEquals(325_000L, plan.monthlyMinor)
        assertEquals(18, plan.paymentCount)
        assertEquals(LocalDate.parse("2026-03-05"), plan.firstDue)
        assertEquals("VALU", plan.provider)
        assertEquals(1, plan.reminderDaysBefore)
        // no chip: Household items
        assertEquals(seededCategoryId("household"), plan.categoryId)
    }

    @Test
    fun `REQ-EXP-053 the chip picks the default category`() {
        assertEquals(seededCategoryId("electronics"), ok(form().copy(purpose = BillPurpose.PHONE)).categoryId)
        assertEquals(seededCategoryId("car"), ok(form().copy(purpose = BillPurpose.CAR)).categoryId)
        assertEquals("picked", ok(form().copy(purpose = BillPurpose.CAR, categoryId = "picked")).categoryId)
    }

    @Test
    fun `REQ-EXP-053 an end date gives the count and a count gives the end date`() {
        val byEnd = form(count = "").copy(byEndDate = true, endDate = "2027-08-05")
        assertEquals(18, byEnd.derived().count)
        assertEquals(18, ok(byEnd).paymentCount)
        val byCount = form()
        assertEquals(LocalDate.parse("2027-08-05"), byCount.derived().endDate)
    }

    @Test
    fun `REQ-EXP-053 the first payment in the past asks how many are already paid with the default`() {
        assertEquals(9, form().dueBefore(today))
        assertEquals(9, ok(form()).alreadyPaid)
        assertEquals(4, ok(form().copy(alreadyPaid = "4")).alreadyPaid)
        assertEquals(0, form(first = "2026-12-05").dueBefore(today))
    }

    @Test
    fun `REQ-EXP-053 Arabic-Indic digits are understood`() {
        val plan = ok(form(monthly = "٣٬٢٥٠", count = "١٨", first = "٢٠٢٦-٠٣-٠٥"))
        assertEquals(325_000L, plan.monthlyMinor)
        assertEquals(18, plan.paymentCount)
        assertEquals(LocalDate.parse("2026-03-05"), plan.firstDue)
    }

    @Test
    fun `REQ-EXP-053 amount empty zero negative or too large is a field error`() {
        listOf("", "0", "-5", "abc", "10000000001").forEach {
            assertTrue("amount '$it'", PlanField.AMOUNT in errors(form(monthly = it)))
        }
        assertTrue(ok(form(monthly = "10000000000")).monthlyMinor == 1_000_000_000_000L)
    }

    @Test
    fun `REQ-EXP-053 zero or more than 120 payments is a field error`() {
        listOf("0", "121", "", "x").forEach { assertTrue("count '$it'", PlanField.COUNT in errors(form(count = it))) }
        assertEquals(120, ok(form(count = "120")).paymentCount)
    }

    @Test
    fun `REQ-EXP-053 an end date before the first payment is a field error`() {
        val f = form(count = "").copy(byEndDate = true, endDate = "2026-03-04")
        assertTrue(PlanField.END_DATE in errors(f))
        assertTrue(PlanField.END_DATE in errors(f.copy(endDate = "")))
        assertTrue(PlanField.FIRST_DATE in errors(form(first = "not a date")))
    }

    @Test
    fun `REQ-EXP-053 a plan with no name is a field error`() {
        assertTrue(PlanField.TITLE in errors(form().copy(title = " ")))
    }

    private fun gam3eya(
        turn: String = "4",
        members: String = "10",
    ) = AddPlanForm(
        provider = BillProvider.GAM3EYA,
        title = "Office gam3eya",
        monthly = "2,000",
        members = members,
        turn = turn,
        firstDate = "2026-11-01",
    )

    @Test
    fun `REQ-EXP-055 a gam3eya of 2,000 for 10 members with turn 4 collects 20,000 in February`() {
        val plan = ok(gam3eya())
        assertEquals(200_000L, plan.monthlyMinor)
        assertEquals(10, plan.paymentCount)
        assertEquals(4, plan.collectionTurn)
        assertEquals(2_000_000L, plan.payoutMinor)
        assertNull(plan.categoryId)
        assertEquals(1_900_000L, ok(gam3eya().copy(payout = "19,000")).payoutMinor)
    }

    @Test
    fun `REQ-EXP-055 a turn outside 1 to the number of members is a field error`() {
        listOf("0", "11", "", "x", "-1").forEach { assertTrue("turn '$it'", PlanField.TURN in errors(gam3eya(turn = it))) }
        assertEquals(10, ok(gam3eya(turn = "10")).collectionTurn)
        assertTrue(PlanField.COUNT in errors(gam3eya(members = "0")))
    }
}
