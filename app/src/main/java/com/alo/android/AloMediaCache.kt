package com.alo.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/** iOS's AloMediaNetwork: what the connection allows an automatic download to do. */
enum class AloMediaNetwork { Wifi, Cellular, Constrained, Offline }

/**
 * iOS's AloMediaDownloadPreset (thresholds follow Telegram's MediaAutoDownloadSettings): which downloaded
 * attachments a connection may fetch on its own. Voice messages and small photos always fit; the rest follows the size.
 */
enum class AloMediaDownloadPreset(val title: String) {
    Off("Выключена"),
    Low("Мало данных"),
    Medium("Средний расход"),
    High("Высокий расход");

    /** [type] is the attachment's type or MIME; [size] is the plaintext size in bytes, `null` when nobody told us. */
    fun permits(type: String, size: Long?): Boolean {
        if (this == Off) return false
        val mb = 1_024L * 1_024L
        val kind = type.lowercase()
        val voice = kind == "voice" || kind == "audio" || kind.startsWith("audio/")
        val photo = kind == "image" || kind.startsWith("image/")
        val video = kind in setOf("video", "circle", "video_circle", "video-circle", "gif") || kind.startsWith("video/")
        val limit = when {
            voice -> if (this == High) 3 * mb else 2 * mb
            photo -> mb
            this == Low -> return false
            video -> if (this == High) 10 * mb else 5 * mb / 2
            else -> if (this == High) 3 * mb else mb
        }
        // Our encrypted originals have no smaller server-side copy, so a file of unknown size waits for a tap.
        return size != null && size in 1..limit
    }
}

data class AloMediaCacheSettings(
    val wifi: AloMediaDownloadPreset = AloMediaDownloadPreset.High,
    val cellular: AloMediaDownloadPreset = AloMediaDownloadPreset.Medium,
    val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES
) {
    fun permits(type: String, size: Long?, network: AloMediaNetwork, userInitiated: Boolean): Boolean = when {
        network == AloMediaNetwork.Offline -> false
        userInitiated -> true
        network == AloMediaNetwork.Wifi -> wifi.permits(type, size)
        network == AloMediaNetwork.Cellular -> cellular.permits(type, size)
        else -> false
    }

    companion object {
        const val GB = 1_024L * 1_024L * 1_024L
        const val DEFAULT_MAXIMUM_BYTES = 2 * GB
        val SIZE_CHOICES_GB = listOf(1, 2, 5, 8, 16)
        private const val PREFS = "alo-android"
        private const val KEY = "media_cache_settings_v1"

        fun load(context: Context): AloMediaCacheSettings {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return AloMediaCacheSettings()
            return runCatching {
                val json = org.json.JSONObject(raw)
                AloMediaCacheSettings(
                    wifi = AloMediaDownloadPreset.entries.firstOrNull { it.name == json.optString("wifi") } ?: AloMediaDownloadPreset.High,
                    cellular = AloMediaDownloadPreset.entries.firstOrNull { it.name == json.optString("cellular") } ?: AloMediaDownloadPreset.Medium,
                    maximumBytes = json.optLong("maximumBytes", DEFAULT_MAXIMUM_BYTES).takeIf { it >= 1_024L * 1_024L } ?: DEFAULT_MAXIMUM_BYTES
                )
            }.getOrDefault(AloMediaCacheSettings())
        }

        fun save(context: Context, settings: AloMediaCacheSettings) {
            val json = org.json.JSONObject()
                .put("wifi", settings.wifi.name)
                .put("cellular", settings.cellular.name)
                .put("maximumBytes", settings.maximumBytes)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
        }

        /** The connection as the download rules see it; «Экономия трафика» turns automatic downloads off. */
        fun network(context: Context): AloMediaNetwork {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return AloMediaNetwork.Cellular
            val active = manager.activeNetwork ?: return AloMediaNetwork.Offline
            val capabilities = manager.getNetworkCapabilities(active) ?: return AloMediaNetwork.Offline
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return AloMediaNetwork.Offline
            if (manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return AloMediaNetwork.Constrained
            return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                AloMediaNetwork.Wifi
            } else {
                AloMediaNetwork.Cellular
            }
        }
    }
}

/**
 * The downloaded copies of media on this device: encrypted attachments (re-downloadable from the server) and Coil's pictures.
 * Messages, keys, drafts and unsent attachments live elsewhere and are never touched here.
 */
object AloMediaDiskCache {
    data class Usage(val bytes: Long = 0L, val count: Int = 0)

    /** A file this young may be on screen or playing right now, so neither trimming nor «Очистить» removes it. */
    private const val PROTECTED_AGE_MS = 10 * 60 * 1000L
    private const val TRIM_INTERVAL_MS = 60 * 1000L
    private var lastTrimAt = 0L

    internal fun encryptedDirectory(context: Context): File = File(context.cacheDir, "alo-e2ee-media/encrypted")
    internal fun imageDirectory(context: Context): File = File(context.cacheDir, "alo_image_cache")

    private fun filesIn(directory: File): List<File> =
        directory.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()

    fun usage(context: Context): Usage =
        (filesIn(encryptedDirectory(context)) + filesIn(imageDirectory(context))).let { all -> Usage(all.sumOf { it.length() }, all.size) }

    /** Coil keeps its own index, so its pictures are trimmed and cleared through Coil (see AloApplication) and only counted here. */
    private fun imageBytes(context: Context): Long = filesIn(imageDirectory(context)).sumOf { it.length() }

    /** Marks a cached file as used now, so trimming removes the longest-unused files first. */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Keeps the cache within [settings].maximumBytes by deleting the longest-unused encrypted attachments (or [all] of
     * them). Returns what is left.
     */
    fun clean(context: Context, settings: AloMediaCacheSettings, all: Boolean = false, now: Long = System.currentTimeMillis()): Usage {
        val candidates = filesIn(encryptedDirectory(context)).sortedBy { it.lastModified() }
        var total = candidates.sumOf { it.length() } + imageBytes(context)
        for (file in candidates) {
            if (!all && total <= settings.maximumBytes) break
            if (now - file.lastModified() < PROTECTED_AGE_MS) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
        return usage(context)
    }

    /** A cheap, rate-limited trim for after a download. */
    fun trimIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now
        clean(context, AloMediaCacheSettings.load(context), now = now)
    }
}
