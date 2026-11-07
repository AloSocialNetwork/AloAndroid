package com.alo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Surface as AndroidSurface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
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
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

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

// Flipping the camera mid-circle makes CameraX tear down the video encoder and build a new one for the other lens. If
// the recording is stopped inside that window, CameraX 1.4–1.6 crashes the whole app on its own thread
// ("Unexpectedly invoke onConfigured() in a STOPPING state"). So a stop, cancel or close asked for during a flip waits
// until the first frame from the new encoder has been written (a Status event), or 8 s at most.
private class CircleCameraSwitch {
    var inProgress = false
    var boundAtMs = 0L
    var oldEncoder: Any? = null
    var pending: (() -> Unit)? = null
    var serial = 0
}

private fun Recorder.currentVideoEncoderOrNull(): Result<Any?> = runCatching {
    Recorder::class.java.getDeclaredField("mVideoEncoder").apply { isAccessible = true }.get(this)
}

@androidx.annotation.OptIn(ExperimentalPersistentRecording::class)
@Composable
internal fun VideoCircleRecorderOverlay(
    bottomInset: Dp = 0.dp,
    onCancel: () -> Unit,
    onRecorded: (String, Int) -> Unit,
    /** Called instead of [onRecorded] when the composer's release sent the circle. */
    onRecordedSend: ((String, Int) -> Unit)? = null,
    /** The composer's held mic; null where the overlay has its own cancel and send (the secret chat). */
    control: AloCircleRecordingControl? = null,
    /** The chat to blur behind the circle. */
    backdrop: AloGlassBackdrop? = null,
    /** The veil ends at the composer (a screen whose composer is drawn under the overlay, the secret chat). */
    veilStopsAtComposer: Boolean = false
) {
    val ownControls = control == null
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // One recorder for the whole circle. Flipping the camera rebinds this same VideoCapture to the other lens; a new
    // recorder per lens (as before) made CameraX finish the running recording with an error, and the overlay closed.
    val videoCapture = remember {
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
        VideoCapture.withOutput(recorder)
    }
    var cameraBound by remember { mutableStateOf(false) }
    var recordingStarted by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var activeOutputFile by remember { mutableStateOf<File?>(null) }
    var elapsedSec by remember { mutableStateOf(0) }
    // Telegram's ring fills smoothly over the minute: the time since the recorder started, every frame.
    var elapsedMs by remember { mutableStateOf(0L) }
    var recordStartedAtMs by remember { mutableStateOf(0L) }
    // What the composer's held mic asked for (release = send, locked pause = preview): see [AloCircleRecordingControl].
    var sendAfterStop by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var discardRecording by remember { mutableStateOf(false) }
    var isStoppingRecording by remember { mutableStateOf(false) }
    var overlayDisposed by remember { mutableStateOf(false) }
    val cameraSwitch = remember { CircleCameraSwitch() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }

    fun finishCameraSwitch() {
        if (!cameraSwitch.inProgress) return
        cameraSwitch.inProgress = false
        cameraSwitch.oldEncoder = null
        cameraSwitch.pending?.also { cameraSwitch.pending = null }?.invoke()
    }

    fun beginCameraSwitch() {
        cameraSwitch.inProgress = true
        cameraSwitch.boundAtMs = 0L
        cameraSwitch.oldEncoder = videoCapture.output.currentVideoEncoderOrNull().getOrNull()
        val serial = ++cameraSwitch.serial
        mainHandler.postDelayed({ if (cameraSwitch.serial == serial) finishCameraSwitch() }, 8_000)
    }

    fun stopCurrentRecording(cancel: Boolean) {
        if (isStoppingRecording) return
        if (cameraSwitch.inProgress && activeRecording != null) {
            cameraSwitch.pending = { stopCurrentRecording(cancel) }
            return
        }
        discardRecording = cancel
        val recording = activeRecording
        if (recording == null) {
            // Nothing recorded yet (the camera was still starting): a send or a preview has no clip either.
            activeOutputFile?.delete()
            activeOutputFile = null
            onCancel()
            return
        }
        isStoppingRecording = true
        recording.stop()
    }

    // A persistent recording is not stopped by unbinding the camera, so leaving the chat must end it explicitly.
    DisposableEffect(Unit) {
        onDispose {
            overlayDisposed = true
            discardRecording = true
            activeRecording?.let { recording ->
                if (cameraSwitch.inProgress) cameraSwitch.pending = { recording.close() } else recording.close()
            }
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    DisposableEffect(previewView, lensFacing) {
        val currentPreviewView = previewView
        if (currentPreviewView == null) {
            onDispose {}
        } else {
            var effectActive = true
            val listener = Runnable {
                if (!effectActive) return@Runnable
                val provider = cameraProviderFuture.get()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                // Some phones have no second lens or refuse to open it: keep recording on the current one.
                if (runCatching { provider.hasCamera(selector) }.getOrDefault(false).not()) {
                    val otherLens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                    val otherSelector = CameraSelector.Builder().requireLensFacing(otherLens).build()
                    when {
                        cameraBound -> finishCameraSwitch()
                        runCatching { provider.hasCamera(otherSelector) }.getOrDefault(false) -> lensFacing = otherLens
                        else -> onCancel()
                    }
                    return@Runnable
                }
                val preview = Preview.Builder().build()
                preview.surfaceProvider = currentPreviewView.surfaceProvider
                val rotation = currentPreviewView.display?.rotation ?: AndroidSurface.ROTATION_0
                val square = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(Rational(1, 1), rotation).build())
                    .addUseCase(preview)
                    .addUseCase(videoCapture)
                    .build()
                val camera = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, square)
                }.getOrElse {
                    if (!cameraBound) onCancel() else finishCameraSwitch()
                    return@Runnable
                }
                cameraBound = true
                boundCamera = camera
                cameraSwitch.boundAtMs = SystemClock.uptimeMillis()
                runCatching {
                    camera.cameraControl.setZoomRatio(
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1.72f else 1.24f
                    )
                }
            }
            cameraProviderFuture.addListener(listener, mainExecutor)
            onDispose {
                effectActive = false
            }
        }
    }

    LaunchedEffect(cameraBound) {
        if (!cameraBound || recordingStarted) return@LaunchedEffect
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
        recordingStarted = true
        val outputFile = File(context.cacheDir, "video-circle-${System.currentTimeMillis()}.mp4")
        activeOutputFile = outputFile
        discardRecording = false
        elapsedSec = 0
        val pendingRecording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
            .withAudioEnabled()
            .asPersistentRecording()
        activeRecording = pendingRecording.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    elapsedSec = 0
                    elapsedMs = 0L
                    recordStartedAtMs = SystemClock.uptimeMillis()
                }

                is VideoRecordEvent.Status -> {
                    // Status events come only from written video frames; after a flip the first one from the new
                    // encoder means CameraX is done switching.
                    if (cameraSwitch.inProgress && cameraSwitch.boundAtMs > 0L) {
                        val encoder = videoCapture.output.currentVideoEncoderOrNull()
                        val switched = encoder.fold(
                            onSuccess = { it != null && it !== cameraSwitch.oldEncoder },
                            onFailure = { SystemClock.uptimeMillis() - cameraSwitch.boundAtMs > 1_500 }
                        )
                        if (switched) finishCameraSwitch()
                    }
                }

                is VideoRecordEvent.Finalize -> {
                    // The recorder knows the real length; the on-screen counter only stands in when it does not.
                    val recordedMs = TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)
                    val durationSec = (if (recordedMs > 0) ((recordedMs + 500) / 1000).toInt() else elapsedSec)
                        .coerceAtLeast(1)
                    val completedFile = activeOutputFile
                    activeRecording = null
                    activeOutputFile = null
                    isStoppingRecording = false
                    cameraSwitch.serial++
                    cameraSwitch.inProgress = false
                    cameraSwitch.pending = null
                    // These errors still leave a playable file (CameraX documents it), so the circle is kept.
                    val keepsFile = event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    val unusable = event.hasError() && !keepsFile
                    if (overlayDisposed) {
                        completedFile?.delete()
                    } else if (discardRecording || unusable || completedFile == null || !completedFile.exists() ||
                        completedFile.length() == 0L
                    ) {
                        completedFile?.delete()
                        onCancel()
                    } else if (recordedMs in 1 until MIN_CIRCLE_MS) {
                        // Shorter than Telegram keeps (iOS throws away under a second): nothing to send.
                        completedFile.delete()
                        onCancel()
                    } else if (sendAfterStop && onRecordedSend != null) {
                        onRecordedSend(Uri.fromFile(completedFile).toString(), durationSec)
                    } else {
                        onRecorded(Uri.fromFile(completedFile).toString(), durationSec)
                    }
                }
            }
        }
    }

    LaunchedEffect(activeRecording) {
        while (activeRecording != null) {
            // 20 updates a second: the ring reads it only while drawing, so nothing recomposes for it.
            delay(50)
            if (recordStartedAtMs > 0L) {
                elapsedMs = SystemClock.uptimeMillis() - recordStartedAtMs
                elapsedSec = (elapsedMs / 1000L).toInt()
            }
            // Telegram stops a circle at 59.5 s; a held one is sent, a locked one goes to its preview.
            if (elapsedMs >= MAX_CIRCLE_MS && !isStoppingRecording) {
                sendAfterStop = control?.isHeld == true
                stopCurrentRecording(cancel = false)
            }
        }
    }

    // The composer's held mic drives a circle as it drives a voice message (Telegram Android ChatActivityEnterView).
    LaunchedEffect(control?.serial) {
        val command = control?.command ?: return@LaunchedEffect
        when (command) {
            AloCircleCommand.Cancel -> stopCurrentRecording(cancel = true)
            AloCircleCommand.Send, AloCircleCommand.Preview -> {
                sendAfterStop = command == AloCircleCommand.Send
                stopCurrentRecording(cancel = false)
            }
        }
    }

    // Telegram's flash: the torch on the back camera, a warm white screen at full brightness on the front one.
    var flashOn by remember { mutableStateOf(false) }
    val frontFlash = flashOn && lensFacing == CameraSelector.LENS_FACING_FRONT
    LaunchedEffect(boundCamera, flashOn, lensFacing) {
        runCatching { boundCamera?.cameraControl?.enableTorch(flashOn && lensFacing == CameraSelector.LENS_FACING_BACK) }
    }
    val activity = context as? android.app.Activity
    DisposableEffect(frontFlash) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        if (frontFlash && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (frontFlash && window != null && before != null) {
                window.attributes = window.attributes.apply { screenBrightness = before }
            }
        }
    }
    // Keep the screen on while the camera is open, as Telegram's InstantCameraView does.
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Telegram's flip: the circle turns about its vertical axis, 580 ms EASE_OUT_QUINT, the lens changing at half way.
    val flip = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun flipCamera() {
        if (cameraSwitch.inProgress || isStoppingRecording || flip.isRunning) return
        if (activeRecording != null) beginCameraSwitch()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        scope.launch {
            flip.snapTo(0f)
            flip.animateTo(1f, tween(580, easing = AloTelegramEaseOutQuint))
            flip.snapTo(0f)
        }
    }

    // Telegram Android's open: the circle grows from 10 % and rises from half the screen, 180 ms Decelerate; the blurred
    // chat behind it fades in over 520 ms EASE_OUT_QUINT.
    val appear = remember { Animatable(0f) }
    val veil = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { veil.animateTo(1f, tween(520, easing = AloTelegramEaseOutQuint)) }
        appear.animateTo(1f, tween(180, easing = LinearOutSlowInEasing))
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier = Modifier
            .zIndex(if (ownControls) 40f else 3.5f)
            .fillMaxSize()
    ) {
        // Telegram's roundVideoRecordBackground: the chat behind, blurred, under a 232/255 veil of the page colour.
        val bottom = bottomInset + navigationBottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (veilStopsAtComposer) bottom else 0.dp)
                .graphicsLayer { alpha = veil.value }
                .then(
                    if (backdrop != null) {
                        Modifier.aloLiquidGlass(
                            RectangleShape,
                            tint = AloNativeAppBackground.copy(alpha = 232f / 255f),
                            blurRadius = 24.dp,
                            backdrop = backdrop
                        )
                    } else {
                        Modifier.background(AloNativeAppBackground.copy(alpha = 232f / 255f))
                    }
                )
                .then(if (ownControls) Modifier.background(Color(0x990A0C12)) else Modifier)
        )
        if (frontFlash) {
            Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E0)))
        }
        val areaHeight = (maxHeight - bottom).coerceAtLeast(1.dp)
        // AndroidUtilities.roundPlayingMessageSize (the screen's short side less 28dp) when the area is tall,
        // roundMessageSize (60 % of it) otherwise.
        val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
        val side = if (areaHeight > maxWidth * 1.3f) shortSide - 28.dp else shortSide * 0.6f
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(areaHeight),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(side + 22.dp)
                    .graphicsLayer {
                        val open = appear.value
                        scaleX = 0.1f + 0.9f * open
                        scaleY = 0.1f + 0.9f * open
                        alpha = open
                        translationY = areaHeight.toPx() / 2f * (1f - open)
                    },
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
                        .size(side)
                        .graphicsLayer {
                            val turn = flip.value
                            rotationY = (if (turn < 0.5f) turn else turn - 1f) * 180f
                            cameraDistance = side.toPx() * 8f
                        }
                        .clip(CircleShape)
                        .background(Color.Black)
                        .semantics { contentDescription = "Камера кружка" }
                )
                // InstantCameraView.onDraw: a white 3dp arc 8dp outside the picture, from the top, round ends.
                Canvas(Modifier.size(side + 16.dp + 3.dp)) {
                    val progress = (elapsedMs / 60_000f).coerceIn(0f, 1f)
                    if (progress > 0f) {
                        val stroke = 3.dp.toPx()
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        // Telegram's buttons: a glass bar at the bottom left above the composer, 44dp flip and flash, 6dp padding.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 1.dp, bottom = bottom + if (ownControls) 96.dp else 0.dp)
                .graphicsLayer { alpha = appear.value }
                .height(56.dp)
                .padding(6.dp)
                .aloLiquidGlass(RoundedCornerShape(22.dp))
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = ::flipCamera)
                    .semantics { contentDescription = "Сменить камеру" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Cameraswitch, null, tint = AloNativeAppText, modifier = Modifier.size(24.dp))
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { flashOn = !flashOn }
                    .semantics { contentDescription = if (flashOn) "Выключить вспышку" else "Включить вспышку" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    null,
                    tint = if (flashOn) Color(0xFFFFC107) else AloNativeAppText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // A screen without the composer's recording bar (the secret chat) keeps its own cancel and send.
        if (ownControls) {
            GhostCircleButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 16.dp)
                    .semantics { contentDescription = "Отменить кружок" },
                onClick = { stopCurrentRecording(cancel = true) },
                icon = Icons.Rounded.Close,
                size = 42.dp,
                iconSize = 18.dp
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottom + 28.dp),
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
                    CircleRecordingClock(elapsedMs = { elapsedMs })
                    Surface(shape = CircleShape, color = AloNativeChatOutgoing) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { stopCurrentRecording(cancel = false) }
                                .semantics { contentDescription = "Отправить кружок" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Stop, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** What the composer asks of a circle being recorded: Telegram's release (send), locked pause (preview) and cancel. */
internal enum class AloCircleCommand { Send, Preview, Cancel }

/** Shared by the composer's held mic and the circle overlay, which owns the camera recording. */
@Stable
internal class AloCircleRecordingControl {
    var command by mutableStateOf<AloCircleCommand?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set
    /** The finger is still on the mic: at the minute Telegram sends the circle rather than opening its preview. */
    var isHeld by mutableStateOf(false)

    fun send(next: AloCircleCommand) {
        command = next
        serial += 1
    }
}

/** Recomposes only itself as the time runs. */
@Composable
private fun CircleRecordingClock(elapsedMs: () -> Long) {
    Text(
        text = aloRecordingClockLabel(elapsedMs()),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = AloNativeAppText
    )
}

private const val MAX_CIRCLE_MS = 59_500L
private const val MIN_CIRCLE_MS = 1_000L
