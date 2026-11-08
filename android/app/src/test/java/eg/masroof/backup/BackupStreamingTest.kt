package eg.masroof.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class BackupStreamingTest {
    @Test
    fun `REQ-OPS-176 500 MB of receipts are written and decrypted in a 48 MB heap because everything streams`() {
        val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$exe")
        // The classpath goes through an argument file: on Windows the full command line would be too long.
        val classpath = System.getProperty("java.class.path").orEmpty().replace('\\', '/')
        val args = File.createTempFile("backup-heap-test", ".args")
        try {
            args.writeText("-Xmx48m\n-cp\n\"$classpath\"\neg.masroof.backup.LargeBackupMain\n500\n")
            val process = ProcessBuilder(java.path, "@${args.path}").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(300, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("timed out: $output", finished)
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("OK 524288000 stored=50"))
            // The heap really was small (the JVM reports a little under -Xmx48m).
            assertTrue(output, Regex("heap=(4[0-9])MB").containsMatchIn(output))
        } finally {
            args.delete()
        }
    }
}
