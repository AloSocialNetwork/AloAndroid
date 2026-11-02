package com.alo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Gif
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class AloExpressionTab { Emoji, Gif, Stickers }

/** Telegram's EmojiView sizes (13.0.3): the strip above and the key row below are 44dp, emoji 8 to a row. */
internal object AloExpressionMetrics {
    val TopStripHeight = 44.dp
    val BottomBarHeight = 44.dp
    const val EmojiColumns = 8
    val SearchHeight = 190.dp
    val DefaultKeyboardHeight = 300.dp
}

/**
 * The panel that takes the keyboard's place (Telegram's EmojiView): emoji by category on a plain background, GIFs and stickers, a key row with
 * the three tabs and a backspace. It is as tall as the keyboard was, so the chat does not jump when one gives way to the other. In [searching]
 * it shows a field and one row of results at the top, above the keyboard.
 */
@Composable
internal fun AloExpressionPanel(
    modifier: Modifier = Modifier,
    tab: AloExpressionTab,
    onTabChange: (AloExpressionTab) -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    onInsertEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    onSendCatalogExpression: (AloCatalogExpressionMedia) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var emojiQuery by remember { mutableStateOf("") }
    var stickerQuery by remember { mutableStateOf("") }
    var gifQuery by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(AloEmojiRecents.load(context)) }
    var installedPacks by remember { mutableStateOf<List<AloStickerPack>>(emptyList()) }
    var catalogPacks by remember { mutableStateOf<List<AloStickerPack>>(emptyList()) }
    var selectedPack by remember { mutableStateOf<AloStickerPack?>(null) }
    var stickerSearchResults by remember { mutableStateOf<List<AloCatalogExpressionMedia>>(emptyList()) }
    var gifResults by remember { mutableStateOf<List<AloCatalogExpressionMedia>>(emptyList()) }
    var stickersLoading by remember { mutableStateOf(false) }
    var gifsLoading by remember { mutableStateOf(false) }
    var stickersLoaded by remember { mutableStateOf(false) }
    var gifsLoaded by remember { mutableStateOf(false) }
    var stickerError by remember { mutableStateOf<String?>(null) }
    var gifError by remember { mutableStateOf<String?>(null) }
    val catalogClient = remember(context) {
        AloApiClient(baseUrl = AloServerConfiguration.apiBaseUrl, applicationContext = context)
    }

    suspend fun loadPack(packId: String) {
        if (packId.isBlank()) {
            selectedPack = null
            return
        }
        selectedPack = catalogClient.loadStickerPack(packId)
    }

    suspend fun loadStickerCatalog() {
        stickersLoading = true
        stickerError = null
        try {
            val installed = catalogClient.loadInstalledStickerPacks()
            val catalog = catalogClient.loadStickerPacks()
            installedPacks = installed
            catalogPacks = catalog
            val currentId = selectedPack?.id
            val desiredId = currentId
                ?.takeIf { id -> (installed + catalog).any { it.id == id } }
                ?: installed.firstOrNull()?.id
                ?: catalog.firstOrNull()?.id
                .orEmpty()
            loadPack(desiredId)
            stickersLoaded = true
        } catch (error: Exception) {
            stickerError = error.message?.takeIf(String::isNotBlank) ?: "Не удалось загрузить наборы стикеров."
        } finally {
            stickersLoading = false
        }
    }

    suspend fun loadGifs() {
        gifsLoading = true
        gifError = null
        try {
            gifResults = if (gifQuery.trim().isBlank()) {
                catalogClient.loadTrendingGifs().gifs
            } else {
                catalogClient.searchGifs(gifQuery).gifs
            }
            gifsLoaded = true
        } catch (error: Exception) {
            gifResults = emptyList()
            gifError = error.message?.takeIf(String::isNotBlank) ?: "Не удалось получить GIF."
        } finally {
            gifsLoading = false
        }
    }

    LaunchedEffect(tab) {
        when (tab) {
            AloExpressionTab.Stickers -> if (!stickersLoaded) loadStickerCatalog()
            AloExpressionTab.Gif -> if (!gifsLoaded) loadGifs()
            AloExpressionTab.Emoji -> Unit
        }
    }
    LaunchedEffect(tab, stickerQuery) {
        if (tab != AloExpressionTab.Stickers || stickerQuery.trim().isBlank()) {
            if (stickerQuery.isBlank()) stickerSearchResults = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        stickersLoading = true
        stickerError = null
        try {
            stickerSearchResults = catalogClient.searchStickers(stickerQuery)
        } catch (error: Exception) {
            stickerSearchResults = emptyList()
            stickerError = error.message?.takeIf(String::isNotBlank) ?: "Не удалось найти стикеры."
        } finally {
            stickersLoading = false
        }
    }
    LaunchedEffect(tab, gifQuery) {
        if (tab != AloExpressionTab.Gif) return@LaunchedEffect
        if (gifQuery.isNotBlank()) delay(280)
        loadGifs()
    }

    val visibleStickerItems = if (stickerQuery.trim().isBlank()) selectedPack?.items.orEmpty() else stickerSearchResults
    val insertEmoji: (String) -> Unit = { emoji ->
        recents = AloEmojiRecents.use(context, emoji)
        onInsertEmoji(emoji)
    }

    Column(modifier = modifier.fillMaxSize().background(AloNativeAppSurface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppBorder.copy(alpha = 0.10f)))
        if (searching) {
            ExpressionSearchContent(
                tab = tab,
                emojiQuery = emojiQuery,
                onEmojiQuery = { emojiQuery = it },
                stickerQuery = stickerQuery,
                onStickerQuery = { stickerQuery = it },
                gifQuery = gifQuery,
                onGifQuery = { gifQuery = it },
                stickerResults = visibleStickerItems,
                gifResults = gifResults,
                loading = (tab == AloExpressionTab.Stickers && stickersLoading) || (tab == AloExpressionTab.Gif && gifsLoading),
                error = when (tab) {
                    AloExpressionTab.Stickers -> stickerError
                    AloExpressionTab.Gif -> gifError
                    AloExpressionTab.Emoji -> null
                },
                onInsertEmoji = insertEmoji,
                onSend = onSendCatalogExpression,
                onClose = {
                    keyboard?.hide()
                    onSearchingChange(false)
                }
            )
        } else {
            when (tab) {
                AloExpressionTab.Emoji -> EmojiTabContent(
                    modifier = Modifier.weight(1f),
                    recents = recents,
                    onSearch = { onSearchingChange(true) },
                    onInsert = insertEmoji
                )
                AloExpressionTab.Gif -> Column(Modifier.weight(1f)) {
                    SearchEntryStrip(
                        placeholder = if (gifQuery.isBlank()) "Поиск GIF" else gifQuery,
                        onClick = { onSearchingChange(true) }
                    )
                    when {
                        gifsLoading && gifResults.isEmpty() -> ExpressionLoading("Загружаем GIF…")
                        gifError != null -> ExpressionError(gifError.orEmpty()) { scope.launch { loadGifs() } }
                        gifResults.isNotEmpty() -> ExpressionMediaGrid(gifResults, columns = 3, onSend = onSendCatalogExpression)
                        gifQuery.isBlank() -> ExpressionEmpty("Сервер пока не вернул популярные GIF.")
                        else -> ExpressionEmpty("По этому запросу GIF не найдены.")
                    }
                }
                AloExpressionTab.Stickers -> Column(Modifier.weight(1f)) {
                    StickersTopStrip(
                        packs = (installedPacks + catalogPacks.filterNot { candidate -> installedPacks.any { it.id == candidate.id } }).distinctBy { it.id },
                        selectedId = selectedPack?.id,
                        onSearch = { onSearchingChange(true) },
                        onSelect = { pack ->
                            scope.launch {
                                stickersLoading = true
                                stickerError = null
                                try {
                                    loadPack(pack.id)
                                } catch (error: Exception) {
                                    stickerError = error.message?.takeIf(String::isNotBlank) ?: "Не удалось открыть набор стикеров."
                                } finally {
                                    stickersLoading = false
                                }
                            }
                        }
                    )
                    when {
                        stickersLoading -> ExpressionLoading("Загружаем стикеры…")
                        stickerError != null -> ExpressionError(stickerError.orEmpty()) { scope.launch { loadStickerCatalog() } }
                        selectedPack != null && !selectedPack!!.installed -> StickerInstallPrompt(selectedPack!!) {
                            scope.launch {
                                stickersLoading = true
                                stickerError = null
                                try {
                                    catalogClient.installStickerPack(selectedPack!!.id)
                                    loadStickerCatalog()
                                } catch (error: Exception) {
                                    stickerError = error.message?.takeIf(String::isNotBlank) ?: "Не удалось установить набор стикеров."
                                } finally {
                                    stickersLoading = false
                                }
                            }
                        }
                        visibleStickerItems.isNotEmpty() -> ExpressionMediaGrid(visibleStickerItems, columns = 4, onSend = onSendCatalogExpression)
                        catalogPacks.isEmpty() -> ExpressionEmpty("На сервере пока нет доступных наборов стикеров.")
                        else -> ExpressionEmpty("Установите набор, чтобы отправлять из него стикеры.")
                    }
                }
            }
            // The key row: the three tabs on the left, the backspace key on the right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(AloExpressionMetrics.BottomBarHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpressionTabKey(Icons.Outlined.EmojiEmotions, "Эмодзи", tab == AloExpressionTab.Emoji) { onTabChange(AloExpressionTab.Emoji) }
                ExpressionTabKey(Icons.Rounded.Gif, "GIF", tab == AloExpressionTab.Gif) { onTabChange(AloExpressionTab.Gif) }
                ExpressionTabKey(Icons.Outlined.StickyNote2, "Стикеры", tab == AloExpressionTab.Stickers) { onTabChange(AloExpressionTab.Stickers) }
                Box(Modifier.weight(1f))
                BackspaceKey(onBackspace)
            }
        }
    }
}

private val categoryIcons: Map<String, ImageVector> = mapOf(
    "recent" to Icons.Rounded.History,
    "smileys" to Icons.Outlined.EmojiEmotions,
    "nature" to Icons.Rounded.Pets,
    "food" to Icons.Rounded.Fastfood,
    "activity" to Icons.Rounded.SportsSoccer,
    "travel" to Icons.Rounded.DirectionsCar,
    "objects" to Icons.Rounded.Lightbulb,
    "symbols" to Icons.Rounded.Interests
)

@Composable
private fun EmojiTabContent(
    modifier: Modifier,
    recents: List<String>,
    onSearch: () -> Unit,
    onInsert: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    // The grid's items: a header then its emoji, for «Недавние» (when there are any) and every category.
    class Section(val id: String, val title: String, val items: List<String>)
    val sections = remember(recents) {
        buildList {
            if (recents.isNotEmpty()) add(Section("recent", "Недавние", recents))
            AloEmojiCatalog.categories.forEach { add(Section(it.id, it.title, it.items)) }
        }
    }
    val headerIndexes = remember(sections) {
        var index = 0
        sections.associate { section ->
            val start = index
            index += 1 + section.items.size
            section.id to start
        }
    }
    val currentSectionId by remember(sections, headerIndexes) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            sections.lastOrNull { (headerIndexes[it.id] ?: 0) <= first }?.id ?: sections.first().id
        }
    }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(AloExpressionMetrics.TopStripHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StripIconButton(Icons.Rounded.Search, "Поиск эмодзи", selected = false, onClick = onSearch)
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(sections, key = { it.id }) { section ->
                    StripIconButton(
                        icon = categoryIcons[section.id] ?: Icons.Outlined.EmojiEmotions,
                        description = section.title,
                        selected = section.id == currentSectionId,
                        onClick = { scope.launch { gridState.animateScrollToItem(headerIndexes[section.id] ?: 0) } }
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(AloExpressionMetrics.EmojiColumns),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
            sections.forEach { section ->
                item(key = "h-${section.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = section.title,
                        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AloNativeAppMuted
                    )
                }
                items(section.items.size, key = { "${section.id}-$it-${section.items[it]}" }) { position ->
                    EmojiCell(section.items[position], onInsert)
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, onInsert: (String) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, tween(90), label = "emojiPress")
    Box(
        modifier = Modifier
            .height(46.dp)
            .fillMaxWidth()
            .pointerInput(emoji) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onInsert(emoji) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            fontSize = 28.sp
        )
    }
}

@Composable
private fun StripIconButton(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = AloExpressionMetrics.TopStripHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) AloNativeChatAccentBlue else AloNativeAppMuted,
            modifier = Modifier.size(24.dp)
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(22.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(AloNativeChatAccentBlue)
            )
        }
    }
}

@Composable
private fun ExpressionTabKey(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(width = 52.dp, height = 36.dp)
            .clip(CircleShape)
            .background(if (selected) AloNativeAppSurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) AloNativeAppText else AloNativeAppMuted,
            modifier = Modifier.size(if (icon == Icons.Rounded.Gif) 30.dp else 24.dp)
        )
    }
}

/** Backspace for the composer's text: a tap deletes one character, holding repeats, as Telegram's key does. */
@Composable
private fun BackspaceKey(onBackspace: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 36.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onBackspace()
                        val repeat = scope.launch {
                            delay(420)
                            while (true) {
                                onBackspace()
                                delay(55)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Backspace,
            contentDescription = "Стереть",
            tint = AloNativeAppMuted,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** A search button shaped like the field it opens: the panel's own field lives in the search state above the keyboard. */
@Composable
private fun SearchEntryStrip(placeholder: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AloExpressionMetrics.TopStripHeight)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(CircleShape)
            .background(AloNativeAppSurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
        Text(placeholder, fontSize = 15.sp, color = AloNativeAppMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StickersTopStrip(
    packs: List<AloStickerPack>,
    selectedId: String?,
    onSearch: () -> Unit,
    onSelect: (AloStickerPack) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(AloExpressionMetrics.TopStripHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StripIconButton(Icons.Rounded.Search, "Поиск стикеров", selected = false, onClick = onSearch)
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(packs, key = { it.id }) { pack ->
                val selected = pack.id == selectedId
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = AloExpressionMetrics.TopStripHeight)
                        .clickable { onSelect(pack) },
                    contentAlignment = Alignment.Center
                ) {
                    if (pack.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = pack.coverUrl,
                            contentDescription = pack.title,
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(pack.title.take(1).uppercase(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AloNativeAppMuted)
                    }
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .width(22.dp)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(AloNativeChatAccentBlue)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressionMediaGrid(
    items: List<AloCatalogExpressionMedia>,
    columns: Int,
    onSend: (AloCatalogExpressionMedia) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(if (columns == 3) 2.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (columns == 3) 2.dp else 0.dp)
    ) {
        items(items.size, key = { items[it].id.ifBlank { items[it].assetUrl } + "-$it" }) { position ->
            val media = items[position]
            Box(
                modifier = Modifier
                    .height(if (columns == 3) 92.dp else 84.dp)
                    .fillMaxWidth()
                    .clickable { onSend(media) }
                    .padding(if (media.isSticker) 6.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = media.previewUrl.ifBlank { media.assetUrl },
                    contentDescription = media.title.ifBlank { if (media.isGif) "GIF" else "Стикер" },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (media.isSticker) ContentScale.Fit else ContentScale.Crop
                )
            }
        }
    }
}

/** Search state: a field at the top, one row of results under it; the keyboard takes the rest of the panel's height. */
@Composable
private fun ExpressionSearchContent(
    tab: AloExpressionTab,
    emojiQuery: String,
    onEmojiQuery: (String) -> Unit,
    stickerQuery: String,
    onStickerQuery: (String) -> Unit,
    gifQuery: String,
    onGifQuery: (String) -> Unit,
    stickerResults: List<AloCatalogExpressionMedia>,
    gifResults: List<AloCatalogExpressionMedia>,
    loading: Boolean,
    error: String?,
    onInsertEmoji: (String) -> Unit,
    onSend: (AloCatalogExpressionMedia) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val query = when (tab) {
        AloExpressionTab.Emoji -> emojiQuery
        AloExpressionTab.Gif -> gifQuery
        AloExpressionTab.Stickers -> stickerQuery
    }
    val setQuery: (String) -> Unit = when (tab) {
        AloExpressionTab.Emoji -> onEmojiQuery
        AloExpressionTab.Gif -> onGifQuery
        AloExpressionTab.Stickers -> onStickerQuery
    }
    val emojiResults = remember(emojiQuery) { AloEmojiCatalog.search(emojiQuery) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = AloNativeAppMuted, modifier = Modifier.size(24.dp))
            }
            BasicTextField(
                value = query,
                onValueChange = setQuery,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AloNativeAppText),
                cursorBrush = SolidColor(AloNativeChatAccentBlue),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = when (tab) {
                                    AloExpressionTab.Emoji -> "Поиск эмодзи"
                                    AloExpressionTab.Gif -> "Поиск GIF"
                                    AloExpressionTab.Stickers -> "Поиск стикеров"
                                },
                                fontSize = 17.sp,
                                color = AloNativeAppMuted
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotBlank()) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { setQuery("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, "Очистить", tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(AloExpressionMetrics.SearchHeight - 52.dp)) {
            when {
                tab == AloExpressionTab.Emoji && query.isBlank() -> ExpressionEmpty("Введите слово: «сердце», «кофе», «smile».")
                tab == AloExpressionTab.Emoji && emojiResults.isEmpty() -> ExpressionEmpty("Эмодзи не найдены.")
                tab == AloExpressionTab.Emoji -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(emojiResults.take(60)) { emoji ->
                        Box(
                            modifier = Modifier.size(56.dp).clickable { onInsertEmoji(emoji) },
                            contentAlignment = Alignment.Center
                        ) { Text(emoji, fontSize = 30.sp) }
                    }
                }
                loading -> ExpressionLoading("Ищем…")
                error != null -> ExpressionEmpty(error)
                else -> {
                    val media = if (tab == AloExpressionTab.Gif) gifResults else stickerResults
                    if (media.isEmpty()) {
                        ExpressionEmpty(if (query.isBlank()) "Введите запрос." else "Ничего не найдено.")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(media, key = { it.id.ifBlank { it.assetUrl } }) { item ->
                                AsyncImage(
                                    model = item.previewUrl.ifBlank { item.assetUrl },
                                    contentDescription = item.title.ifBlank { "Результат" },
                                    modifier = Modifier.size(104.dp).clickable { onSend(item) },
                                    contentScale = if (item.isSticker) ContentScale.Fit else ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerInstallPrompt(pack: AloStickerPack, onInstall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = pack.title.ifBlank { "Набор стикеров" },
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = AloNativeAppText,
            textAlign = TextAlign.Center
        )
        if (pack.description.isNotBlank()) {
            Text(
                text = pack.description,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 14.sp,
                color = AloNativeAppMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .height(42.dp)
                .clip(CircleShape)
                .background(AloNativeChatAccentBlue)
                .clickable(onClick = onInstall)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Установить набор", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

@Composable
private fun ExpressionLoading(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AloNativeChatAccentBlue)
        Text(text, modifier = Modifier.padding(start = 9.dp), fontSize = 14.sp, color = AloNativeAppMuted)
    }
}

@Composable
private fun ExpressionEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = AloNativeAppMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExpressionError(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, fontSize = 14.sp, color = AloNativeAppMuted, textAlign = TextAlign.Center)
        Text(
            text = "Повторить",
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(CircleShape)
                .clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AloNativeChatAccentBlue
        )
    }
}
