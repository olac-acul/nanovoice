package dev.nanovoice.wake

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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.nanovoice.MainActivity
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.R
import dev.nanovoice.assistant.NanoVoiceInteractionService
import dev.nanovoice.voice.WakeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens for the wake word in the background and opens the assistant when it hears it. A foreground service of type
 * microphone, as Android requires for listening while the app is not on screen; it shows a permanent notification
 * with a Stop button. It steps aside while the app itself uses the microphone (chat, assistant sheet).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastWakeAt = 0L

    /** Keeps the processor running with the screen off: without it Android pauses it and the word is never heard. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** startForeground failed (no microphone permission, or started from the background): nothing runs. */
    private var refused = false

    override fun onCreate() {
        super.onCreate()
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // A microphone service Android will not let run (permission revoked, a background start): pause and say so,
            // instead of crashing and being restarted into the same crash.
            Log.w(TAG, "wake word service refused: ${e.javaClass.simpleName}")
            refused = true
            notifyPaused(this, getSystemService(NotificationManager::class.java))
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NanoVoice:wake-word")
            .apply { setReferenceCounted(false); acquire() }
        val container = (application as NanoVoiceApp).container
        val vosk = container.vosk
        scope.launch {
            // Not while the app listens (our recorder is closed meanwhile), nor while it speaks: the synthetic voice
            // saying the word would reopen the sheet.
            combine(vosk.inUse, container.spokenOutput.speaking) { listening, speaking -> listening || speaking }
                .distinctUntilChanged()
                .collectLatest { busy ->
                    if (busy) return@collectLatest
                    delay(RESUME_DELAY_MS) // let the app's own recorder go first, and the last spoken words fade
                    while (isActive) {
                        try {
                            vosk.listenForWakePhrase().collect { event ->
                                when (event) {
                                    is WakeEvent.Heard -> onWake()
                                    is WakeEvent.Failed -> Log.w(TAG, "wake word listening stopped: ${event.error} ${event.detail.orEmpty()}")
                                    else -> Unit
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "wake word listening failed: ${e.javaClass.simpleName}")
                        }
                        delay(RETRY_DELAY_MS) // the microphone was busy (a call, another app): try again later
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (refused) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            val store = (application as NanoVoiceApp).container.settingsStore
            store.save(store.settings.value.copy(wakeWord = false))
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun onWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < DEBOUNCE_MS) return // the same word heard twice, or the sheet is already opening
        lastWakeAt = now
        Log.i(TAG, "wake word heard")
        if (!NanoVoiceInteractionService.show()) Log.w(TAG, "NanoVoice is not the default assistant: cannot open the sheet")
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, MainActivity.openIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.wake_notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 7001
        private const val PAUSED_NOTIFICATION_ID = 7002
        private const val ACTION_STOP = "dev.nanovoice.wake.STOP"
        private const val RESUME_DELAY_MS = 600L
        private const val RETRY_DELAY_MS = 5_000L
        private const val DEBOUNCE_MS = 5_000L

        /**
         * Starts or stops the service to match the setting. Android only lets a microphone service start while the app
         * is visible (or in a few exempt cases): when it refuses, a notification offers to resume from the app.
         */
        fun apply(context: Context, enabled: Boolean): Boolean {
            val intent = Intent(context, WakeWordService::class.java)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!enabled) {
                context.stopService(intent)
                manager.cancel(PAUSED_NOTIFICATION_ID)
                return true
            }
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifyPaused(context, manager) // without the microphone it could only crash
                return false
            }
            val started = runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start the wake word service: $it") }
                .isSuccess
            if (started) manager.cancel(PAUSED_NOTIFICATION_ID) else notifyPaused(context, manager)
            return started
        }

        private fun notifyPaused(context: Context, manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
            val open = PendingIntent.getActivity(
                context, 2, MainActivity.openIntent(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                PAUSED_NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.wake_paused_title))
                    .setContentText(context.getString(R.string.wake_paused_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
