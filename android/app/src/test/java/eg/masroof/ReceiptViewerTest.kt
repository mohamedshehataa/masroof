package eg.masroof

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.ui.edit.PdfPage
import eg.masroof.ui.edit.ReceiptViewer
import eg.masroof.ui.edit.pageBitmapSize
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReceiptViewerTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val row = ReceiptEntity("r1", "e1", "gone.jpg", "image/jpeg", 10, "h", 1, 0)

    @Test
    fun `REQ-RCPT-018 a row whose file is missing shows the message and a working Remove instead of crashing`() {
        var removed = 0
        compose.setContent {
            MasroofTheme { ReceiptViewer(row, File("no-such-dir/gone.jpg"), onClose = {}, onRemove = { removed++ }) }
        }
        compose.onNodeWithText(context.getString(R.string.rcpt_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.rcpt_remove)).assertIsDisplayed().performClick()
        assertEquals(1, removed)
    }

    @Test
    fun `REQ-RCPT-018 the pdf page indicator reads 1 slash 2`() {
        assertEquals("1 / 2", context.getString(R.string.rcpt_page_of, "1", "2"))
    }

    @Test
    fun `REQ-RCPT-018 a very tall pdf page is drawn smaller so its bitmap stays under 16 MB`() {
        val (w, h) = pageBitmapSize(1, 50, 1440)!!
        assertTrue(w >= 1 && h >= 1)
        assertTrue(w.toLong() * h * 4 <= 16L * 1024 * 1024)
        assertEquals(1440 to 2037, pageBitmapSize(595, 842, 1440)) // a normal A4 page keeps the full width
        assertEquals(1440 to 1863, pageBitmapSize(612, 792, 1440))
    }

    @Test
    fun `REQ-RCPT-018 pages longer than 50 to 1 are not drawn`() {
        assertEquals(null, pageBitmapSize(1, 10_000, 1440))
        assertEquals(null, pageBitmapSize(57, 14_400, 1440))
        assertEquals(null, pageBitmapSize(1, 30_000_000, 1440))
        assertEquals(null, pageBitmapSize(30_000_000, 1, 1440))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
    fun `REQ-RCPT-018 a 1 by 300 page shows without throwing, drawn or as the error line`() {
        val tall = android.graphics.Bitmap.createBitmap(10, 3_000, android.graphics.Bitmap.Config.ARGB_8888)
        compose.setContent {
            MasroofTheme {
                androidx.compose.foundation.layout.Column {
                    PdfPage(Result.success(tall), 0)
                    PdfPage(Result.failure(IllegalStateException("page too long to draw")), 1)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.rcpt_failed)).assertExists()
    }
}
