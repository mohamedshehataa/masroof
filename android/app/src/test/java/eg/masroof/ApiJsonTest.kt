package eg.masroof

import eg.masroof.api.models.ExpenseCreate
import eg.masroof.api.models.ExpensePage
import eg.masroof.api.models.Money
import eg.masroof.api.models.PaymentMethod
import eg.masroof.api.models.TransactionType
import eg.masroof.data.remote.apiJson
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Guards the generated client against the real wire format: Money.amountMinor is Long, enums/UUID/Instant round-trip. */
class ApiJsonTest {
    @Test
    fun `REQ-AND-CLIENT create request serialises money as integer minor units`() {
        val body =
            ExpenseCreate(
                type = TransactionType.EXPENSE,
                amount = Money(12_550, "EGP"),
                occurredAt = Instant.parse("2026-10-15T10:00:00Z"),
                categoryId = UUID.fromString("7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60"),
                paymentMethod = PaymentMethod.UNKNOWN,
            )
        val json = apiJson.encodeToString(ExpenseCreate.serializer(), body)
        assertTrue(json, json.contains("\"amountMinor\":12550"))
        assertTrue(json, json.contains("\"type\":\"EXPENSE\""))
        assertTrue(json, json.contains("\"categoryId\":\"7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60\""))
        assertEquals(body, apiJson.decodeFromString(ExpenseCreate.serializer(), json))
    }

    @Test
    fun `REQ-AND-CLIENT expense page deserialises and ignores unknown fields`() {
        val json =
            """
            {"items":[{"id":"7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60","householdId":"0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f",
            "type":"EXPENSE","amount":{"amountMinor":125000,"currency":"EGP"},"occurredAt":"2026-10-15T10:00:00Z",
            "paymentMethod":"CARD","status":"CONFIRMED","confidence":0.9,"tags":[],"isRecurring":false,"splitMode":"NONE",
            "items":[],"sources":[],"splits":[],"createdAt":"2026-10-15T10:00:00Z","updatedAt":"2026-10-15T10:00:00Z",
            "futureField":1}],"nextCursor":null}
            """.trimIndent()
        val page = apiJson.decodeFromString(ExpensePage.serializer(), json)
        assertEquals(
            125_000L,
            page.items
                .single()
                .amount.amountMinor,
        )
        assertEquals(null, page.nextCursor)
    }
}
