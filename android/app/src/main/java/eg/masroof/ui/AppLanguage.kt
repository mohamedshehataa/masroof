package eg.masroof.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * In-app language choice (en/ar) on top of the device language. Kept in a tiny synchronous preference because it must
 * be known in `attachBaseContext`, before any coroutine or DataStore is available.
 */
object AppLanguage {
    private const val PREFS = "masroof_ui"
    private const val KEY = "language"
    const val ARABIC = "ar"
    const val ENGLISH = "en"

    fun stored(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** The language the UI is showing right now. */
    fun current(context: Context): String =
        context.resources.configuration.locales[0]
            .language

    /** Switches between Arabic and English; the caller recreates the activity. */
    fun toggle(context: Context) {
        val next = if (current(context) == ARABIC) ENGLISH else ARABIC
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, next) }
    }

    /** A context whose resources and layout direction follow the stored choice (unchanged when none is stored). */
    fun wrap(base: Context): Context {
        val lang = stored(base) ?: return base
        val locale = Locale.forLanguageTag(lang)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
