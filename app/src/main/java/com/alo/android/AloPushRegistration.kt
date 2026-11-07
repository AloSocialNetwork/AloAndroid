package com.alo.android

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object AloPushRegistration {
    private const val PREFS = "alo-android"
    private const val TOKEN_KEY = "push_fcm_token_v1"
    private const val SOUNDS_KEY = "notification_sounds_enabled_v1"
    private const val PREVIEWS_KEY = "notification_previews_enabled_v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var backgroundApiClient: AloApiClient? = null

    /** Settings → Notifications → «Уведомления со звуком»; on by default, kept per device. */
    fun soundsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SOUNDS_KEY, true)

    /**
     * Stores the choice and tells the server. Every token registration (app start, new token) repeats the choice,
     * so a request that failed offline is not lost.
     */
    fun setSoundsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SOUNDS_KEY, enabled).apply()
        sync(context)
    }

    /**
     * Settings → Notifications → «Показывать текст» (Telegram's «Предпросмотр сообщений»); on by default, per device.
     * Off, a push names the chat and says only «Новое сообщение»: the server leaves the text out of it.
     */
    fun previewsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREVIEWS_KEY, true)

    fun setPreviewsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREVIEWS_KEY, enabled).apply()
        sync(context)
    }

    /** The server marks some pushes silent; with sounds switched off in settings every push is. */
    internal fun isSilent(serverSilent: Boolean, soundsEnabled: Boolean): Boolean = serverSilent || !soundsEnabled

    fun initialize(context: Context) {
        AloMessageNotifications.createChannels(context)
        if (!firebaseConfigured()) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        requestToken(context, attemptsLeft = 3)
    }

    // On a fresh install Play services may not be ready yet, and Firebase does not retry a failed registration by itself.
    private fun requestToken(context: Context, attemptsLeft: Int) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                storeToken(context, token)
                sync(context, token = token)
            }
            .addOnFailureListener {
                if (attemptsLeft <= 0) return@addOnFailureListener
                scope.launch {
                    delay(30_000)
                    requestToken(context, attemptsLeft - 1)
                }
            }
    }

    fun sync(context: Context, apiClient: AloApiClient? = null, token: String? = null) {
        val safeToken = token?.trim().orEmpty().ifBlank { storedToken(context) }
        if (safeToken.isBlank()) return
        scope.launch {
            val client = apiClient ?: backgroundClient(context)
            runCatching {
                client.registerPushToken(
                    token = safeToken,
                    deviceId = deviceId(context),
                    appVersion = BuildConfig.VERSION_NAME,
                    soundsEnabled = soundsEnabled(context),
                    previewsEnabled = previewsEnabled(context)
                )
            }
            // The other accounts saved on this phone get their pushes too, as in Telegram (each with its own session).
            val store = AloSecureAccountStore(context.applicationContext)
            val active = store.activeAccountId()
            for (account in store.accounts()) {
                if (account.userId == active) continue
                val cookie = store.sessionFor(account.userId) ?: continue
                runCatching {
                    client.registerPushTokenAs(
                        cookieHeader = cookie,
                        token = safeToken,
                        deviceId = deviceId(context),
                        appVersion = BuildConfig.VERSION_NAME,
                        soundsEnabled = soundsEnabled(context),
                        previewsEnabled = previewsEnabled(context)
                    )
                }
            }
        }
    }

    suspend fun unregister(context: Context, apiClient: AloApiClient) {
        val token = storedToken(context)
        if (token.isBlank()) return
        runCatching {
            apiClient.unregisterPushToken(token = token, deviceId = deviceId(context))
        }
    }

    fun onNewToken(context: Context, token: String) {
        storeToken(context, token)
        sync(context, token = token)
    }

    private fun firebaseConfigured(): Boolean =
        BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    /** One client for pushes, replies and avatars while the app itself may not be running. */
    internal fun backgroundClient(context: Context): AloApiClient =
        backgroundApiClient ?: synchronized(this) {
            backgroundApiClient ?: createApiClient(context.applicationContext).also { backgroundApiClient = it }
        }

    private fun createApiClient(context: Context): AloApiClient {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AloApiClient(
            baseUrl = AloServerConfiguration.apiBaseUrl,
            applicationContext = context.applicationContext,
            e2eeCrypto = AloE2eeCrypto(prefs),
            e2eeTrustStore = AloE2eeTrustStore(prefs),
            e2eeMediaCacheDir = File(context.cacheDir, "alo-e2ee-media"),
            libSignalPreferences = prefs
        )
    }

    private fun storeToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, token.trim())
            .apply()
    }

    private fun storedToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, "")
            .orEmpty()
            .trim()

    private fun deviceId(context: Context): String {
        return AloSecureAccountStore(context.applicationContext).installDeviceId()
    }

}

class AloFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AloPushRegistration.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Data-only pushes: the app draws every notification itself (AloMessageNotifications).
        val data = message.data.toMutableMap()
        message.notification?.let { notification ->
            notification.title?.let { data.putIfAbsent("title", it) }
            notification.body?.let { data.putIfAbsent("body", it) }
        }
        deliver(applicationContext, data, message.messageId)
    }

    companion object {
        internal fun deliver(context: Context, data: Map<String, String>, messageId: String?) {
            AloMessageNotifications.handle(context, data, messageId)
        }
    }
}
