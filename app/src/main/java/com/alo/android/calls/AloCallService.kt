package com.alo.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a call alive while Alo is in the background, as Telegram's VoIPService does: without a foreground service
 * Android silences the microphone of a backgrounded app and freezes its process, the signalling stops and the other
 * side ends the call after its disconnect timeout. Runs from the moment a call is placed or answered until it ends,
 * with the ongoing «Идёт звонок» notification (tap returns to the call, «Завершить» hangs up).
 */
internal class AloCallService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            Handler(Looper.getMainLooper()).post { onHangUp?.invoke() }
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Alo" }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title, video), serviceTypes(video))
        }.onFailure {
            // Started from the background without the right to (Android 14+): the call still runs while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun serviceTypes(video: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(title: String, video: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, AloCallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.alo_logo)
            .setContentTitle(title)
            .setContentText(if (video) "Идёт видеозвонок" else "Идёт звонок")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(0, "Завершить", hangUp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Текущий звонок", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Показывается, пока идёт звонок: звонок продолжается, даже когда Alo свёрнут."
        channel.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "alo_ongoing_call"
        private const val NOTIFICATION_ID = 4103
        private const val ACTION_HANG_UP = "com.alo.android.action.HANG_UP_CALL"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VIDEO = "video"

        /** What «Завершить» in the notification does; set by the call coordinator. */
        @Volatile
        var onHangUp: (() -> Unit)? = null

        private var running: Pair<String, Boolean>? = null

        /** Starts or updates the service for the current call; call [stop] when it ends. */
        fun sync(context: Context, title: String?, video: Boolean) {
            val wanted = title?.let { it to video }
            if (wanted == running) return
            if (wanted == null) {
                running = null
                context.stopService(Intent(context, AloCallService::class.java))
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            running = wanted
            runCatching {
                // startService, not startForegroundService: the latter obliges startForeground within seconds and the
                // system kills the app if the service stops first (a call ended at once, Android 14 refused the type).
                context.startService(
                    Intent(context, AloCallService::class.java)
                        .putExtra(EXTRA_TITLE, wanted.first)
                        .putExtra(EXTRA_VIDEO, wanted.second)
                )
            }.onFailure { running = null }
        }
    }
}
