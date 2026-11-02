package com.alo.android

import android.webkit.CookieManager
import java.net.URL

/**
 * The only source of truth for API, media and universal-link endpoints in Android.
 *
 * BuildConfig values are generated from the `aloPublicDomain` property. Feature
 * code must go through this object instead of concatenating a server address.
 * An IP can be used as a debug API override, but production links and TLS must
 * remain domain based.
 */
internal object AloServerConfiguration {
    /**
     * The only LAN endpoint for a developer build on a physical device.
     * Update this value when the Mac receives a different local address.
     * Release builds never fall back to it and must receive an HTTPS URL.
     */
    internal const val debugLocalDevelopmentHost = "192.168.0.19"
    private const val localDevelopmentPort = 3000
    private const val localDevelopmentBaseUrl = "http://$debugLocalDevelopmentHost:$localDevelopmentPort/"

    val apiBaseUrl: String = normalizedBaseUrl(
        AloDebugServer.baseUrl.takeIf { BuildConfig.ALO_DEV_HOOKS && it.isNotBlank() }
            ?: BuildConfig.ALO_BASE_URL.ifBlank {
                check(BuildConfig.DEBUG) { "ALO_BASE_URL is required in release builds." }
                localDevelopmentBaseUrl
            }
    )
    val appLinkBaseUrl: String = normalizedBaseUrl(BuildConfig.ALO_APP_LINK_BASE_URL)
    val appLinkHost: String = runCatching { URL(appLinkBaseUrl).host.lowercase() }.getOrDefault("")

    fun apiUrl(path: String): String {
        val raw = path.trim()
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        return URL(URL(apiBaseUrl), raw).toString()
    }

    fun appLink(kind: String, value: String): String {
        return appLinkPath("${kind.trim('/')}/${value.trim('/')}")
    }

    fun appLinkPath(path: String): String {
        val normalizedPath = path.trim('/').trim()
        if (appLinkBaseUrl.startsWith("https://", ignoreCase = true)) {
            return URL(URL(appLinkBaseUrl), normalizedPath).toString()
        }
        return "alo://$normalizedPath"
    }

    fun publicLinkLabel(path: String = ""): String {
        val host = appLinkHost.ifBlank { "ало.online" }
        val normalizedPath = path.trim('/').trim()
        return if (normalizedPath.isEmpty()) host else "$host/$normalizedPath"
    }

    fun authenticatedMediaHeaders(rawUrl: String): Map<String, String> {
        val requestUrl = runCatching { URL(rawUrl) }.getOrNull() ?: return emptyMap()
        val appUrl = runCatching { URL(apiBaseUrl) }.getOrNull() ?: return emptyMap()
        if (!sameOrigin(requestUrl, appUrl)) return emptyMap()

        val cookie = runCatching {
            CookieManager.getInstance().getCookie(requestUrl.toString())
                ?: CookieManager.getInstance().getCookie(apiBaseUrl)
        }.getOrNull().orEmpty()

        return buildMap {
            if (cookie.isNotBlank()) put("Cookie", cookie)
            put("X-Requested-With", "AloAndroid")
        }
    }

    private fun normalizedBaseUrl(raw: String): String {
        val value = raw.trim()
        return if (value.isEmpty() || value.endsWith('/')) value else "$value/"
    }

    private fun sameOrigin(first: URL, second: URL): Boolean {
        val firstPort = if (first.port >= 0) first.port else first.defaultPort
        val secondPort = if (second.port >= 0) second.port else second.defaultPort
        return first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            firstPort == secondPort
    }
}

/**
 * Debug builds only: the cross-client checks (`~/alo/tools/cross-client-check`) point the app at a throwaway server
 * with a test person's session by writing `shared_prefs/alo-debug-server.xml` (`base_url`, `session_token`) through
 * `adb shell run-as`. Read in [AloApplication.attachBaseContext], before anything asks for the server address.
 * Delete the file to return to the normal server.
 */
internal object AloDebugServer {
    @Volatile var baseUrl: String = ""
    @Volatile var sessionToken: String = ""
    /** Cross-client checks take screenshots of secret chats, which otherwise keep the window secure (debug builds only). */
    @Volatile var allowScreenshots: Boolean = false
    /** Test builds do not look for updates unless a check asks them to. */
    @Volatile var checkUpdates: Boolean = false
    /** Measuring: what the frames cost without the glass. */
    @Volatile var plainGlass: Boolean = false

    fun load(context: android.content.Context) {
        if (!BuildConfig.ALO_DEV_HOOKS) return
        val prefs = context.getSharedPreferences("alo-debug-server", android.content.Context.MODE_PRIVATE)
        baseUrl = prefs.getString("base_url", "").orEmpty().trim()
        sessionToken = prefs.getString("session_token", "").orEmpty().trim()
        allowScreenshots = prefs.getString("allow_screenshots", "").orEmpty().trim() == "1"
        // A benchmark build is not debuggable, so `run-as` cannot write shared_prefs: adb pushes the same keys as a file
        // to Android/data/com.alo.android/files/alo-debug-server.properties.
        val file = context.getExternalFilesDir(null)?.resolve("alo-debug-server.properties")
        if (file != null && file.isFile) {
            val values = java.util.Properties().apply { file.inputStream().use { load(it) } }
            baseUrl = values.getProperty("base_url", baseUrl).trim()
            sessionToken = values.getProperty("session_token", sessionToken).trim()
            checkUpdates = values.getProperty("update_check", "").trim() == "1"
            plainGlass = values.getProperty("plain_glass", "").trim() == "1"
        }
    }
}
