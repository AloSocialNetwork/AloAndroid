package com.alo.android

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
    var webView: WebView? by remember(state.url) { mutableStateOf(null) }

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
                        }
                        webChromeClient = WebChromeClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        addJavascriptInterface(
                            object {
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
