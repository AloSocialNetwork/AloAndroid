package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// iOS's SearchView: «Поиск» with the settings gear and a glass field over the list, the relation
// capsules, people and communities with a friend request button, and this device's messages.

private enum class AloRelationList(val title: String, val subtitle: String) {
    Friends("Друзья", "Список твоих подтверждённых друзей."),
    Followers("Подписчики", "Кто подписан на тебя."),
    Following("Подписки", "На кого подписан ты."),
    Channels("Сообщества", "Сообщества, доступные в подборке.")
}

@Composable
internal fun AloSearchScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit,
    /** True while a list of friends or followers covers the whole screen (as on iOS, it has no tab bar). */
    onFullScreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val cacheStore = remember(context.applicationContext) {
        AloOfflineCacheStore(
            context.applicationContext,
            context.applicationContext.getSharedPreferences("alo-android", android.content.Context.MODE_PRIVATE)
        )
    }
    val query = state.searchQuery.trim().lowercase(Locale.getDefault())
    // What the server found for this text (anyone, not only the people the bootstrap carries), once it has answered for it.
    LaunchedEffect(state.searchQuery) { onAction(AloNativeAppAction.SearchRemote(state.searchQuery)) }
    val remoteReady = state.searchRemoteQuery.lowercase(Locale.getDefault()) == query && query.length >= 2
    val knownPeople = (data.searchUsers + data.searchChannels).filter {
        query.isBlank() || it.name.lowercase(Locale.getDefault()).contains(query) || it.username.lowercase(Locale.getDefault()).contains(query)
    }
    // The server matched its answers itself (a community also by its description), so they are not filtered again here.
    val people = (knownPeople + if (remoteReady) state.searchRemoteUsers + state.searchRemoteChannels else emptyList())
        .distinctBy { "${it.isChannel}:${it.id}" }
    val foundPosts = if (remoteReady) state.searchRemotePosts else emptyList()
    var localHits by remember(data.me.id) { mutableStateOf(emptyList<AloOfflineMessageSearchHit>()) }
    LaunchedEffect(query, data.me.id) {
        if (query.isBlank() || data.me.id <= 0) {
            localHits = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        localHits = withContext(Dispatchers.IO) { cacheStore.searchAllMessages(data.me.id, query, limit = 100) }
    }
    var openedList by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openedList) { onFullScreenChange(openedList != null) }
    DisposableEffect(Unit) { onDispose { onFullScreenChange(false) } }
    val backdrop = rememberAloGlassBackdrop()
    var headerHeight by remember { mutableStateOf(110.dp) }
    val density = LocalDensity.current
    fun open(entity: AloEntitySummary) = onAction(
        AloNativeAppAction.OpenViewedProfile(if (entity.isChannel) AloProfileTarget.Channel(entity.id) else AloProfileTarget.User(entity.id))
    )

    AloNaturalLineHeight {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().aloGlassSource(backdrop),
                contentPadding = PaddingValues(top = headerHeight + 8.dp, bottom = AloBottomBarOverlayPadding)
            ) {
                item(key = "relations") {
                    val relations = data.profileRelations
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            AloRelationList.Friends to relations.friendsCount,
                            AloRelationList.Followers to relations.followersCount,
                            AloRelationList.Following to relations.followingCount,
                            AloRelationList.Channels to relations.channelsCount
                        ).forEach { (list, count) -> AloRelationCapsule(list.title, count) { openedList = list.name } }
                    }
                }
                items(people, key = { "${it.isChannel}:${it.id}" }) { entity ->
                    AloSearchResultRow(entity, onOpen = { open(entity) }, onAction = onAction)
                    Box(Modifier.padding(start = AloTelegramMetrics.PersonRow.LeftBaseline).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
                }
                if (localHits.isNotEmpty()) {
                    item(key = "local-header") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Сообщения на этом устройстве", color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("E2EE", color = AloNativeAppAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(localHits, key = { "hit-${it.peerId}-${it.message.id}" }) { hit ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onAction(AloNativeAppAction.SelectConversation(hit.peerId)) }
                                .padding(start = 11.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarBadge(name = hit.conversation.title, avatarUrl = hit.conversation.avatarUrl, verified = false, size = AloTelegramMetrics.PersonRow.SearchAvatarSize, colorSeed = "search-${hit.peerId}")
                            Spacer(Modifier.width(AloTelegramMetrics.PersonRow.LeftBaseline - 11.dp - AloTelegramMetrics.PersonRow.SearchAvatarSize))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(hit.conversation.title.ifBlank { "Чат" }, color = AloNativeAppText, fontSize = AloTelegramMetrics.PersonRow.NameSize, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(formatMessageTime(hit.message.createdAt), color = AloNativeAppMuted, fontSize = AloTelegramMetrics.DialogRow.TimeSize)
                                }
                                Text(
                                    hit.message.body.ifBlank { hit.message.attachmentName }.ifBlank { "Вложение" },
                                    color = AloNativeAppMuted, fontSize = AloTelegramMetrics.DialogRow.PreviewSize, maxLines = 2
                                )
                            }
                        }
                        Box(Modifier.padding(start = AloTelegramMetrics.PersonRow.LeftBaseline).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
                    }
                }
                if (foundPosts.isNotEmpty()) {
                    item(key = "posts-header") {
                        Text(
                            "Публикации", color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)
                        )
                    }
                    items(foundPosts, key = { "found-post-${it.id}" }) { post ->
                        AloSearchPostRow(post = post, onOpen = { onAction(AloNativeAppAction.OpenComments(post.id)) })
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
                    }
                    aloLoadMoreItem(
                        key = "search-posts-more",
                        hasMore = state.searchRemotePostsHasMore,
                        isLoading = state.isSearchingRemote,
                        loaded = foundPosts.size,
                        onNeedMore = { onAction(AloNativeAppAction.LoadMoreSearchPosts) }
                    )
                }
                if (state.isSearchingRemote && people.isEmpty() && foundPosts.isEmpty() && query.length >= 2) {
                    item(key = "searching") {
                        Text("Ищу…", color = AloNativeAppMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 24.dp))
                    }
                } else if (!state.isSearchingRemote && remoteReady && people.isEmpty() && foundPosts.isEmpty() && localHits.isEmpty()) {
                    item(key = "nothing") {
                        Text(
                            "Ничего не найдено", color = AloNativeAppMuted, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to AloNativeAppBackground, 0.6f to AloNativeAppBackground.copy(alpha = 0.9f), 1f to Color.Transparent))
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                    .padding(bottom = 8.dp)
            ) {
                AloSocialHeader(
                    title = "Поиск",
                    subtitle = if (state.isRefreshing) "Обновление…" else "",
                    settingsLabel = "Настройки",
                    backdrop = backdrop,
                    onSettings = { onAction(AloNativeAppAction.OpenProfileSettings) }
                )
                Spacer(Modifier.height(AloScreenChrome.FieldGap))
                AloSocialSearchField(
                    value = state.searchQuery,
                    onChange = { onAction(AloNativeAppAction.UpdateSearchQuery(it)) },
                    backdrop = backdrop,
                    modifier = Modifier.padding(horizontal = AloScreenChrome.SideInset)
                )
            }
        }
    }

    openedList?.let { name ->
        val list = AloRelationList.valueOf(name)
        val items = when (list) {
            AloRelationList.Friends -> data.searchUsers.filter { it.relation?.friendStatus == "friends" }
            AloRelationList.Followers -> data.searchUsers.filter { it.relation?.followedBy == true && it.relation.friendStatus != "friends" }
            AloRelationList.Following -> data.searchUsers.filter { it.relation?.following == true && it.relation.friendStatus != "friends" }
            AloRelationList.Channels -> data.searchChannels
        }
        AloRelationListScreen(list, items, onClose = { openedList = null }, onOpen = { entity ->
            openedList = null
            open(entity)
        })
    }
}

/** iOS's AloSocialSearchField: a 40pt glass capsule with the magnifier and a clear button. */
@Composable
internal fun AloSocialSearchField(value: String, onChange: (String) -> Unit, backdrop: AloGlassBackdrop, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .fillMaxWidth()
            .height(AloScreenChrome.FieldHeight)
            .aloLiquidGlass(shape, backdrop = backdrop)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск", color = AloNativeAppMuted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp),
                cursorBrush = SolidColor(AloNativeAppAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Поиск" }
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cancel, "Очистить поиск", tint = AloNativeAppMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AloRelationCapsule(title: String, count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp).clip(shape).background(AloNativeAppSurfaceRaised.copy(alpha = 0.84f))
                .border(0.5.dp, AloNativeAppBorder.copy(alpha = AloNativeAppBorder.alpha * 0.5f), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("$count", color = AloNativeAppAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AloSearchResultRow(entity: AloEntitySummary, onOpen: () -> Unit, onAction: (AloNativeAppAction) -> Unit) {
    // Telegram's ProfileSearchCell: 60dp tall, a 46dp avatar 11dp in, the text from 72dp, name 16sp bold.
    Row(
        Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.PersonRow.SearchHeight).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = AloTelegramMetrics.PersonRow.SearchHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(11.dp))
            AvatarBadge(name = entity.name, avatarUrl = entity.avatarUrl, verified = false, size = AloTelegramMetrics.PersonRow.SearchAvatarSize, colorSeed = entity.id.toString())
            Spacer(Modifier.width(AloTelegramMetrics.PersonRow.LeftBaseline - 11.dp - AloTelegramMetrics.PersonRow.SearchAvatarSize))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entity.name, color = AloNativeAppText, fontSize = AloTelegramMetrics.PersonRow.NameSize, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (entity.verified) Icon(Icons.Rounded.Verified, null, tint = AloNativeAppAccent, modifier = Modifier.size(16.dp))
                }
                Text(
                    if (entity.isChannel) "Сообщество" else "@${entity.username.removePrefix("@")}",
                    color = AloNativeAppMuted, fontSize = AloTelegramMetrics.PersonRow.UserStatusSize, maxLines = 1
                )
            }
        }
        if (!entity.isChannel) {
            val relation = entity.relation
            val status = relation?.friendStatus ?: "none"
            // The rule of the profile, the website and iPhone: a friend request first; following only for a person who
            // closed friend requests (a follow back makes friends).
            val followsInstead = status == "none" && relation?.canSendFriendRequest == false
            val title = when (status) {
                "friends" -> "В друзьях"
                "outgoing" -> "Отправлено"
                "incoming" -> "Принять"
                else -> when {
                    !followsInstead -> "Добавить"
                    relation?.following == true -> "Вы подписаны"
                    else -> "Подписаться"
                }
            }
            val enabled = status == "none" || status == "incoming"
            val prominent = enabled && !(followsInstead && relation?.following == true)
            AloFriendRequestButton(title, prominent = prominent, enabled = enabled) {
                if (status == "incoming" && (relation?.incomingRequestId ?: 0) > 0) {
                    onAction(AloNativeAppAction.RespondFriendRequest(relation!!.incomingRequestId, true))
                } else if (status == "incoming") {
                    onOpen()
                } else if (followsInstead) {
                    onAction(AloNativeAppAction.ToggleFollowUser(entity.id))
                } else {
                    onAction(AloNativeAppAction.SendFriendRequest(entity.id))
                }
            }
        }
    }
}

/** iOS's AloSocialRequestButtonStyle: a 32pt button inside a 44pt target. */
@Composable
private fun AloFriendRequestButton(title: String, prominent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = AloSquircle(10.dp)
    Box(Modifier.heightIn(min = 44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(32.dp).clip(shape)
                // The app's accent, as in the notifications' «Принять»: the outgoing bubble's colour is a pale #D8EBFF
                // in the light theme, and the white «Добавить» on it could not be read.
                .background(if (prominent) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                .then(if (prominent) Modifier else Modifier.border(1.dp, AloNativeAppBorder, shape))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                title, color = if (prominent) AloNativeAppButtonText else AloNativeAppText.copy(alpha = if (enabled) 1f else 0.5f),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

/** iOS's SearchRelationListScreen: a large rounded title, a subtitle and the people as rows. */
@Composable
private fun AloRelationListScreen(list: AloRelationList, items: List<AloEntitySummary>, onClose: () -> Unit, onOpen: (AloEntitySummary) -> Unit) {
    BackHandler(onBack = onClose)
    AloNaturalLineHeight {
        Box(Modifier.fillMaxSize().background(AloNativeAppBackground).windowInsetsPadding(WindowInsets.statusBars)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                item {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 72.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(list.title, color = AloNativeAppText, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Text(list.subtitle, color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (items.isEmpty()) {
                    item {
                        AloUnavailableView(
                            when (list) {
                                AloRelationList.Friends -> Icons.Outlined.People
                                AloRelationList.Followers -> Icons.Outlined.PersonAddAlt
                                AloRelationList.Following -> Icons.Outlined.GroupAdd
                                AloRelationList.Channels -> Icons.Outlined.Campaign
                            },
                            "${list.title.lowercase(Locale.forLanguageTag("ru"))} пока нет",
                            "Когда здесь появятся связи, они будут показаны списком.",
                            Modifier.padding(top = 48.dp)
                        )
                    }
                }
                items(items, key = { "${it.isChannel}:${it.id}" }) { entity ->
                    // Telegram's contacts list (`UserCell`): 58dp, an avatar 7dp in, the text from 64dp, the divider from 68dp.
                    AloUserCell(
                        name = entity.name,
                        status = if (entity.isChannel) "Сообщество" else "@${entity.username}",
                        avatarUrl = entity.avatarUrl,
                        colorSeed = entity.id.toString(),
                        style = AloUserCellStyle.Contact,
                        onClick = { onOpen(entity) }
                    )
                }
            }
            AloScreenBackButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** A found post: who wrote it, when, and the start of the text; a tap opens the post. */
@Composable
private fun AloSearchPostRow(post: AloPost, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarBadge(name = post.author.name, avatarUrl = post.author.avatarUrl, verified = false, size = 40.dp, colorSeed = post.author.id.toString())
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(post.author.name, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(formatPostTimestamp(post.createdAt), color = AloNativeAppMuted, fontSize = 12.sp, maxLines = 1)
            }
            Text(
                post.body.ifBlank { "Публикация с вложением" },
                color = AloNativeAppText, fontSize = 15.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
