package com.alo.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AloLocalGalleryItem(
    val uri: String,
    val mimeType: String,
    val name: String,
    val isVideo: Boolean
)

@Composable
internal fun ChatAttachmentSheet(
    capabilities: AloChatAttachmentCapabilities,
    contacts: List<AloEntitySummary>,
    onDismiss: () -> Unit,
    onPickGalleryUri: (String) -> Unit,
    onPickGalleryUris: (List<String>) -> Unit,
    onPickFile: () -> Unit,
    onShareContact: (AloEntitySummary) -> Unit,
    onShareStaticLocation: (Double, Double, String, String) -> Unit,
    onShareLiveLocation: (AloLiveLocationRequest) -> Unit,
    onStartVoice: () -> Unit,
    onStartCircle: () -> Unit,
    onCreatePoll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var galleryReloadKey by remember { mutableStateOf(0) }
    var selectedUris by remember { mutableStateOf(listOf<String>()) }
    var permissionRequested by remember { mutableStateOf(false) }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val sheetHeight = AloAttachMetrics.sheetHeight(screenHeightDp)
    var isContactPickerOpen by remember { mutableStateOf(false) }
    var isLocationPickerOpen by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var dismissDragOffset by remember { mutableStateOf(0f) }
    val permissions = remember { galleryPermissions() }
    val hasGalleryPermission = remember(galleryReloadKey) { hasGalleryAccess(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        galleryReloadKey += 1
    }
    val galleryItems by produceState<List<AloLocalGalleryItem>>(
        initialValue = emptyList(),
        key1 = galleryReloadKey,
        key2 = hasGalleryPermission
    ) {
        value = if (hasGalleryPermission) {
            withContext(Dispatchers.IO) {
                loadRecentChatGalleryMedia(context)
            }
        } else {
            emptyList()
        }
    }
    val animatedOffset by animateFloatAsState(
        targetValue = dismissDragOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "attachmentSheetOffset"
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "attachmentSheetScrim"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    fun dismissWithAnimation(afterDismiss: (() -> Unit)? = null) {
        visible = false
        dismissDragOffset = 0f
        scope.launch {
            delay(190)
            onDismiss()
            afterDismiss?.invoke()
        }
    }
    // Back closes the sheet first, not the chat behind it.
    androidx.activity.compose.BackHandler { dismissWithAnimation() }

    Box(
        modifier = Modifier
            .zIndex(40f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * scrimAlpha))
            .clickable(onClick = { dismissWithAnimation() }),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 4 }
            ),
            exit = fadeOut(animationSpec = tween(140)) + slideOutVertically(
                animationSpec = tween(180, easing = LinearOutSlowInEasing),
                targetOffsetY = { it / 4 }
            ),
            label = "attachmentSheet"
        ) {
            Surface(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = animatedOffset
                    }
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .clickable(onClick = {}),
                // ChatAttachAlert (13.0.3) rounds its top corners by 20dp.
                shape = RoundedCornerShape(
                    topStart = AloTelegramMetrics.Sheets.AttachRadius,
                    topEnd = AloTelegramMetrics.Sheets.AttachRadius
                ),
                color = AloNativeAppSurface,
                border = BorderStroke(1.dp, AloNativeAppBorder)
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    // Only the handle and the title drag the sheet down, so the grid below keeps its own scrolling.
                    Column(
                        modifier = Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    dismissDragOffset = (dismissDragOffset + dragAmount).coerceAtLeast(0f)
                                },
                                onDragEnd = {
                                    if (dismissDragOffset > 160f) dismissWithAnimation() else dismissDragOffset = 0f
                                },
                                onDragCancel = { dismissDragOffset = 0f }
                            )
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 8.dp)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(AloNativeAppMuted.copy(alpha = 0.55f))
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(AloAttachMetrics.TitleRowHeight)
                                .padding(start = AloTelegramMetrics.Sheets.TitleStart, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Галерея",
                                modifier = Modifier.weight(1f),
                                fontSize = AloTelegramMetrics.Sheets.TitleSize,
                                fontWeight = FontWeight.Medium,
                                color = AloNativeAppText
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { dismissWithAnimation() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Закрыть",
                                    tint = AloNativeAppMuted,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (hasGalleryPermission && galleryItems.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(AloAttachMetrics.GridColumns),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(AloAttachMetrics.GridGap),
                                verticalArrangement = Arrangement.spacedBy(AloAttachMetrics.GridGap)
                            ) {
                                gridItems(galleryItems, key = { it.uri }) { item ->
                                    val order = selectedUris.indexOf(item.uri)
                                    ChatGalleryThumb(
                                        item = item,
                                        selectionIndex = if (order >= 0) order + 1 else null,
                                        onClick = {
                                            selectedUris = if (order >= 0) {
                                                selectedUris - item.uri
                                            } else {
                                                (selectedUris + item.uri).take(AloAttachMetrics.MaxSelected)
                                            }
                                        },
                                        onLongClick = { onPickGalleryUri(item.uri) }
                                    )
                                }
                            }
                        } else {
                            ChatGalleryEmptyState(
                                hasPermission = hasGalleryPermission,
                                openSettings = permissionRequested && !hasGalleryPermission,
                                onAllow = {
                                    if (permissionRequested && !hasGalleryPermission) {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                .setData(Uri.fromParts("package", context.packageName, null))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } else {
                                        permissionRequested = true
                                        permissionLauncher.launch(permissions)
                                    }
                                },
                                onReload = { galleryReloadKey += 1 }
                            )
                        }
                    }
                    // Telegram swaps the tabs for the send bar while something is chosen.
                    AnimatedContent(
                        targetState = selectedUris.isNotEmpty(),
                        transitionSpec = {
                            (fadeIn(tween(160)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 })
                        },
                        label = "attachmentBottomBar"
                    ) { hasSelection ->
                        if (hasSelection) {
                            ChatAttachmentSelectionBar(
                                count = selectedUris.size,
                                onClear = { selectedUris = emptyList() },
                                onAdd = {
                                    val selected = selectedUris
                                    dismissWithAnimation { onPickGalleryUris(selected) }
                                }
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(AloAttachMetrics.ActionsRowHeight),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (capabilities.canSendMedia) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.PhotoLibrary,
                                        label = "Галерея",
                                        color = AloAttachMetrics.GalleryColor,
                                        selected = true,
                                        onClick = {
                                            if (hasGalleryPermission) {
                                                galleryReloadKey += 1
                                            } else {
                                                permissionRequested = true
                                                permissionLauncher.launch(permissions)
                                            }
                                        }
                                    )
                                }
                                if (capabilities.canSendMedia) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.InsertDriveFile,
                                        label = "Файл",
                                        color = AloAttachMetrics.FileColor,
                                        onClick = { dismissWithAnimation(afterDismiss = onPickFile) }
                                    )
                                }
                                if (capabilities.canSendMessages) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.LocationOn,
                                        label = "Геопозиция",
                                        color = AloAttachMetrics.LocationColor,
                                        onClick = { isLocationPickerOpen = true }
                                    )
                                }
                                if (capabilities.canSendMessages && contacts.isNotEmpty()) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.Person,
                                        label = "Контакт",
                                        color = AloAttachMetrics.ContactColor,
                                        onClick = { isContactPickerOpen = true }
                                    )
                                }
                                if (capabilities.canCreatePoll) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.Poll,
                                        label = "Опрос",
                                        color = AloAttachMetrics.PollColor,
                                        onClick = { dismissWithAnimation(afterDismiss = onCreatePoll) }
                                    )
                                }
                                if (capabilities.canSendMedia) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.Mic,
                                        label = "Голосовое",
                                        color = AloAttachMetrics.VoiceColor,
                                        onClick = { dismissWithAnimation(afterDismiss = onStartVoice) }
                                    )
                                }
                                if (capabilities.canSendMedia) item {
                                    ChatAttachmentAction(
                                        icon = Icons.Rounded.Videocam,
                                        label = "Кружок",
                                        color = AloAttachMetrics.CircleColor,
                                        onClick = { dismissWithAnimation(afterDismiss = onStartCircle) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isContactPickerOpen) {
            ChatContactPickerDialog(
                contacts = contacts,
                onDismiss = { isContactPickerOpen = false },
                onPick = { contact ->
                    isContactPickerOpen = false
                    dismissWithAnimation { onShareContact(contact) }
                }
            )
        }
        if (isLocationPickerOpen) {
            AloChatLocationPicker(
                onDismiss = { isLocationPickerOpen = false },
                onShareStatic = { latitude, longitude, title, address ->
                    isLocationPickerOpen = false
                    dismissWithAnimation { onShareStaticLocation(latitude, longitude, title, address) }
                },
                onShareLive = { request ->
                    isLocationPickerOpen = false
                    dismissWithAnimation { onShareLiveLocation(request) }
                }
            )
        }
    }
}

@Composable
private fun ChatContactPickerDialog(
    contacts: List<AloEntitySummary>,
    onDismiss: () -> Unit,
    onPick: (AloEntitySummary) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredContacts = remember(contacts, query) {
        val normalizedQuery = query.trim().lowercase()
        contacts.filter { contact ->
            normalizedQuery.isBlank() ||
                contact.name.lowercase().contains(normalizedQuery) ||
                contact.username.lowercase().contains(normalizedQuery)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AloNativeAppSurface,
        titleContentColor = AloNativeAppText,
        textContentColor = AloNativeAppMuted,
        title = { Text("Поделиться контактом") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск") }
                )
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AloSquircle(14.dp))
                                .clickable { onPick(contact) }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = AloNativeChatAccentBlue.copy(alpha = 0.78f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = contact.name.trim().take(1).uppercase().ifBlank { "?" },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name.ifBlank { "Пользователь" },
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = AloNativeAppText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (contact.username.isNotBlank()) {
                                    Text(
                                        text = "@${contact.username.removePrefix("@")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AloNativeAppMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/** Telegram's ChatAttachAlert (13.0.3): a tall sheet, a 3-column grid with 2dp gaps, round colour icons under it. */
internal object AloAttachMetrics {
    const val GridColumns = 3
    val GridGap = 2.dp
    const val MaxSelected = 10
    val TitleRowHeight = 48.dp
    val ActionsRowHeight = 92.dp
    /** Narrow enough that the seventh button peeks in from the right: the row scrolls. */
    val ActionWidth = 70.dp
    val ActionIconSize = 52.dp

    val GalleryColor = Color(0xFF3D8FEF)
    val FileColor = Color(0xFF1FA7C9)
    val LocationColor = Color(0xFF37B24D)
    val ContactColor = Color(0xFFF2A93B)
    val PollColor = Color(0xFFEF6C3E)
    val VoiceColor = Color(0xFFE5484D)
    val CircleColor = Color(0xFF8E6BE0)

    /** About two thirds of the screen, never a stub and never the whole screen. */
    fun sheetHeight(screenHeightDp: Int) = (screenHeightDp * 0.68f).dp.coerceIn(420.dp, 640.dp)
}

@Composable
private fun ChatAttachmentAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    Column(
        modifier = Modifier
            .width(AloAttachMetrics.ActionWidth)
            .clip(AloSquircle(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(AloAttachMetrics.ActionIconSize)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(AloTelegramMetrics.Sheets.AttachIconSize + 2.dp)
            )
        }
        Text(
            text = label,
            modifier = Modifier.padding(top = 6.dp),
            // GlassTabView: the label is 12sp.
            fontSize = AloTelegramMetrics.Sheets.AttachLabelSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) AloNativeAppText else AloNativeAppMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** What the grid shows without access to the gallery, or with nothing in it. */
@Composable
private fun ChatGalleryEmptyState(
    hasPermission: Boolean,
    openSettings: Boolean,
    onAllow: () -> Unit,
    onReload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(AloAttachMetrics.GalleryColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = AloAttachMetrics.GalleryColor,
                modifier = Modifier.size(40.dp)
            )
        }
        Text(
            text = if (hasPermission) "В галерее пока пусто" else "Доступ к галерее",
            modifier = Modifier.padding(top = 18.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = AloNativeAppText,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (hasPermission) {
                "Новые фото и видео появятся здесь, чтобы отправлять их стопкой."
            } else {
                "Разрешите доступ к фото и видео, чтобы выбирать их прямо в чате."
            },
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 15.sp,
            color = AloNativeAppMuted,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .padding(top = 22.dp)
                .height(44.dp)
                .clip(CircleShape)
                .background(AloNativeChatAccentBlue)
                .clickable(onClick = if (hasPermission) onReload else onAllow)
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    hasPermission -> "Обновить"
                    openSettings -> "Открыть настройки"
                    else -> "Разрешить доступ"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

/** Telegram's bottom bar while photos are chosen: how many, a way to start over, and the button that takes them. */
@Composable
private fun ChatAttachmentSelectionBar(
    count: Int,
    onClear: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AloAttachMetrics.ActionsRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Выбрано: $count",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = AloNativeAppText
            )
            Text(
                text = "Сбросить",
                modifier = Modifier
                    .clip(AloSquircle(8.dp))
                    .clickable(onClick = onClear)
                    .padding(vertical = 4.dp),
                fontSize = 14.sp,
                color = AloNativeChatAccentBlue
            )
        }
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(CircleShape)
                .background(AloNativeChatAccentBlue)
                .clickable(onClick = onAdd)
                .padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Добавить",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = count.toString(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ChatGalleryThumb(
    item: AloLocalGalleryItem,
    selectionIndex: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val selected = selectionIndex != null
    val dim by animateFloatAsState(if (selected) 0.22f else 0f, tween(140), label = "thumbDim")
    val fill by animateColorAsState(
        if (selected) AloNativeChatAccentBlue else Color.Black.copy(alpha = 0.26f),
        tween(140),
        label = "thumbCheck"
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(AloNativeAppBackgroundSecondary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        // CheckBox2: a 24dp circle with a white ring in the corner, filled and numbered once chosen.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selectionIndex != null) {
                Text(
                    text = selectionIndex.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Text(text = "Видео", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

private fun galleryPermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** Android 14 lets a person share only some photos: that counts as access to the gallery, with those photos in it. */
private fun hasGalleryAccess(context: Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 ->
            (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun loadRecentChatGalleryMedia(context: Context): List<AloLocalGalleryItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
    val items = mutableListOf<AloLocalGalleryItem>()
    context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        while (cursor.moveToNext() && items.size < 90) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn).orEmpty().ifBlank { "media-$id" }
            val mimeType = cursor.getString(mimeColumn).orEmpty()
            val mediaType = cursor.getInt(typeColumn)
            val uri = ContentUris.withAppendedId(collection, id).toString()
            items += AloLocalGalleryItem(
                uri = uri,
                mimeType = mimeType,
                name = name,
                isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            )
        }
    }
    return items
}
