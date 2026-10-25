package eg.masroof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.core.DigitStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** null = follow the UI language (Arabic -> Arabic-Indic digits). */
    val digitStyle: DigitStyle? = null,
) {
    companion object {
        /** The Android emulator's alias for the host machine, where `make up` serves Caddy on port 80. */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2/"
    }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettings
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SettingsRepository {
        private val urlKey = stringPreferencesKey("server_url")
        private val arabicDigitsKey = booleanPreferencesKey("arabic_digits")
        private val digitsSetKey = booleanPreferencesKey("digits_set")

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
                )
            }

        override suspend fun current(): AppSettings = settings.first()

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            val next = transform(current())
            context.dataStore.edit { p ->
                p[urlKey] = next.serverUrl
                if (next.digitStyle == null) {
                    p.remove(digitsSetKey)
                } else {
                    p[digitsSetKey] = true
                    p[arabicDigitsKey] = next.digitStyle == DigitStyle.ARABIC_INDIC
                }
            }
        }
    }

/** Normalises user input to a Retrofit-safe base URL (scheme required, trailing slash). Null when unusable. */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) return null
    if (trimmed.substringAfter("://").isBlank()) return null
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}
