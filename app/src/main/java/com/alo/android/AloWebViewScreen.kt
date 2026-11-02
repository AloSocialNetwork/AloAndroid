package com.alo.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

private data class PageError(
    val title: String,
    val message: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AloWebViewScreen(
    startUrl: String,
    openFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    requestWebPermission: (PermissionRequest) -> Unit,
    openExternalLink: (Uri) -> Unit,
    onRequireNativeAuth: () -> Unit
) {
    val context = LocalContext.current
    val startUri = remember(startUrl) { Uri.parse(startUrl) }
    val density = LocalDensity.current
    val safeTopInsetPx = WindowInsets.safeDrawing.getTop(density)
    val safeBottomInsetPx = WindowInsets.safeDrawing.getBottom(density)

    var pageError by remember { mutableStateOf<PageError?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var canNavigateBack by remember { mutableStateOf(false) }

    val webView = remember(context, startUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = "${userAgentString} AloAndroid/0.2"
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadProgress = newProgress
                    if (newProgress >= 100) {
                        isLoading = false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    requestWebPermission(request)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    return openFileChooser(filePathCallback, fileChooserParams)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    val targetUri = request.url ?: return false

                    if (isNativeAuthRedirect(targetUri, startUri)) {
                        onRequireNativeAuth()
                        return true
                    }

                    if (isInternalUrl(targetUri, startUri)) {
                        return false
                    }

                    openExternalLink(targetUri)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    val targetUri = url?.let(Uri::parse)
                    if (targetUri != null && isNativeAuthRedirect(targetUri, startUri)) {
                        onRequireNativeAuth()
                        view?.stopLoading()
                        return
                    }

                    pageError = null
                    isLoading = true
                    loadProgress = 0
                    canNavigateBack = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.let { syncSafeAreaInsets(it, safeTopInsetPx, safeBottomInsetPx) }
                    isLoading = false
                    loadProgress = 100
                    canNavigateBack = view?.canGoBack() == true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: WebResourceError?
                ) {
                    if (!request.isForMainFrame) return

                    pageError = PageError(
                        title = "Не удалось открыть Alo",
                        message = error?.description?.toString()?.ifBlank { "Сайт недоступен." }
                            ?: "Сайт недоступен."
                    )
                    isLoading = false
                    loadProgress = 0
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (!request.isForMainFrame) return

                    val reason = errorResponse.reasonPhrase?.ifBlank { "HTTP ${errorResponse.statusCode}" }
                        ?: "HTTP ${errorResponse.statusCode}"

                    pageError = PageError(
                        title = "Сервер вернул ошибку",
                        message = reason
                    )
                    isLoading = false
                    loadProgress = 0
                }
            }

            loadUrl(startUrl)
        }
    }

    BackHandler(enabled = canNavigateBack) {
        webView.goBack()
        canNavigateBack = webView.canGoBack()
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                syncSafeAreaInsets(view, safeTopInsetPx, safeBottomInsetPx)
            }
        )

        AnimatedVisibility(
            visible = isLoading,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        pageError?.let { error ->
            ElevatedCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 420.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = error.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = error.message,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Проверь, что backend Alo доступен по адресу:\n$startUrl",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                    Button(
                        onClick = {
                            pageError = null
                            isLoading = true
                            loadProgress = 0
                            webView.reload()
                        },
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth()
                    ) {
                        Text("Повторить")
                    }
                    OutlinedButton(
                        onClick = { openExternalLink(Uri.parse(startUrl)) },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        Text("Открыть в браузере")
                    }
                }
            }
        }
    }
}

private fun syncSafeAreaInsets(
    webView: WebView,
    topInsetPx: Int,
    bottomInsetPx: Int
) {
    val safeTop = topInsetPx.coerceAtLeast(0)
    val safeBottom = bottomInsetPx.coerceAtLeast(0)
    val script = """
        (function() {
          var top = '${safeTop}px';
          var bottom = '${safeBottom}px';
          if (document.documentElement) {
            document.documentElement.style.setProperty('--mobile-safe-top', top);
            document.documentElement.style.setProperty('--mobile-safe-top-inset', top);
            document.documentElement.style.setProperty('--android-safe-bottom-inset', bottom);
          }
          if (document.body) {
            document.body.style.setProperty('--mobile-safe-top', top);
            document.body.style.setProperty('--mobile-safe-top-inset', top);
            document.body.style.setProperty('--android-safe-bottom-inset', bottom);
          }
        })();
    """.trimIndent()

    webView.post {
        webView.evaluateJavascript(script, null)
    }
}

private fun isNativeAuthRedirect(candidate: Uri, base: Uri): Boolean {
    if (!isInternalUrl(candidate, base)) return false
    val path = (candidate.path ?: "").lowercase(Locale.ROOT)
    return path == "/auth" || path == "/auth/login" || path == "/auth/login.html"
}

private fun isInternalUrl(candidate: Uri, base: Uri): Boolean {
    val candidateScheme = candidate.scheme?.lowercase(Locale.ROOT) ?: return false
    if (candidateScheme != "http" && candidateScheme != "https") {
        return false
    }

    val candidateHost = candidate.host ?: return false
    val baseHost = base.host ?: return false

    return candidateHost.equals(baseHost, ignoreCase = true) &&
        normalizedPort(candidate) == normalizedPort(base)
}

private fun normalizedPort(uri: Uri): Int {
    if (uri.port != -1) return uri.port
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
}
