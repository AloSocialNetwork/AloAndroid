package com.alo.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notifications built by the app from FCM data messages, as Telegram does (NotificationsController.showExtraNotifications):
 * one notification per chat in MessagingStyle with the sender's avatar and the last messages, a group summary
 * «N новых сообщений из M чатов», «Ответить» right from the shade, and an incoming call with the caller's avatar.
 * The server already decides what may be said (a secret chat is only «Новое сообщение»; «Показывать текст» off hides the text).
 */
object AloMessageNotifications {
    private const val TAG = "AloNotifications"
    private const val GROUP_KEY = "com.alo.android.MESSAGES"
    private const val SUMMARY_ID = 0x0A10_0001
    private const val SECRET_ID = 0x0A10_0002
    const val CHANNEL_ID = "alo_messages"
    const val SILENT_CHANNEL_ID = "alo_messages_silent"
    private const val CALL_CHANNEL_ID = "alo_calls"
    internal const val KEY_REPLY = "alo_reply_text"
    internal const val ACTION_REPLY = "com.alo.android.action.REPLY"
    internal const val ACTION_DECLINE_CALL = "com.alo.android.action.DECLINE_CALL"
    internal const val ACTION_MARK_READ = "com.alo.android.action.MARK_READ"
    // Alo's blue (AloUiPalette.accent) tints the small mark, as Telegram tints its plane 0xff11acfa.
    private const val ACCENT = 0xFF0A84FF.toInt()
    private const val EXTRA_CHAT_KEY = "alo_chat_key"
    private const val EXTRA_COUNT = "alo_message_count"
    private const val HIDDEN_TEXT = "Новое сообщение"
    // The same as AloApiClient.GROUP_CHAT_PEER_OFFSET: a group is peer 1_000_000_000 + its room id.
    private const val GROUP_PEER_OFFSET = 1_000_000_000
    // Telegram's colours of an avatar without a photo.
    private val AVATAR_COLORS = intArrayOf(
        0xFFE17076.toInt(), 0xFFFAA774.toInt(), 0xFFA695E7.toInt(), 0xFF7BC862.toInt(),
        0xFF6EC9CB.toInt(), 0xFF65AADD.toInt(), 0xFFEE7AAE.toInt()
    )

    /** The chat on screen right now (set by MainActivity while it is resumed): its messages ring nothing. */
    @Volatile
    var foregroundChatKey: (() -> String?)? = null

    internal fun chatKey(kind: String, peerId: Int, roomId: Int): String =
        if (roomId > 0 || kind == "group" || kind == "channel") "group:${if (roomId > 0) roomId else peerId - GROUP_PEER_OFFSET}"
        else "direct:$peerId"

    private fun notificationIdFor(key: String): Int = key.hashCode() and 0x7FFF_FFFF or 0x1000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Новые сообщения и важные события Alo"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SILENT_CHANNEL_ID, "Сообщения без звука", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Бесшумные сообщения Alo"
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "Звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Входящие звонки Alo"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Called from FirebaseMessagingService.onMessageReceived (a background thread with ~10 s to finish). */
    fun handle(context: Context, incoming: Map<String, String>, fcmMessageId: String?) {
        if (!canNotify(context)) return
        val data = withAccount(context, incoming) ?: return
        // An exception here would kill the app for a mere push; whatever failed, the person still gets the line.
        runCatching {
            createChannels(context)
            when (data["type"]) {
                "message" -> showMessage(context, data)
                "secret_message" -> showSecret(context, data)
                "incoming_call" -> showIncomingCall(context, data)
                else -> showEvent(context, data, fcmMessageId)
            }
        }.onFailure { error ->
            Log.w(TAG, "Rich notification failed, showing a plain one", error)
            runCatching { showPlain(context, data, fcmMessageId) }
        }
    }

    // The phone may hold several accounts (AloAccountSwitcher). A push names its account (accountId): one this phone has
    // signed out of shows nothing; with several accounts the notification says whose it is, as Telegram does; a push of
    // an account that is not open now gets no «Ответить»/«Прочитано» (they would act as the open one) and its tap
    // switches the app to that account.
    private const val KEY_ACCOUNT_LABEL = "_accountLabel"
    private const val KEY_FOREIGN = "_foreign"

    private fun withAccount(context: Context, data: Map<String, String>): Map<String, String>? {
        val id = data["accountId"]?.toIntOrNull() ?: 0
        if (id <= 0) return data
        val store = runCatching { AloSecureAccountStore(context.applicationContext) }.getOrNull() ?: return data
        val accounts = runCatching { store.accounts() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) return data
        val account = accounts.firstOrNull { it.userId == id } ?: return null
        val active = store.activeAccountId()
        return data + buildMap {
            if (accounts.size > 1) put(KEY_ACCOUNT_LABEL, account.displayName)
            if (active > 0 && active != id) put(KEY_FOREIGN, "1")
        }
    }

    private fun isForeign(data: Map<String, String>) = data[KEY_FOREIGN] == "1"

    private fun NotificationCompat.Builder.forAccount(data: Map<String, String>): NotificationCompat.Builder = apply {
        data[KEY_ACCOUNT_LABEL]?.takeIf(String::isNotBlank)?.let { setSubText(it) }
    }

    private fun showPlain(context: Context, data: Map<String, String>, fcmMessageId: String?) {
        val id = (fcmMessageId ?: data["openToken"] ?: System.nanoTime().toString()).hashCode() and 0x7FFF_FFFF
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setContentTitle(data["title"].orEmpty().ifBlank { "Alo" })
            .setContentText(data["body"].orEmpty().ifBlank { HIDDEN_TEXT })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, data, id))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun silentFor(context: Context, data: Map<String, String>): Boolean =
        AloPushRegistration.isSilent(serverSilent = data["silent"] == "1", soundsEnabled = AloPushRegistration.soundsEnabled(context))

    private fun openIntent(context: Context, data: Map<String, String>, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data["openToken"]?.let { putExtra("openToken", it) }
            data["payloadVersion"]?.let { putExtra("payloadVersion", it) }
            data["type"]?.let { putExtra("type", it) }
            data["callId"]?.let { putExtra("callId", it) }
            data["accountId"]?.let { putExtra("accountId", it) }
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showMessage(context: Context, data: Map<String, String>) {
        val chatKind = data["chatKind"].orEmpty()
        val peerId = data["peerId"]?.toIntOrNull() ?: 0
        val roomId = data["roomId"]?.toIntOrNull() ?: 0
        if (peerId <= 0 && roomId <= 0) return showEvent(context, data, null)
        // The same chat id in another account of the phone is another chat.
        val key = chatKey(chatKind, peerId, roomId) + if (isForeign(data)) "@${data["accountId"]}" else ""
        if (foregroundChatKey?.invoke() == key) return
        val id = notificationIdFor(key)
        val title = data["title"].orEmpty().ifBlank { "Alo" }
        val group = chatKind == "group"
        val channel = chatKind == "channel"
        // Hidden on this device even if the server has not heard of the switch yet.
        val text = if (AloPushRegistration.previewsEnabled(context)) data["text"].orEmpty().ifBlank { HIDDEN_TEXT } else HIDDEN_TEXT
        val senderName = data["senderName"].orEmpty().ifBlank { title }
        val senderId = data["senderId"]?.toIntOrNull() ?: 0
        val silent = silentFor(context, data)

        val chatBitmap = avatarBitmap(context, data["chatAvatar"].orEmpty(), title, roomId.takeIf { it > 0 } ?: peerId)
        val chatIcon = IconCompat.createWithBitmap(chatBitmap)
        val sender = Person.Builder()
            .setName(if (channel) title else senderName)
            .setKey(if (channel) key else "user:${senderId.takeIf { it > 0 } ?: peerId}")
            .setIcon(
                if (group) avatarIcon(context, data["senderAvatar"].orEmpty(), senderName, senderId) else chatIcon
            )
            .build()
        val manager = NotificationManagerCompat.from(context)
        val previous = activeNotification(context, id)
        val style = previous?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("Вы").setKey("me").build())
        if (group || channel) {
            style.conversationTitle = title
            style.isGroupConversation = group
        }
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), sender))
        while (style.messages.size > 7) style.messages.removeAt(0)
        val count = (previous?.extras?.getInt(EXTRA_COUNT) ?: 0) + 1

        // A long-lived shortcut makes it a conversation notification (Android 11+): the avatar in the shade and the
        // «Разговоры» section, as Telegram's notifications are.
        val shortcutReady = runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, key)
                    .setShortLabel(title.take(25).ifBlank { "Alo" })
                    .setLongLabel(title)
                    .setIcon(chatIcon)
                    .setLongLived(true)
                    .setIsConversation()
                    .apply { if (!group && !channel) setPerson(sender) }
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .build()
            )
        }.isSuccess
        val builder = NotificationCompat.Builder(context, if (silent) SILENT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setStyle(style)
            .apply { if (shortcutReady) setShortcutId(key) }
            .setContentTitle(title)
            .setContentText(if (group) "$senderName: $text" else text)
            // The round avatar of the chat beside the text and Alo's own mark in the corner, as Telegram shows them.
            .setLargeIcon(chatBitmap)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setSortKey((Long.MAX_VALUE - System.currentTimeMillis()).toString())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setAutoCancel(true)
            .setNumber(count)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openIntent(context, data, id))
            .addExtras(Bundle().apply {
                putString(EXTRA_CHAT_KEY, key)
                putInt(EXTRA_COUNT, count)
            })
        builder.forAccount(data)
        if (!channel && !isForeign(data)) {
            val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Сообщение").build()
            val replyIntent = Intent(context, AloNotificationActionReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra("chatKind", if (group) "group" else "direct")
                putExtra("peerId", if (roomId > 0) GROUP_PEER_OFFSET + roomId else peerId)
                putExtra("roomId", roomId)
                putExtra("notificationId", id)
            }
            val pending = PendingIntent.getBroadcast(
                context, id, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_alo, "Ответить", pending)
                    .addRemoteInput(reply)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build()
            )
        }
        // «Прочитано» (Telegram's MarkAsRead): the chat is read on the server and its notification goes.
        if (!isForeign(data)) addMarkRead(context, builder, group || channel, roomId, peerId, id)
        runCatching { manager.notify(id, builder.build()) }
        updateSummary(context)
    }

    private fun addMarkRead(context: Context, builder: NotificationCompat.Builder, group: Boolean, roomId: Int, peerId: Int, id: Int) {
        val readIntent = Intent(context, AloNotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra("chatKind", if (group) "group" else "direct")
            putExtra("peerId", if (roomId > 0) GROUP_PEER_OFFSET + roomId else peerId)
            putExtra("roomId", roomId)
            putExtra("notificationId", id)
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_alo, "Прочитано",
                PendingIntent.getBroadcast(context, id, readIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        )
    }

    // A secret chat: Telegram's NotificationHiddenMessage — no name, no text, one notification for all of them.
    private fun showSecret(context: Context, data: Map<String, String>) {
        val previous = activeNotification(context, SECRET_ID)
        val count = (previous?.extras?.getInt(EXTRA_COUNT) ?: 0) + 1
        val silent = silentFor(context, data)
        val notification = NotificationCompat.Builder(context, if (silent) SILENT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setContentTitle("Alo")
            .setContentText(if (count > 1) "$count ${newMessages(count)}" else HIDDEN_TEXT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setAutoCancel(true)
            .setNumber(count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setContentIntent(openIntent(context, data, SECRET_ID))
            .addExtras(Bundle().apply { putInt(EXTRA_COUNT, count) })
            .forAccount(data)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SECRET_ID, notification) }
        updateSummary(context)
    }

    // Friend requests, likes, invitations: «Имя» / «Поставил(а) лайк…».
    private fun showEvent(context: Context, data: Map<String, String>, fcmMessageId: String?) {
        val title = data["title"].orEmpty().ifBlank { "Alo" }
        val body = data["body"].orEmpty().ifBlank { "Новое уведомление" }
        val id = (fcmMessageId ?: data["openToken"] ?: System.nanoTime().toString()).hashCode() and 0x7FFF_FFFF
        val silent = silentFor(context, data)
        val avatar = data["chatAvatar"].orEmpty()
        val notification = NotificationCompat.Builder(context, if (silent) SILENT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .apply { if (avatar.isNotBlank() || title != "Alo") setLargeIcon(avatarBitmap(context, avatar, title, title.hashCode())) }
            .setPriority(if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, data, id))
            .forAccount(data)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    // «Входящий звонок» / «Входящий видеозвонок» with the caller's name and avatar (Telegram's VoIP notification).
    private fun showIncomingCall(context: Context, data: Map<String, String>) {
        val callId = data["callId"]?.toIntOrNull() ?: return
        val name = data["callerName"].orEmpty().ifBlank { data["title"].orEmpty() }.ifBlank { "Собеседник" }
        val video = data["callType"] == "video"
        val id = callNotificationId(callId)
        val caller = Person.Builder()
            .setName(name)
            .setKey("user:${data["callerId"].orEmpty()}")
            .setIcon(avatarIcon(context, data["callerAvatar"].orEmpty(), name, data["callerId"]?.toIntOrNull() ?: callId))
            .setImportant(true)
            .build()
        val answer = openIntent(context, data, id)
        val decline = PendingIntent.getBroadcast(
            context, id,
            Intent(context, AloNotificationActionReceiver::class.java).apply {
                action = ACTION_DECLINE_CALL
                putExtra("callId", callId)
                // Another account of the phone: declining here would end it as the open account, so it only goes quiet.
                putExtra("localOnly", isForeign(data))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setContentTitle(name)
            .setContentText(if (video) "Входящий видеозвонок" else "Входящий звонок")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(video))
            .setLargeIcon(avatarBitmap(context, data["callerAvatar"].orEmpty(), name, callId))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(answer)
            .setFullScreenIntent(answer, true)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .forAccount(data)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    internal fun callNotificationId(callId: Int): Int = 0x0C00_0000 or (callId and 0x00FF_FFFF)

    /** The call is answered, declined or over: its ringing notification goes. */
    fun cancelCall(context: Context, callId: Int) {
        NotificationManagerCompat.from(context).cancel(callNotificationId(callId))
    }

    fun cancelAllCalls(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.activeNotifications.filter { it.id and 0x7F00_0000 == 0x0C00_0000 }.forEach { manager.cancel(it.id) } }
    }

    /** The chat was opened: its notification is read (Telegram clears it the same way). */
    fun cancelChat(context: Context, kind: String, peerId: Int, roomId: Int) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdFor(chatKey(kind, peerId, roomId)))
            updateSummary(context)
        }
    }

    internal fun refreshSummary(context: Context) = updateSummary(context)

    // The group header: «5 новых сообщений из 2 чатов» with a line per chat.
    private fun updateSummary(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val chats = manager.activeNotifications.filter {
            it.id != SUMMARY_ID && it.notification.group == GROUP_KEY
        }
        // Telegram bundles chats under a summary only when there are several of them (useSummaryNotification).
        // A summary already shown stays for the last chat: cancelling it would take its children with it.
        if (chats.isEmpty()) {
            manager.cancel(SUMMARY_ID)
            return
        }
        if (chats.size < 2 && manager.activeNotifications.none { it.id == SUMMARY_ID }) return
        val total = chats.sumOf { it.notification.extras.getInt(EXTRA_COUNT).coerceAtLeast(1) }
        val summaryText = if (chats.size > 1) "$total ${newMessages(total)} из ${chats.size} ${chatsWord(chats.size)}"
        else "$total ${newMessages(total)}"
        val inbox = NotificationCompat.InboxStyle().setSummaryText(summaryText)
        chats.take(6).forEach { item ->
            val extras = item.notification.extras
            val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
            inbox.addLine(if (title.isNotBlank()) "$title: $text" else text)
        }
        val summary = NotificationCompat.Builder(context, SILENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setContentTitle("Alo")
            .setContentText(summaryText)
            .setStyle(inbox)
            .setNumber(total)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, SUMMARY_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary) }
    }

    /** After «Ответить»: the chat's notification shows the sent reply (or the error) and stops spinning. */
    internal fun showReplyResult(context: Context, notificationId: Int, replyText: String, error: String?) {
        val previous = activeNotification(context, notificationId) ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(previous) ?: return
        if (error == null) {
            style.addMessage(NotificationCompat.MessagingStyle.Message(replyText, System.currentTimeMillis(), null as Person?))
        }
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) previous.channelId else SILENT_CHANNEL_ID
        val rebuilt = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alo)
            .setColor(ACCENT)
            .setStyle(style)
            .setContentTitle(previous.extras.getCharSequence(NotificationCompat.EXTRA_TITLE))
            .setContentText(error ?: replyText)
            .setSubText(error)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(previous.contentIntent)
            .setLargeIcon(previous.getLargeIcon())
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setShortcutId(previous.shortcutId) }
            .addExtras(Bundle().apply {
                putString(EXTRA_CHAT_KEY, previous.extras.getString(EXTRA_CHAT_KEY))
                putInt(EXTRA_COUNT, previous.extras.getInt(EXTRA_COUNT))
            })
            .apply { previous.actions?.forEach { addAction(NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_stat_alo), it.title, it.actionIntent
            ).apply { it.remoteInputs?.forEach { input -> addRemoteInput(RemoteInput.Builder(input.resultKey).setLabel(input.label).build()) } }.build()) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, rebuilt) }
    }

    private fun activeNotification(context: Context, id: Int): android.app.Notification? =
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == id }
            ?.notification

    internal fun newMessages(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "новое сообщение"
            mod10 in 2..4 && mod100 !in 12..14 -> "новых сообщения"
            else -> "новых сообщений"
        }
    }

    private fun chatsWord(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return if (mod10 == 1 && mod100 != 11) "чата" else "чатов"
    }

    // ---- Avatars: the photo (downloaded with the session cookie), or Telegram's initials on a coloured circle.

    private fun avatarIcon(context: Context, path: String, name: String, seed: Int): IconCompat =
        IconCompat.createWithBitmap(avatarBitmap(context, path, name, seed))

    private fun avatarBitmap(context: Context, path: String, name: String, seed: Int): Bitmap {
        val size = (context.resources.displayMetrics.density * 64).toInt().coerceIn(96, 256)
        val photo = if (path.isNotBlank()) loadPhoto(context, path, size) else null
        return roundAvatar(photo, name, seed, size)
    }

    private fun loadPhoto(context: Context, path: String, size: Int): Bitmap? {
        val bytes = runCatching {
            runBlocking { withTimeoutOrNull(6_000) { AloPushRegistration.backgroundClient(context).downloadNotificationImage(path) } }
        }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    internal fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
            .joinToString("") { word -> word.codePointAt(0).let { String(Character.toChars(it)) } }
            .uppercase()

    private fun roundAvatar(photo: Bitmap?, name: String, seed: Int, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        canvas.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (photo != null) {
            val side = minOf(photo.width, photo.height)
            val source = Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
            canvas.drawBitmap(photo, source, Rect(0, 0, size, size), paint)
        } else {
            canvas.drawColor(AVATAR_COLORS[Math.floorMod(seed, AVATAR_COLORS.size)])
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = size * 0.4f
            val letters = initials(name).ifBlank { "A" }
            val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letters, size / 2f, baseline, paint)
        }
        return output
    }
}

/** «Ответить» from the shade and «Отклонить» of a ringing call. */
class AloNotificationActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        when (intent.action) {
            AloMessageNotifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AloMessageNotifications.KEY_REPLY)?.toString()?.trim().orEmpty()
                val notificationId = intent.getIntExtra("notificationId", 0)
                if (text.isBlank()) return pending.finish()
                scope.launch {
                    val result = runCatching {
                        AloPushRegistration.backgroundClient(appContext).sendMessage(
                            kind = intent.getStringExtra("chatKind") ?: "direct",
                            peerId = intent.getIntExtra("peerId", 0),
                            roomId = intent.getIntExtra("roomId", 0),
                            body = text
                        )
                    }
                    runCatching {
                        AloMessageNotifications.showReplyResult(
                            appContext, notificationId, text,
                            result.exceptionOrNull()?.let { "Не отправлено: ${it.message ?: "нет сети"}" }
                        )
                    }
                    pending.finish()
                }
            }
            AloMessageNotifications.ACTION_MARK_READ -> {
                val notificationId = intent.getIntExtra("notificationId", 0)
                runCatching {
                    NotificationManagerCompat.from(appContext).cancel(notificationId)
                    AloMessageNotifications.refreshSummary(appContext)
                }
                scope.launch {
                    runCatching {
                        AloPushRegistration.backgroundClient(appContext).loadConversation(
                            kind = intent.getStringExtra("chatKind") ?: "direct",
                            peerId = intent.getIntExtra("peerId", 0),
                            roomId = intent.getIntExtra("roomId", 0),
                            markRead = true
                        )
                    }
                    pending.finish()
                }
            }
            AloMessageNotifications.ACTION_DECLINE_CALL -> {
                val callId = intent.getIntExtra("callId", 0)
                AloMessageNotifications.cancelCall(appContext, callId)
                scope.launch {
                    if (callId > 0 && !intent.getBooleanExtra("localOnly", false)) {
                        runCatching { AloPushRegistration.backgroundClient(appContext).endDirectCall(callId, "declined") }
                    }
                    pending.finish()
                }
            }
            else -> pending.finish()
        }
    }
}
