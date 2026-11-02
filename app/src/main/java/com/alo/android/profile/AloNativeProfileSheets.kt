package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage

private fun profileCoverStyleOptions(): List<String> = listOf(
    "graphite",
    "azure",
    "ocean",
    "emerald",
    "forest",
    "sunset",
    "amber",
    "rose",
    "violet"
)

private fun avatarMediaItems(urls: List<String>, title: String): List<AloMediaItem> {
    return urls.filter { it.isNotBlank() }.distinct().map { url ->
        AloMediaItem(
            type = "image",
            url = url,
            posterUrl = url,
            alt = title,
            caption = ""
        )
    }
}

@Composable
internal fun ProfileAvatarSheet(
    title: String,
    avatarUrls: List<String>,
    canUpload: Boolean,
    onDismiss: () -> Unit,
    onUploadNew: () -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit
) {
    val items = remember(avatarUrls, title) { avatarMediaItems(avatarUrls, title) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size.coerceAtLeast(1) })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(120f)
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
                .clickable(onClick = {}),
            shape = AloSquircle(28.dp),
            color = AloNativeAppSurface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AloGlassCircleButton(onClick = onDismiss, description = "Закрыть", close = true)
                    Text(
                        text = "Фотографии профиля",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText
                    )
                }
                if (items.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(452.dp)
                    ) { page ->
                        AsyncImage(
                            model = items[page].url,
                            contentDescription = title,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(AloSquircle(24.dp))
                                .clickable { onOpenMediaGallery(items, page) },
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (items.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            items.forEachIndexed { index, _ ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (pagerState.currentPage == index) AloNativeAppAccent else AloNativeAppMuted.copy(alpha = 0.34f)
                                        )
                                )
                            }
                        }
                    }
                } else {
                    EmptyPanelBody(
                        title = "Фотографий пока нет",
                        message = "Когда появятся аватары, их можно будет листать здесь."
                    )
                }
                if (canUpload) {
                    Button(
                        onClick = onUploadNew,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(min = 220.dp, max = 360.dp)
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AloNativeAppAccent,
                            contentColor = AloNativeAppButtonText
                        )
                    ) {
                        Text(
                            text = "Загрузить новое фото",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileCoverStyleScreen(
    title: String,
    currentStyle: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var selectedStyle by remember(currentStyle) { mutableStateOf(currentStyle.ifBlank { "graphite" }) }
    val options = remember { profileCoverStyleOptions() }
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
    ) {
        ChatWallpaperBackground(modifier = Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .verticalScroll(scrollState)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AloSquircle(38.dp),
                color = AloNativeAppSurface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.035f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .padding(start = 10.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // iOS AloProfileBanner: AloChatCircleButton with a cross.
                    AloGlassCircleButton(onClick = onDismiss, description = "Закрыть", close = true)
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Баннер профиля",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppText
                        )
                        Text(
                            text = "Подбери фон и посмотри результат сразу.",
                            modifier = Modifier.padding(top = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = AloNativeAppMuted
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                shape = AloSquircle(28.dp),
                color = AloNativeAppSurface.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                            .clip(AloSquircle(24.dp))
                            .background(profileCoverBrush(selectedStyle))
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0x16070B12), Color(0x30070B12))
                                    )
                                )
                        )
                        Text(
                            text = title,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(314.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        userScrollEnabled = false
                    ) {
                        items(options, key = { it }) { style ->
                            val selected = style == selectedStyle
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.12f)
                                    .clip(AloSquircle(18.dp))
                                    .background(profileCoverBrush(style))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) Color.White.copy(alpha = 0.94f) else AloNativeAppBorder.copy(alpha = 0.08f),
                                        shape = AloSquircle(18.dp)
                                    )
                                    .clickable { selectedStyle = style }
                            )
                        }
                    }
                    Button(
                        onClick = { onApply(selectedStyle) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AloNativeAppAccent,
                            contentColor = AloNativeAppButtonText
                        )
                    ) {
                        Text(
                            text = "Применить",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}
