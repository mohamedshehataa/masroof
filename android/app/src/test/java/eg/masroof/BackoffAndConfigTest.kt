package eg.masroof

import eg.masroof.work.OutboxBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/** Plain JVM tests (no Robolectric). */
class BackoffAndConfigTest {
    private val low = Random(1)

    @Test
    fun `REQ-AND-015 backoff grows exponentially from 30 seconds within the jitter band`() {
        for (attempt in 0..5) {
            val nominal = OutboxBackoff.BASE_SECONDS shl attempt
            repeat(50) {
                val d = OutboxBackoff.delaySeconds(attempt, random = Random(it))
                assertTrue("attempt $attempt: $d vs $nominal", d >= nominal * 80 / 100 && d <= nominal * 120 / 100)
            }
        }
    }

    @Test
    fun `REQ-AND-015 backoff never exceeds the 6 hour ceiling however long the outage`() {
        assertEquals(6L * 3600, OutboxBackoff.CEILING_SECONDS)
        for (attempt in listOf(10, 20, 50, 1_000, Int.MAX_VALUE)) {
            repeat(50) { assertTrue(OutboxBackoff.delaySeconds(attempt, random = Random(it)) <= OutboxBackoff.CEILING_SECONDS) }
        }
    }

    @Test
    fun `REQ-AND-015 backoff is jittered`() {
        val delays = (0 until 40).map { OutboxBackoff.delaySeconds(8, random = Random(it)) }.toSet()
        assertTrue(delays.size > 5)
    }

    @Test
    fun `REQ-AND-015 Retry-After is a lower bound and is itself clamped to the ceiling`() {
        assertTrue(OutboxBackoff.delaySeconds(0, retryAfterSeconds = 900, random = low) >= 900)
        assertEquals(OutboxBackoff.CEILING_SECONDS, OutboxBackoff.delaySeconds(0, retryAfterSeconds = 10L * 24 * 3600, random = low))
        assertTrue(OutboxBackoff.delaySeconds(12, retryAfterSeconds = 5, random = low) > 5)
    }

    @Test
    fun `REQ-AND-015 negative or absent Retry-After is ignored`() {
        val d = OutboxBackoff.delaySeconds(0, retryAfterSeconds = -5, random = Random(3))
        assertTrue(d in 24..36)
    }

    @Test
    fun `REQ-SEC-050 the release network security config disallows cleartext`() {
        // Gradle runs unit tests with the module directory as working dir; src/main is the release config
        // (the debug variant overrides it from src/debug, which is not covered by this test).
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue("missing ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val base = doc.getElementsByTagName("base-config")
        assertEquals(1, base.length)
        assertEquals(
            "false",
            base
                .item(0)
                .attributes
                .getNamedItem("cleartextTrafficPermitted")
                .nodeValue,
        )
        val domains = doc.getElementsByTagName("domain-config")
        for (i in 0 until domains.length) {
            val permitted =
                domains
                    .item(i)
                    .attributes
                    .getNamedItem("cleartextTrafficPermitted")
                    ?.nodeValue
            assertFalse("a domain-config permits cleartext", permitted == "true")
        }
    }
}
