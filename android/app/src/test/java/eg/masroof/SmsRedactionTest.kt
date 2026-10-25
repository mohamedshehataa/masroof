package eg.masroof

import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.MessageClass
import eg.masroof.ingest.OtpPromoFilter
import eg.masroof.ingest.SenderPolicy
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun lines(name: String): List<JsonObject> =
    File("../../fixtures/sms/$name")
        .readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }

private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

/** Invisible characters are written as markers in the shared fixture file and expanded here. */
private fun JsonObject.body() =
    str("body")
        .replace("{SHY}", 0x00AD.toChar().toString())
        .replace("{ISEP}", 0x2063.toChar().toString())
        .replace("{ZWSP}", 0x200B.toChar().toString())

private fun assertRedacted(
    id: String,
    redacted: String,
    secrets: Set<String>,
    allowed: Set<String>,
) {
    for (run in Regex("\\d+").findAll(redacted).map { it.value }.filter { it.length >= 4 }) {
        assertTrue("$id keeps only allowed runs, found $run", run in allowed)
    }
    val digitsOnly = redacted.replace(Regex("\\D"), "")
    for (secret in secrets) {
        assertFalse("$id hides $secret", redacted.contains(secret))
        assertFalse("$id hides $secret across separators", digitsOnly.contains(secret))
    }
}

class SmsRedactionTest {
    @Test
    fun `REQ-SMS-030 every probe - no secret digit run survives redaction and the strong-signal class is right`() {
        val probes = lines("secrets.jsonl")
        assertTrue(probes.size >= 25)
        for (p in probes) {
            val id = p.str("id")
            val redacted = SmsRedactor.redact(p.body())
            assertRedacted(id, redacted, p.strings("secrets"), p.strings("allowed"))
            assertEquals("$id is idempotent", redacted, SmsRedactor.redact(redacted))
            if (p.containsKey("class")) {
                assertEquals("$id class", MessageClass.valueOf(p.str("class")), OtpPromoFilter.classify(p.body()))
            }
        }
    }

    @Test
    fun `REQ-SMS-030 digits in every script and glued to letters are masked`() {
        assertEquals("Code••••••", SmsRedactor.redact("Code٨٨٩٩٠٠"))
        assertEquals("abc••••def", SmsRedactor.redact("abc1234def"))
        assertEquals("EGP 1,250.50 at X", SmsRedactor.redact("EGP 1,250.50 at X"))
        assertEquals("", SmsRedactor.redact(""))
    }

    @Test
    fun `REQ-SMS-030 genuine purchases with reference codes are kept and their codes masked`() {
        val cases = lines("genuine.jsonl")
        assertEquals(22, cases.size)
        for (c in cases) {
            val id = c.str("id")
            assertEquals("$id is not an OTP", MessageClass.OTHER, OtpPromoFilter.classify(c.str("body")))
            val redacted = SmsRedactor.redact(c.str("body"))
            assertRedacted(id, redacted, c.strings("secrets"), c.strings("allowed"))
        }
    }

    @Test
    fun `REQ-SMS-030 an Arabic message whose only number is a 4 to 8 digit amount is not an OTP`() {
        for (body in listOf("تم خصم 1500 جنيه لدى جرير", "تم ايداع 1000 جنيه في حسابك", "تم خصم مبلغ 1500 لدى يونيكلو طوكيو")) {
            assertEquals(body, MessageClass.OTHER, OtpPromoFilter.classify(body))
            assertTrue(body, SmsRedactor.redact(body).contains("1000") || SmsRedactor.redact(body).contains("1500"))
        }
    }

    @Test
    fun `REQ-SMS-030 the receiver hands over only the redacted body, never the raw text`() {
        val sent = mutableListOf<IncomingSms>()
        val scheduler =
            object : eg.masroof.ingest.SmsWorkScheduler {
                override fun enqueueIntake(sms: IncomingSms) {
                    sent += sms
                }

                override fun scheduleUpload() = Unit

                override fun scheduleUploadRetry(
                    delaySeconds: Long,
                    attempt: Int,
                ) = Unit
            }
        val settings =
            object : eg.masroof.ingest.SenderSettings {
                override fun policy() = SenderPolicy()

                override fun addSender(sender: String) = Unit

                override fun removeSender(sender: String) = Unit

                override fun setDefaultEnabled(
                    name: String,
                    enabled: Boolean,
                ) = Unit

                override fun suggestions() = emptyList<String>()

                override fun suggest(sender: String) = Unit

                override fun dismissSuggestion(sender: String) = Unit
            }
        SmsHandoff(settings, scheduler)
            .handOff(listOf(IncomingSms("CIB", "Auth code:551234 for purchase of EGP 500.00 at AMAZON", 1_000L)))
        assertEquals(1, sent.size)
        assertFalse(sent[0].body.contains("551234"))
        assertTrue(sent[0].body.contains("500.00"))
    }
}
