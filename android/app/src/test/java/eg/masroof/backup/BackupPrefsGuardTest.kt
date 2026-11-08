package eg.masroof.backup

import eg.masroof.data.backup.BackupPrefsInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The preference guard (STORY-183): the table guard's twin. It scans the production sources for every DataStore, every SharedPreferences
 * file and every key, and fails until each key is placed on the IN or the OUT list of ITS OWN store, so a key that another story adds
 * cannot be silently missing from the backup (a test that compares the backup with the allowlisted snapshot can never see it), and a key
 * that reuses a name allowed for another store is caught too.
 */
class BackupPrefsGuardTest {
    private class Found(
        val dataStores: Set<String>,
        val sharedFiles: Set<String>,
        /** store id (`datastore:x` / `shared:y`) to the keys the sources use in it */
        val keys: Map<String, Set<String>>,
    )

    // Constants in a SharedPreferences file that are not preference keys.
    private val notKeys = setOf("ar", "en", "captured_transactions", "bill_reminders", "masroof_session_keyset", "masroof_session_master")

    /**
     * Which preferences file(s) the keys of a source file belong to. The classes take their SharedPreferences from the DI module, so the
     * owner cannot be read from the source itself: a source file that keeps keys and is not listed here fails the guard.
     */
    private val sharedOwners: Map<String, List<String>> =
        mapOf(
            "SenderAllowlist.kt" to listOf("masroof_senders"),
            "NotificationCapture.kt" to listOf("masroof_notifications"),
            "Captured.kt" to listOf("masroof_captured_alerts"),
            "AppLanguage.kt" to listOf("masroof_ui"),
            "AddExpenseScreen.kt" to listOf("masroof_ui"),
            "CaptureSecurity.kt" to listOf("masroof_capture", "masroof_capture_key"),
            "EncryptedSessionStore.kt" to listOf("masroof_session"),
            "BackupState.kt" to listOf("masroof_backup"),
            "AppModule.kt" to listOf("masroof_device"),
            "ImportStore.kt" to listOf("masroof_server_import"),
        )

    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun scan(
        sources: List<Pair<String, String>>,
        ignoreFile: (String) -> Boolean = { it.endsWith("BackupPrefsInventory.kt") },
    ): Found {
        val ds = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val keys = HashMap<String, MutableSet<String>>()

        fun add(
            owner: String,
            key: String,
        ) {
            keys.getOrPut(owner) { mutableSetOf() }.add(key)
        }
        for ((path, text) in sources) {
            if (ignoreFile(path)) continue
            val name = File(path).name
            val consts = Regex("""val (\w+)\s*=\s*"([^"]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val stores = Regex("""preferencesDataStore\(\s*(?:name\s*=\s*)?"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            ds += stores
            // DataStore keys belong to the DataStore declared in the same source file; none declared = "unowned", which fails.
            val dsOwner = stores.singleOrNull()?.let { "datastore:$it" } ?: "unowned:$name"
            Regex("""(?:string|boolean|int|long|stringSet|float|double)PreferencesKey\(\s*"([^"]+)"""")
                .findAll(text)
                .forEach { add(dsOwner, it.groupValues[1]) }
            Regex("""getSharedPreferences\(\s*(?:"([^"]+)"|(\w+))""").findAll(text).forEach { m ->
                val literal = m.groupValues[1]
                val ident = m.groupValues[2]
                if (literal.isNotEmpty()) files += literal else consts[ident]?.let { files += it }
            }
            if (Regex("""\bSharedPreferences\b|getSharedPreferences""").containsMatchIn(text)) {
                val used = mutableSetOf<String>()
                for (value in consts.values) {
                    if (value in notKeys) continue
                    if (value.startsWith("masroof_")) {
                        files += value
                    } else if (Regex("[a-z][a-z0-9_]*").matches(value)) {
                        used += value
                    }
                }
                Regex("""\.(?:get|put)(?:Boolean|String|StringSet|Int|Long|Float)\(\s*"([^"]+)"""")
                    .findAll(text)
                    .forEach { used += it.groupValues[1] }
                val owners = sharedOwners[name]?.map { "shared:$it" } ?: listOf("unowned:$name")
                // A key of a file with several owners is accepted when it is placed for any of them: remember the candidates.
                for (key in used) add(owners.joinToString("|"), key)
            }
        }
        return Found(ds, files, keys)
    }

    private fun mainSources(): List<Pair<String, String>> =
        File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path to it.readText() }
            .toList()

    private val inKeys: Map<String, Set<String>> =
        BackupPrefsInventory.DATASTORE_KEYS + BackupPrefsInventory.SHARED_KEYS.mapKeys { "shared:${it.key}" }
    private val outKeys: Map<String, Set<String>> = BackupPrefsInventory.OUT_KEYS.mapValues { it.value.keys }
    private val inFiles = BackupPrefsInventory.SHARED_KEYS.keys
    private val inStores =
        BackupPrefsInventory.DATASTORE_KEYS.keys
            .map { it.removePrefix("datastore:") }
            .toSet()

    private fun placed(
        owner: String,
        key: String,
    ) = owner.split('|').any { key in inKeys[it].orEmpty() || key in outKeys[it].orEmpty() }

    /** `owner: key` for every key that is on neither list of its own store. */
    private fun unplaced(found: Found): List<String> =
        found.keys.flatMap { (owner, keys) -> keys.filterNot { placed(owner, it) }.map { "$owner: $it" } }.sorted()

    @Test
    fun `REQ-OPS-176 every DataStore, preferences file and key in the sources is on the IN or the OUT list of its own store`() {
        val found = scan(mainSources())
        assertEquals("DataStores", emptySet<String>(), found.dataStores - inStores)
        val outFiles = BackupPrefsInventory.OUT_FILES.keys
        assertEquals("preference files", emptySet<String>(), found.sharedFiles - inFiles - outFiles)
        assertEquals("keys that are on neither the IN nor the OUT list of their store", emptyList<String>(), unplaced(found))
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
        assertTrue(found.dataStores.contains("brand_new_store"))
        assertTrue(found.sharedFiles.contains("masroof_backfill"))
        assertEquals(
            listOf("datastore:brand_new_store: brand_new_answers_key", "unowned:Fresh.kt: backfill_resume_position"),
            unplaced(found),
        )
        assertTrue("masroof_backfill" !in inFiles && "masroof_backfill" !in BackupPrefsInventory.OUT_FILES)
    }

    @Test
    fun `REQ-OPS-176 a key that reuses a name allowed for another store fails the guard for its own store`() {
        // "added" is allowed in masroof_senders, but a new DataStore key of the settings store with that name is not placed for it.
        val probe =
            """
            private val Context.dataStore by preferencesDataStore(
                name = "settings",
            )
            private val added = stringSetPreferencesKey("added")
            """.trimIndent()
        val found = scan(listOf("Settings.kt" to probe))
        assertEquals(listOf("datastore:settings: added"), unplaced(found))
        assertTrue("the name itself is allowed elsewhere", "added" in inKeys.getValue("shared:masroof_senders"))
    }

    @Test
    fun `REQ-OPS-176 a SharedPreferences source file that has no owner on record fails the guard`() {
        val probe =
            "class P(private val p: android.content.SharedPreferences) { " +
                "companion object { const val K = \"sms_capture_enabled\" } }"
        assertEquals(listOf("unowned:Unlisted.kt: sms_capture_enabled"), unplaced(scan(listOf("Unlisted.kt" to probe))))
    }

    @Test
    fun `REQ-OPS-176 the lists hold no stale entry and no key is both in and out`() {
        val found = scan(mainSources())
        val seen =
            found.keys.values
                .flatten()
                .toSet()
        val listed = (inKeys + outKeys).values.flatten().toSet()
        assertEquals("keys on a list that no source uses", emptySet<String>(), listed - seen)
        for ((store, keys) in inKeys) assertEquals(emptySet<String>(), keys.intersect(outKeys[store].orEmpty()))
        assertEquals(emptySet<String>(), inFiles.intersect(BackupPrefsInventory.OUT_FILES.keys))
        assertTrue(inKeys.getValue("datastore:answers").containsAll(listOf("kept_apart_pairs", "merged_away")))
    }
}
