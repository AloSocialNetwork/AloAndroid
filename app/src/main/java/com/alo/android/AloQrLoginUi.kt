package com.alo.android

import androidx.compose.foundation.layout.wrapContentHeight

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.clickable

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

internal data class AloQrLoginPayload(
    val id: String,
    val secret: String,
    /** alo://login/link/…: shown by a signed-in device for a new one to scan, not to approve. */
    val isDeviceLink: Boolean = false,
    /** The website that showed the code (https://<host>/qr#…); empty for alo://… codes. */
    val webHost: String = ""
) {
    /** The website's code may be approved only on the Alo server this app talks to (or its public site). */
    val isFromTrustedHost: Boolean
        get() = webHost.isEmpty() || isAloWebHost(webHost)

    companion object {
        private val stableId = Regex("^[A-Za-z0-9_-]+$")

        fun isAloWebHost(
            host: String,
            appLinkHost: String = AloServerConfiguration.appLinkHost,
            apiBaseUrl: String = AloServerConfiguration.apiBaseUrl
        ): Boolean {
            val value = host.lowercase()
            if (value.isEmpty()) return false
            if (value == appLinkHost.lowercase()) return true
            val apiHost = runCatching { URI(apiBaseUrl).host?.lowercase() }.getOrNull().orEmpty()
            return apiHost.isNotEmpty() && (apiHost == value || apiHost == "api.$value")
        }

        /**
         * The website's https://<host>/qr#<id>.<secret> (what the phone camera opens), the server's
         * alo://login/qr/<id>/<secret> and alo://login/link/<id>/<secret>, and the older query form.
         */
        fun parse(raw: String): AloQrLoginPayload? {
            val value = raw.trim()
            if (value.isBlank() || value.length > 1_024) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
                val host = uri.host?.lowercase().orEmpty()
                if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null) return null
                if (uri.rawPath != "/qr" && uri.rawPath != "/qr/") return null
                val parts = uri.rawFragment.orEmpty().split('.')
                if (parts.size != 2) return null
                val (id, secret) = parts
                return if (id.length in 16..160 && secret.length in 32..256 && stableId.matches(id) && stableId.matches(secret)) {
                    AloQrLoginPayload(id, secret, webHost = host)
                } else null
            }
            if (uri.scheme.equals("alo", ignoreCase = true) && uri.host.equals("login", ignoreCase = true) &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null && uri.port == -1
            ) {
                val parts = uri.rawPath.orEmpty().split('/')
                if (parts.size == 4 && parts[0].isEmpty() && (parts[1] == "qr" || parts[1] == "link")) {
                    val id = parts[2]
                    val secret = parts[3]
                    if (id.length in 16..160 && secret.length in 32..256 && stableId.matches(id) && stableId.matches(secret)) {
                        return AloQrLoginPayload(id, secret, isDeviceLink = parts[1] == "link")
                    }
                }
                return null
            }
            val values = uri.rawQuery.orEmpty().split('&')
                .mapNotNull { token ->
                    val index = token.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    URLDecoder.decode(token.substring(0, index), StandardCharsets.UTF_8.name()) to
                        URLDecoder.decode(token.substring(index + 1), StandardCharsets.UTF_8.name())
                }
                .toMap()
            val id = values["id"].orEmpty().trim()
            val secret = values["secret"].orEmpty().trim()
            return if (id.isNotBlank() && secret.isNotBlank()) AloQrLoginPayload(id, secret) else null
        }
    }
}

@Composable
internal fun AloQrCode(
    payload: String,
    modifier: Modifier = Modifier
) {
    val matrix = remember(payload) {
        runCatching { QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1) }.getOrNull()
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .clip(AloSquircle(20.dp))
            .background(Color.White)
            .padding(14.dp)
    ) {
        val code = matrix ?: return@Canvas
        drawQrMatrix(code.width, code.height) { x, y -> code[x, y] }
    }
}

private fun DrawScope.drawQrMatrix(
    columns: Int,
    rows: Int,
    isDark: (Int, Int) -> Boolean
) {
    if (columns <= 0 || rows <= 0) return
    val cell = min(size.width / columns, size.height / rows)
    val left = (size.width - cell * columns) / 2f
    val top = (size.height - cell * rows) / 2f
    for (x in 0 until columns) {
        for (y in 0 until rows) {
            if (!isDark(x, y)) continue
            drawRect(
                color = Color(0xFF101116),
                topLeft = androidx.compose.ui.geometry.Offset(left + x * cell, top + y * cell),
                size = androidx.compose.ui.geometry.Size(cell, cell)
            )
        }
    }
}

/** Full on-device QR reader. Frames are analysed locally by ML Kit; the raw QR
 * text is only delivered to the caller after a QR code is decoded. */
@Composable
internal fun AloQrLoginScannerOverlay(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "Наведите камеру на QR-код входа Alo.",
    /** A way out under the camera, such as «Показать QR на этом устройстве» on the sign-in screen. */
    secondaryAction: Pair<String, () -> Unit>? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val providerFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // The emulator's camera cannot show a code: a test build reads a real screenshot of the sign-in page, put at
    // Android/data/<package>/files/alo-debug-qr.png (adb can write there), through the same ML Kit scanner.
    if (BuildConfig.ALO_DEV_HOOKS) {
        LaunchedEffect(Unit) {
            val file = java.io.File(context.getExternalFilesDir(null), "alo-debug-qr.png")
            val bitmap = if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.path) else null
            if (bitmap != null) {
                file.delete()
                BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                ).process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { codes ->
                    val value = codes.firstOrNull()?.rawValue.orEmpty()
                    if (value.isNotBlank() && delivered.compareAndSet(false, true)) onScanned(value)
                }
            }
        }
    }

    DisposableEffect(previewView, hasPermission) {
        val currentPreview = previewView
        if (!hasPermission || currentPreview == null) {
            onDispose {}
        } else {
            val listener = Runnable {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = currentPreview.surfaceProvider
                    }
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val image = imageProxy.image
                        if (image == null || delivered.get()) {
                            imageProxy.close()
                        } else {
                            scanner.process(
                                InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
                            )
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstOrNull()?.rawValue.orEmpty()
                                    if (value.isNotBlank() && delivered.compareAndSet(false, true)) {
                                        onScanned(value)
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    onDismiss()
                }
            }
            providerFuture.addListener(listener, mainExecutor)
            onDispose {
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = AloSquircle(28.dp),
            color = AloNativeAppSurface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Сканировать QR-код",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                    IconButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = onDismiss
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрыть сканер",
                            tint = AloNativeAppText
                        )
                    }
                }
                if (hasPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }.also { previewView = it }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(AloSquircle(18.dp))
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted,
                        textAlign = TextAlign.Center
                    )
                    secondaryAction?.let { (title, action) ->
                        Text(
                            text = title,
                            modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = action).wrapContentHeight(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppAccent
                        )
                    }
                } else {
                    Text(
                        text = "Для сканирования нужен доступ к камере.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AloNativeAppMuted,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AloNativeAppAccent,
                            contentColor = AloNativeAppButtonText
                        )
                    ) { Text("Понятно") }
                }
            }
        }
    }
}
