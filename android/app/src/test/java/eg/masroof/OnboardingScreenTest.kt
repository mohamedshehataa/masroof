package eg.masroof

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.ui.onboarding.FinishSetupCard
import eg.masroof.ui.onboarding.OnboardingActions
import eg.masroof.ui.onboarding.OnboardingContent
import eg.masroof.ui.onboarding.OnboardingSteps
import eg.masroof.ui.onboarding.OnboardingUi
import eg.masroof.ui.onboarding.SetupGuideSection
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class GuideTaps {
    val log = mutableListOf<String>()
    val actions =
        OnboardingActions(
            onLanguage = { log += "lang:$it" },
            onNext = { log += "next" },
            onRequestSms = { log += "sms" },
            onOpenAppSettings = { log += "settings" },
            onRange = { log += "range:$it" },
            onStartPastSms = { log += "past" },
            onOpenNotificationAccess = { log += "access" },
            onNameChange = { log += "typed" },
            onAddMember = { log += "member:$it" },
            onFinish = { log += "finish" },
        )
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.show(
    ui: OnboardingUi,
    taps: GuideTaps = GuideTaps(),
    fontScale: Float = 1f,
    onDirection: (LayoutDirection) -> Unit = {},
) = setContent {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        MasroofTheme {
            onDirection(LocalLayoutDirection.current)
            OnboardingContent(ui, taps.actions)
        }
    }
}

private fun ui(
    step: Int,
    language: String = "en",
) = OnboardingUi(step = step, language = language)

/** STORY-187: the first-run guide as a function of its state (REQ-AND-139..141), English, left to right. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AND-139 step 1 offers both languages, marks the current one and says which step of seven`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.LANGUAGE), taps)
        compose.onNodeWithText("Step 1 of 7").assertIsDisplayed()
        compose.onNodeWithText("العربية").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("English").assertIsDisplayed()
        assertEquals(listOf("lang:ar"), taps.log)
    }

    @Test
    fun `REQ-AND-139 step 2 says everything stays on this phone, with no account and how backup works, and does not mention restore`() {
        compose.show(ui(OnboardingSteps.STAYS_ON_PHONE))
        compose.onNodeWithText("Everything stays on this phone").assertIsDisplayed()
        compose.onNodeWithText("There is no account", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Back up now", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("restore", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-AND-139 every step but the last has Skip and every Skip and Continue move on`() {
        val taps = GuideTaps()
        var step by mutableIntStateOf(0)
        compose.setContent { MasroofTheme { OnboardingContent(ui(step), taps.actions) } }
        for (s in 0 until OnboardingSteps.DONE) {
            compose.runOnIdle { step = s }
            compose.onNodeWithText("Skip").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
            compose.onNodeWithText("Continue").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        }
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Continue").performClick()
        assertEquals("Skip and Continue both move on, never block", listOf("next", "member:"), taps.log)
    }

    @Test
    fun `REQ-AND-139 the last step shows Open Wallet, no Skip, and finishes`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.DONE), taps)
        compose.onNodeWithText("Step 7 of 7").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Open Wallet").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("finish"), taps.log)
    }

    @Test
    fun `REQ-AND-140 step 3 explains first and asks the system only when Turn on is tapped`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.SMS_CAPTURE), taps)
        compose.onNodeWithText("Android will now ask you to allow receiving SMS.", substring = true).assertIsDisplayed()
        assertTrue("nothing asked yet", taps.log.isEmpty())
        compose.onNodeWithText("Turn on SMS capture").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("sms"), taps.log)
    }

    @Test
    fun `REQ-AND-140 a denied RECEIVE_SMS says capture is off, Continue still works and the button opens the app settings`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.SMS_CAPTURE).copy(smsDenied = true), taps)
        compose.onNodeWithText("SMS capture is off - you can turn it on in Settings").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Open app settings")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("settings", "next"), taps.log)
    }

    @Test
    fun `REQ-AND-140 a granted RECEIVE_SMS shows capture on and no permission button`() {
        compose.show(ui(OnboardingSteps.SMS_CAPTURE).copy(smsGranted = true))
        compose.onNodeWithText("SMS capture is on").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Turn on SMS capture").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-AND-139 step 4 defaults to 90 days and Allow and start asks the permission through the start action`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.PAST_SMS), taps)
        compose.onNodeWithText("Last 90 days").assertIsDisplayed()
        compose.onNodeWithText("Last 30 days").performClick()
        compose.onNodeWithText("Last 6 months").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Allow and start reading")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("range:30", "past"), taps.log)
    }

    @Test
    fun `REQ-AND-139 step 4 with READ_SMS already granted starts directly`() {
        compose.show(ui(OnboardingSteps.PAST_SMS).copy(readSmsGranted = true))
        compose.onNodeWithText("Start reading").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-140 step 4 with READ_SMS denied says why nothing started and Continue still works`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.PAST_SMS).copy(readSmsDenied = true), taps)
        compose.onNodeWithText("Past messages can't be read without permission", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("settings", "next"), taps.log)
    }

    @Test
    fun `REQ-AND-140 step 5 explains, opens the system notification-access screen, and shows nothing until the user returns`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.NOTIFICATIONS), taps)
        compose.onNodeWithText("Android will open a screen", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Not enabled").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Open notification access").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("access"), taps.log)
    }

    @Test
    fun `REQ-AND-140 back from the notification-access screen without enabling shows Not enabled and Continue works`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.NOTIFICATIONS).copy(notificationAccess = false), taps)
        compose.onNodeWithText("Not enabled").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("next"), taps.log)
    }

    @Test
    fun `REQ-AND-140 enabled notification access shows Enabled`() {
        compose.show(ui(OnboardingSteps.NOTIFICATIONS).copy(notificationAccess = true))
        compose.onNodeWithText("Enabled").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-141 step 6 sends the typed name on Continue and reports typing`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.MEMBER), taps)
        compose.onNodeWithText("Who else spends in this household?").assertIsDisplayed()
        compose.onNodeWithText("Name").performTextInput("Sara")
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("typed", "member:Sara"), taps.log)
    }

    @Test
    fun `REQ-AND-141 an invalid name shows the error under the field and Skip still works`() {
        val taps = GuideTaps()
        compose.show(ui(OnboardingSteps.MEMBER).copy(nameError = true), taps)
        compose.onNodeWithText("Enter a name up to 40 characters").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(listOf("next"), taps.log)
    }

    @Test
    fun `REQ-AND-139 font scale 1_5 keeps Continue and Skip visible on the busiest steps`() {
        compose.show(ui(OnboardingSteps.PAST_SMS).copy(readSmsDenied = true), fontScale = 1.5f)
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-139 the finish-setup card offers Continue setup and Dismiss with 48dp targets`() {
        val taps = GuideTaps()
        compose.setContent {
            MasroofTheme {
                FinishSetupCard(onOpen = { taps.log += "open" }, onDismiss = { taps.log += "dismiss" })
            }
        }
        compose.onNodeWithText("Finish setup").assertIsDisplayed()
        compose.onNodeWithText("Continue setup").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Dismiss").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("open", "dismiss"), taps.log)
    }

    @Test
    fun `REQ-AND-139 Settings has a Setup guide entry that opens the guide`() {
        var opened = 0
        compose.setContent { MasroofTheme { SetupGuideSection(onOpen = { opened++ }) } }
        compose.onNodeWithText("Setup guide").assertIsDisplayed()
        compose.onNodeWithText("Open setup guide").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }
}

/** The same guide in Egyptian Arabic: right to left at once, Arabic text and digits (REQ-AND-139 language step). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class OnboardingScreenArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AND-139 Arabic lays the guide out right to left with Arabic text and Arabic-Indic digits`() {
        var direction: LayoutDirection? = null
        compose.show(ui(OnboardingSteps.LANGUAGE, "ar"), onDirection = { direction = it })
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("اختار لغتك").assertIsDisplayed()
        compose.onNodeWithText("الخطوة ١ من ٧").assertIsDisplayed()
        compose.onNodeWithText("تخطّي").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("كمّل").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-AND-141 the Arabic member step shows its error in Arabic`() {
        compose.show(ui(OnboardingSteps.MEMBER, "ar").copy(nameError = true))
        compose.onNodeWithText("اكتب اسم لحد ٤٠ حرف").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-140 the Arabic denial lines show`() {
        compose.show(ui(OnboardingSteps.SMS_CAPTURE, "ar").copy(smsDenied = true), fontScale = 1.5f)
        compose.onNodeWithText("تسجيل الرسائل مقفول - تقدر تشغّله من الإعدادات").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("كمّل").assertIsDisplayed()
    }
}
