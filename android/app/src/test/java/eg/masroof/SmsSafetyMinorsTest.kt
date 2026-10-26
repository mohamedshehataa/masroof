package eg.masroof

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.parse.NotificationApps
import eg.masroof.ui.components.SecureWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 19 security minors: no screenshots of the review and add screens, own package never read, codes masked before WorkManager. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SmsSafetyMinorsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `REQ-SMS-085 the review and add screens are FLAG_SECURE while shown and normal again afterwards`() {
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SecureWindow() }
        compose.waitForIdle()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        shown = false
        compose.waitForIdle()
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `REQ-SMS-086 this app's own notifications are never read as bank messages`() {
        assertTrue(NotificationApps.isDenied("eg.masroof"))
        assertTrue(NotificationApps.isDenied("eg.masroof.debug"))
        assertFalse(NotificationApps.isDenied("com.egyptianbanks.instapay"))
    }

    @Test
    fun `REQ-SMS-086 a code-like digit run is masked before the text goes into WorkManager input data`() {
        val scheduler = FakeSmsScheduler()
        val handoff = SmsHandoff(MemorySettings(), scheduler, MemoryGate())
        val body = "Your card ending 4512 was charged EGP 500.00 at AMAZON. Tracking 90817263541 Auth code 55667788"
        handoff.handOff(listOf(IncomingSms("CIB", body, 1_000L)))
        val queued = scheduler.intake.single().text
        assertFalse(queued.contains("90817263541"))
        assertFalse(queued.contains("55667788"))
        assertTrue("the card's last four and the amount still reach the parser", queued.contains("4512") && queued.contains("500.00"))
    }
}
