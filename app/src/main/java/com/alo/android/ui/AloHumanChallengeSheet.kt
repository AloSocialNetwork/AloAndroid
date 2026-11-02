package com.alo.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun AloHumanChallengeSheet(
    state: AloHumanChallengeUiState?,
    onSolved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val visible = state != null
    BackHandler(enabled = visible) {
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(horizontal = 18.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            state?.let { challenge ->
                AloHumanChallengeCard(
                    state = challenge,
                    onSolved = onSolved,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AloHumanChallengeCard(
    state: AloHumanChallengeUiState,
    onSolved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val context = LocalContext.current
    var webView: WebView? by remember(state.url) { mutableStateOf(null) }
    // Chrome version of a WebView too old for Cloudflare's check, or 0. Known before loading; the page also reports it.
    var outdatedWebView by remember(state.url) { mutableStateOf(aloOutdatedWebViewMajor(context)) }

    DisposableEffect(state.url) {
        onDispose {
            webView?.removeJavascriptInterface("AloTurnstile")
            webView?.destroy()
            webView = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(30.dp))
            .background(AloNativeAppSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 48.dp)
            ) {
                Text(
                    text = "Проверка действия",
                    style = MaterialTheme.typography.titleLarge,
                    color = AloNativeAppText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AloNativeAppMuted
                )
            }
            IconButton(
                modifier = Modifier.align(Alignment.TopEnd),
                onClick = onDismiss
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Закрыть",
                    tint = AloNativeAppMuted
                )
            }
        }

        if (outdatedWebView > 0) {
            AloOutdatedWebViewContent(version = outdatedWebView, onOpenPlay = { aloOpenWebViewInPlay(context) })
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(AloSquircle(22.dp))
                .background(AloNativeAppBackground),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        setBackgroundColor(Color.Transparent.toArgb())
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // Links Cloudflare shows (privacy, help) and market:// leave for the phone's own apps.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (request.isForMainFrame && url.host == Uri.parse(state.url).host) return false
                                if (!request.isForMainFrame) return false
                                runCatching {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                return true
                            }
                        }
                        webChromeClient = WebChromeClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onUnsupported(version: String) {
                                    mainHandler.post { outdatedWebView = version.toIntOrNull()?.takeIf { it > 0 } ?: 1 }
                                }

                                @JavascriptInterface
                                fun onToken(token: String) {
                                    mainHandler.post {
                                        if (token.isNotBlank()) {
                                            onSolved(token)
                                        }
                                    }
                                }
                            },
                            "AloTurnstile"
                        )
                        loadUrl(state.url)
                    }
                },
                update = { view ->
                    if (view.url != state.url) {
                        view.loadUrl(state.url)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}

// Cloudflare's check is written for Chrome 80+ (2020). An Android 7–9 phone whose WebView was never updated has an older
// one (seen: Android 8.1, Chrome 67): the box stays empty and no token ever comes, so such a phone is sent to Google Play.
private const val ALO_OLDEST_CHALLENGE_WEBVIEW = 80

internal fun aloOutdatedWebViewMajor(context: Context): Int {
    val agent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() ?: return 0
    val major = Regex("""Chrome/(\d+)""").find(agent)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
    return if (major in 1 until ALO_OLDEST_CHALLENGE_WEBVIEW) major else 0
}

// On Android 7–9 the WebView may be Chrome itself; from Android 8 the system says which package it is.
private fun aloWebViewPackageName(): String =
    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage()?.packageName else null)
        ?: "com.google.android.webview"

private fun aloOpenWebViewInPlay(context: Context) {
    val name = aloWebViewPackageName()
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$name")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$name"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }.recoverCatching { context.startActivity(web) }
}

@Composable
private fun AloOutdatedWebViewContent(version: Int, onOpenPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Встроенный браузер телефона устарел" + (if (version > 1) " (Chrome $version)" else "") +
                ", и проверка Cloudflare на нём не запускается. Обновите в Google Play «Android System WebView» " +
                "или Chrome, затем попробуйте снова.",
            style = MaterialTheme.typography.bodyLarge,
            color = AloNativeAppText
        )
        AloPrimaryPillButton(title = "Открыть Google Play", onClick = onOpenPlay)
    }
}
