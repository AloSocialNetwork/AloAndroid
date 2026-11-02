package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val POST_MAXIMUM_LENGTH = 1_200

/**
 * «Новый пост» (iOS's FeedView composer): close, title, drafts and «Опубликовать» in one row; the
 * avatar beside the text; picked photos and videos in a strip; a media button and the length counter
 * above the keyboard. Closing with content offers to keep a draft on the server.
 */
@Composable
internal fun AloPostEditor(
    state: AloNativeAppUiState,
    me: AloCurrentUser,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)
    ) {
        AloPostEditorContent(state, me, onAction, onDismiss)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloPostEditorContent(
    state: AloNativeAppUiState,
    me: AloCurrentUser,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val picked = remember { mutableStateListOf<AloStoryPickedMedia>() }
    val draftMedia = remember { mutableStateListOf<AloMediaItem>() }
    var draftId by remember { mutableStateOf<Int?>(null) }
    var publishingError by remember { mutableStateOf("") }
    var confirmsClose by remember { mutableStateOf(false) }
    var draftsOpen by remember { mutableStateOf(false) }
    var isSavingDraft by remember { mutableStateOf(false) }
    val startText = LocalAloLiveInput.current.postText
    var field by remember { mutableStateOf(TextFieldValue(startText, TextRange(startText.length))) }
    val text = field.text
    val mediaCount = picked.size + draftMedia.size
    val remaining = POST_MAXIMUM_LENGTH - text.length
    val hasContent = text.isNotBlank() || mediaCount > 0
    val isPublishDisabled = isSavingDraft || remaining < 0 || !hasContent

    fun setText(value: String) {
        field = TextFieldValue(value, TextRange(value.length))
        onAction(AloNativeAppAction.UpdateComposer(value))
    }
    fun uploads() = picked.map { item ->
        AloStoryUploadPage(null, item.uri, context.contentResolver.getType(item.uri) ?: if (item.isVideo) "video/mp4" else "image/jpeg",
            if (item.isVideo) "video" else "image")
    }
    fun close() {
        keyboard?.hide()
        if (hasContent) confirmsClose = true else onDismiss()
    }
    fun publish() {
        publishingError = ""
        keyboard?.hide()
        onAction(AloNativeAppAction.PublishPost(uploads(), draftMedia.toList(), draftId) { failure ->
            if (failure == null) onDismiss() else publishingError = failure
        })
    }
    fun saveDraftAndClose() {
        isSavingDraft = true
        onAction(AloNativeAppAction.SavePostDraft(draftId, uploads(), draftMedia.toList()) { failure ->
            isSavingDraft = false
            if (failure == null) onDismiss() else publishingError = failure
        })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val room = 10 - draftMedia.size
        picked.clear()
        picked.addAll(uris.take(room).map { AloStoryPickedMedia(it, context.contentResolver.getType(it)?.startsWith("video") == true) })
    }
    fun pickMedia() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    LaunchedEffect(Unit) {
        onAction(AloNativeAppAction.LoadPostDrafts)
        // Open straight into typing.
        kotlinx.coroutines.delay(250)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BackHandler { if (draftsOpen) draftsOpen = false else close() }

    Column(
        Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        // Close, title, drafts and publish in one row, as X and Threads lay out a composer.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp).offset(y = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .clickable(enabled = !state.isSubmittingPost && !isSavingDraft, onClick = ::close),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, "Закрыть редактор поста", tint = AloNativeAppText, modifier = Modifier.size(22.dp)) }
                AloDropdownMenu(expanded = confirmsClose, onDismissRequest = { confirmsClose = false }) {
                    Text(
                        if (draftId == null) "Сохранить черновик?" else "Сохранить изменения?",
                        color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Text(
                        if (draftId == null) "Черновик можно будет открыть с любого устройства." else "Черновик останется таким, каким был до открытия.",
                        color = AloNativeAppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 260.dp)
                    )
                    if (draftId != null) {
                        DropdownMenuItem(text = { Text("Сохранить изменения") }, onClick = { confirmsClose = false; saveDraftAndClose() })
                        DropdownMenuItem(text = { Text("Не сохранять") }, onClick = { confirmsClose = false; setText(""); onDismiss() })
                        DropdownMenuItem(text = { Text("Удалить черновик", color = Color(0xFFFF453A)) }, onClick = {
                            confirmsClose = false
                            draftId?.let { onAction(AloNativeAppAction.DeletePostDraft(it)) }
                            setText(""); onDismiss()
                        })
                    } else {
                        DropdownMenuItem(text = { Text("Сохранить черновик") }, onClick = { confirmsClose = false; saveDraftAndClose() })
                        DropdownMenuItem(text = { Text("Удалить", color = Color(0xFFFF453A)) }, onClick = { confirmsClose = false; setText(""); onDismiss() })
                    }
                    DropdownMenuItem(text = { Text("Продолжить редактирование", fontWeight = FontWeight.SemiBold) }, onClick = { confirmsClose = false })
                }
            }
            Text(if (draftId == null) "Новый пост" else "Черновик", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (state.postDrafts.isNotEmpty()) {
                Box(Modifier.clickable(enabled = !state.isSubmittingPost) { keyboard?.hide(); draftsOpen = true }) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.Description, "Черновики: ${state.postDrafts.size}", tint = AloNativeAppText, modifier = Modifier.size(20.dp)) }
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                            .clip(RoundedCornerShape(percent = 50)).background(AloNativeAppAccent).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("${state.postDrafts.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (isPublishDisabled) AloNativeAppSurfaceRaised else AloNativeAppAccent)
                    .clickable(enabled = !isPublishDisabled && !state.isSubmittingPost, onClick = ::publish)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (state.isSubmittingPost || isSavingDraft) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Опубликовать", color = if (isPublishDisabled) AloNativeAppMuted else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (publishingError.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(AloSquircle(14.dp)).background(Color(0xFFFF453A).copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = Color(0xFFFF453A), modifier = Modifier.size(18.dp))
                    Text(publishingError, color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("Повторить", color = AloNativeAppAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = ::publish))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarBadge(name = me.name, avatarUrl = me.avatarUrl, verified = false, size = 44.dp, colorSeed = me.id.toString())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(me.name, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                        if (text.isEmpty()) Text("Что нового?", color = AloNativeAppMuted, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                        BasicTextField(
                            value = field,
                            onValueChange = { field = it; onAction(AloNativeAppAction.UpdateComposer(it.text)) },
                            enabled = !state.isSubmittingPost,
                            textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp, lineHeight = 22.sp),
                            cursorBrush = SolidColor(AloNativeAppAccent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).focusRequester(focus)
                        )
                    }
                }
            }
            if (mediaCount > 0) {
                LazyRow(contentPadding = PaddingValues(start = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(draftMedia.toList(), key = { "d-" + it.url }) { item ->
                        AloPostMediaTile(model = item.posterUrl.ifBlank { item.url }, isVideo = item.type.startsWith("video"),
                            enabled = !state.isSubmittingPost) { draftMedia.remove(item) }
                    }
                    items(picked.toList(), key = { "p-" + it.uri }) { item ->
                        AloPostMediaTile(model = item.uri.toString(), isVideo = item.isVideo, enabled = !state.isSubmittingPost) { picked.remove(item) }
                    }
                    if (mediaCount < 10) {
                        item(key = "add") {
                            Box(
                                Modifier.size(96.dp, 180.dp).clip(AloSquircle(16.dp)).background(AloNativeAppSurface).clickable(onClick = ::pickMedia),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Add, "Добавить медиа", tint = AloNativeAppAccent, modifier = Modifier.size(26.dp)) }
                        }
                    }
                }
            }
        }

        // Mentions: whoever is being typed after «@», as on iOS.
        val mention = aloMentionQuery(text)
        LaunchedEffect(mention) { onAction(AloNativeAppAction.LoadMentionSuggestions(mention)) }
        if (mention != null && state.mentionSuggestions.isNotEmpty()) {
            Column(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(AloSquircle(16.dp)).background(AloNativeAppSurfaceRaised)
            ) {
                state.mentionSuggestions.take(4).forEach { person ->
                    Row(
                        Modifier.fillMaxWidth().clickable { setText(aloInsertMention(person.username, text)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AvatarBadge(name = person.name, avatarUrl = person.avatarUrl, verified = false, size = 32.dp, colorSeed = person.id.toString())
                        Text(person.name, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("@${person.username}", color = AloNativeAppMuted, fontSize = 14.sp)
                    }
                }
            }
        }

        // A glass media button and the length counter, riding above the keyboard.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .clickable(enabled = !state.isSubmittingPost, onClick = ::pickMedia),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Collections, "Фото или видео", tint = AloNativeAppAccent, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.weight(1f))
            if (remaining <= 200) {
                Text("$remaining", color = if (remaining < 0) Color(0xFFFF453A) else AloNativeAppMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (draftsOpen) {
        AloPostDraftsSheet(
            drafts = state.postDrafts,
            onDismiss = { draftsOpen = false },
            onDelete = { onAction(AloNativeAppAction.DeletePostDraft(it.id)) },
            onPick = { draft ->
                draftsOpen = false
                picked.clear()
                draftMedia.clear()
                draftMedia.addAll(draft.media)
                draftId = draft.id
                setText(draft.body)
            }
        )
    }
}

/** A picked or saved photo/video in the strip: 180dp tall, its own width within limits, with ✕. */
@Composable
private fun AloPostMediaTile(model: String, isVideo: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    Box(Modifier.size(135.dp, 180.dp).clip(AloSquircle(16.dp)).background(AloNativeAppSurface)) {
        AloRemoteMediaImage(model = model, contentDescription = "Вложение", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isVideo = isVideo)
        if (isVideo) {
            Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clickable(enabled = enabled, onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Удалить вложение", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Saved drafts, newest first: a tap opens one in the editor, a long press deletes it. */
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
private fun AloPostDraftsSheet(
    drafts: List<AloPostDraft>,
    onDismiss: () -> Unit,
    onDelete: (AloPostDraft) -> Unit,
    onPick: (AloPostDraft) -> Unit
) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    AloModalBottomSheet(onDismissRequest = onDismiss, containerColor = AloNativeAppBackground) {
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Черновики", color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterStart))
                Text("Готово", color = AloNativeAppAccent, fontSize = AloTelegramMetrics.Sheets.ItemTextSize, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onDismiss).padding(6.dp))
            }
            if (drafts.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Description, null, tint = AloNativeAppMuted, modifier = Modifier.size(36.dp))
                    Text("Черновиков нет", color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                    Text("Закройте незаконченный пост и выберите «Сохранить черновик».", color = AloNativeAppMuted, fontSize = 14.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
            LazyColumn {
                items(drafts, key = { it.id }) { draft ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(draft) }, onLongClick = { menuFor = draft.id })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val cover = draft.media.firstOrNull()
                            if (cover != null) {
                                AloRemoteMediaImage(
                                    model = cover.posterUrl.ifBlank { cover.url }, contentDescription = null,
                                    modifier = Modifier.size(52.dp).clip(AloSquircle(10.dp)), contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(Modifier.size(52.dp).clip(AloSquircle(10.dp)).background(AloNativeAppSurfaceRaised), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Description, null, tint = AloNativeAppMuted, modifier = Modifier.size(22.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(draft.body.ifBlank { "Без текста" }, color = AloNativeAppText, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        formatPostTimestamp(draft.updatedAt).takeIf { it.isNotBlank() },
                                        if (draft.media.isNotEmpty()) "медиа: ${draft.media.size}" else null
                                    ).joinToString(" · "),
                                    color = AloNativeAppMuted, fontSize = 13.sp
                                )
                            }
                        }
                        AloDropdownMenu(expanded = menuFor == draft.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Удалить", color = Color(0xFFFF453A)) }, onClick = { menuFor = null; onDelete(draft) })
                        }
                    }
                }
            }
        }
    }
}
