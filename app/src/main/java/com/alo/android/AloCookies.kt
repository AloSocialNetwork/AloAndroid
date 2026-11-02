package com.alo.android

import android.webkit.CookieManager
import java.net.URL

/**
 * The app's cookies (the `alo_session` sign-in above all). They live in the WebView's CookieManager, so the captcha page
 * and the API share one session. CookieManager needs an installed WebView: on a phone where it is missing or being
 * updated, `CookieManager.getInstance()` throws and the app used to fall at start (MissingWebViewPackageException, seen on
 * an Android 8.1 Sharp). Then the cookies are kept here in memory; MainActivity puts the saved session back from
 * AloSecureAccountStore on every start, so the person stays signed in.
 */
internal object AloCookies {
    private val fallback = LinkedHashMap<String, LinkedHashMap<String, String>>()

    // Asked once per process: Android does not bring a WebView back into a running app, and every failed attempt
    // costs a stack trace in the log on the main thread.
    @Volatile
    private var webViewMissing = false

    /** The WebView's cookie store, or null when this phone has no working WebView right now. */
    private fun manager(): CookieManager? {
        if (webViewMissing) return null
        return runCatching { CookieManager.getInstance() }.getOrNull().also { if (it == null) webViewMissing = true }
    }

    val webViewAvailable: Boolean get() = manager() != null

    fun acceptCookies() {
        manager()?.setAcceptCookie(true)
    }

    fun get(url: String): String? {
        val manager = manager()
        if (manager != null) return manager.getCookie(url)
        val host = hostOf(url) ?: return null
        return synchronized(fallback) {
            fallback[host]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    /** One `Set-Cookie` value (or a plain `name=value`). */
    fun set(url: String, cookie: String) {
        val manager = manager()
        if (manager != null) {
            manager.setCookie(url, cookie)
            return
        }
        val host = hostOf(url) ?: return
        val parts = cookie.split(';').map(String::trim)
        val pair = parts.firstOrNull()?.takeIf { '=' in it } ?: return
        val name = pair.substringBefore('=').trim().takeIf(String::isNotEmpty) ?: return
        val value = pair.substringAfter('=').trim()
        // The server signs out by sending the cookie empty and expired.
        val expired = value.isEmpty() || parts.drop(1).any { attribute ->
            attribute.lowercase().let { it.startsWith("max-age=") && (it.substringAfter('=').toLongOrNull() ?: 1) <= 0 }
        }
        synchronized(fallback) {
            val jar = fallback.getOrPut(host) { LinkedHashMap() }
            if (expired) jar.remove(name) else jar[name] = value
        }
    }

    fun removeAll() {
        manager()?.removeAllCookies(null)
        synchronized(fallback) { fallback.clear() }
    }

    fun flush() {
        manager()?.flush()
    }

    private fun hostOf(url: String): String? = runCatching { URL(url).host?.lowercase() }.getOrNull()?.takeIf(String::isNotEmpty)
}
