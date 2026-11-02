package com.alo.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun aloHasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** Cancelling the screen or timing out also unregisters the platform request. */
internal suspend fun aloCurrentLocation(context: Context): Location = withTimeout(20_000) {
    check(aloHasLocationPermission(context)) { "Разрешите доступ к геопозиции, чтобы показать ваше место." }
    val manager = context.getSystemService(LocationManager::class.java)
        ?: error("Служба геопозиции недоступна.")
    val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val provider = listOfNotNull(
        LocationManager.GPS_PROVIDER.takeIf { precise },
        LocationManager.NETWORK_PROVIDER
    ).firstOrNull { manager.isProviderEnabled(it) }
        ?: error("Включите геопозицию в настройках устройства. Выбрать точку на карте можно и без неё.")
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(
                manager, provider, cancellation, ContextCompat.getMainExecutor(context)
            ) { location ->
                if (!continuation.isActive) return@getCurrentLocation
                val age = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                if (location != null && age != null && age in 0..60_000 &&
                    location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
                ) continuation.resume(location)
                else continuation.resumeWithException(IllegalStateException("Не удалось определить текущее место. Повторите попытку."))
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

@Composable
internal fun AloChatLocationPicker(
    onDismiss: () -> Unit,
    onShareStatic: (Double, Double, String, String) -> Unit,
    onShareLive: (AloLiveLocationRequest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var cameraTarget by remember { mutableStateOf<LatLng?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLiveDuration by remember { mutableStateOf(false) }
    var pendingLivePeriod by remember { mutableStateOf<Int?>(null) }
    var manualCoordinates by rememberSaveable { mutableStateOf(false) }
    var manualLatitude by rememberSaveable { mutableStateOf("") }
    var manualLongitude by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    fun requestCurrent() {
        if (locating || submitted) return
        locating = true
        error = null
        scope.launch {
            try {
                val location = aloCurrentLocation(context)
                val period = pendingLivePeriod
                pendingLivePeriod = null
                if (period != null) {
                    // A live share always starts at a fresh device fix, never at the manually selected pin.
                    submitted = true
                    onShareLive(AloLiveLocationRequest(location.latitude, location.longitude, "Живая геопозиция", livePeriodSeconds = period))
                } else {
                    latitude = location.latitude
                    longitude = location.longitude
                    cameraTarget = LatLng(location.latitude, location.longitude)
                }
            } catch (_: TimeoutCancellationException) {
                error = "Определение места заняло слишком много времени. Попробуйте ещё раз или выберите точку на карте."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось определить геопозицию."
            } finally {
                pendingLivePeriod = null
                locating = false
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (aloHasLocationPermission(context)) requestCurrent()
        else {
            pendingLivePeriod = null
            error = "Доступ к геопозиции не предоставлен. Вы можете выбрать точку на карте вручную."
        }
    }
    fun locate(period: Int? = null) {
        if (locating || submitted) return
        pendingLivePeriod = period
        if (aloHasLocationPermission(context)) requestCurrent()
        else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AloNativeAppBackground) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
                        Icon(Icons.Rounded.Close, "Закрыть карту", tint = AloNativeChatAccentBlue, modifier = Modifier.size(24.dp))
                    }
                    Text("Геопозиция", Modifier.align(Alignment.Center), color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.40f).coerceIn(220f, 380f).dp)) {
                            AloLocationMap(cameraTarget) { point ->
                                latitude = point.latitude
                                longitude = point.longitude
                            }
                            if (latitude != null) Icon(
                                Icons.Rounded.LocationOn, "Выбранная точка", tint = AloNativeChatAccentBlue,
                                modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp)
                            )
                            FilledIconButton(
                                onClick = { locate() }, enabled = !locating && !submitted,
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = AloNativeAppSurface, contentColor = AloNativeChatAccentBlue)
                            ) {
                                if (locating) CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Определяем ваше место" }, strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.MyLocation, "Моё место", Modifier.size(24.dp))
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (latitude == null) "Коснитесь карты или переместите её, чтобы выбрать место."
                                else String.format(java.util.Locale.US, "Выбранная точка: %.5f, %.5f", latitude, longitude),
                                color = AloNativeAppMuted, fontSize = 13.sp
                            )
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название места") }, shape = AloSquircle(14.dp))
                            OutlinedTextField(address, { address = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес или примечание") }, shape = AloSquircle(14.dp))
                            TextButton(onClick = { manualCoordinates = !manualCoordinates }) { Text("Указать координаты") }
                            if (manualCoordinates) {
                                OutlinedTextField(manualLatitude, { manualLatitude = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Широта: от −90 до 90") })
                                OutlinedTextField(manualLongitude, { manualLongitude = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Долгота: от −180 до 180") })
                                TextButton(onClick = {
                                    val lat = manualLatitude.replace(',', '.').toDoubleOrNull()
                                    val lon = manualLongitude.replace(',', '.').toDoubleOrNull()
                                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                        latitude = lat; longitude = lon; cameraTarget = LatLng(lat, lon); error = null
                                    } else error = "Проверьте широту и долготу."
                                }) { Text("Выбрать точку") }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        val lat = latitude ?: return@Button
                        val lon = longitude ?: return@Button
                        submitted = true
                        onShareStatic(lat, lon, title.trim().ifBlank { "Место на карте" }, address.trim())
                    }, enabled = latitude != null && longitude != null && !submitted && !locating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = AloSquircle(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AloNativeChatAccentBlue, contentColor = Color.White)
                    ) { Text("Отправить выбранное место", fontSize = 16.sp) }
                    TextButton(onClick = { showLiveDuration = true }, enabled = !locating && !submitted, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Транслировать мою геопозицию", fontSize = 16.sp, color = AloNativeChatAccentBlue)
                    }
                }
            }
        }
        if (showLiveDuration) AlertDialog(
            onDismissRequest = { showLiveDuration = false }, containerColor = AloNativeAppSurface,
            title = { Text("Живая геопозиция", fontSize = 18.sp) },
            text = {
                Column {
                    Text("Место будет обновляться, пока идёт трансляция, в том числе при свёрнутом приложении. Остановить её можно в Alo или в уведомлении.")
                    listOf(900 to "15 минут", 3600 to "1 час", 28800 to "8 часов").forEach { (period, label) ->
                        TextButton(onClick = { showLiveDuration = false; locate(period) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { showLiveDuration = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun AloLocationMap(cameraTarget: LatLng?, onSelect: (LatLng) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    val view = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(view, lifecycle) {
        var started = false
        var resumed = false
        fun sync() {
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.STARTED) && !started) { view.onStart(); started = true }
            if (state.isAtLeast(Lifecycle.State.RESUMED) && !resumed) { view.onResume(); resumed = true }
            if (!state.isAtLeast(Lifecycle.State.RESUMED) && resumed) { view.onPause(); resumed = false }
            if (!state.isAtLeast(Lifecycle.State.STARTED) && started) { view.onStop(); started = false }
        }
        alive = true
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        view.getMapAsync { controller ->
            if (!alive) return@getMapAsync
            map = controller
            controller.cameraPosition = CameraPosition.Builder().target(cameraTarget ?: LatLng(0.0, 0.0)).zoom(if (cameraTarget == null) 1.0 else 15.0).build()
            var userMoving = false
            controller.addOnCameraMoveStartedListener { reason -> userMoving = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE }
            controller.addOnCameraIdleListener {
                if (alive && userMoving) controller.cameraPosition.target?.let { select(it) }
                userMoving = false
            }
            controller.addOnMapClickListener { point ->
                if (alive) { select(point); controller.animateCamera(CameraUpdateFactory.newLatLng(point), 200) }
                true
            }
        }
        val failure = MapView.OnDidFailLoadingMapListener { if (alive) { loading = false; failed = true } }
        view.addOnDidFailLoadingMapListener(failure)
        onDispose {
            alive = false
            lifecycle.removeObserver(observer)
            view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(map, retry) {
        val controller = map ?: return@LaunchedEffect
        loading = true; failed = false
        controller.setStyle(BuildConfig.ALO_MAP_STYLE_URL) { if (alive) loading = false }
        kotlinx.coroutines.delay(20_000)
        if (loading) { loading = false; failed = true }
    }
    LaunchedEffect(cameraTarget, map) {
        cameraTarget?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15.0), 200) }
    }
    Box(Modifier.fillMaxSize().background(AloNativeAppSurface)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = AloNativeChatAccentBlue, strokeWidth = 2.dp)
        if (failed) Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = AloNativeAppSurface, shape = AloSquircle(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Не удалось загрузить карту", color = AloNativeAppText, fontSize = 14.sp)
                TextButton(onClick = { retry++ }) { Text("Повторить") }
            }
        }
    }
}
