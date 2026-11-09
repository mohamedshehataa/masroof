package eg.masroof.backup

import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog
import java.io.File

/** One class per scenario of design section 6.2, so Gradle can run them in parallel forks (the tests are inherited from the base). */
class RestoreMatrixWipedPhoneTest : RestoreMatrixBase() {
    override val scenarioName = "a wiped phone"

    @Test
    fun `REQ-OPS-179 a Keystore failure at the key step is INCOMPLETE and a damaged staged file is PARTIAL, each with a way out`() {
        for (damage in listOf("settings.json", "local-id.key", "receipts-new/r1.jpg", "receipts-new")) {
            val (p, file) = scenario("a wiped phone")
            try {
                val hooks =
                    object : RestoreHooks {
                        override fun afterCommit() {
                            val dir = p.stage.listFiles()!!.first()
                            val f = File(dir, damage)
                            if (f.isDirectory) {
                                f.deleteRecursively()
                            } else if (damage.endsWith("jpg")) {
                                f.writeBytes(byteArrayOf(1))
                            } else {
                                f.delete()
                            }
                        }
                    }
                val (r, s) = stage(p, file, hooks)
                val outcome = runCatching { runBlocking { r.apply(s) } }.exceptionOrNull() as? RestoreException
                assertEquals("$damage is evidence that cannot change by retrying", RestoreFailure.PARTIAL, outcome?.failure)
                assertEquals(RestoreOutcome.PARTIAL, runBlocking { p.restorer().recover(atStart = false) })
                userLoop("damaged $damage", p, file, target2)
            } finally {
                p.close()
            }
        }
        val (p, file) = scenario("a wiped phone")
        try {
            p.keyFailures = 1
            val (r, s) = stage(p, file)
            val failure = runCatching { runBlocking { r.apply(s) } }.exceptionOrNull() as? RestoreException
            assertEquals(RestoreFailure.INCOMPLETE, failure?.failure)
            userLoop("keystore", p, file, target2)
        } finally {
            p.close()
        }
    }

    @Test
    fun `REQ-SEC-068 the restore logs nothing but an outcome`() {
        ShadowLog.clear()
        run("a wiped phone", Fault(Fault.Kind.NONE, 0))
        logsAreClean("clean run")
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("restore-stage") })
    }
}

class RestoreMatrixDataPhoneTest : RestoreMatrixBase() {
    override val scenarioName = "b phone with data and receipts"
}

class RestoreMatrixOverIncompleteTest : RestoreMatrixBase() {
    override val scenarioName = "c over INCOMPLETE G1"
}

class RestoreMatrixOverPartialTest : RestoreMatrixBase() {
    override val scenarioName = "d over PARTIAL G1"
}

class RestoreMatrixOverCorruptTest : RestoreMatrixBase() {
    override val scenarioName = "e over a Corrupt journal"
}

class RestoreMatrixNoReceiptsTest : RestoreMatrixBase() {
    override val scenarioName = "f zero receipts, no receipts directory"
}
