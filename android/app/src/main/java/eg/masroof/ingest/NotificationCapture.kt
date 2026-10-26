package eg.masroof.ingest

import android.app.Notification
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.edit
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps

/**
 * Which app notifications are read (REQ-NTF-001/002). Off by default: the user turns it on in Settings and grants
 * "Notification access" in the system. Only the listed packages are read; the InstaPay package is a verified-on-device
 * candidate, so the list is user-extendable.
 */
interface NotificationSettings {
    fun enabled(): Boolean

    fun setEnabled(on: Boolean)

    fun packages(): Set<String>

    fun isMonitored(pkg: String): Boolean

    fun addPackage(pkg: String)

    fun removePackage(pkg: String)
}

class PrefsNotificationSettings(
    private val prefs: SharedPreferences,
) : NotificationSettings {
    override fun enabled() = prefs.getBoolean(ENABLED, false)

    override fun setEnabled(on: Boolean) = prefs.edit { putBoolean(ENABLED, on) }

    override fun packages(): Set<String> = NotificationApps.defaults.keys + added() - removed()

    private fun added() = prefs.getStringSet(ADDED, emptySet()).orEmpty().toSet()

    private fun removed() = prefs.getStringSet(REMOVED, emptySet()).orEmpty().toSet()

    override fun isMonitored(pkg: String) = pkg in packages() && !NotificationApps.isDenied(pkg)

    override fun addPackage(pkg: String) {
        val clean = pkg.trim()
        if (!PACKAGE.matches(clean) || NotificationApps.isDenied(clean)) return
        prefs.edit {
            putStringSet(ADDED, added() + clean)
            putStringSet(REMOVED, removed() - clean)
        }
    }

    override fun removePackage(pkg: String) {
        prefs.edit {
            putStringSet(ADDED, added() - pkg)
            putStringSet(REMOVED, removed() + pkg)
        }
    }

    private companion object {
        const val ENABLED = "notifications_enabled"
        const val ADDED = "packages_added"
        const val REMOVED = "packages_removed"
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** The listener's logic without Android types, so it is unit-testable: package check, then a WorkManager enqueue. */
class NotificationHandoff(
    private val settings: NotificationSettings,
    private val scheduler: SmsWorkScheduler,
    private val gate: CaptureGate,
) {
    fun handOff(
        packageName: String,
        title: String?,
        text: String?,
        postedAtMillis: Long,
    ) {
        if (!gate.isOpen() || !settings.enabled() || !settings.isMonitored(packageName)) return
        val joined = listOfNotNull(title, text).filter { it.isNotBlank() }.joinToString("\n")
        if (joined.isBlank() || MessageHygiene.isPlainSecret(joined)) return
        scheduler.enqueueIntake(IncomingMessage(CaptureSource.NOTIFICATION, packageName, joined, postedAtMillis).capped())
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationEntryPoint {
    fun notificationSettings(): NotificationSettings

    fun smsScheduler(): SmsWorkScheduler

    fun captureGate(): CaptureGate
}

/**
 * System-managed listener (the only always-bound component; no foreground service). It reads title and text of
 * allowlisted packages and hands them to WorkManager; it never parses, stores or uploads anything itself.
 */
class MasroofNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification.extras
        val graph = EntryPointAccessors.fromApplication(applicationContext, NotificationEntryPoint::class.java)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        NotificationHandoff(graph.notificationSettings(), graph.smsScheduler(), graph.captureGate())
            .handOff(n.packageName, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), text?.toString(), n.postTime)
    }
}
