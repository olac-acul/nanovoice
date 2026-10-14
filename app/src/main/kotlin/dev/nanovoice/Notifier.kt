package dev.nanovoice

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicInteger

/** System notifications for what the agent says on its own, when the app is not on screen. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val nextId = AtomicInteger(1)

    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** True while any activity of the app is visible; then the message is already shown in the chat. */
    fun appInForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * A message the agent sends on its own goes to a notification unless the chat shows it: when no screen of the app
     * is visible, or when only the assistant sheet is, over the lock screen, where it does not show agent messages.
     */
    fun shouldNotify() = shouldNotify(appInForeground(), keyguard?.isKeyguardLocked == true)

    fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showAgentMessage(text: String) {
        if (!canNotify()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            MainActivity.openIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // On a lock screen set to hide sensitive content, only this placeholder shows.
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                android.app.Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notif_private))
                    .build(),
            )
            .build()
        manager.notify(nextId.getAndIncrement(), notification)
    }

    companion object {
        private const val CHANNEL = "agent_messages"

        internal fun shouldNotify(appInForeground: Boolean, locked: Boolean) = !appInForeground || locked
    }
}
