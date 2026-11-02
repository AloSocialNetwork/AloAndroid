package com.alo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Rational
import android.view.Surface as AndroidSurface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaSpec
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

// With the "auto" output format CameraX takes the circle's audio codec from the camera's camcorder profile, and some
// devices (the Android emulator among them) list AMR-NB 8 kHz there: iPhone and browsers cannot play that sound, and an
// iPhone then refuses the whole circle (no first frame, no playback). MPEG-4 output means AAC, as on the other clients.
// CameraX 1.4 keeps the format setter of the builder internal, so it is reached by reflection (release builds are not
// minified); if that ever fails the circle is recorded as before.
@SuppressLint("RestrictedApi")
private fun Recorder.Builder.withMpeg4Output(): Recorder.Builder = apply {
    runCatching {
        val field = Recorder.Builder::class.java.getDeclaredField("mMediaSpecBuilder").apply { isAccessible = true }
        (field.get(this) as MediaSpec.Builder).setOutputFormat(MediaSpec.OUTPUT_FORMAT_MPEG_4)
    }
}

@Composable
internal fun VideoCircleRecorderOverlay(
    bottomInset: Dp = 0.dp,
    onCancel: () -> Unit,
    onRecorded: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var activeOutputFile by remember { mutableStateOf<File?>(null) }
    var elapsedSec by remember { mutableStateOf(0) }
    var discardRecording by remember { mutableStateOf(false) }
    var isStoppingRecording by remember { mutableStateOf(false) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val recordingProgress by animateFloatAsState(
        targetValue = (elapsedSec / 60f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "videoCircleProgress"
    )

    fun stopCurrentRecording(cancel: Boolean) {
        if (isStoppingRecording) return
        discardRecording = cancel
        val recording = activeRecording
        if (recording == null) {
            if (cancel) {
                activeOutputFile?.delete()
                activeOutputFile = null
                onCancel()
            }
            return
        }
        isStoppingRecording = true
        recording.stop()
    }

    DisposableEffect(previewView, lensFacing) {
        val currentPreviewView = previewView
        if (currentPreviewView == null) {
            onDispose {}
        } else {
            val listener = Runnable {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                // A circle is a small square clip, as in Telegram and the other Alo apps (iPhone 400×400, website 400×400):
                // SD from the camera, cropped to the centre square by the view port, about 1 Mbit/s. A full-frame HD movie
                // was ~10 Mbit/s, so a one-minute circle could not even be uploaded (the server keeps up to 30 MB).
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.SD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .setTargetVideoEncodingBitRate(1_200_000)
                    .withMpeg4Output()
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                provider.unbindAll()
                preview.surfaceProvider = currentPreviewView.surfaceProvider
                val rotation = currentPreviewView.display?.rotation ?: AndroidSurface.ROTATION_0
                val square = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(Rational(1, 1), rotation).build())
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .build()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    square
                )
                runCatching {
                    camera.cameraControl.setZoomRatio(
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1.72f else 1.24f
                    )
                }
            }
            cameraProviderFuture.addListener(listener, mainExecutor)
            onDispose {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        }
    }

    LaunchedEffect(videoCapture) {
        val capture = videoCapture ?: return@LaunchedEffect
        if (activeRecording != null) return@LaunchedEffect
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission || !hasAudioPermission) {
            onCancel()
            return@LaunchedEffect
        }
        val outputFile = File(context.cacheDir, "video-circle-${System.currentTimeMillis()}.mp4")
        activeOutputFile = outputFile
        discardRecording = false
        elapsedSec = 0
        val pendingRecording = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
            .withAudioEnabled()
        activeRecording = pendingRecording.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    elapsedSec = 0
                }

                is VideoRecordEvent.Finalize -> {
                    // The recorder knows the real length; the on-screen counter only stands in when it does not.
                    val recordedMs = TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)
                    val durationSec = (if (recordedMs > 0) ((recordedMs + 500) / 1000).toInt() else elapsedSec)
                        .coerceAtLeast(1)
                    val completedFile = activeOutputFile
                    activeRecording = null
                    activeOutputFile = null
                    videoCapture = null
                    isStoppingRecording = false
                    if (discardRecording || event.hasError() || completedFile == null || !completedFile.exists()) {
                        completedFile?.delete()
                        onCancel()
                    } else {
                        onRecorded(Uri.fromFile(completedFile).toString(), durationSec)
                    }
                }
            }
        }
    }

    LaunchedEffect(activeRecording) {
        while (activeRecording != null) {
            delay(1000)
            elapsedSec += 1
            if (elapsedSec >= 60) {
                stopCurrentRecording(cancel = false)
            }
        }
    }

    Box(
        modifier = Modifier
            .zIndex(40f)
            .fillMaxSize()
            .background(Color(0xE60A0C12))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(306.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { previewView = it }
                },
                modifier = Modifier
                    .size(296.dp)
                    .clip(CircleShape)
            )
            CircularProgressIndicator(
                progress = { recordingProgress },
                modifier = Modifier.fillMaxSize(),
                color = AloNativeChatOutgoing,
                trackColor = Color.White.copy(alpha = 0.12f),
                strokeWidth = 4.dp
            )
        }

        GhostCircleButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp),
            onClick = { stopCurrentRecording(cancel = true) },
            icon = Icons.Rounded.Close,
            size = 42.dp,
            iconSize = 18.dp
        )

        GhostCircleButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 16.dp),
            onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
            },
            icon = Icons.Rounded.Refresh,
            size = 42.dp,
            iconSize = 18.dp
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = bottomInset + 28.dp),
            shape = AloSquircle(22.dp),
            color = AloNativeAppSurface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF5D73))
                )
                Text(
                    text = "%d:%02d / 1:00".format(elapsedSec / 60, elapsedSec % 60),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeAppText
                )
                Surface(
                    shape = CircleShape,
                    color = AloNativeChatOutgoing
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { stopCurrentRecording(cancel = false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
