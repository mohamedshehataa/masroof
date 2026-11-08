package eg.masroof.ask

import eg.masroof.ui.ask.AskViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

private class FakeAsk : AskEngine {
    val asked = mutableListOf<AskQuery>()
    var fail = false

    override suspend fun answer(query: AskQuery): AskAnswer {
        asked.add(query)
        if (fail) throw IOException("cannot read")
        return AskAnswer.Biggest(PeriodInfo(AskPeriod.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), null, emptyList())
    }

    override suspend fun intro() = AskIntro(MerchantStat("Amazon", 1_646_000, 4), emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val engine = FakeAsk()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-AN-165 the intro fact is loaded when the screen opens and the conversation starts empty`() =
        runTest {
            val vm = AskViewModel(engine, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T09:00:00Z"), java.time.ZoneOffset.UTC))
            assertEquals(
                "Amazon",
                vm.state.value.intro
                    ?.topMerchant
                    ?.name,
            )
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-AN-160 a typed question is parsed on the phone and answered, blank text is ignored`() =
        runTest {
            val vm = AskViewModel(engine, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T09:00:00Z"), java.time.ZoneOffset.UTC))
            vm.ask("   ")
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )
            vm.ask("biggest expense this month")
            val message =
                vm.state.value.messages
                    .single()
            assertEquals("biggest expense this month", message.question)
            assertEquals(AskQuery.Biggest(AskPeriod.THIS_MONTH), message.query)
            assertTrue(message.answer is AskAnswer.Biggest)
        }

    @Test
    fun `REQ-AN-165 a chip asks its own typed query and shows its label as the question`() =
        runTest {
            val vm = AskViewModel(engine, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T09:00:00Z"), java.time.ZoneOffset.UTC))
            vm.askQuery(AskQuery.Roast, "Roast me")
            assertEquals(listOf<AskQuery>(AskQuery.Roast), engine.asked)
            assertEquals(
                "Roast me",
                vm.state.value.messages
                    .single()
                    .question,
            )
        }

    @Test
    fun `REQ-AN-165 a question longer than 200 characters is cut and a failed read can be retried`() =
        runTest {
            val vm = AskViewModel(engine, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T09:00:00Z"), java.time.ZoneOffset.UTC))
            engine.fail = true
            vm.ask("payday " + "x".repeat(500))
            val failed =
                vm.state.value.messages
                    .single()
            assertEquals(200, failed.question.length)
            assertEquals(AskAnswer.Failed, failed.answer)
            engine.fail = false
            vm.retry(failed.id)
            assertTrue(
                vm.state.value.messages
                    .single()
                    .answer is AskAnswer.Biggest,
            )
            assertEquals(2, engine.asked.size)
        }

    @Test
    fun `REQ-AN-165 each question is kept in order with its own answer`() =
        runTest {
            val vm = AskViewModel(engine, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T09:00:00Z"), java.time.ZoneOffset.UTC))
            vm.ask("roast me")
            vm.ask("top places")
            assertEquals(
                listOf("roast me", "top places"),
                vm.state.value.messages
                    .map { it.question },
            )
            assertEquals(listOf(AskQuery.Roast, AskQuery.TopMerchants(AskPeriod.THIS_MONTH)), engine.asked)
            assertEquals(
                2,
                vm.state.value.messages
                    .map { it.id }
                    .distinct()
                    .size,
            )
        }
}

/** REQ-AN-165: nothing in the Ask code reaches the network, the api-client, the auth layer, or a log. */
class AskIsLocalOnlyTest {
    private fun sources(vararg packages: String): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val root =
                listOf(
                    File(dir, "src/main/java/eg/masroof"),
                    File(dir, "app/src/main/java/eg/masroof"),
                ).firstOrNull { it.isDirectory }
            if (root != null) return packages.flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        error("app sources not found")
    }

    @Test
    fun `REQ-AN-165 no network client, generated api client or auth code is reachable from the Ask package`() {
        val files = sources("ask", "ui/ask")
        assertTrue("found only ${files.size} Ask sources", files.size >= 10)
        val forbidden =
            listOf(
                "retrofit2",
                "okhttp3",
                "eg.masroof.api",
                "eg.masroof.data.remote",
                "eg.masroof.data.auth",
                "eg.masroof.data.insights",
                "java.net.",
                "javax.net",
                "HttpURLConnection",
                "WebView",
                "ApiRemoteSource",
                "work.OutboxSync",
            )
        val offenders =
            files.flatMap { f ->
                f.readLines().filter { line -> line.trimStart().startsWith("import ") || line.contains("Http") }.flatMap { line ->
                    forbidden.filter { line.contains(it) }.map { "${f.name}: $it" }
                }
            }
        assertTrue("network references: $offenders", offenders.isEmpty())
    }

    @Test
    fun `REQ-AN-165 questions and answers are never logged`() {
        val files = sources("ask", "ui/ask")
        val offenders =
            files.filter { f ->
                f.readText().let {
                    it.contains("android.util.Log") ||
                        Regex("""\bLog\.[a-z]""").containsMatchIn(it) ||
                        it.contains("println(")
                }
            }
        assertTrue("logging in ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun `REQ-AN-165 the question draft is not saved in the saved-state bundle and data does not depend on the Ask package`() {
        val screen = sources("ui/ask").filter { it.name == "AskScreen.kt" }.single().readText()
        assertTrue("rememberSaveable would write the question into the saved state", !screen.contains("rememberSaveable"))
        val data = sources("data/db").filter { f -> f.readText().contains("eg.masroof.ask") }
        assertTrue("data layer references the Ask package: ${data.map { it.name }}", data.isEmpty())
    }
}
