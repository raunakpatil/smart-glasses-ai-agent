package com.ronie.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.ronie.app.MainActivity
import com.ronie.app.RonieApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps Ronie going with the screen off or the app in the background.
 *
 * Two ways to run: a conversation the user started with Talk, or "ready" mode, where the service waits in the
 * background and starts listening for "Ronie" on its own whenever the glasses connect (and lets go of the mic and the
 * glasses' call link when they disconnect). Android only lets a background service use the mic if it was started
 * while the app was open, so ready mode is switched on from the app.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RonieApplication
        val voice = app.voice
        when (intent?.action) {
            ACTION_STOP -> {
                watcher?.cancel()
                voice.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_READY -> {
                startForeground(NOTIFICATION_ID, notification(ready = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                watchGlasses(app)
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification(ready = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                voice.start()
            }
        }
        return START_NOT_STICKY
    }

    /** Glasses on: listen for the name. Glasses off: stop listening (a conversation started with Talk is left alone). */
    private fun watchGlasses(app: RonieApplication) {
        if (watcher?.isActive == true) return
        if (app.glasses.name.value != null) app.voice.start()
        watcher = scope.launch {
            app.glasses.name.drop(1).collect { glasses -> // a StateFlow only emits changes
                if (glasses != null) app.voice.start() else app.voice.stop()
            }
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        (application as RonieApplication).voice.stop()
        super.onDestroy()
    }

    private fun notification(ready: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ronie conversation", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (ready) "Ronie is ready" else "Ronie is listening")
            .setContentText(
                if (ready) "Put on your glasses and say \"Hey Ronie\". Tap Stop to turn this off."
                else "Talk any time. Tap Stop to end."
            )
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.ronie.app.STOP"
        private const val ACTION_READY = "com.ronie.app.READY"

        fun start(context: Context) = context.startForegroundService(Intent(context, VoiceService::class.java))
        fun ready(context: Context) = context.startForegroundService(Intent(context, VoiceService::class.java).setAction(ACTION_READY))
        fun stop(context: Context) = context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))
    }
}
