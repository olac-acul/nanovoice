package dev.nanovoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.nanovoice.voice.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the download notification says: the names being installed, the first one's phase and percent (null: not known yet). */
internal data class DownloadNotice(val names: List<String>, val unpacking: Boolean, val percent: Int?)

/** The notice for [names] (in the order they started) with the first one's [state]. */
internal fun downloadNotice(names: List<String>, state: ModelState?): DownloadNotice = when (state) {
    is ModelState.Downloading -> DownloadNotice(names, unpacking = false, percent = percentOf(state.progress))
    is ModelState.Unpacking -> DownloadNotice(names, unpacking = true, percent = percentOf(state.progress))
    else -> DownloadNotice(names, unpacking = false, percent = null)
}

private fun percentOf(progress: Float): Int? = if (progress in 0f..1f) (progress * 100).toInt() else null

/**
 * Keeps the app's process alive while a voice model downloads (Parakeet is about 670 MB): a foreground service with
 * the progress in its notification, from the first install started to the last one ended. Without it, Android may end
 * the process as soon as the app leaves the screen; the download would then go on from where it stopped only at the next
 * install (the files resume), but nothing would be installed meanwhile.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as NanoVoiceApp).container
        val first = downloadNotice(container.downloading.value.values.toList(), null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification(first))
            }
        } catch (e: RuntimeException) {
            // Not allowed now (a start from the background): the download still runs, only less protected.
            Log.w(TAG, "download service refused: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            val manager = getSystemService(NotificationManager::class.java)
            scope.launch {
                container.downloading
                    .flatMapLatest { running ->
                        val key = running.keys.firstOrNull() ?: return@flatMapLatest flowOf(null)
                        container.downloadState(key).map { downloadNotice(running.values.toList(), it) }
                    }
                    .distinctUntilChanged()
                    .collect { notice ->
                        if (notice == null) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            manager.notify(NOTIFICATION_ID, notification(notice))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 and later limit how long a data-sync service runs each day: past it, the download goes on unprotected. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(notice: DownloadNotice): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(this, 0, MainActivity.openIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val names = notice.names.joinToString(", ").ifEmpty { getString(R.string.download_models) }
        val text = when {
            notice.unpacking -> getString(R.string.download_unpacking, names)
            notice.percent != null -> getString(R.string.download_progress, names, notice.percent)
            else -> getString(R.string.download_starting, names)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.download_title))
            .setContentText(text)
            .setProgress(100, notice.percent ?: 0, notice.percent == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 7101

        /** Started with each install from the app's screens; it ends itself when no download is left. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ModelDownloadService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the download service: $it") }
        }
    }
}
