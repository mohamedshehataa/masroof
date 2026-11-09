package eg.masroof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STORY-208 fix round 4, rule 2: one write path. Every call that inserts, updates, deletes or restores an expense row is listed
 * here with the reason it is allowed. A new call site anywhere else (or one more in a listed file) fails this test, so a future writer
 * cannot bypass the link rules (`AnswerWrites.reconcile`, the money-field lock of the editor) by accident.
 */
class ExpenseWritePathTest {
    private val write =
        Regex(
            "expenses\\(\\)\\s*\\.\\s*(insert|update|upsert|softDelete|restore|delete|deleteAll|setFailed|" +
                "moveSources|setExcluded|claimExcluded|deleteBatch|enrichFromTwin|purgeDeletedBefore)\\(",
        )
    private val categoryWrite = Regex("\\b(setEntryCategory|restoreCategory)\\(")

    /** file (under src/main/java/eg/masroof) to the number of write calls it may contain, with the reason. */
    private val allowed =
        mapOf(
            // the helper: editor writes reconcile the linked rows in the same transaction and lock their money fields
            "data/edit/ExpenseEdit.kt" to 6,
            // the helper: answers, Undo, Unlink and reconcile themselves
            "data/answers/AnswerRepository.kt" to 5,
            "data/answers/AnswerWrites.kt" to 13,
            // STORY-173: the one merge write (update the capture, soft-delete the typed entry) and its Undo; only on a tap, links refused
            "data/edit/DuplicateMerge.kt" to 4,
            // capture Undo: soft delete, then reconcile in the same transaction
            "ingest/Captured.kt" to 1,
            // new rows only (nothing can be linked to a row that does not exist yet) and the twin enrichment of fresh captures
            // STORY-186: +1 enrichFromTwin when a backfilled SMS joins a notification entry (untouched entries only)
            "ingest/SmsWork.kt" to 5,
            "ingest/CaptureQueueConverter.kt" to 2,
            "data/ExpenseRepository.kt" to 1,
            "data/OutboxConverter.kt" to 3,
            "ui/viewmodel/ReviewViewModel.kt" to 2,
            // gam3eya: refuses a linked row (candidates exclude them), creates its own entries, restores its own conversion on Undo
            "data/bills/InstallmentRepository.kt" to 4,
            // flags that are not money fields (excluded from totals; a claim refuses linked rows) and the 30-day purge of deleted rows
            "data/wealth/MetalRepository.kt" to 2,
            "data/wealth/WealthRepository.kt" to 1,
            "data/edit/DeletedEntryPurge.kt" to 1,
            // the DAO declarations and the frozen server-import upserts
            "data/db/Daos.kt" to 7,
        )

    private fun sources(): List<File> =
        File("src/main/java/eg/masroof")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `REQ-EXP-065 no expense write exists outside the listed write path`() {
        val files = sources()
        assertTrue("sources found", files.isNotEmpty())
        val found =
            files
                .associate {
                    it.invariantSeparatorsPath.substringAfter(
                        "eg/masroof/",
                    ) to write.findAll(it.readText()).count()
                }.filterValues {
                    it >
                        0
                }
        val unlisted = found.keys - allowed.keys
        assertTrue("expense writes outside the write path: $unlisted", unlisted.isEmpty())
        found.forEach { (file, count) ->
            assertTrue("$file has $count expense writes, the list says ${allowed[file]}", count == (allowed[file] ?: 0))
        }
    }

    @Test
    fun `REQ-EXP-065 no code keeps the expense DAO in a variable, so no write can hide behind an alias`() {
        val alias = Regex("(val|var)\\s+\\w+(\\s*:\\s*\\w+)?\\s*=\\s*[\\w.]*expenses\\(\\)\\s*$", RegexOption.MULTILINE)
        val aliasGetter =
            Regex(
                "(val|fun)\\s+\\w+\\s*(\\(\\))?\\s*(:\\s*\\w+\\s*)?(get\\(\\)\\s*)?=\\s*[\\w.]*expenses\\(\\)\\s*$",
                RegexOption.MULTILINE,
            )
        val offenders =
            sources()
                .filter { it.invariantSeparatorsPath.substringAfter("eg/masroof/") != "data/db/Daos.kt" }
                .filter { alias.containsMatchIn(it.readText()) || aliasGetter.containsMatchIn(it.readText()) }
                .map { it.name }
        assertTrue("an expense DAO alias hides writes from this scan: $offenders", offenders.isEmpty())
    }

    @Test
    fun `REQ-EXP-064 the category writes of merchant rules reconcile the linked refunds`() {
        val callers =
            sources()
                .filter {
                    categoryWrite.containsMatchIn(it.readText())
                }.map { it.invariantSeparatorsPath.substringAfter("eg/masroof/") }
                .toSet()
        assertEquals(setOf("data/categories/MerchantRules.kt", "data/db/CategoryRulesDao.kt"), callers)
        val rules = File("src/main/java/eg/masroof/data/categories/MerchantRules.kt").readText()
        assertTrue("applyToPast and its Undo run the link rules", Regex("reconcileLinked\\(").findAll(rules).count() >= 3)
    }

    @Test
    fun `REQ-EXP-065 the files that write linked rows run the link rules`() {
        listOf(
            "data/edit/ExpenseEdit.kt",
            "ingest/Captured.kt",
            "data/answers/AnswerRepository.kt",
        ).forEach {
            assertTrue(
                "$it must call AnswerWrites.reconcile or settle",
                File("src/main/java/eg/masroof/$it").readText().let { t ->
                    "reconcile(" in
                        t ||
                        "settle(" in t
                },
            )
        }
    }
}
