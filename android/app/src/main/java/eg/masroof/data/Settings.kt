package eg.masroof.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.core.DigitStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** null = follow the UI language (Arabic -> Arabic-Indic digits). */
    val digitStyle: DigitStyle? = null,
    /** First day (1..28) of the household month; analytics periods run from it to the day before the next one. */
    val monthStartDay: Int = DEFAULT_MONTH_START_DAY,
    /** "Count ATM withdrawals as spending" (ADR-0009 D2). */
    val includeCashWithdrawals: Boolean = false,
) {
    companion object {
        /** The Android emulator's alias for the host machine, where `make up` serves Caddy on port 80. */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2/"
        const val DEFAULT_MONTH_START_DAY = 1
        val MONTH_START_DAYS = 1..28
    }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val legacyTokenKey = stringPreferencesKey("access_token")

/** One-time upgrade step: the pre-login build kept a plaintext access token in this DataStore. Remove it. */
fun legacyTokenMigration(): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData.contains(legacyTokenKey)

        override suspend fun migrate(currentData: Preferences): Preferences =
            currentData.toMutablePreferences().apply { remove(legacyTokenKey) }

        override suspend fun cleanUp() = Unit
    }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(legacyTokenMigration()) },
)

@Singleton
class DataStoreSettings
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SettingsRepository {
        private val urlKey = stringPreferencesKey("server_url")
        private val arabicDigitsKey = booleanPreferencesKey("arabic_digits")
        private val digitsSetKey = booleanPreferencesKey("digits_set")
        private val monthStartKey = intPreferencesKey("month_start_day")
        private val cashKey = booleanPreferencesKey("include_cash_withdrawals")

        override val settings: Flow<AppSettings> =
            context.dataStore.data.map { p ->
                AppSettings(
                    serverUrl = p[urlKey] ?: AppSettings.DEFAULT_SERVER_URL,
                    digitStyle =
                        if (p[digitsSetKey] == true) {
                            if (p[arabicDigitsKey] == true) DigitStyle.ARABIC_INDIC else DigitStyle.WESTERN
                        } else {
                            null
                        },
                    monthStartDay = validMonthStartDay(p[monthStartKey]),
                    includeCashWithdrawals = p[cashKey] ?: false,
                )
            }

        override suspend fun current(): AppSettings = settings.first()

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            val next = transform(current())
            context.dataStore.edit { p ->
                p[urlKey] = next.serverUrl
                p[monthStartKey] = validMonthStartDay(next.monthStartDay)
                p[cashKey] = next.includeCashWithdrawals
                if (next.digitStyle == null) {
                    p.remove(digitsSetKey)
                } else {
                    p[digitsSetKey] = true
                    p[arabicDigitsKey] = next.digitStyle == DigitStyle.ARABIC_INDIC
                }
            }
        }
    }

/**
 * Normalises user input to a Retrofit-safe base URL (scheme required, trailing slash). Whitespace anywhere in the
 * text (a stray space pasted into the host, as in `http://10.0.0.5 /`) is dropped. Null when the result is not a
 * valid http(s) URL, so callers report "invalid URL" instead of letting Retrofit throw later.
 */
fun normalizeServerUrl(input: String): String? {
    val cleaned = input.filterNot { it.isWhitespace() }
    if (!(cleaned.startsWith("http://") || cleaned.startsWith("https://"))) return null
    if (cleaned.substringAfter("://").isBlank()) return null
    val withSlash = if (cleaned.endsWith("/")) cleaned else "$cleaned/"
    return withSlash.takeIf { it.toHttpUrlOrNull() != null }
}

private val invalidMonthStartLogged =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

/** A stored or restored month start day outside 1..28 falls back to 1; only an error code is logged, never the value. */
fun validMonthStartDay(stored: Int?): Int {
    if (stored == null) return AppSettings.DEFAULT_MONTH_START_DAY
    if (stored in AppSettings.MONTH_START_DAYS) return stored
    // Once per process: the setting flow re-reads the stored value on every emission.
    if (invalidMonthStartLogged.compareAndSet(false, true)) android.util.Log.e("Settings", "E_MONTH_START_DAY_INVALID")
    return AppSettings.DEFAULT_MONTH_START_DAY
}
