package com.alo.android

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AloAppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val size: Long,
    val sha256: String,
    val url: String
) {
    val fileName: String get() = url.substringAfterLast('/')
}

internal sealed interface AloUpdatePrompt {
    data class Offer(val release: AloAppRelease) : AloUpdatePrompt
    data class Downloading(val release: AloAppRelease, val done: Long, val total: Long) : AloUpdatePrompt
    data class Verifying(val release: AloAppRelease) : AloUpdatePrompt
    data class Ready(val release: AloAppRelease) : AloUpdatePrompt
    data class NeedsPermission(val release: AloAppRelease) : AloUpdatePrompt
    data class Failed(val release: AloAppRelease, val message: String) : AloUpdatePrompt
    data object UpToDate : AloUpdatePrompt
}

internal val LocalAloAppUpdater = staticCompositionLocalOf<AloAppUpdater?> { null }

/**
 * Updates the app from our own server (it is not in Google Play). On every opening it asks the server for the latest version
 * and, if that is newer, offers it; the system's DownloadManager fetches the APK, so the download survives leaving the app and
 * continues after a bad connection, and Android's installer does the rest. Android installs it over the app only if the
 * signature matches, so the server cannot put anything else on the phone; the hash only catches a damaged file early.
 */
internal class AloAppUpdater(private val context: Context, private val scope: CoroutineScope) {
    var prompt by mutableStateOf<AloUpdatePrompt?>(null)
        private set

    /** Test builds talk to throwaway servers: they ask only when a check says so (`update_check=1`, see [AloDebugServer]). */
    private val enabled = !BuildConfig.ALO_DEV_HOOKS || AloDebugServer.checkUpdates
    private val prefs by lazy { context.getSharedPreferences("alo-update", Context.MODE_PRIVATE) }
    private val downloads by lazy { context.getSystemService(DownloadManager::class.java) }
    private val folder get() = context.getExternalFilesDir("updates")
    private var job: Job? = null
    private var minimized = false
    private var visible = false

    /** MainActivity.onStart: the app was opened. */
    fun onAppOpened() {
        visible = true
        if (!enabled || job?.isActive == true || prompt != null) return
        val now = System.currentTimeMillis()
        if (now - Session.lastCheckAt < CHECK_EVERY_MS || now - Session.laterAt < LATER_MS) return
        Session.lastCheckAt = now
        job = scope.launch { check(manual = false) }
    }

    fun onAppHidden() {
        visible = false
    }

    /** MainActivity.onResume: back from the system's «install from this app» screen. */
    fun onAppResumed() {
        (prompt as? AloUpdatePrompt.NeedsPermission)?.takeIf { canInstall() }?.let { install(it.release) }
    }

    /** «Версия …» in Settings. */
    fun checkNow() {
        if (!enabled || job?.isActive == true) return
        Session.laterAt = 0
        Session.lastCheckAt = System.currentTimeMillis()
        minimized = false
        job = scope.launch { check(manual = true) }
    }

    fun download(release: AloAppRelease) {
        job?.cancel()
        minimized = false
        job = scope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                downloadDirectly(release)
                return@launch
            }
            val request = DownloadManager.Request(Uri.parse(AloServerConfiguration.apiUrl(release.url)))
                .setTitle("Alo ${release.versionName}")
                .setDescription("Обновление приложения")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val id = try {
                withContext(Dispatchers.IO) {
                    forgetPending()
                    File(folder, release.fileName).delete()
                    request.setDestinationInExternalFilesDir(context, "updates", release.fileName)
                    downloads.enqueue(request)
                }
            } catch (_: Exception) {
                // No external storage, or the system's downloader is switched off: the browser can still fetch the file.
                prompt = null
                openInBrowser(release)
                return@launch
            }
            prefs.edit().putLong(KEY_ID, id).putInt(KEY_CODE, release.versionCode).putString(KEY_SHA, release.sha256).apply()
            follow(release, id, installWhenDone = true)
        }
    }

    /** The dialog is closed; the download goes on, with its notification, and asks again when it is done. */
    fun minimize() {
        minimized = true
        prompt = null
    }

    fun cancelDownload() {
        job?.cancel()
        minimized = false
        scope.launch { withContext(Dispatchers.IO) { forgetPending() } }
        later()
    }

    fun later() {
        Session.laterAt = System.currentTimeMillis()
        prompt = null
    }

    fun dismiss() {
        prompt = null
    }

    fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(release: AloAppRelease) {
        if (!canInstall()) {
            prompt = AloUpdatePrompt.NeedsPermission(release)
            return
        }
        prompt = AloUpdatePrompt.Ready(release)
        val file = File(folder, release.fileName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            prompt = AloUpdatePrompt.Failed(release, "На телефоне не нашлось установщика приложений.")
        }
    }

    private suspend fun check(manual: Boolean) {
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        if (release.isFailure) {
            if (manual) prompt = AloUpdatePrompt.Failed(UNKNOWN, "Не удалось связаться с сервером.")
            return
        }
        val latest = release.getOrNull()
        if (latest == null || latest.versionCode <= BuildConfig.VERSION_CODE) {
            withContext(Dispatchers.IO) { forgetPending() }
            if (manual) prompt = AloUpdatePrompt.UpToDate
            return
        }
        // A download from an earlier opening: the app may have been closed or killed meanwhile.
        val id = prefs.getLong(KEY_ID, 0)
        val same = id != 0L && prefs.getInt(KEY_CODE, 0) == latest.versionCode && prefs.getString(KEY_SHA, "") == latest.sha256
        val status = if (same) withContext(Dispatchers.IO) { readDownload(id) } else null
        when (status?.state) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                follow(latest, id, installWhenDone = false)
            DownloadManager.STATUS_SUCCESSFUL -> finish(latest, installWhenDone = false)
            else -> {
                withContext(Dispatchers.IO) { forgetPending() }
                prompt = AloUpdatePrompt.Offer(latest)
            }
        }
    }

    private suspend fun follow(release: AloAppRelease, id: Long, installWhenDone: Boolean) {
        while (scope.isActive) {
            val status = withContext(Dispatchers.IO) { readDownload(id) }
            when (status?.state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    finish(release, installWhenDone && visible && !minimized)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    val message = if (status.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
                        "Не хватает места на телефоне. Нужно ${aloUpdateSize(release.size)}."
                    } else {
                        "Не удалось скачать обновление. Проверьте интернет и попробуйте ещё раз."
                    }
                    withContext(Dispatchers.IO) { forgetPending() }
                    minimized = false
                    prompt = AloUpdatePrompt.Failed(release, message)
                    return
                }
                null -> {
                    // Cancelled from the notification.
                    forgetPendingRecord()
                    minimized = false
                    prompt = null
                    return
                }
                else -> if (!minimized) {
                    prompt = AloUpdatePrompt.Downloading(release, status.done, status.total.takeIf { it > 0 } ?: release.size)
                }
            }
            delay(if (visible && !minimized) 300 else 2_000)
        }
    }

    /**
     * Android 7.0: the system's DownloadManager does not trust our certificate (only this app does, network_security_config),
     * so the app fetches the file itself. No resuming and no notification, but a phone this old gets its update.
     */
    private suspend fun downloadDirectly(release: AloAppRelease) {
        val done = try {
            withContext(Dispatchers.IO) {
                forgetPending()
                val file = File(folder ?: context.cacheDir, release.fileName)
                val connection = URL(AloServerConfiguration.apiUrl(release.url)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var copied = 0L
                            var shownAt = 0L
                            while (scope.isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                val now = System.currentTimeMillis()
                                if (!minimized && now - shownAt > 300) {
                                    shownAt = now
                                    prompt = AloUpdatePrompt.Downloading(release, copied, release.size)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                true
            }
        } catch (error: java.io.IOException) {
            false
        } catch (error: IllegalStateException) {
            false
        }
        if (done) {
            finish(release, installWhenDone = visible && !minimized)
        } else {
            minimized = false
            prompt = AloUpdatePrompt.Failed(release, "Не удалось скачать обновление. Проверьте интернет и попробуйте ещё раз.")
        }
    }

    private suspend fun finish(release: AloAppRelease, installWhenDone: Boolean) {
        if (!minimized) prompt = AloUpdatePrompt.Verifying(release)
        val good = withContext(Dispatchers.IO) { verify(release) }
        minimized = false
        if (!good) {
            withContext(Dispatchers.IO) { forgetPending() }
            prompt = AloUpdatePrompt.Failed(release, "Файл обновления скачался с ошибкой. Попробуйте ещё раз.")
            return
        }
        if (installWhenDone) install(release) else prompt = AloUpdatePrompt.Ready(release)
    }

    private fun verify(release: AloAppRelease): Boolean {
        val file = File(folder, release.fileName)
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) return false
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return info.packageName == context.packageName && code == release.versionCode.toLong()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun openInBrowser(release: AloAppRelease) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(AloServerConfiguration.apiUrl(release.url)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun fetchLatest(): AloAppRelease? {
        val connection = URL(AloServerConfiguration.apiUrl("/api/client/android-release")).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val text = connection.inputStream.use { it.readBytes().decodeToString() }
            return parseAloAppRelease(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private class DownloadStatus(val state: Int, val reason: Int, val done: Long, val total: Long)

    private fun readDownload(id: Long): DownloadStatus? {
        return downloads.query(DownloadManager.Query().setFilterById(id))?.use { row ->
            if (!row.moveToFirst()) return null
            DownloadStatus(
                state = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                done = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
    }

    /** Removes an unfinished or already installed download with its file. */
    private fun forgetPending() {
        val id = prefs.getLong(KEY_ID, 0)
        if (id != 0L) runCatching { downloads.remove(id) }
        forgetPendingRecord()
        folder?.listFiles()?.forEach { it.delete() }
    }

    private fun forgetPendingRecord() {
        prefs.edit().clear().apply()
    }

    /** One per run of the app: leaving and coming back in a minute asks nothing, and «Позже» lasts a while. */
    private object Session {
        var lastCheckAt = 0L
        var laterAt = 0L
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
        const val LATER_MS = 6 * 60 * 60 * 1000L
        const val KEY_ID = "download_id"
        const val KEY_CODE = "version_code"
        const val KEY_SHA = "sha256"
        val UNKNOWN = AloAppRelease(0, "", "", 0, "", "")
    }
}

/** The server's answer; anything that does not point into our downloads folder is ignored. */
internal fun parseAloAppRelease(response: JSONObject): AloAppRelease? {
    val release = response.optJSONObject("release") ?: return null
    val url = release.optString("url")
    val sha256 = release.optString("sha256")
    val code = release.optInt("versionCode")
    val size = release.optLong("size")
    if (code <= 0 || size <= 0 || sha256.length != 64) return null
    if (!Regex("^/downloads/android/alo-[A-Za-z0-9._-]+\\.apk$").matches(url)) return null
    return AloAppRelease(
        versionCode = code,
        versionName = release.optString("versionName"),
        notes = release.optString("notes"),
        size = size,
        sha256 = sha256.lowercase(),
        url = url
    )
}

private fun aloUpdateSize(bytes: Long): String = "${(bytes + 512 * 1024) / (1024 * 1024)} МБ"

@Composable
internal fun AloAppUpdateDialog(updater: AloAppUpdater) {
    val prompt = updater.prompt ?: return
    @Composable
    fun button(title: String, onClick: () -> Unit) = TextButton(onClick = onClick) { Text(title, color = AloNativeAppAccent) }
    when (prompt) {
        is AloUpdatePrompt.Offer -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Вышло обновление") },
            text = {
                val release = prompt.release
                Text(
                    buildString {
                        append("Версия ${release.versionName}, ${aloUpdateSize(release.size)}.")
                        if (release.notes.isNotBlank()) append("\n\n").append(release.notes)
                    }
                )
            },
            dismissButton = { button("Позже", updater::later) },
            confirmButton = { button("Обновить") { updater.download(prompt.release) } }
        )
        is AloUpdatePrompt.Downloading -> AlertDialog(
            onDismissRequest = updater::minimize,
            title = { Text("Скачиваю обновление") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { (prompt.done.toFloat() / prompt.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AloNativeAppAccent
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("${aloUpdateSize(prompt.done)} из ${aloUpdateSize(prompt.total)}")
                }
            },
            dismissButton = { button("Отменить", updater::cancelDownload) },
            confirmButton = { button("Свернуть", updater::minimize) }
        )
        is AloUpdatePrompt.Verifying -> AlertDialog(
            onDismissRequest = updater::minimize,
            title = { Text("Проверяю файл") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = AloNativeAppAccent) },
            confirmButton = {}
        )
        is AloUpdatePrompt.Ready -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Обновление скачано") },
            text = { Text("Версия ${prompt.release.versionName} готова к установке. Ваши чаты и вход в аккаунт сохранятся.") },
            dismissButton = { button("Позже", updater::later) },
            confirmButton = { button("Установить") { updater.install(prompt.release) } }
        )
        is AloUpdatePrompt.NeedsPermission -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Нужно разрешение") },
            text = { Text("Android спросит, можно ли устанавливать приложения из Alo. Включите «Разрешить» и вернитесь назад: установка продолжится сама. Понадобится один раз.") },
            dismissButton = { button("Позже", updater::later) },
            confirmButton = { button("Открыть настройки", updater::openPermissionSettings) }
        )
        is AloUpdatePrompt.Failed -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Обновление") },
            text = { Text(prompt.message) },
            dismissButton = if (prompt.release.versionCode > 0) ({ button("Позже", updater::later) }) else null,
            confirmButton = {
                if (prompt.release.versionCode > 0) button("Повторить") { updater.download(prompt.release) }
                else button("Понятно", updater::dismiss)
            }
        )
        AloUpdatePrompt.UpToDate -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Обновлений нет") },
            text = { Text("У вас последняя версия: ${BuildConfig.VERSION_NAME}.") },
            confirmButton = { button("Понятно", updater::dismiss) }
        )
    }
}

/** Declared in the manifest apart from the camera's provider, so each hands out only its own folder. */
class AloUpdateFileProvider : FileProvider()
