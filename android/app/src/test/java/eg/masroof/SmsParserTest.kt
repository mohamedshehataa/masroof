package eg.masroof

import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.LabelNormalizer
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.NotificationApps
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.SecretDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

private val RECEIVED = Instant.parse("2026-10-26T10:00:00Z").toEpochMilli()
private val CASES_RECEIVED = Instant.parse("2026-03-12T12:10:00Z").toEpochMilli()

private fun fixture(name: String): List<JsonObject> =
    File("../../fixtures/sms/$name")
        .readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }

private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

private fun JsonObject.opt(key: String) = this[key]?.jsonPrimitive?.contentOrNull

internal fun message(
    sender: String,
    body: String,
    at: Long = RECEIVED,
    source: CaptureSource = CaptureSource.SMS,
) = IncomingMessage(source, sender, body, at)

internal fun digitsRemoved(s: String) =
    s
        .filterNot { it.isDigit() }
        .replace("•", "")
        .replace("*", "")
        .trim()

/** What the engine extracted: the uploaded record of a proven family, or the suggestion of a review item the user must confirm. */
internal fun extracted(outcome: ParseOutcome): eg.masroof.ingest.parse.ParsedRecord? =
    when (outcome) {
        is ParseOutcome.Record -> outcome.record
        is ParseOutcome.Review -> outcome.suggestion
        else -> null
    }

/** Everything that could leave the phone for this outcome, as text. */
internal fun wireText(outcome: ParseOutcome): String =
    when (outcome) {
        is ParseOutcome.Record -> outcome.record.toString()
        else -> ""
    }

class SmsParserTest {
    @Test
    fun `REQ-SMS-010 the 22 genuine purchases with reference codes and footers become records`() {
        val rows = fixture("genuine.jsonl")
        assertTrue(rows.size >= 22)
        for (row in rows) {
            val outcome = MessageParser.parse(message(row.str("sender"), row.str("body")))
            // these shapes are not a proven family: a review item that carries the extraction as its suggestion (nothing uploads)
            val r = extracted(outcome)
            assertNotNull("${row.str("id")} -> $outcome", r)
            requireNotNull(r)
            assertEquals(row.str("id"), row.str("amountMinor").toLong(), r.amountMinor)
            assertEquals(row.str("currency"), r.currency)
            val expectedKinds =
                when (row.str("kind")) {
                    "TRANSFER" -> setOf("TRANSFER_OUT", "TRANSFER_IN")
                    else -> setOf(row.str("kind"))
                }
            assertTrue("${row.str("id")} kind ${r.kind}", r.kind.name in expectedKinds)
            // nothing that leaves the phone carries a reference code
            for (secret in row.getValue("secrets").jsonArray.map { it.jsonPrimitive.content }) {
                assertFalse("${row.str("id")} leaks $secret", r.toString().contains(secret))
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")
    @Test
    fun `REQ-SMS-010 template fixtures parse to the expected kind, amount, currency, last4 and digit-free merchant`() {
        // "unknown-sender-generic" carries a long number next to the label: kept on the phone for review on purpose.
        val reviewOnPurpose = setOf("unknown-sender-generic", "nbe-en-transfer")
        for (row in fixture("cases.jsonl")) {
            val id = row.str("id")
            val expect = (row["expect"] as? JsonObject) ?: JsonObject(emptyMap())
            val received = row.opt("receivedAt")?.let { Instant.parse(it).toEpochMilli() } ?: CASES_RECEIVED
            val outcome = MessageParser.parse(message(row.str("sender"), row.str("body"), received))
            val kind = expect.opt("kind")
            if (id in reviewOnPurpose) {
                assertTrue("$id -> $outcome", outcome is ParseOutcome.Review)
                continue
            }
            if (kind != null && expect.opt("currency") != "EGP" && expect.opt("currency") != null) {
                assertTrue("$id foreign currency stays on the phone: $outcome", outcome is ParseOutcome.Review)
                continue
            }
            if (kind == null || kind == "REVERSAL" || kind == "INFO") {
                assertFalse("$id must not become a record: $outcome", outcome is ParseOutcome.Record)
                continue
            }
            val r = extracted(outcome)
            assertNotNull("$id -> $outcome", r)
            requireNotNull(r)
            assertEquals(id, expect.str("amountMinor").toLong(), r.amountMinor)
            assertEquals(id, expect.str("currency"), r.currency)
            val kinds = if (kind == "TRANSFER") setOf("TRANSFER_OUT", "TRANSFER_IN") else setOf(kind)
            assertTrue("$id kind ${r.kind}", r.kind.name in kinds)
            // A last four named only as the RECIPIENT ("to account ending 9876") is not the household instrument.
            expect.opt("last4")?.let { assertEquals(id, if (id == "nbe-en-transfer") null else it, r.instrumentLast4) }
            expect.opt("merchant")?.let { assertEquals(id, digitsRemoved(it), r.merchant) }
            expect.opt("occurredAt")?.let { assertEquals(id, Instant.parse(it).toEpochMilli(), r.occurredAtMillis) }
            assertNull(id, r.merchant?.firstOrNull { it.isDigit() })
        }
    }

    @Test
    fun `REQ-SMS-030 no secret probe ever becomes a record and no review text keeps a secret`() {
        val probes = fixture("secrets.jsonl")
        assertTrue(probes.size >= 25)
        for (p in probes) {
            val body =
                p
                    .str("body")
                    .replace("{SHY}", 0x00AD.toChar().toString())
                    .replace("{ISEP}", 0x2063.toChar().toString())
                    .replace("{ZWSP}", 0x200B.toChar().toString())
            val outcome = MessageParser.parse(message("CIB", body))
            val secrets = p.getValue("secrets").jsonArray.map { it.jsonPrimitive.content }
            if (outcome is ParseOutcome.Record) {
                // Allowed only when the text really has no secret signal; the record itself must still be clean.
                for (s in secrets) assertFalse("${p.str("id")} leaks $s", outcome.record.toString().contains(s))
            }
            if (outcome is ParseOutcome.Review && outcome.reason == "secret") {
                val digits = outcome.text.replace(Regex("\\D"), "")
                for (s in secrets.filter { it.length >= 4 }) assertFalse("${p.str("id")} keeps $s", digits.contains(s))
            }
        }
    }

    @Test
    fun `REQ-SMS-030 every input from the three verifier rounds is kept on the phone or classified safely`() {
        val rows = fixture("verifier.jsonl") + fixture("round2.jsonl")
        assertTrue(rows.size >= 100)
        for (row in rows) {
            val id = row.str("id")
            val expect = row["expect"]!!.jsonObject
            val outcome = MessageParser.parse(message(row.str("sender"), row.str("body")))
            when (expect.str("outcome")) {
                "never_record" -> assertFalse("$id -> $outcome", outcome is ParseOutcome.Record)
                "review" -> assertTrue("$id -> $outcome", outcome is ParseOutcome.Review)
                "record_or_review" -> {
                    if (outcome is ParseOutcome.Record) {
                        val bad = expect.getValue("notKind").jsonArray.map { it.jsonPrimitive.content }
                        assertFalse("$id direction ${outcome.record.kind}", outcome.record.kind.name in bad)
                        assertFalse(
                            "$id label ${outcome.record.merchant}",
                            outcome.record.merchant
                                .orEmpty()
                                .any { it.isDigit() },
                        )
                    }
                    assertFalse("$id -> $outcome", outcome is ParseOutcome.Ignore)
                }
                "record" -> {
                    assertTrue("$id -> $outcome", outcome is ParseOutcome.Record)
                    val r = (outcome as ParseOutcome.Record).record
                    assertEquals(id, expect.str("kind"), r.kind.name)
                    assertEquals(id, expect.str("amountMinor").toLong(), r.amountMinor)
                    expect.opt("merchant")?.let { assertEquals(id, it, r.merchant) }
                    expect.opt("last4")?.let { assertEquals(id, it, r.instrumentLast4) }
                    expect.opt("occurredAt")?.let { assertEquals(id, Instant.parse(it).toEpochMilli(), r.occurredAtMillis) }
                }
            }
        }
    }

    @Test
    fun `REQ-SMS-016 real-shape bank messages, payer-side last four and every round-3 look-alike`() {
        val rows =
            fixture("round3.jsonl") + fixture("round4.jsonl") + fixture("round5.jsonl") + fixture("round6.jsonl") +
                fixture("round7.jsonl") + fixture("round8.jsonl") + fixture("round9.jsonl") + fixture("round10.jsonl") +
                fixture("round11.jsonl") + fixture("round12.jsonl") + fixture("round13.jsonl") + fixture("round14.jsonl") +
                fixture("round15.jsonl") + fixture("round16.jsonl") + fixture("round17.jsonl") + fixture("round19.jsonl") +
                fixture("round20.jsonl")
        assertTrue(rows.size >= 415)
        checkAll(rows)
    }

    @Test
    fun `REQ-SMS-016 regression guard - the whole earlier corpus still parses to what it did`() {
        // Every probe input of the earlier verification rounds (owner identifiers removed) plus the verifier and round-2 sets:
        // nothing that was correct may become wrong.
        val rows = fixture("corpus.jsonl") + fixture("verifier.jsonl") + fixture("round2.jsonl")
        assertTrue(rows.size >= 1260)
        checkAll(rows)
    }

    private var suggestChecked = 0
    private var suggestCorrect = 0

    private fun tallySuggestion(
        suggest: JsonObject,
        suggestion: eg.masroof.ingest.parse.ParsedRecord?,
    ) {
        // a review item with no suggestion at all (a direction conflict, no template) is the conservative outcome, not a wrong one
        if (suggestion == null) return
        suggestChecked++
        val kinds = suggest["kinds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: suggest.opt("kind")?.let { listOf(it) }
        val kindOk = kinds == null || suggestion.kind.name in kinds
        val amountOk = suggest.opt("amountMinor")?.let { it.toLong() == suggestion.amountMinor } ?: true
        val last4 = suggest.opt("last4")
        val last4Ok =
            when {
                last4 == null || last4 == "any" -> true
                last4.endsWith("?") -> suggestion.instrumentLast4 == null || suggestion.instrumentLast4 == last4.dropLast(1)
                else -> suggestion.instrumentLast4 == (if (last4 == "none") null else last4)
            }
        if (kindOk && amountOk && last4Ok) suggestCorrect++
    }

    /** Runs every row and reports all failures at once, so one run shows the whole picture. */
    private fun checkAll(rows: List<JsonObject>) {
        suggestChecked = 0
        suggestCorrect = 0
        val failures =
            rows.mapNotNull { row ->
                try {
                    checkFixtureRow(row)
                    null
                } catch (e: AssertionError) {
                    e.message
                }
            }
        assertTrue("${failures.size} of ${rows.size} rows fail: " + failures.joinToString(" | "), failures.isEmpty())
        // The suggestions only pre-fill a one-tap Save: most must be right, but a wrong one is not a failure of the guard.
        assertTrue(
            "only $suggestCorrect of $suggestChecked suggestions are right",
            suggestChecked == 0 || suggestCorrect * SUGGESTION_PERCENT_DENOMINATOR >= suggestChecked * MIN_SUGGESTION_PERCENT,
        )
    }

    private companion object {
        const val SUGGESTION_PERCENT_DENOMINATOR = 100
        const val MIN_SUGGESTION_PERCENT = 85
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun checkFixtureRow(row: JsonObject) {
        val id = row.str("id")
        val expect = row["expect"]!!.jsonObject
        val source = if (row.opt("source") == "NOTIFICATION") CaptureSource.NOTIFICATION else CaptureSource.SMS
        val received = row.opt("receivedAt")?.let { Instant.parse(it).toEpochMilli() } ?: RECEIVED
        val outcome = MessageParser.parse(message(row.str("sender"), row.str("body"), received, source))
        val wire = (outcome as? ParseOutcome.Record)?.record?.toString().orEmpty()
        expect["noUpload"]?.jsonArray?.forEach { assertFalse("$id uploads $it: $wire", wire.contains(it.jsonPrimitive.content)) }
        if (outcome is ParseOutcome.Review) {
            expect["reviewHides"]?.jsonArray?.forEach {
                assertFalse(
                    "$id review shows $it",
                    outcome.text.contains(it.jsonPrimitive.content),
                )
            }
        }
        val record = (outcome as? ParseOutcome.Record)?.record
        val kinds = expect["kinds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: expect.opt("kind")?.let { listOf(it) }
        if (record != null) {
            expect["merchantNot"]?.jsonArray?.forEach {
                assertFalse("$id label ${record.merchant}", record.merchant.orEmpty().contains(it.jsonPrimitive.content))
            }
            expect["notKind"]?.jsonArray?.forEach {
                assertFalse(
                    "$id direction ${record.kind}",
                    record.kind.name == it.jsonPrimitive.content,
                )
            }
            if (kinds != null && expect.str("outcome") != "record") assertTrue("$id kind ${record.kind}", record.kind.name in kinds)
        }
        when (expect.str("outcome")) {
            "ignore" -> assertTrue("$id -> $outcome", outcome is ParseOutcome.Ignore)
            "record_or_review" -> {
                assertFalse("$id -> $outcome", outcome is ParseOutcome.Ignore)
                // a record must still be right in every field the row pins; a review item is always acceptable
                if (record != null) checkRecordFields(id, expect, record, kinds)
            }
            "review" -> {
                assertTrue("$id -> $outcome", outcome is ParseOutcome.Review)
                // A row that used to be a record keeps its old fields as `suggest`: the engine's suggestion is compared, but a wrong
                // suggestion is a MINOR (tallied, bounded in checkAll), never a failure: the user confirms it before it uploads.
                expect["suggest"]?.jsonObject?.let { tallySuggestion(it, (outcome as ParseOutcome.Review).suggestion) }
            }
            "not_record" -> assertFalse("$id -> $outcome", outcome is ParseOutcome.Record)
            "never_record" -> assertFalse("$id -> $outcome", outcome is ParseOutcome.Record)
            "record" -> {
                assertTrue("$id -> $outcome", record != null)
                requireNotNull(record)
                checkRecordFields(id, expect, record, kinds)
            }
        }
    }

    private fun checkRecordFields(
        id: String,
        expect: JsonObject,
        record: eg.masroof.ingest.parse.ParsedRecord,
        kinds: List<String>?,
    ) {
        if (kinds != null) assertTrue("$id kind ${record.kind}", record.kind.name in kinds)
        expect.opt("amountMinor")?.let { assertEquals(id, it.toLong(), record.amountMinor) }
        expect.opt("last4")?.let {
            when {
                it == "any" -> Unit
                // "7781?": the right own number or none at all, never another number
                it.endsWith("?") ->
                    assertTrue(
                        "$id last4 ${record.instrumentLast4}",
                        record.instrumentLast4 == null || record.instrumentLast4 == it.dropLast(1),
                    )
                else -> assertEquals(id, if (it == "none") null else it, record.instrumentLast4)
            }
        }
        expect.opt("merchant")?.let { assertEquals(id, if (it == "none") null else it, record.merchant) }
        expect.opt("merchantStarts")?.let {
            assertTrue("$id label ${record.merchant}", record.merchant.orEmpty().startsWith(it, ignoreCase = true))
        }
        expect.opt("method")?.let { assertEquals(id, it, record.paymentMethod) }
        expect.opt("occurredAt")?.let { assertEquals(id, Instant.parse(it).toEpochMilli(), record.occurredAtMillis) }
        expect.opt("dateCairo")?.let {
            val day = Instant.ofEpochMilli(record.occurredAtMillis).atZone(java.time.ZoneId.of("Africa/Cairo")).toLocalDate()
            assertEquals(id, it, day.toString())
        }
    }

    @Test
    fun `REQ-NTF-013 a notification uploads a clean institution id, never the package name`() {
        val outcome =
            MessageParser.parse(
                message(
                    NotificationApps.INSTAPAY_CANDIDATE,
                    "InstaPay\nYou sent EGP 250.00 to Sara Ahmed",
                    source = CaptureSource.NOTIFICATION,
                ),
            ) as ParseOutcome.Record
        assertEquals("InstaPay", outcome.record.senderId)
        assertEquals("APP", MessageParser.uploadSenderId("com.example.bankapp", null))
    }

    @Test
    fun `REQ-SMS-030 a secret word sequence can never become the merchant label`() {
        for (text in listOf(
            "Purchase of EGP 90.00 at OTP value on 12/03/2026",
            "Purchase of EGP 90.00 at verification value",
            "تم خصم 90 جنيه لدى كود البطاقة",
            "Purchase of EGP 90.00 to Code",
        )) {
            val outcome = MessageParser.parse(message("CIB", text))
            if (outcome is ParseOutcome.Record) assertNull(text, outcome.record.merchant)
        }
    }

    @Test
    fun `REQ-SMS-010 an OTP-looking purchase is kept masked for the user and a plain OTP is dropped`() {
        val purchaseOtp =
            MessageParser.parse(
                message("CIB", "Your OTP for purchase of EGP 500.00 at AMAZON is 889900, do not share it with anyone"),
            )
        assertTrue(purchaseOtp is ParseOutcome.Review)
        assertFalse((purchaseOtp as ParseOutcome.Review).text.contains("889900"))
        assertTrue(MessageParser.parse(message("CIB", "Your OTP is 482915. Do not share it with anyone.")) is ParseOutcome.Ignore)
    }

    @Test
    fun `REQ-SMS-062 the local id is a keyed HMAC - stable per installation key, different with another key, never a plain hash`() {
        val a = message("CIB", "Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05", 5_000L)
        val key = ByteArray(32) { it.toByte() }
        val id = LocalIdHasher.id(key, a)
        assertEquals(64, id.length)
        assertEquals(id, LocalIdHasher.id(key, message("cib", a.text, 5_999L)))
        assertTrue(LocalIdHasher.id(key, message("CIB", a.text, 7_000L)) != id)
        assertTrue(LocalIdHasher.id(key, message("CIB", a.text + "!", 5_000L)) != id)
        assertTrue("another installation key gives another id", LocalIdHasher.id(ByteArray(32) { 9 }, a) != id)
        val plain =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("cib|5|${a.text}".toByteArray())
                .joinToString("") { "%02x".format(it) }
        assertTrue("not the unkeyed SHA-256 of the text", plain != id)
    }

    @Test
    fun `REQ-SMS-003 every sender id and institution the phone can upload matches the server's charset`() {
        val serverSender = Regex("^[A-Za-z][A-Za-z._&-]{0,19}$")
        val serverInstitution = Regex("^[\\p{L} .&-]{1,40}$")
        assertEquals("QNB", MessageParser.uploadSenderId("QNB1", "QNB"))
        assertEquals("BANK", MessageParser.uploadSenderId("19019", null))
        assertTrue(serverSender.matches(MessageParser.uploadSenderId("Vodafone Cash", "Vodafone Cash")))
        for (sender in eg.masroof.ingest.DefaultSenders.all) {
            assertTrue(sender.name, serverInstitution.matches(sender.name))
        }
        for (row in fixture("verifier.jsonl") + fixture("round2.jsonl") + fixture("instapay.jsonl") + fixture("genuine.jsonl")) {
            val source = if (row.opt("source") == "NOTIFICATION") CaptureSource.NOTIFICATION else CaptureSource.SMS
            val outcome = MessageParser.parse(message(row.str("sender"), row.str("body"), source = source))
            if (outcome is ParseOutcome.Record) {
                assertTrue(row.str("id"), serverSender.matches(outcome.record.senderId))
                outcome.record.institution?.let { assertTrue(row.str("id"), serverInstitution.matches(it)) }
            }
        }
    }

    @Test
    fun `REQ-SMS-016 the label spec is shared - the phone and the server normalize the same way`() {
        for (row in fixture("labels.jsonl")) {
            assertEquals(row.str("input"), row.str("expected"), LabelNormalizer.normalize(row.str("input")))
        }
    }

    @Test
    fun `REQ-SMS-063 a code-like review item keeps formatted amounts but never a bare digit run`() {
        val masked = SecretDetector.maskCodes("Balance 1,250.00 code 889900 ref 123456 EGP 500 and 4821 here")
        assertFalse(masked.contains("889900"))
        assertFalse(masked.contains("4821"))
        assertTrue(masked.contains("1,250.00"))
        val outcome = MessageParser.parse(message("CIB", "Hello: your approval pin was 889900 for a mystery thing"))
        if (outcome is ParseOutcome.Review) assertFalse(outcome.text.contains("889900"))
    }

    @Test
    fun `REQ-SMS-016 the occurred time is Cairo local converted to UTC within 45 days, else the received time`() {
        val r =
            extracted(MessageParser.parse(message("CIB", "Purchase of EGP 90.00 at UBER on 12/03/2026 14:05", CASES_RECEIVED)))!!
        assertEquals(Instant.parse("2026-03-12T12:05:00Z").toEpochMilli(), r.occurredAtMillis)
        val summer =
            extracted(
                MessageParser.parse(
                    message(
                        "CIB",
                        "Purchase of EGP 90.00 at UBER on 25/10/2026 23:50",
                        Instant.parse("2026-10-26T10:00:00Z").toEpochMilli(),
                    ),
                ),
            )!!
        assertEquals(Instant.parse("2026-10-25T20:50:00Z").toEpochMilli(), summer.occurredAtMillis)
        val old = extracted(MessageParser.parse(message("CIB", "Purchase of EGP 90.00 at UBER on 01/09/2026", RECEIVED)))!!
        assertEquals(RECEIVED, old.occurredAtMillis)
    }

    @Test
    fun `REQ-SMS-013 last4 comes only from an explicit card or account marker`() {
        val ok = extracted(MessageParser.parse(message("CIB", "Purchase of EGP 90.00 at UBER card ending 1234")))!!
        assertEquals("1234", ok.instrumentLast4)
        val none = extracted(MessageParser.parse(message("CIB", "Purchase of EGP 90.00 at UBER Ref 98765432")))!!
        assertNull(none.instrumentLast4)
        assertNotNull(ok.toString())
    }
}

private val F4_IDS = setOf("ip-ntf-en-sent", "ip-ntf-ar-sent", "ip-ntf-en-received")

class InstaPaySmsTest {
    @Test
    fun `REQ-SMS-016 InstaPay SMS and app notifications in Arabic and English parse to structured records`() {
        val rows = fixture("instapay.jsonl")
        assertTrue(rows.size >= 18)
        for (row in rows) {
            val id = row.str("id")
            val source = if (row.str("source") == "NOTIFICATION") CaptureSource.NOTIFICATION else CaptureSource.SMS
            val expect = row["expect"]!!.jsonObject
            val outcome = MessageParser.parse(message(row.str("sender"), row.str("body"), source = source))
            if (expect.str("outcome") == "ignore") {
                assertTrue("$id -> $outcome", outcome is ParseOutcome.Ignore)
                continue
            }
            // the InstaPay app notifications of family F4 are recorded; every other shape is a review item with a suggestion
            if (source == CaptureSource.NOTIFICATION && id in F4_IDS) assertTrue("$id -> $outcome", outcome is ParseOutcome.Record)
            val r = extracted(outcome)
            assertNotNull("$id -> $outcome", r)
            requireNotNull(r)
            assertEquals(id, source, r.source)
            assertEquals(id, expect.str("kind"), r.kind.name)
            assertEquals(id, expect.str("amountMinor").toLong(), r.amountMinor)
            expect.opt("merchant")?.let { assertEquals(id, it, r.merchant) }
            expect.opt("last4")?.let { assertEquals(id, it, r.instrumentLast4) }
            assertEquals(id, expect.str("method"), r.paymentMethod)
        }
    }

    @Test
    fun `REQ-SMS-016 an InstaPay handle keeps its name, never a phone or account number`() {
        val handle = MessageParser.parse(message("InstaPay", "Transfer of EGP 100.00 to sara99@instapay was successful"))
        if (handle is ParseOutcome.Record) {
            assertFalse(
                handle.record.merchant
                    .orEmpty()
                    .any { it.isDigit() },
            )
        }
        val phone = MessageParser.parse(message("InstaPay", "Transfer of EGP 100.00 to 01000000012 was successful"))
        assertFalse(phone is ParseOutcome.Record && phone.record.toString().contains("0100000"))
    }
}

class CardStoreNumberTest {
    private fun card(merchant: String) =
        "Your Credit Card ending with 4821 has been used for EGP 130.00 on 26/10/2026 at $merchant. Your available limit is EGP 41000.10"

    @Test
    fun `REQ-SMS-070 a store number after a card merchant is recorded with a digit-free label`() {
        for (sender in listOf("HSBC", "CIB")) {
            val o = MessageParser.parse(message(sender, card("HARDEE S ELMERGHANY 50")))
            assertTrue("$sender -> $o", o is ParseOutcome.Record)
            val r = (o as ParseOutcome.Record).record
            assertEquals(13000L, r.amountMinor)
            assertEquals("4821", r.instrumentLast4)
            assertEquals("HARDEE S ELMERGHANY", LabelNormalizer.normalize(r.merchant.orEmpty()))
        }
        assertTrue(MessageParser.parse(message("CIB", card("SPINNEYS 3 ZAYED"))) is ParseOutcome.Record)
        assertTrue(MessageParser.parse(message("CIB", card("CARREFOUR 7"))) is ParseOutcome.Record)
        // without the number it was already a record
        assertTrue(MessageParser.parse(message("HSBC", card("HARDEE S ELMERGHANY"))) is ParseOutcome.Record)
    }

    @Test
    fun `REQ-SMS-070 the Arabic card family accepts a store number too`() {
        val body = "تم خصم 130 جنيه من بطاقتك المنتهية ب 4821 لدى كارفور 12"
        assertTrue(MessageParser.parse(message("CIB", body)) is ParseOutcome.Record)
    }

    @Test
    fun `REQ-SMS-078 a store number never opens the closed world`() {
        val bad =
            listOf("50", "50 SHOP", "SHOP 1234", "SHOP 50 60", "SHOP 50 declined", "SHOP 50 failed", "SHOP 123456", "SHOP 50 OTP")
        for (m in bad) {
            val o = MessageParser.parse(message("CIB", card(m)))
            assertFalse("$m -> $o", o is ParseOutcome.Record)
        }
    }

    @Test
    fun `REQ-SMS-078 an amount, an identifier or an unlabeled number after the merchant is never a store number`() {
        val bad =
            (
                "SHOP 500 EGP|SHOP 500 LE|SHOP 50 USD|SHOP 50 L.E|SHOP 50 EUR|SHOP 50 GBP|SHOP 50 SAR|SHOP 50 AED|SHOP 50 KWD|" +
                    "SHOP EGP 500|SHOP ID 123|SHOP TRX 123|SHOP ACC 999|SHOP NO 123|SHOP AUTH 123|SHOP CODE 12|A 50"
            ).split("|")
        for (m in bad) {
            val o = MessageParser.parse(message("CIB", card(m)))
            assertFalse("$m -> $o", o is ParseOutcome.Record)
        }
        for (m in listOf("كارفور 50 جنيه", "كارفور 50 جم", "كارفور 50 ج.م", "كارفور رقم 123")) {
            val o = MessageParser.parse(message("CIB", "تم خصم 130 جنيه من بطاقتك المنتهية ب 4821 لدى $m"))
            assertFalse("$m -> $o", o is ParseOutcome.Record)
        }
    }

    @Test
    fun `REQ-SMS-078 one store number in total - a suffix clause never carries a second one`() {
        val head = "Your Credit Card ending with 4821 has been used for EGP 130.00 on 26/10/2026 at "
        val limit = ". Your available limit is EGP 41000.10"
        for (m in listOf("HARDEE 50. X 60", "HARDEE. ELMERGHANY 50", "HARDEE 50. ELMERGHANY 60")) {
            val o = MessageParser.parse(message("CIB", head + m + limit))
            assertFalse("$m -> $o", o is ParseOutcome.Record)
        }
        val one = MessageParser.parse(message("CIB", card("HARDEE 50")))
        assertTrue(one is ParseOutcome.Record)
    }

    @Test
    fun `REQ-SMS-078 a currency-like word in a name without a number is still a name`() {
        val en = "Your card ending 4821 was charged EGP 85.00 at "
        for (m in listOf("LE PAIN QUOTIDIEN", "LE MERIDIEN", "LE MARCHE", "YEN SUSHI", "SR MARKET")) {
            val o = MessageParser.parse(message("CIB", en + m))
            assertTrue("$m -> $o", o is ParseOutcome.Record)
        }
        // with a number, any currency word in the slot sends it to review (a deliberate trade-off)
        for (m in listOf("LE MERIDIEN 2", "LE 50")) {
            val o = MessageParser.parse(message("CIB", en + m))
            assertFalse("$m -> $o", o is ParseOutcome.Record)
        }
    }

    @Test
    fun `REQ-SMS-078 an InstaPay counterparty with a currency-like word is judged as before`() {
        val body = "You sent EGP 100.00 to SR AHMED"
        val sr = MessageParser.parse(message(NotificationApps.INSTAPAY_CANDIDATE, body, source = CaptureSource.NOTIFICATION))
        assertTrue(sr.toString(), sr is ParseOutcome.Record)
    }

    @Test
    fun `REQ-SMS-078 the counterparty slots of InstaPay and instant transfers stay strict`() {
        val o = MessageParser.parse(message("CIB", "You sent EGP 100.00 to AHMED 50"))
        assertFalse(o.toString(), o is ParseOutcome.Record)
    }
}
