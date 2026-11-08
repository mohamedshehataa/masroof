package eg.masroof

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A generated test's `-D<name>.seed` must reach the test JVM, or the run silently uses the default seed. */
class GeneratorSeedForwardingTest {
    private val script = File("build.gradle.kts").readText()

    @Test
    fun `REQ-DEDUP-065 the duplicate generator seed is forwarded to the unit test JVM`() {
        assertTrue(script.contains("\"duplicate.seed\""))
        assertTrue(Regex("getProperty\\(\"duplicate\\.seed\"\\).*systemProperty\\(\"duplicate\\.seed\"").containsMatchIn(script))
    }

    @Test
    fun `REQ-EXP-065 the answer generator seed is still forwarded`() {
        assertTrue(Regex("getProperty\\(\"answer\\.seed\"\\).*systemProperty\\(\"answer\\.seed\"").containsMatchIn(script))
    }
}
