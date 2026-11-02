package com.alo.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class AloScreenShareService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
            )
        }.onSuccess {
            foregroundReady?.complete(true)
        }.onFailure {
            foregroundReady?.complete(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.alo_logo)
            .setContentTitle("Alo")
            .setContentText("Идёт демонстрация экрана")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Демонстрация экрана",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Показывается только во время демонстрации экрана в звонке."
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "alo_screen_share"
        private const val NOTIFICATION_ID = 4102

        private var foregroundReady: CompletableDeferred<Boolean>? = null

        /**
         * Starts the service and waits until it is in the foreground. Android 14+ gives a screen capture only to an app
         * whose mediaProjection service already runs, and starting a service only asks for it: capture started right
         * after failed. Call after the person agreed in the system dialog.
         */
        suspend fun startAndAwait(context: Context): Boolean {
            val ready = CompletableDeferred<Boolean>()
            foregroundReady = ready
            val started = runCatching { start(context) }.isSuccess
            val result = started && withTimeoutOrNull(3_000) { ready.await() } == true
            foregroundReady = null
            return result
        }

        fun start(context: Context) {
            // See AloCallService.sync: a failed startForeground must not kill the app.
            context.startService(Intent(context, AloScreenShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AloScreenShareService::class.java))
        }
    }
}
