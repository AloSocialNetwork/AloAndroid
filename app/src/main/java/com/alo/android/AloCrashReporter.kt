package com.alo.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Tells the server why the app fell, so a crash on someone's phone can be read without a cable. A crash is written to a
 * file on the spot and sent at the next start; Android 11+ also says what the app could not catch itself: «not
 * responding» and crashes in native code. It says nothing of who it happened to: no account, no message text. Exception
 * messages may quote data, so they are shortened and scrubbed (see [scrub]), and the ones that quote it by design are left out.
 * Nothing here may ever make things worse: every step swallows its own failure, and sending uses no session and no WebView.
 */
internal object AloCrashReporter {
    private const val DIRECTORY = "crash-reports"
    private const val PREFS = "alo-crash-reports"
    private const val LAST_EXIT_KEY = "last_exit_timestamp"
    private const val MAX_FILES = 20
    private const val MAX_STACK = 24_000
    private const val MAX_MESSAGE = 200
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 40
    private const val SEND_DELAY_MS = 5_000L

    @Volatile private var recentEvent = ""
    @Volatile private var recentEventAt = 0L

    /** What just happened, reported with a crash that follows it («push_tap»: it fell right after a tap on a push). */
    fun note(event: String) {
        recentEvent = event
        recentEventAt = System.currentTimeMillis()
    }

    /** Called first thing in the Application, before anything that can fall. */
    fun install(context: Context) {
        val directory = File(context.filesDir, DIRECTORY)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(directory, describe(thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Sends what the last runs left behind, a few seconds later and off the main thread. */
    fun sendPending(context: Context) {
        val appContext = context.applicationContext
        Thread {
            runCatching {
                Thread.sleep(SEND_DELAY_MS)
                val directory = File(appContext.filesDir, DIRECTORY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) collectExitReasons(appContext, directory)
                send(directory)
            }
        }.apply { name = "alo-crash-reports"; isDaemon = true }.start()
    }

    internal fun describe(threadName: String, error: Throwable): JSONObject {
        val eventAge = if (recentEventAt > 0) (System.currentTimeMillis() - recentEventAt) / 1000 else 0
        return base("crash", System.currentTimeMillis())
            .put("thread", threadName.take(120))
            .put("exception", rootOf(error).javaClass.name)
            .put("stack", stackOf(error).take(MAX_STACK))
            .put("recentEvent", recentEvent)
            .put("recentEventSeconds", eventAge)
    }

    private fun base(kind: String, occurredAtMillis: Long): JSONObject = JSONObject()
        .put("kind", kind)
        .put("occurredAt", Instant.ofEpochMilli(occurredAtMillis).toString())
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("osVersion", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER.orEmpty())
        .put("model", Build.MODEL.orEmpty())

    private fun rootOf(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < MAX_CAUSES) {
            current = current.cause!!
            depth += 1
        }
        return current
    }

    /** The chain of causes, not printStackTrace: only frames and a scrubbed message go out. */
    internal fun stackOf(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth <= MAX_CAUSES) {
            if (depth > 0) append("Caused by: ")
            append(current.javaClass.name)
            messageOf(current)?.let { append(": ").append(it) }
            append('\n')
            current.stackTrace.take(MAX_FRAMES).forEach { append("  at ").append(it).append('\n') }
            current = current.cause?.takeIf { it !== current }
            depth += 1
        }
    }

    // org.json quotes the text it could not parse, and that text can be a message.
    private fun messageOf(error: Throwable): String? {
        if (error.javaClass.name.startsWith("org.json.")) return null
        return error.message?.let(::scrub)?.takeIf { it.isNotBlank() }
    }

    internal fun scrub(message: String): String = message
        .replace(Regex("alo_session=[^;\\s]+"), "alo_session=…")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"), "…@…")
        .replace(Regex("\\b[A-Fa-f0-9]{32,}\\b"), "…")
        .replace(Regex("\\?[^\\s]*"), "?…")
        .take(MAX_MESSAGE)

    private fun write(directory: File, report: JSONObject) {
        directory.mkdirs()
        val name = "${System.currentTimeMillis()}-${report.optString("kind")}"
        val temporary = File(directory, ".$name.tmp")
        temporary.writeText(report.toString())
        temporary.renameTo(File(directory, "$name.json"))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectExitReasons(context: Context, directory: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(LAST_EXIT_KEY, 0)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
        exits.filter { it.timestamp > last }.forEach { exit ->
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                // REASON_CRASH: the handler in install() has already written it, with a better stack.
                else -> return@forEach
            }
            runCatching { write(directory, describeExit(kind, exit)) }
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(LAST_EXIT_KEY, it).apply() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeExit(kind: String, exit: ApplicationExitInfo): JSONObject {
        val trace = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        val stack = when {
            trace == null -> ""
            kind == "anr" -> mainThreadOf(String(trace, Charsets.UTF_8))
            else -> nativeFramesOf(trace)
        }
        return base(kind, exit.timestamp)
            .put("thread", if (kind == "anr") "main" else "")
            .put("exception", (exit.description ?: "").let(::scrub).ifBlank { kind } + if (kind == "native") " (signal ${exit.status})" else "")
            .put("stack", stack.take(MAX_STACK))
    }

    /** An ANR dump holds every thread; the main one is what was stuck. */
    internal fun mainThreadOf(dump: String): String {
        val start = dump.indexOf("\"main\"")
        if (start < 0) return ""
        val end = dump.indexOf("\n\n", start).takeIf { it > 0 } ?: dump.length
        return dump.substring(start, end)
    }

    // A tombstone is binary (protobuf). Only the names of libraries are taken: its memory dumps could hold anything.
    private fun nativeFramesOf(bytes: ByteArray): String =
        Regex("/[\\w./~=+-]+\\.(?:so|apk|oat)").findAll(String(bytes, Charsets.ISO_8859_1))
            .map { it.value }
            .distinct()
            .take(60)
            .joinToString("\n")

    private fun send(directory: File) {
        val files = directory.listFiles { file -> file.name.endsWith(".json") }?.sortedByDescending { it.name }.orEmpty()
        files.drop(MAX_FILES).forEach { it.delete() }
        files.take(MAX_FILES).chunked(10).forEach { batch ->
            val reports = JSONArray()
            val readable = batch.filter { file ->
                runCatching { reports.put(JSONObject(file.readText())) }.onFailure { file.delete() }.isSuccess
            }
            if (reports.length() == 0) return@forEach
            // Kept for the next start if the server cannot be reached.
            if (!post(JSONObject().put("platform", "android").put("reports", reports).toString())) return
            readable.forEach { it.delete() }
        }
    }

    private fun post(body: String): Boolean {
        val connection = URL(AloServerConfiguration.apiUrl("/api/client/crash-reports")).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
