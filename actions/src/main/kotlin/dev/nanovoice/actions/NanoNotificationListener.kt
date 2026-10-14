package dev.nanovoice.actions

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Connected only after the user grants notification access in Android's settings. It does nothing on its own: the
 * agent's "read my notifications" asks it for what is on screen right now ([snapshot]).
 */
class NanoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    data class Item(val app: String, val title: String, val text: String, val postedAt: Long)

    companion object {
        @Volatile private var connected: NanoNotificationListener? = null

        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
        }

        /** The latest notifications on screen, newest first, without NanoVoice's own, ongoing ones and group summaries. */
        fun snapshot(context: Context, limit: Int): List<Item>? {
            val service = connected ?: return null
            val pm = context.packageManager
            return runCatching { service.activeNotifications }.getOrNull().orEmpty()
                .filter { it.packageName != context.packageName && !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                .sortedByDescending { it.postTime }
                .take(limit)
                .map { sbn ->
                    val extras = sbn.notification.extras
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    Item(
                        app = label,
                        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(80),
                        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(160),
                        postedAt = sbn.postTime,
                    )
                }
        }
    }
}
