package com.alo.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject

/**
 * Debug builds only. Draws a notification from a push payload (the `data` of an FCM message) without Firebase, so the
 * notification and a tap on it can be checked on any emulator:
 *   adb shell am broadcast --include-stopped-packages -n com.alo.android/.AloDebugPushReceiver --es payload <base64 of the JSON>
 */
class AloDebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A real crash for checking AloCrashReporter: am broadcast … --ez crash true
        if (intent.getBooleanExtra("crash", false)) {
            Handler(Looper.getMainLooper()).post { throw IllegalStateException("Debug crash, mail test@example.com alo_session=abc") }
            return
        }
        // «Not responding»: the main thread stuck past the broadcast timeout (am broadcast --receiver-foreground … --ez hang true).
        if (intent.getBooleanExtra("hang", false)) {
            Thread.sleep(120_000)
            return
        }
        val raw = intent.getStringExtra("payload") ?: return
        val json = JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
        val data = json.keys().asSequence().associateWith { json.getString(it) }
        val appContext = context.applicationContext
        val pending = goAsync()
        // FirebaseMessagingService.onMessageReceived runs on a background thread too.
        Thread {
            try {
                AloFirebaseMessagingService.deliver(appContext, data, data["openToken"])
            } finally {
                pending.finish()
            }
        }.start()
    }
}
