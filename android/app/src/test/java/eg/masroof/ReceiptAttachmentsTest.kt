package eg.masroof

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import eg.masroof.data.receipts.AttachmentInfo
import eg.masroof.data.receipts.MAX_EDGE_PX
import eg.masroof.data.receipts.MAX_UPLOAD_BYTES
import eg.masroof.data.receipts.Prepared
import eg.masroof.data.receipts.copyAtMost
import eg.masroof.data.receipts.fitWithin
import eg.masroof.data.receipts.sampleSizeFor
import eg.masroof.data.remote.HttpFailure
import eg.masroof.ui.viewmodel.AttachMessage
import eg.masroof.ui.viewmodel.AttachmentsViewModel
import eg.masroof.ui.viewmodel.SectionLoad
import eg.masroof.ui.viewmodel.ViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val remote = FakeReceiptRemote()
    private lateinit var files: FakeReceiptFiles
    private val uri: Uri = Uri.parse("content://picker/1")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        files = FakeReceiptFiles(tmp.newFolder())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AttachmentsViewModel(remote, files, SavedStateHandle(mapOf("id" to "e1")))

    private fun info(id: String = "a1") = AttachmentInfo(id, "image/jpeg", 100)

    @Test
    fun `REQ-AND-130 attachments load from the server`() =
        runTest {
            remote.attachments = mutableListOf(info("a1"), info("a2"))
            val vm = vm()
            assertEquals(SectionLoad.LOADED, vm.state.value.load)
            assertEquals(
                listOf("a1", "a2"),
                vm.state.value.list
                    .map { it.id },
            )
        }

    @Test
    fun `REQ-AND-130 a failed load shows the error and retry loads again`() =
        runTest {
            remote.listFailure = IOException("offline")
            val vm = vm()
            assertEquals(SectionLoad.FAILED, vm.state.value.load)
            assertFalse(vm.state.value.canAttach)
            remote.listFailure = null
            vm.load()
            assertEquals(SectionLoad.LOADED, vm.state.value.load)
            assertEquals(2, remote.listCalls)
        }

    @Test
    fun `REQ-AND-130 an http failure on load also shows the error`() =
        runTest {
            remote.listFailure = HttpFailure(503)
            assertEquals(SectionLoad.FAILED, vm().state.value.load)
        }

    @Test
    fun `REQ-AND-131 a picked photo is uploaded as jpeg and appears, temp file deleted`() =
        runTest {
            val vm = vm()
            vm.onImagePicked(uri)
            assertEquals(listOf("e1" to "image/jpeg"), remote.uploads)
            assertEquals(1, vm.state.value.list.size)
            assertFalse(vm.state.value.uploading)
            assertFalse(File(files.dir, "img.jpg").exists())
        }

    @Test
    fun `REQ-AND-131 cancelling the picker or the camera uploads nothing`() =
        runTest {
            val vm = vm()
            vm.onImagePicked(null)
            vm.cameraTarget()
            vm.onCameraResult(false)
            assertTrue(remote.uploads.isEmpty())
            assertTrue(
                vm.state.value.list
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-AND-131 a camera photo is uploaded`() =
        runTest {
            val vm = vm()
            vm.cameraTarget()
            vm.onCameraResult(true)
            assertEquals(1, remote.uploads.size)
        }

    @Test
    fun `REQ-AND-131 maps 413 to too large`() = failure(413, AttachMessage.TOO_LARGE)

    @Test
    fun `REQ-AND-131 maps 415 to only photos and pdfs`() = failure(415, AttachMessage.UNSUPPORTED)

    @Test
    fun `REQ-AND-131 maps 409 to at most five`() = failure(409, AttachMessage.TOO_MANY)

    @Test
    fun `REQ-AND-131 maps 401 to sign in and other codes to a generic failure`() {
        failure(401, AttachMessage.SIGN_IN)
        failure(500, AttachMessage.FAILED)
    }

    private fun failure(
        code: Int,
        expected: AttachMessage,
    ) = runTest {
        remote.uploadFailure = HttpFailure(code)
        val fresh = vm()
        fresh.onImagePicked(uri)
        assertEquals(expected, fresh.state.value.message)
        assertTrue(
            fresh.state.value.list
                .isEmpty(),
        )
        assertFalse(fresh.state.value.uploading)
    }

    @Test
    fun `REQ-AND-131 offline upload says so and adds nothing`() =
        runTest {
            remote.uploadFailure = IOException("down")
            val vm = vm()
            vm.onImagePicked(uri)
            assertEquals(AttachMessage.OFFLINE, vm.state.value.message)
            assertTrue(
                vm.state.value.list
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-AND-131 an unreadable image is refused before any upload`() =
        runTest {
            files.image = Prepared.Unreadable
            val vm = vm()
            vm.onImagePicked(uri)
            assertEquals(AttachMessage.UNREADABLE, vm.state.value.message)
            assertTrue(remote.uploads.isEmpty())
        }

    @Test
    fun `REQ-AND-131 a sixth attachment is refused on the phone`() =
        runTest {
            remote.attachments = MutableList(5) { info("a$it") }
            val vm = vm()
            assertFalse(vm.state.value.canAttach)
            vm.onImagePicked(uri)
            assertEquals(AttachMessage.TOO_MANY, vm.state.value.message)
            assertTrue(remote.uploads.isEmpty())
        }

    @Test
    fun `REQ-AND-132 a pdf within the limit is uploaded as is`() =
        runTest {
            val vm = vm()
            vm.onPdfPicked(uri)
            assertEquals(listOf("e1" to "application/pdf"), remote.uploads)
        }

    @Test
    fun `REQ-AND-132 a pdf over eight MiB is refused before any upload`() =
        runTest {
            files.pdf = Prepared.TooLarge
            val vm = vm()
            vm.onPdfPicked(uri)
            assertEquals(AttachMessage.TOO_LARGE, vm.state.value.message)
            assertTrue(remote.uploads.isEmpty())
        }

    @Test
    fun `REQ-AND-133 viewing downloads to a cache file that is deleted on close`() =
        runTest {
            val vm = vm()
            vm.view(info())
            val ready = vm.state.value.viewer as ViewerState.Ready
            assertTrue(ready.file.exists())
            assertFalse(ready.isPdf)
            vm.closeViewer()
            assertFalse(ready.file.exists())
            assertNull(vm.state.value.viewer)
        }

    @Test
    fun `REQ-AND-133 a pdf is viewed as a pdf and a failed download shows the error`() =
        runTest {
            val vm = vm()
            vm.view(AttachmentInfo("p1", "application/pdf", 10))
            assertTrue((vm.state.value.viewer as ViewerState.Ready).isPdf)
            vm.closeViewer()
            remote.downloadFailure = HttpFailure(404)
            vm.view(info())
            assertEquals(ViewerState.Failed, vm.state.value.viewer)
        }

    @Test
    fun `REQ-AND-134 remove asks first, cancel sends nothing`() =
        runTest {
            remote.attachments = mutableListOf(info("a1"))
            val vm = vm()
            vm.askRemove("a1")
            assertEquals("a1", vm.state.value.confirmRemoveId)
            vm.dismissRemove()
            assertTrue(remote.deletes.isEmpty())
            assertEquals(1, vm.state.value.list.size)
        }

    @Test
    fun `REQ-AND-134 confirming sends one delete and the row disappears`() =
        runTest {
            remote.attachments = mutableListOf(info("a1"), info("a2"))
            val vm = vm()
            vm.askRemove("a1")
            vm.confirmRemove()
            vm.confirmRemove() // a second tap finds no pending confirmation
            assertEquals(listOf("a1"), remote.deletes)
            assertEquals(
                listOf("a2"),
                vm.state.value.list
                    .map { it.id },
            )
            assertNull(vm.state.value.removingId)
        }

    @Test
    fun `REQ-AND-134 a 404 counts as removed`() =
        runTest {
            remote.attachments = mutableListOf(info("a1"))
            remote.deleteFailure = HttpFailure(404)
            val vm = vm()
            vm.askRemove("a1")
            vm.confirmRemove()
            assertTrue(
                vm.state.value.list
                    .isEmpty(),
            )
            assertNull(vm.state.value.message)
        }

    @Test
    fun `REQ-AND-134 a failed delete keeps the row and says why`() =
        runTest {
            remote.attachments = mutableListOf(info("a1"))
            remote.deleteFailure = IOException("down")
            val vm = vm()
            vm.askRemove("a1")
            vm.confirmRemove()
            assertEquals(1, vm.state.value.list.size)
            assertEquals(AttachMessage.OFFLINE, vm.state.value.message)
        }
}

class ReceiptImageMathTest {
    @Test
    fun `REQ-AND-131 sample size keeps the long edge at or above 2048`() {
        assertEquals(1, sampleSizeFor(1000, 800))
        assertEquals(1, sampleSizeFor(3000, 2000))
        assertEquals(2, sampleSizeFor(4096, 3072))
        assertEquals(4, sampleSizeFor(8192, 6144))
        assertEquals(8, sampleSizeFor(16_384, 12_288))
        listOf(4000 to 3000, 12_000 to 9_000, 5_000 to 100).forEach { (w, h) ->
            assertTrue(maxOf(w, h) / sampleSizeFor(w, h) >= MAX_EDGE_PX)
        }
    }

    @Test
    fun `REQ-AND-131 fit keeps the aspect ratio and the long edge at most 2048`() {
        assertEquals(1000 to 800, fitWithin(1000, 800))
        assertEquals(2048 to 1536, fitWithin(4096, 3072))
        assertEquals(1536 to 2048, fitWithin(3072, 4096))
        assertEquals(2048 to 1, fitWithin(10_000, 1))
        assertEquals(2048 to 2048, fitWithin(2048, 2048))
    }

    @Test
    fun `REQ-AND-132 the pdf size guard stops past eight MiB and passes exactly eight`() {
        assertEquals(8_388_608L, MAX_UPLOAD_BYTES)
        val exact = ByteArray(MAX_UPLOAD_BYTES.toInt())
        val sinkOk = ByteArrayOutputStream()
        assertFalse(copyAtMost(ByteArrayInputStream(exact), sinkOk, MAX_UPLOAD_BYTES))
        assertEquals(exact.size, sinkOk.size())
        val over = ByteArray(MAX_UPLOAD_BYTES.toInt() + 1)
        assertTrue(copyAtMost(ByteArrayInputStream(over), ByteArrayOutputStream(), MAX_UPLOAD_BYTES))
        assertFalse(copyAtMost(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), MAX_UPLOAD_BYTES))
    }
}

class ReceiptManifestTest {
    private val manifest: String = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `REQ-AND-131 the manifest declares no camera or storage permission`() {
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        listOf("CAMERA", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "MANAGE_EXTERNAL_STORAGE").forEach { bad ->
            assertFalse("$bad must not be declared", permissions.any { it.endsWith(".$bad") })
        }
        assertFalse(manifest.contains("android.hardware.camera"))
    }

    @Test
    fun `REQ-AND-131 the file provider is private and limited to the receipts cache folder`() {
        val provider = Regex("""<provider[^>]*FileProvider[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        assertTrue(provider.contains("""android:exported="false""""))
        assertTrue(provider.contains("""android:grantUriPermissions="true""""))
        val paths = File("src/main/res/xml/receipt_paths.xml").readText()
        assertTrue(paths.contains("""path="receipts/""""))
        assertEquals(1, Regex("<cache-path").findAll(paths).count())
        assertFalse(paths.contains("<root-path") || paths.contains("<files-path") || paths.contains("<external"))
    }
}
