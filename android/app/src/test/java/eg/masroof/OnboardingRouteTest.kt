package eg.masroof

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DefaultSeed
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ui.onboarding.FinishSetupCard
import eg.masroof.ui.onboarding.OnboardingActions
import eg.masroof.ui.onboarding.OnboardingContent
import eg.masroof.ui.onboarding.OnboardingProgress
import eg.masroof.ui.onboarding.OnboardingRoute
import eg.masroof.ui.onboarding.OnboardingSteps
import eg.masroof.ui.onboarding.OnboardingStore
import eg.masroof.ui.onboarding.OnboardingUi
import eg.masroof.ui.onboarding.OnboardingViewModel
import eg.masroof.ui.onboarding.SetupGate
import eg.masroof.ui.onboarding.SetupGuideSection
import eg.masroof.ui.onboarding.SetupMode
import eg.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A system permission dialog that answers at once with [granted]. */
private fun registryAnswering(granted: Boolean) =
    object : ActivityResultRegistryOwner {
        override val activityResultRegistry =
            object : ActivityResultRegistry() {
                @Suppress("UNCHECKED_CAST")
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    dispatchResult(requestCode, granted as O)
                }
            }
    }

/** STORY-187: the real route and the gate around it, on a real in-memory Room and the real preferences stores. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OnboardingRouteTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private lateinit var db: AppDatabase
    private var counter = 0
    private val ids =
        object : IdFactory {
            override fun next() = "m-${++counter}"
        }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)
    private val backfillStore by lazy { BackfillStore(context.getSharedPreferences("route-backfill", Context.MODE_PRIVATE)) }
    private val store by lazy { OnboardingStore(context.getSharedPreferences("route-onb", Context.MODE_PRIVATE)) }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        DefaultSeed.seed(db.openHelper.writableDatabase)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun vm() =
        OnboardingViewModel(
            store,
            db,
            ids,
            BackfillController(WorkManager.getInstance(context), backfillStore, clock),
            PrefsSenderSettings(context.getSharedPreferences("route-senders", Context.MODE_PRIVATE)),
            PrefsNotificationSettings(context.getSharedPreferences("route-ntf", Context.MODE_PRIVATE)),
            Dispatchers.Unconfined,
        )

    private fun showRoute(
        vm: OnboardingViewModel,
        granted: Boolean,
    ) = compose.setContent {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryAnswering(granted)) {
            MasroofTheme {
                val progress by vm.progress.collectAsState()
                OnboardingRoute(progress, vm) {}
            }
        }
    }

    @Test
    fun `REQ-AND-140 READ_SMS denied on the past-SMS step starts no backfill, says why and does not move on`() {
        store.save(OnboardingProgress(SetupMode.GUIDE, OnboardingSteps.PAST_SMS))
        val vm = vm()
        showRoute(vm, granted = false)
        compose.onNodeWithText("Allow and start reading").performScrollTo().performClick()
        compose.onNodeWithText("Past messages can't be read without permission", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals("no backfill", BackfillPhase.IDLE, backfillStore.state.value.phase)
        assertEquals("the step stays; Continue still works", OnboardingSteps.PAST_SMS, vm.progress.value.step)
        // the system will not ask again here (Robolectric: no rationale), so the main button now opens the app settings
        compose.onNodeWithText("Open app settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(OnboardingSteps.NOTIFICATIONS, vm.progress.value.step)
        assertEquals(BackfillPhase.IDLE, backfillStore.state.value.phase)
    }

    @Test
    fun `REQ-AND-139 READ_SMS granted starts the 90-day backfill and moves to the next step`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        store.save(OnboardingProgress(SetupMode.GUIDE, OnboardingSteps.PAST_SMS))
        val vm = vm()
        showRoute(vm, granted = true)
        compose.onNodeWithText("Start reading").performScrollTo().performClick()
        assertEquals(BackfillPhase.RUNNING, backfillStore.state.value.phase)
        assertEquals("D90", backfillStore.state.value.rangeKey)
        assertEquals(OnboardingSteps.NOTIFICATIONS, vm.progress.value.step)
    }

    @Test
    fun `REQ-AND-140 RECEIVE_SMS granted by the dialog turns the app's capture switch on`() {
        store.save(OnboardingProgress(SetupMode.GUIDE, OnboardingSteps.SMS_CAPTURE))
        val vm = vm()
        showRoute(vm, granted = true)
        compose.onNodeWithText("Turn on SMS capture").performScrollTo().performClick()
        compose.onNodeWithText("SMS capture is on").performScrollTo().assertIsDisplayed()
        assertTrue(vm.smsCaptureWanted())
    }

    private fun showGate(vm: OnboardingViewModel) =
        compose.setContent {
            MasroofTheme {
                val progress by vm.progress.collectAsState()
                SetupGate(
                    progress = progress,
                    guide = {
                        OnboardingContent(
                            OnboardingUi(step = progress.step, language = "en"),
                            OnboardingActions(onNext = vm::next, onFinish = vm::finish),
                        )
                    },
                    app = { showCard ->
                        Column {
                            if (showCard) FinishSetupCard(onOpen = vm::reopen, onDismiss = vm::dismissCard)
                            Text("Wallet")
                            SetupGuideSection(onOpen = vm::reopen)
                        }
                    },
                )
            }
        }

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `REQ-AND-139 a fresh install shows the guide instead of the app, and finishing it shows Wallet`() {
        showGate(vm())
        waitFor("Step 1 of 7")
        assertTrue("the app is not behind the guide", !shown("Wallet"))
        repeat(OnboardingSteps.DONE) { compose.onNodeWithText("Skip").performClick() }
        compose.onNodeWithText("Step 7 of 7").assertIsDisplayed()
        compose.onNodeWithText("Open Wallet").performClick()
        compose.onNodeWithText("Wallet").assertIsDisplayed()
        assertTrue(!shown("Step 7 of 7") && !shown("Finish setup"))
    }

    @Test
    fun `REQ-AND-139 a phone with data shows Wallet with the Finish setup card, Continue setup opens the guide and Back closes it`() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO budget (id, period, amountMinor, currency, rollover, alertThresholds, createdAtMillis, updatedAtMillis) " +
                "VALUES ('b','MONTHLY',100000,'EGP',0,'50',1,1)",
        )
        val vm = vm()
        showGate(vm)
        waitFor("Finish setup")
        compose.onNodeWithText("Wallet").assertIsDisplayed()
        assertTrue("the guide is not forced", !shown("Step 1 of 7"))
        compose.onNodeWithText("Continue setup").performClick()
        compose.onNodeWithText("Step 1 of 7").assertIsDisplayed()
        vm.back()
        compose.waitForIdle()
        compose.onNodeWithText("Wallet").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-139 Settings Setup guide reopens a finished guide at step 1 and a finished guide is not shown again`() {
        store.save(OnboardingProgress(SetupMode.DONE, 0))
        showGate(vm())
        compose.onNodeWithText("Wallet").assertIsDisplayed()
        assertTrue(!shown("Step 1 of 7") && !shown("Finish setup"))
        compose.onNodeWithText("Open setup guide").performClick()
        compose.onNodeWithText("Step 1 of 7").assertIsDisplayed()
    }
}
