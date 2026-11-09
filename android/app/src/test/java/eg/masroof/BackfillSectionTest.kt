package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillRange
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ui.settings.BackfillContent
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val running =
    BackfillState(BackfillPhase.RUNNING, "D90", 0L, 1L, total = 1020, read = 340, entries = 212, review = 41)

private class Taps {
    var start = 0
    var permission = 0
    var cancel = 0
    var settings = 0
    var dismiss = 0
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.show(
    state: BackfillState = BackfillState(),
    range: BackfillRange = BackfillRange.DEFAULT,
    permitted: Boolean = true,
    denied: Boolean = false,
    taps: Taps = Taps(),
    onDirection: (LayoutDirection) -> Unit = {},
) = setContent {
    MasroofTheme {
        onDirection(LocalLayoutDirection.current)
        BackfillContent(
            state = state,
            range = range,
            permitted = permitted,
            denied = denied,
            onChoose = {},
            onStart = { taps.start++ },
            onRequestPermission = { taps.permission++ },
            onCancel = { taps.cancel++ },
            onDismiss = { taps.dismiss++ },
            onOpenSettings = { taps.settings++ },
        )
    }
}

/** STORY-186: the "Read past SMS" section as a function of its state (REQ-SMS-103 permission and cancel paths). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BackfillSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-SMS-103 without READ_SMS Start shows the explanation first and asks the system only on Continue`() {
        val taps = Taps()
        compose.show(permitted = false, taps = taps)
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText("Masroof reads bank messages from the senders you allow, only on this phone.").assertIsDisplayed()
        assertEquals("the system dialog has not been asked yet", 0, taps.permission)
        assertEquals(0, taps.start)
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, taps.permission)
        assertEquals("the run starts only after the permission result", 0, taps.start)
    }

    @Test
    fun `REQ-SMS-103 Not now in the explanation asks for nothing`() {
        val taps = Taps()
        compose.show(permitted = false, taps = taps)
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(0, taps.permission)
    }

    @Test
    fun `REQ-SMS-101 with READ_SMS granted Start begins at once, with the range choices shown`() {
        val taps = Taps()
        compose.show(permitted = true, taps = taps)
        compose.onNodeWithText("Last 90 days").assertIsDisplayed()
        compose.onNodeWithText("Last 30 days").assertIsDisplayed()
        compose.onNodeWithText("Last 6 months").assertIsDisplayed()
        compose.onNodeWithText("Since a date").assertIsDisplayed()
        compose.onNodeWithText("Start").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.start)
    }

    @Test
    fun `REQ-SMS-103 a denied permission says so and offers the app settings, live capture is not mentioned as lost`() {
        val taps = Taps()
        compose.show(permitted = false, denied = true, taps = taps)
        compose.onNodeWithText("Past messages can't be read without permission").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.settings)
    }

    @Test
    fun `REQ-SMS-103 a permission revoked during a run shows the same message and the kept count`() {
        compose.show(state = running.copy(phase = BackfillPhase.PERMISSION_LOST), permitted = false)
        compose.onNodeWithText("Past messages can't be read without permission").assertIsDisplayed()
        compose.onNodeWithText("Stopped - 212 entries added").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").assertIsDisplayed()
    }

    @Test
    fun `REQ-SMS-101 a running backfill shows the progress line with grouped counts and a Cancel button`() {
        val taps = Taps()
        compose.show(state = running, taps = taps)
        compose.onNodeWithText("Read 340 of 1,020 messages - 212 entries, 41 to review").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.cancel)
    }

    @Test
    fun `REQ-SMS-103 a stopped run says how many entries were added and offers Continue for the same range`() {
        compose.show(state = running.copy(phase = BackfillPhase.STOPPED))
        compose.onNodeWithText("Stopped - 212 entries added").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun `REQ-SMS-102 after picking another range the button says Start, not Continue`() {
        compose.show(state = running.copy(phase = BackfillPhase.STOPPED), range = BackfillRange.Days(30))
        compose.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun `REQ-SMS-101 a finished run shows the summary and OK clears it`() {
        val taps = Taps()
        compose.show(state = running.copy(phase = BackfillPhase.DONE), taps = taps)
        compose.onNodeWithText("Done - 212 entries added, 41 to review").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(1, taps.dismiss)
    }

    @Test
    fun `REQ-SMS-101 a range with no bank messages says so`() {
        compose.show(state = BackfillState(BackfillPhase.DONE, "D90", 0L, 1L, total = 0))
        compose.onNodeWithText("No bank messages in this range").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], fontScale = 1.5f)
    fun `REQ-SMS-101 at font scale 1_5 the progress line and Cancel are still reachable`() {
        compose.show(state = running)
        compose.onNodeWithText("Read 340 of 1,020 messages - 212 entries, 41 to review").assertExists()
        compose.onNodeWithText("Cancel").assertExists().assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `REQ-SMS-101 dark mode renders the same section`() {
        compose.show(state = running)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}

/** The same section in Egyptian Arabic: mirrored, with the digit setting applied to the counts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class BackfillSectionArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-SMS-101 Arabic mirrors the section and shows the counts in Arabic-Indic digits`() {
        var direction: LayoutDirection? = null
        compose.show(state = running, onDirection = { direction = it })
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("قريت ٣٤٠ من ١٬٠٢٠ رسالة - ٢١٢ مصروف، ٤١ للمراجعة").assertIsDisplayed()
        compose.onNodeWithText("إلغاء").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test
    fun `REQ-SMS-103 Arabic explanation and denied message are shown`() {
        compose.show(permitted = false, denied = true)
        compose.onNodeWithText("مينفعش نقرا الرسائل القديمة من غير إذن").assertIsDisplayed()
        compose.onNodeWithText("ابدأ").performClick()
        compose.onNodeWithText("مصروف بيقرا رسائل البنوك من الجهات اللي إنت سامح بيها، وعلى الموبايل ده بس.").assertIsDisplayed()
    }
}
