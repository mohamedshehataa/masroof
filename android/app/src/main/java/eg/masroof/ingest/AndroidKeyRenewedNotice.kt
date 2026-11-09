package eg.masroof.ingest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one note after a replacing mint or drop of the local-id key (design D10): no amounts, no SMS text. Needs the notification
 * permission; without it nothing is shown. Tapping opens the app (immutable PendingIntent).
 */
@Singleton
class AndroidKeyRenewedNotice
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permission: NotificationPermission = AndroidNotificationPermission(context),
    ) : KeyRenewedNotice {
        override fun post() {
            if (!permission.granted()) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CAPTURED_CHANNEL_ID,
                    context.getString(R.string.captured_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.captured_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            val open =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    PendingIntent.getActivity(
                        context,
                        REQUEST_CODE,
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
            val notification =
                NotificationCompat
                    .Builder(context, CAPTURED_CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(context.getString(R.string.key_renewed_title))
                    .setContentText(context.getString(R.string.key_renewed_text))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.key_renewed_text)))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private companion object {
            const val NOTIFICATION_ID = 0x4B45
            const val REQUEST_CODE = 0x4B46
        }
    }
