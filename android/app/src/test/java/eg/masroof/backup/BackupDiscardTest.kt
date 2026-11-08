package eg.masroof.backup

import android.net.Uri
import eg.masroof.data.backup.BackupStage
import eg.masroof.data.backup.DocumentDiscarder
import eg.masroof.data.backup.DocumentGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeGate(
    private val size: Long?,
    private val deletes: Boolean = true,
) : DocumentGate {
    val deleted = mutableListOf<Uri>()

    override fun size(uri: Uri): Long? = size

    override fun delete(uri: Uri): Boolean {
        deleted += uri
        return deletes
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupDiscardTest {
    @get:Rule val folder = TemporaryFolder()
    private val uri: Uri = Uri.parse("content://docs.example/document/old-backup")

    @Test
    fun `REQ-OPS-177 an existing backup the user chose to overwrite is never deleted`() {
        val gate = FakeGate(size = 58L * 1024 * 1024)
        assertFalse(runBlocking { DocumentDiscarder(gate).discard(uri) })
        assertEquals("a document with data is left alone", emptyList<Uri>(), gate.deleted)
    }

    @Test
    fun `REQ-OPS-177 a document whose size is unknown is left alone too`() {
        val gate = FakeGate(size = null)
        assertFalse(runBlocking { DocumentDiscarder(gate).discard(uri) })
        assertEquals(emptyList<Uri>(), gate.deleted)
    }

    @Test
    fun `REQ-OPS-177 an empty document the picker created is removed`() {
        val gate = FakeGate(size = 0)
        assertTrue(runBlocking { DocumentDiscarder(gate).discard(uri) })
        assertEquals(listOf(uri), gate.deleted)
    }

    @Test
    fun `REQ-OPS-177 an empty document the provider refuses to delete reports false`() {
        assertFalse(runBlocking { DocumentDiscarder(FakeGate(size = 0, deletes = false)).discard(uri) })
    }

    @Test
    fun `REQ-SEC-066 the stage folder sweep removes every leftover file`() {
        val dir = BackupStage.dir(folder.newFolder("nobackup"))
        dir.mkdirs()
        java.io.File(dir, "masroof-stage123.bin").writeBytes(byteArrayOf(1))
        java.io.File(dir, "other").writeBytes(byteArrayOf(2))
        assertEquals(0, BackupStage.sweep(dir))
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertEquals(0, BackupStage.sweep(java.io.File(dir, "missing")))
    }
}
