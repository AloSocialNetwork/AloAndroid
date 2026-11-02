package com.alo.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

class AloApplication : Application(), SingletonImageLoader.Factory {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Before content providers: a crash this early is the one nobody else would report.
        AloCrashReporter.install(base)
        // Before content providers and the first look at the server address (debug builds only).
        AloDebugServer.load(base)
    }

    override fun onCreate() {
        super.onCreate()
        AloCrashReporter.sendPending(this)
        AloPushRegistration.initialize(this)
        Thread { AloMediaDiskCache.clean(this, AloMediaCacheSettings.load(this)) }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val headers = aloAuthenticatedMediaHeaders(original.url.toString())
                if (headers.isEmpty()) {
                    chain.proceed(original)
                } else {
                    val authenticated = original.newBuilder().apply {
                        headers.forEach { (name, value) -> header(name, value) }
                        // Do not reuse an old cached redirect for private media.
                        // Coil still retains successfully decoded images in its own cache.
                        header("Cache-Control", "no-cache")
                    }.build()
                    chain.proceed(authenticated)
                }
            }
            .build()

        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("alo_image_cache").toOkioPath())
                    // A quarter of the user's cache limit (Settings → Данные и память), between 256 MB and 1 GB.
                    .maxSizeBytes(
                        (AloMediaCacheSettings.load(this@AloApplication).maximumBytes / 4)
                            .coerceIn(256L * 1_024L * 1_024L, 1_024L * 1_024L * 1_024L)
                    )
                    .build()
            }
            .crossfade(140)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = httpClient))
                // A frame for videos in the app's gallery.
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
