package eg.masroof.backup

import eg.masroof.data.backup.BackupPrefsInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The preference guard (STORY-183): the table guard's twin. It scans the production sources for every DataStore, every SharedPreferences
 * file and every key, and fails until each is placed on the IN or the OUT list on purpose, so a key that another story adds cannot be
 * silently missing from the backup. (A test that compares the backup with the allowlisted snapshot can never see a missing key.)
 */
class BackupPrefsGuardTest {
    private class Found(
        val dataStores: Set<String>,
        val sharedFiles: Set<String>,
        val dataStoreKeys: Set<String>,
        val sharedKeys: Set<String>,
    )

    // Constants in a SharedPreferences file that are not preference keys.
    private val notKeys = setOf("ar", "en", "captured_transactions", "bill_reminders", "masroof_session_keyset", "masroof_session_master")

    @Suppress("NestedBlockDepth")
    private fun scan(
        sources: List<Pair<String, String>>,
        ignoreFile: (String) -> Boolean = { it.endsWith("BackupPrefsInventory.kt") },
    ): Found {
        val ds = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val dsKeys = mutableSetOf<String>()
        val spKeys = mutableSetOf<String>()
        for ((name, text) in sources) {
            if (ignoreFile(name)) continue
            val consts = Regex("""val (\w+)\s*=\s*"([^"]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            Regex("""preferencesDataStore\(\s*(?:name\s*=\s*)?"([^"]+)"""").findAll(text).forEach { ds += it.groupValues[1] }
            Regex("""(?:string|boolean|int|long|stringSet|float|double)PreferencesKey\(\s*"([^"]+)"""")
                .findAll(text)
                .forEach { dsKeys += it.groupValues[1] }
            Regex("""getSharedPreferences\(\s*(?:"([^"]+)"|(\w+))""").findAll(text).forEach { m ->
                val literal = m.groupValues[1]
                val ident = m.groupValues[2]
                if (literal.isNotEmpty()) files += literal else consts[ident]?.let { files += it }
            }
            if (Regex("""\bSharedPreferences\b|getSharedPreferences""").containsMatchIn(text)) {
                for (value in consts.values) {
                    if (value in notKeys) {
                        continue
                    } else if (value.startsWith("masroof_")) {
                        files += value
                    } else if (Regex("[a-z][a-z0-9_]*").matches(value) && value !in notKeys) {
                        spKeys += value
                    }
                }
                Regex("""\.(?:get|put)(?:Boolean|String|StringSet|Int|Long|Float)\(\s*"([^"]+)"""")
                    .findAll(text)
                    .forEach { spKeys += it.groupValues[1] }
            }
        }
        return Found(ds, files, dsKeys, spKeys)
    }

    private fun mainSources(): List<Pair<String, String>> =
        File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path to it.readText() }
            .toList()

    private val inKeys =
        BackupPrefsInventory.DATASTORE_KEYS.values
            .flatten()
            .toSet() +
            BackupPrefsInventory.SHARED_KEYS.values
                .flatten()
                .toSet()
    private val inFiles = BackupPrefsInventory.SHARED_KEYS.keys
    private val inStores =
        BackupPrefsInventory.DATASTORE_KEYS.keys
            .map { it.removePrefix("datastore:") }
            .toSet()

    @Test
    fun `REQ-OPS-176 every DataStore, preferences file and key in the sources is on the IN or the OUT list`() {
        val found = scan(mainSources())
        assertEquals("DataStores", emptySet<String>(), found.dataStores - inStores)
        assertEquals("preference files", emptySet<String>(), found.sharedFiles - inFiles - BackupPrefsInventory.OUT_FILES.keys)
        val unplaced = (found.dataStoreKeys + found.sharedKeys) - inKeys - BackupPrefsInventory.OUT_KEYS.keys
        assertEquals("keys that are on neither the IN nor the OUT list", emptySet<String>(), unplaced)
    }

    @Test
    fun `REQ-OPS-176 the guard really sees an unplaced key, file and DataStore`() {
        val probe =
            """
            private val a = stringSetPreferencesKey("brand_new_answers_key")
            private val Context.store by preferencesDataStore(name = "brand_new_store")
            class Fresh(private val prefs: android.content.SharedPreferences) {
                fun f(c: Context) = c.getSharedPreferences("masroof_backfill", 0)
                companion object { const val NEW_KEY = "backfill_resume_position" }
            }
            """.trimIndent()
        val found = scan(listOf("Fresh.kt" to probe))
        assertTrue(found.dataStoreKeys.contains("brand_new_answers_key"))
        assertTrue(found.dataStores.contains("brand_new_store"))
        assertTrue(found.sharedFiles.contains("masroof_backfill"))
        assertTrue(found.sharedKeys.contains("backfill_resume_position"))
        assertTrue("brand_new_answers_key" !in inKeys && "backfill_resume_position" !in inKeys)
        assertTrue("masroof_backfill" !in inFiles && "masroof_backfill" !in BackupPrefsInventory.OUT_FILES)
    }

    @Test
    fun `REQ-OPS-176 the lists hold no stale entry and no key is both in and out`() {
        val found = scan(mainSources())
        val seen = found.dataStoreKeys + found.sharedKeys
        assertEquals("IN keys that no source uses", emptySet<String>(), inKeys - seen)
        assertEquals("OUT keys that no source uses", emptySet<String>(), BackupPrefsInventory.OUT_KEYS.keys - seen)
        assertEquals(emptySet<String>(), inKeys.intersect(BackupPrefsInventory.OUT_KEYS.keys))
        assertEquals(emptySet<String>(), inFiles.intersect(BackupPrefsInventory.OUT_FILES.keys))
        assertTrue(inKeys.containsAll(listOf("kept_apart_pairs", "merged_away")))
    }
}
