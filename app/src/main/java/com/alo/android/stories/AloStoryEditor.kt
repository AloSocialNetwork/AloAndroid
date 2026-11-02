package com.alo.android

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AloStoryMode { None, Draw, Text, Sticker }

private val AloStoryStickers = listOf(
    "😀", "😂", "🥰", "😍", "😎", "🤩", "🥳", "😴", "🤔", "😮", "😢", "😡",
    "👍", "👏", "🙌", "🙏", "💪", "✌️", "🤝", "👀", "❤️", "🧡", "💛", "💚",
    "💙", "💜", "🔥", "✨", "⭐️", "🌈", "☀️", "🌙", "⚡️", "❄️", "🎉", "🎂",
    "🎁", "🎈", "🎵", "📸", "☕️", "🍕", "🍔", "🍦", "🌸", "🌿", "🐶", "🐱"
)

internal enum class AloStoryAudience(val key: String, val title: String, val tint: Color) {
    Everyone("everyone", "Все", Color(0xFF0A84FF)),
    Friends("friends", "Друзья", Color(0xFF33B866)),
    CloseFriends("close_friends", "Близкие друзья", Color(0xFF29C759)),
    Selected("selected", "Выбранные", Color(0xFF945CF2));

    val icon get() = when (this) {
        Everyone -> Icons.Rounded.Language
        Friends -> Icons.Rounded.Group
        CloseFriends -> Icons.Rounded.Star
        Selected -> Icons.Rounded.PersonSearch
    }
}

internal fun aloPeopleCount(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "человек"
        mod10 in 2..4 && mod100 !in 12..14 -> "человека"
        else -> "человек"
    }
    return "$count $word"
}

/**
 * «Новая история» after Telegram's editor (iOS's AloStoryEditorScreen): a full-screen 9:16 canvas with
 * drawing, emoji stickers, styled text on a gradient (a text story) or photos/videos with a caption.
 * «Далее» asks who may see it and for how long; pages with edits are flattened into 1080 × 1920 pictures.
 */
@Composable
internal fun AloStoryEditor(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!state.isSubmittingStory) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AloStoryEditorContent(state, onAction, onDismiss)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloStoryEditorContent(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val media = remember { mutableStateListOf<AloStoryPickedMedia>() }
    val edits = remember { mutableStateMapOf<String, AloStoryPageEdits>() }
    var mode by remember { mutableStateOf(AloStoryMode.None) }
    var look by remember { mutableStateOf(AloStoryTextLook()) }
    var gradientIndex by remember { mutableStateOf(0) }
    var draftText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var caption by remember { mutableStateOf("") }
    var brushLevel by remember { mutableFloatStateOf(0.3f) }
    var brushColor by remember { mutableStateOf(Color.White) }
    var isSizingBrush by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    var publishOpen by remember { mutableStateOf(false) }
    var audience by remember { mutableStateOf(AloStoryAudience.Everyone) }
    var selectedPeople by remember { mutableStateOf(listOf<AloEntitySummary>()) }
    var lifetimeHours by remember { mutableStateOf(24) }
    var isPreparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var draggingOverTrash by remember { mutableStateOf<Boolean?>(null) }
    var nextId by remember { mutableStateOf(1L) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val pager = rememberPagerState { media.size.coerceAtLeast(1) }
    val pageKey = media.getOrNull(pager.currentPage)?.uri?.toString() ?: "text"
    val pageEdits = edits[pageKey] ?: AloStoryPageEdits()
    val textFocus = remember { FocusRequester() }

    fun updateEdits(change: (AloStoryPageEdits) -> AloStoryPageEdits) {
        edits[pageKey] = change(edits[pageKey] ?: AloStoryPageEdits())
    }
    fun startText(id: Long?) {
        val item = id?.let { target -> pageEdits.items.firstOrNull { it.id == target } }
        draftText = item?.text.orEmpty()
        if (item != null) look = item.look
        editingId = id
        selectedId = null
        mode = AloStoryMode.Text
    }
    fun commitText() {
        val text = draftText.trim()
        var placed: Long? = null
        updateEdits { current ->
            val editing = editingId
            val items = current.items.toMutableList()
            val index = editing?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
            when {
                index >= 0 && text.isEmpty() -> items.removeAt(index)
                index >= 0 -> { items[index] = items[index].copy(text = text, look = look); placed = items[index].id }
                text.isNotEmpty() -> {
                    val item = AloStoryItem(id = nextId++, text = text, look = look, y = 0.4f)
                    items += item; placed = item.id
                }
            }
            current.copy(items = items)
        }
        keyboard?.hide()
        editingId = null
        draftText = ""
        selectedId = placed
        mode = if (placed == null) AloStoryMode.None else AloStoryMode.Sticker
    }
    fun deselect() { selectedId = null; mode = AloStoryMode.None }
    /** When the first photo or video arrives, what was made on the text story moves onto it. */
    fun adoptTextPage(into: String) {
        if (mode == AloStoryMode.Text) commitText()
        if (mode == AloStoryMode.Sticker) deselect()
        edits.remove("text")?.takeIf { !it.isEmpty }?.let { edits[into] = it }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { uri -> AloStoryPickedMedia(uri, context.contentResolver.getType(uri)?.startsWith("video") == true) }
        media.clear()
        media.addAll(picked)
        edits.keys.filter { it != "text" }.forEach { edits.remove(it) }
        adoptTextPage(picked.first().uri.toString())
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) {
            media.add(AloStoryPickedMedia(uri, isVideo = false))
            if (media.size == 1) adoptTextPage(uri.toString())
            scope.launch { pager.scrollToPage(media.lastIndex) }
        }
    }

    LaunchedEffect(Unit) {
        // A new story starts as a text story with the keyboard up.
        kotlinx.coroutines.delay(250)
        if (media.isEmpty() && mode == AloStoryMode.None) startText(null)
    }
    LaunchedEffect(mode) { if (mode == AloStoryMode.Text) { kotlinx.coroutines.delay(60); runCatching { textFocus.requestFocus() } } }
    BackHandler {
        when (mode) {
            AloStoryMode.Text -> commitText()
            AloStoryMode.Sticker, AloStoryMode.Draw -> deselect()
            else -> if (!state.isSubmittingStory) onDismiss()
        }
    }

    val isPublishDisabled = isPreparing || state.isSubmittingStory ||
        (media.isEmpty() && (edits["text"]?.isEmpty ?: true) && !(mode == AloStoryMode.Text && draftText.isNotBlank()))

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        val barHeight = 64.dp
        val availableWidth = maxWidth - 16.dp
        val availableHeight = maxHeight - barHeight - 12.dp
        val canvasWidth = minOf(availableWidth, availableHeight * 9f / 16f)
        val canvasHeight = canvasWidth * 16f / 9f
        val canvasWidthPx = with(density) { canvasWidth.toPx() }
        val canvasHeightPx = with(density) { canvasHeight.toPx() }

        Column(Modifier.fillMaxSize().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(canvasWidth, canvasHeight)
                    .clip(AloSquircle(22.dp))
            ) {
                // The page: a gradient for a text story, the picked photos and videos otherwise.
                if (media.isEmpty()) {
                    val colors = AloStoryTextLook.gradients[gradientIndex]
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
                } else {
                    HorizontalPager(state = pager, userScrollEnabled = mode == AloStoryMode.None, modifier = Modifier.fillMaxSize()) { index ->
                        val item = media[index]
                        if (item.isVideo) {
                            AloStoryLoopingVideo(item.uri, isActive = index == pager.currentPage)
                        } else {
                            AloRemoteMediaImage(
                                model = item.uri.toString(), contentDescription = "Медиа истории",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                AloStoryDrawingLayer(
                    strokes = pageEdits.strokes,
                    isActive = mode == AloStoryMode.Draw,
                    brushColor = brushColor,
                    brushWidth = with(density) { AloStoryBrush.widthDp(brushLevel).dp.toPx() } / canvasWidthPx,
                    onStroke = { stroke -> updateEdits { it.copy(strokes = it.strokes + stroke) } }
                )

                // A tap on the empty page drops the selection or, on a text story, starts typing.
                if (mode == AloStoryMode.Sticker || (mode == AloStoryMode.None && media.isEmpty())) {
                    Box(
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (mode == AloStoryMode.Sticker) deselect() else startText(null)
                        }
                    )
                }
                if (mode == AloStoryMode.None && media.isEmpty() && pageEdits.items.none { it.isText }) {
                    Text("Нажмите, чтобы написать", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                }

                // The items: drag to move, pinch to stretch, twist to turn; drop on the trash to remove.
                pageEdits.items.filter { it.id != editingId }.forEach { item ->
                    androidx.compose.runtime.key(item.id) {
                        AloStoryItemView(
                            item = item,
                            canvasWidthPx = canvasWidthPx,
                            canvasHeightPx = canvasHeightPx,
                            isSelected = mode == AloStoryMode.Sticker && selectedId == item.id,
                            enabled = mode == AloStoryMode.None || mode == AloStoryMode.Sticker,
                            onTap = {
                                if (item.isText && mode == AloStoryMode.Sticker && selectedId == item.id) startText(item.id)
                                else { keyboard?.hide(); selectedId = item.id; mode = AloStoryMode.Sticker }
                            },
                            onDrag = { centre ->
                                draggingOverTrash = centre.y > canvasHeightPx - with(density) { 90.dp.toPx() } &&
                                    abs(centre.x - canvasWidthPx / 2) < with(density) { 60.dp.toPx() }
                            },
                            onCommit = { updated ->
                                val overTrash = draggingOverTrash == true
                                draggingOverTrash = null
                                updateEdits { current ->
                                    current.copy(items = if (overTrash) current.items.filter { it.id != item.id }
                                    else current.items.map { if (it.id == item.id) updated else it })
                                }
                                if (overTrash && selectedId == item.id) deselect()
                            }
                        )
                    }
                }

                if (media.isNotEmpty() && mode == AloStoryMode.None) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = if (media.size > 1) 34.dp else 14.dp)
                            .fillMaxWidth()
                            .clip(AloSquircle(22.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.18f), AloSquircle(22.dp))
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        if (caption.isEmpty()) Text("Добавить подпись…", color = Color.White.copy(alpha = 0.62f), fontSize = 17.sp)
                        BasicTextField(
                            value = caption, onValueChange = { caption = it.take(600) }, maxLines = 4,
                            textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (media.size > 1) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(media.size) { index ->
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (index == pager.currentPage) 1f else 0.4f)))
                            }
                        }
                    }
                }

                if (mode == AloStoryMode.Text) {
                    AloStoryTextEditor(
                        value = draftText,
                        onValueChange = { draftText = it.take(600) },
                        look = look,
                        placeholder = if (media.isEmpty()) "Что происходит?" else "Текст",
                        dim = if (media.isEmpty()) 0.12f else 0.42f,
                        wrapWidth = canvasWidth * AloStoryTextLook.TEXT_WRAP,
                        canvasHeight = canvasHeight,
                        focusRequester = textFocus,
                        onDone = ::commitText
                    )
                }
                if (mode == AloStoryMode.Draw) {
                    AloStoryBrushControls(
                        level = brushLevel,
                        onLevel = { brushLevel = it },
                        isSizing = isSizingBrush,
                        onSizing = { isSizingBrush = it },
                        color = brushColor,
                        onColor = { brushColor = it },
                        sliderHeight = minOf(240.dp, canvasHeight * 0.36f)
                    )
                }

                // Top controls, per mode, as on iOS.
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (mode) {
                            AloStoryMode.None -> {
                                AloStoryCircleButton(Icons.Rounded.Close, "Закрыть редактор истории") { if (!state.isSubmittingStory) onDismiss() }
                                Spacer(Modifier.weight(1f))
                                AloStoryCircleButton(Icons.Outlined.Gesture, "Рисовать") { keyboard?.hide(); mode = AloStoryMode.Draw }
                                AloStoryCircleButton(Icons.Outlined.SentimentSatisfied, "Стикеры") { keyboard?.hide(); stickersOpen = true }
                                AloStoryCircleButton(null, "Добавить текст", label = "Aa") { startText(null) }
                                if (media.isNotEmpty()) {
                                    AloStoryCircleButton(Icons.Outlined.Delete, "Убрать это медиа") {
                                        val index = pager.currentPage
                                        if (index in media.indices) {
                                            edits.remove(media[index].uri.toString())
                                            media.removeAt(index)
                                        }
                                    }
                                }
                            }
                            AloStoryMode.Draw -> {
                                AloStoryCircleButton(Icons.AutoMirrored.Rounded.Undo, "Отменить штрих") {
                                    updateEdits { it.copy(strokes = it.strokes.dropLast(1)) }
                                }
                                Spacer(Modifier.weight(1f))
                                AloStoryDoneButton { mode = AloStoryMode.None }
                            }
                            AloStoryMode.Text -> {
                                if (media.isEmpty()) {
                                    AloStoryCircleButton(Icons.Outlined.Palette, "Фон") {
                                        gradientIndex = (gradientIndex + 1) % AloStoryTextLook.gradients.size
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                AloStoryDoneButton(::commitText)
                            }
                            AloStoryMode.Sticker -> {
                                AloStoryCircleButton(Icons.Rounded.Delete, "Удалить") {
                                    selectedId?.let { id -> updateEdits { current -> current.copy(items = current.items.filter { it.id != id }) } }
                                    deselect()
                                }
                                Spacer(Modifier.weight(1f))
                                AloStoryDoneButton(::deselect)
                            }
                        }
                    }
                    if (mode == AloStoryMode.Text) AloStoryTextStyleBar(look) { look = it }
                }

                if (draggingOverTrash != null) {
                    val over = draggingOverTrash == true
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp)
                            .size(52.dp)
                            .graphicsLayer { scaleX = if (over) 1.15f else 1f; scaleY = if (over) 1.15f else 1f }
                            .clip(CircleShape)
                            .background(if (over) Color(0xFFFF453A) else Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isPreparing || state.isSubmittingStory) {
                        Row(
                            Modifier.clip(AloSquircle(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Text(if (isPreparing) "Готовим историю…" else "Публикуем…", color = Color.White, fontSize = 14.sp)
                        }
                    }
                    if (error.isNotBlank()) {
                        Row(
                            Modifier.clip(AloSquircle(14.dp)).background(Color(0xFFFF453A).copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Warning, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(error, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Bottom bar: gallery, camera, and «Далее» with the audience's symbol.
            Row(
                Modifier.fillMaxWidth().height(barHeight).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AloStoryBarButton(Icons.Outlined.Collections, "Медиа") {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                AloStoryBarButton(Icons.Outlined.CameraAlt, "Снять на камеру") {
                    val directory = File(context.cacheDir, "story-camera").apply { mkdirs() }
                    val file = File(directory, "photo-${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.story-camera", file)
                    cameraUri = uri
                    runCatching { camera.launch(uri) }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (isPublishDisabled) AloNativeAppSurfaceRaised else AloNativeAppAccent)
                        .clickable(enabled = !isPublishDisabled) {
                            if (mode == AloStoryMode.Text) commitText()
                            onAction(AloNativeAppAction.LoadStoryAudience)
                            publishOpen = true
                        }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.isSubmittingStory || isPreparing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        val color = if (isPublishDisabled) AloNativeAppMuted else Color.White
                        Text("Далее", color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Icon(audience.icon, null, tint = color, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (stickersOpen) {
            AloStoryStickerPicker(onDismiss = { stickersOpen = false }) { emoji ->
                stickersOpen = false
                val item = AloStoryItem(id = nextId++, emoji = emoji, y = 0.56f)
                updateEdits { it.copy(items = it.items + item) }
                // Selected straight away: its frame shows it can be moved and stretched.
                selectedId = item.id
                mode = AloStoryMode.Sticker
            }
        }
        if (publishOpen) {
            AloStoryPublishSheet(
                state = state,
                audience = audience,
                onAudience = { audience = it },
                selectedPeople = selectedPeople,
                onSelectedPeople = { selectedPeople = it },
                lifetimeHours = lifetimeHours,
                onLifetime = { lifetimeHours = it },
                onAction = onAction,
                onDismiss = { publishOpen = false },
                onPublish = {
                    publishOpen = false
                    if (mode == AloStoryMode.Text) commitText()
                    selectedId = null
                    mode = AloStoryMode.None
                    isPreparing = true
                    error = ""
                    scope.launch {
                        val snapshot = edits.toMap()
                        val pages = runCatching {
                            withContext(Dispatchers.Default) {
                                if (media.isEmpty()) {
                                    // A text story is its picture: the text is drawn on the gradient, not repeated below.
                                    listOf(AloStoryUploadPage(
                                        AloStoryRenderer.render(context, null, gradientIndex, snapshot["text"] ?: AloStoryPageEdits(), canvasWidthPx, density.density),
                                        null, "image/jpeg", "image"
                                    ))
                                } else media.map { item ->
                                    val pageEditsForItem = snapshot[item.uri.toString()] ?: AloStoryPageEdits()
                                    when {
                                        // Video overlays need a re-encode Android can't do here; the clip goes as it is.
                                        item.isVideo -> AloStoryUploadPage(null, item.uri, context.contentResolver.getType(item.uri) ?: "video/mp4", "video")
                                        pageEditsForItem.isEmpty -> AloStoryUploadPage(null, item.uri, context.contentResolver.getType(item.uri) ?: "image/jpeg", "image")
                                        else -> AloStoryUploadPage(
                                            AloStoryRenderer.render(context, item.uri, gradientIndex, pageEditsForItem, canvasWidthPx, density.density),
                                            null, "image/jpeg", "image"
                                        )
                                    }
                                }
                            }
                        }.getOrElse {
                            isPreparing = false
                            error = "Не удалось подготовить историю."
                            return@launch
                        }
                        isPreparing = false
                        onAction(
                            AloNativeAppAction.PublishStory(
                                pages = pages,
                                body = if (media.isEmpty()) "" else caption.trim(),
                                lifetimeHours = lifetimeHours,
                                audience = audience.key,
                                audienceUserIds = if (audience == AloStoryAudience.Selected) selectedPeople.map { it.id } else emptyList()
                            ) { failure ->
                                if (failure == null) onDismiss() else error = failure
                            }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AloStoryCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector?, description: String, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
        else Text(label.orEmpty(), color = Color.White, fontSize = 19.sp)
    }
}

@Composable
private fun AloStoryBarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun AloStoryDoneButton(onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(percent = 50)).background(AloNativeAppAccent).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Готово", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Typeface, plate and colour of the text being typed. */
@Composable
private fun AloStoryTextStyleBar(look: AloStoryTextLook, onLook: (AloStoryTextLook) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(width = 44.dp, height = 40.dp).clip(AloSquircle(14.dp)).background(Color.Black.copy(alpha = 0.32f))
                    .clickable { onLook(look.copy(typeface = (look.typeface + 1) % AloStoryTextLook.TYPEFACES)) },
                contentAlignment = Alignment.Center
            ) { Text("Aa", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.padding(horizontal = 2.dp).size(width = 1.dp, height = 26.dp).background(Color.White.copy(alpha = 0.3f)))
            (0 until 5).forEach { plate ->
                val sample = look.copy(plate = plate)
                val chosen = look.plate == plate
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(AloSquircle(12.dp))
                        .background(Color.Black.copy(alpha = 0.32f))
                        .border(if (chosen) 2.dp else 0.dp, if (chosen) Color.White else Color.Transparent, AloSquircle(12.dp))
                        .clickable { onLook(look.copy(plate = plate)) },
                    contentAlignment = Alignment.Center
                ) {
                    val shape = RoundedCornerShape(5.dp)
                    Box(
                        Modifier
                            .then(sample.plateFill?.let { Modifier.background(it, shape) } ?: Modifier)
                            .then(sample.plateStroke?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
                            .padding(horizontal = if (sample.hasPlate) 6.dp else 0.dp, vertical = if (sample.hasPlate) 2.dp else 0.dp)
                    ) {
                        Text("А", color = sample.textColor, fontSize = 15.sp, fontFamily = sample.fontFamily, fontWeight = sample.fontWeight)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AloStoryTextLook.colors.forEachIndexed { index, color ->
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onLook(look.copy(colorIndex = index)) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(color).border(if (look.colorIndex == index) 3.dp else 1.5.dp, Color.White, CircleShape))
                }
            }
        }
    }
}

/** Typing a text item: the page dims and the text sits in its style, wrapping like the placed item. */
@Composable
private fun AloStoryTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    look: AloStoryTextLook,
    placeholder: String,
    dim: Float,
    wrapWidth: Dp,
    canvasHeight: Dp,
    focusRequester: FocusRequester,
    onDone: () -> Unit
) {
    val density = LocalDensity.current
    val fontSize = with(density) { (AloStoryTextLook.TEXT_SIZE * look.sizeFactor).dp.toSp() }
    val style = TextStyle(color = look.textColor, fontSize = fontSize, fontFamily = look.fontFamily, fontWeight = look.fontWeight, textAlign = TextAlign.Center)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center
    ) {
        val shape = AloSquircle(12.dp)
        Box(
            Modifier
                .offset(y = -canvasHeight * 0.1f)
                .widthIn(max = wrapWidth + if (look.hasPlate) 28.dp else 0.dp)
                .width(IntrinsicSize.Max)
                .then(look.plateFill?.let { Modifier.background(it, shape) } ?: Modifier)
                .then(look.plateStroke?.let { Modifier.border(2.5.dp, it, shape) } ?: Modifier)
                .padding(horizontal = if (look.hasPlate) 14.dp else 0.dp, vertical = if (look.hasPlate) 8.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = look.textColor.copy(alpha = 0.6f)))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                cursorBrush = SolidColor(AloNativeAppAccent),
                maxLines = 8,
                modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 4.dp).focusRequester(focusRequester)
            )
        }
    }
}

/** One placed item: its content at its size, a dashed frame with corner handles when selected. */
@Composable
private fun AloStoryItemView(
    item: AloStoryItem,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    isSelected: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
    onCommit: (AloStoryItem) -> Unit
) {
    var offset by remember(item) { mutableStateOf(Offset.Zero) }
    var pinch by remember(item) { mutableFloatStateOf(1f) }
    var twist by remember(item) { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scale = (item.scale * pinch).coerceIn(0.35f, 5f)
    val centreX = item.x * canvasWidthPx + offset.x
    val centreY = item.y * canvasHeightPx + offset.y
    Box(
        Modifier
            .offset { IntOffset((centreX - size.width / 2f).roundToInt(), (centreY - size.height / 2f).roundToInt()) }
            .onSizeChanged { size = it }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(item.id, enabled) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var travel = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            travel += pan
                            if (!moved && (travel.getDistance() > viewConfiguration.touchSlop || event.changes.size > 1)) moved = true
                            if (moved) {
                                offset += pan
                                pinch *= zoom
                                twist += rotation
                                onDrag(Offset(item.x * canvasWidthPx + offset.x, item.y * canvasHeightPx + offset.y))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!moved) {
                            onTap()
                        } else {
                            onCommit(
                                item.copy(
                                    x = ((item.x * canvasWidthPx + offset.x) / canvasWidthPx).coerceIn(0f, 1f),
                                    y = ((item.y * canvasHeightPx + offset.y) / canvasHeightPx).coerceIn(0f, 1f),
                                    scale = (item.scale * pinch).coerceIn(0.35f, 5f),
                                    rotation = AloStoryRotation.snapped(item.rotation + twist)
                                )
                            )
                        }
                    }
                }
            )
            .graphicsLayer { rotationZ = AloStoryRotation.snapped(item.rotation + twist) }
            .then(if (isSelected) Modifier.drawBehind {
                drawRect(Color.White.copy(alpha = 0.9f), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                val handle = 10.dp.toPx()
                listOf(Offset.Zero, Offset(this.size.width, 0f), Offset(0f, this.size.height), Offset(this.size.width, this.size.height)).forEach { corner ->
                    drawRect(Color.White, topLeft = corner - Offset(handle / 2, handle / 2), size = Size(handle, handle))
                }
            } else Modifier)
            .padding(10.dp)
    ) {
        AloStoryItemContent(item, scale, canvasWidthPx)
    }
}

/** How an item looks; the size goes into the font (not a scale transform), so stretched text stays sharp. */
@Composable
private fun AloStoryItemContent(item: AloStoryItem, scale: Float, canvasWidthPx: Float) {
    val density = LocalDensity.current
    if (item.emoji != null) {
        Text(item.emoji, fontSize = with(density) { (AloStoryTextLook.EMOJI_SIZE * scale).dp.toSp() })
        return
    }
    val look = item.look
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        color = look.textColor,
        fontSize = with(density) { (AloStoryTextLook.TEXT_SIZE * look.sizeFactor * scale).dp.toSp() },
        fontFamily = look.fontFamily, fontWeight = look.fontWeight, textAlign = TextAlign.Center
    )
    val wrap = (canvasWidthPx * AloStoryTextLook.TEXT_WRAP * scale).roundToInt().coerceAtLeast(1)
    val layout = remember(item.text, style, wrap) { measurer.measure(item.text, style, constraints = Constraints(maxWidth = wrap)) }
    val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
    val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
    val padX = with(density) { if (look.hasPlate) 14.dp.toPx() * scale else 0f }
    val padY = with(density) { if (look.hasPlate) 8.dp.toPx() * scale else 0f }
    val width = right - left + padX * 2
    val height = layout.size.height + padY * 2
    Canvas(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
        val radius = 12.dp.toPx() * scale
        look.plateFill?.let { drawRoundRect(it, cornerRadius = CornerRadius(radius)) }
        look.plateStroke?.let {
            val stroke = 2.5.dp.toPx() * scale
            drawRoundRect(it, topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
                cornerRadius = CornerRadius(radius), style = Stroke(stroke))
        }
        drawText(layout, topLeft = Offset(padX - left, padY))
    }
}

/** The page's drawing; live (taking strokes) only in draw mode. */
@Composable
private fun AloStoryDrawingLayer(
    strokes: List<AloStoryStroke>,
    isActive: Boolean,
    brushColor: Color,
    brushWidth: Float,
    onStroke: (AloStoryStroke) -> Unit
) {
    val current = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(Size(1f, 1f)) }
    Canvas(
        Modifier
            .fillMaxSize()
            .then(if (!isActive) Modifier else Modifier.pointerInput(brushColor, brushWidth) {
                detectDragGestures(
                    onDragStart = { start -> current.clear(); current.add(Offset(start.x / size.width, start.y / size.height)) },
                    onDragEnd = {
                        if (current.isNotEmpty()) onStroke(AloStoryStroke(current.toList(), brushColor, brushWidth))
                        current.clear()
                    },
                    onDragCancel = { current.clear() },
                    onDrag = { change, _ ->
                        change.consume()
                        current.add(Offset(change.position.x / size.width, change.position.y / size.height))
                    }
                )
            })
    ) {
        canvasSize = size
        fun draw(points: List<Offset>, color: Color, width: Float) {
            if (points.isEmpty()) return
            drawPath(
                AloStoryRenderer.strokePath(points, size.width, size.height).asComposePath(),
                color = color,
                style = Stroke(width = width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        strokes.forEach { draw(it.points, it.color, it.width) }
        if (current.isNotEmpty()) draw(current.toList(), brushColor, brushWidth)
    }
}

/** Instagram's brush size (a tapered slider on the left, a live dot while it moves) and colours. */
@Composable
private fun AloStoryBrushControls(
    level: Float,
    onLevel: (Float) -> Unit,
    isSizing: Boolean,
    onSizing: (Boolean) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    sliderHeight: Dp
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        if (isSizing) {
            val dot = AloStoryBrush.widthDp(level).dp
            Box(Modifier.align(Alignment.Center).size(dot).clip(CircleShape).background(color).border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape))
        }
        val knob = 24.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .size(44.dp, sliderHeight)
                .pointerInput(Unit) {
                    val heightPx = sliderHeight.toPx()
                    val knobPx = knob.toPx()
                    fun levelAt(y: Float) = (1f - (y - knobPx / 2) / (heightPx - knobPx)).coerceIn(0f, 1f)
                    detectDragGestures(
                        onDragStart = { onSizing(true); onLevel(levelAt(it.y)) },
                        onDragEnd = { onSizing(false) },
                        onDragCancel = { onSizing(false) },
                        onDrag = { change, _ -> change.consume(); onLevel(levelAt(change.position.y)) }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(Modifier.size(16.dp, sliderHeight)) {
                val top = size.width / 2
                val bottom = size.width / 8
                val path = androidx.compose.ui.graphics.Path().apply {
                    addArc(androidx.compose.ui.geometry.Rect(Offset(size.width / 2, top), top), 180f, 180f)
                    lineTo(size.width / 2 + bottom, size.height - bottom)
                    addArc(androidx.compose.ui.geometry.Rect(Offset(size.width / 2, size.height - bottom), bottom), 0f, 180f)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.32f))
                drawPath(path, Color.White.copy(alpha = 0.65f), style = Stroke(1.dp.toPx()))
            }
            Box(
                Modifier
                    .offset { IntOffset(0, ((1f - level) * (sliderHeight - knob).toPx()).roundToInt()) }
                    .size(knob)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AloStoryBrush.colors.forEach { swatch ->
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onColor(swatch) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(swatch).border(if (swatch == color) 3.dp else 1.5.dp, Color.White, CircleShape))
                }
            }
        }
    }
}

/** A curated emoji set; tapping one places it in the middle of the page. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AloStoryStickerPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AloModalBottomSheet(onDismissRequest = onDismiss, containerColor = AloNativeAppBackground) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            AloSheetTitle("Стикеры")
            LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.padding(horizontal = 14.dp).height(360.dp)) {
                items(AloStoryStickers) { emoji ->
                    Box(Modifier.height(56.dp).clip(AloSquircle(12.dp)).clickable { onPick(emoji) }, contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 34.sp)
                    }
                }
            }
        }
    }
}

/** A picked video on the page: muted, looping, playing only while its page shows. */
@Composable
private fun AloStoryLoopingVideo(uri: Uri, isActive: Boolean) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(uri)
                setOnPreparedListener { player -> player.isLooping = true; player.setVolume(0f, 0f); if (isActive) start() }
                view = this
            }
        },
        onRelease = { it.stopPlayback() }
    )
    LaunchedEffect(isActive, view) { view?.let { if (isActive) it.start() else it.pause() } }
}

/** «Кто увидит историю»: everyone, friends, close friends or chosen people, and how long it lasts. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AloStoryPublishSheet(
    state: AloNativeAppUiState,
    audience: AloStoryAudience,
    onAudience: (AloStoryAudience) -> Unit,
    selectedPeople: List<AloEntitySummary>,
    onSelectedPeople: (List<AloEntitySummary>) -> Unit,
    lifetimeHours: Int,
    onLifetime: (Int) -> Unit,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit,
    onPublish: () -> Unit
) {
    var picker by remember { mutableStateOf<AloStoryAudience?>(null) }
    val closeFriends = state.storySettings.closeFriends
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AloNativeAppBackground
    ) {
        val pickerFor = picker
        if (pickerFor != null) {
            AloPeoplePicker(
                title = when (pickerFor) {
                    AloStoryAudience.CloseFriends -> "Близкие друзья"
                    AloStoryAudience.Selected -> "Кто увидит"
                    else -> "Скрыть от"
                },
                people = state.friends,
                initial = when (pickerFor) {
                    AloStoryAudience.CloseFriends -> closeFriends.map { it.id }.toSet()
                    AloStoryAudience.Selected -> selectedPeople.map { it.id }.toSet()
                    else -> state.storySettings.hiddenFrom.map { it.id }.toSet()
                },
                onBack = { picker = null },
                onDone = { ids ->
                    when (pickerFor) {
                        AloStoryAudience.CloseFriends -> onAction(AloNativeAppAction.UpdateCloseFriends(ids.toList()))
                        AloStoryAudience.Selected -> onSelectedPeople(state.friends.filter { it.id in ids })
                        else -> onAction(AloNativeAppAction.UpdateStoryHiddenFrom(ids.toList()))
                    }
                    picker = null
                }
            )
            return@AloModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AloSheetTitle("Кто увидит историю", bleed = 16.dp)
            Column(Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.7f))) {
                AloStoryAudience.entries.forEachIndexed { index, option ->
                    val chosen = audience == option
                    val detail = when (option) {
                        AloStoryAudience.Everyone, AloStoryAudience.Friends ->
                            if (state.storySettings.hiddenFrom.isEmpty()) "Скрыть от…" else "Скрыто от: ${aloPeopleCount(state.storySettings.hiddenFrom.size)}"
                        AloStoryAudience.CloseFriends -> if (closeFriends.isEmpty()) "Выбрать близких друзей" else "${aloPeopleCount(closeFriends.size)} · Изменить"
                        AloStoryAudience.Selected -> if (selectedPeople.isEmpty()) "Выбрать людей" else "${aloPeopleCount(selectedPeople.size)} · Изменить"
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onAudience(option)
                            if (option == AloStoryAudience.Selected && selectedPeople.isEmpty()) picker = option
                            if (option == AloStoryAudience.CloseFriends && closeFriends.isEmpty()) picker = option
                        }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(option.tint), contentAlignment = Alignment.Center) {
                            Icon(option.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(option.title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            // The link chooses this audience too: its touch area grows over the title above it.
                            Text(detail, color = AloNativeAppAccent, fontSize = 14.sp, modifier = Modifier.clickable {
                                onAudience(option)
                                picker = option
                            })
                        }
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .then(if (chosen) Modifier.background(AloNativeAppAccent) else Modifier.border(1.5.dp, AloNativeAppMuted.copy(alpha = 0.6f), CircleShape)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (chosen) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (index < AloStoryAudience.entries.lastIndex) {
                        Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(0.5.dp).background(AloNativeAppBorder))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("История исчезнет через", color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().height(36.dp).clip(AloSquircle(10.dp)).background(Color.White.copy(alpha = 0.10f)).padding(2.dp)) {
                    listOf(6, 12, 24, 48).forEach { hours ->
                        Box(
                            Modifier.weight(1f).fillMaxSize().clip(AloSquircle(8.dp))
                                .background(if (hours == lifetimeHours) Color.White.copy(alpha = 0.28f) else Color.Transparent)
                                .clickable { onLifetime(hours) },
                            contentAlignment = Alignment.Center
                        ) { Text("$hours ч", color = AloNativeAppText, fontSize = 14.sp, fontWeight = if (hours == lifetimeHours) FontWeight.SemiBold else FontWeight.Normal) }
                    }
                }
            }
            val enabled = !(audience == AloStoryAudience.Selected && selectedPeople.isEmpty())
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (enabled) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                    .clickable(enabled = enabled, onClick = onPublish),
                contentAlignment = Alignment.Center
            ) { Text("Опубликовать", color = if (enabled) Color.White else AloNativeAppMuted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Choosing people from your friends: a searchable list with ticks and «Готово». */
@Composable
private fun AloPeoplePicker(
    title: String,
    people: List<AloEntitySummary>,
    initial: Set<Int>,
    onBack: () -> Unit,
    onDone: (Set<Int>) -> Unit
) {
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val shown = people.filter { needle.isBlank() || it.name.lowercase().contains(needle) || it.username.lowercase().contains(needle) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Назад", color = AloNativeAppAccent, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(6.dp))
            Text(title, color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
            Text("Готово", color = AloNativeAppAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterEnd).clickable { onDone(chosen) }.padding(6.dp))
        }
        AloSheetSearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (people.isEmpty()) {
            Text("Пока нет друзей", color = AloNativeAppMuted, fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
        }
        shown.forEach { person ->
            val selected = person.id in chosen
            AloUserCell(
                name = person.name,
                status = person.username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
                avatarUrl = person.avatarUrl,
                colorSeed = person.id.toString(),
                style = AloUserCellStyle.Contact,
                onClick = { chosen = if (selected) chosen - person.id else chosen + person.id },
                trailing = {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .then(if (selected) Modifier.background(AloNativeAppAccent) else Modifier.border(1.5.dp, AloNativeAppMuted.copy(alpha = 0.6f), CircleShape)),
                        contentAlignment = Alignment.Center
                    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            )
        }
    }
}
