package com.alo.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit
) {
    // Only the posts, under «Записи», as on the website (the owner, 2026-10-28: no «Посты / Нравится» switch).
    val profileTab = "posts"
    var isCoverEditorOpen by rememberSaveable { mutableStateOf(false) }
    var avatarSheetOpen by remember(data.me.id, data.me.avatarGallery) { mutableStateOf(false) }
    // «Нравится» is the server's list of every liked post; what is already on the screen shows meanwhile.
    val likedPosts = remember(state.likedPosts, data.feedPopular, data.feedSubscriptions, data.profilePosts) {
        (state.likedPosts + data.profilePosts + data.feedPopular + data.feedSubscriptions)
            .distinctBy { it.id }
            .filter { it.viewerLiked }
    }
    val pagedList = if (profileTab == "likes") AloPagedList.LikedPosts else AloPagedList.ProfilePosts
    // The server's «Нравится» is asked for when the tab is opened; the list below adds the next pages as it is scrolled.
    LaunchedEffect(profileTab) {
        if (profileTab == "likes" && state.likedPosts.isEmpty()) onAction(AloNativeAppAction.LoadMore(AloPagedList.LikedPosts))
    }
    val tabsBackdrop = rememberAloGlassBackdrop()
    val listState = rememberLazyListState()
    val tabsPinned = rememberTabsPinned(listState, "profile-tabs", 1)
    // Changing the tab must not throw the page back to its top: the body has a floor under it, and a pinned switcher parks at the top edge smoothly.
    val tabScope = rememberCoroutineScope()
    var tabsHeightPx by remember { mutableIntStateOf(0) }
    val tabScroll = rememberAloProfileTabScroll(
        listState = listState,
        tabsIndex = 1,
        topPadding = AloNativeLayout.spacingSmall,
        bottomPadding = AloBottomBarOverlayPadding + AloNativeLayout.spacingSmall,
        tabsHeightPx = tabsHeightPx
    )
    val selectTab: (String) -> Unit = { _ -> }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().aloGlassSource(tabsBackdrop),
            contentPadding = PaddingValues(
                top = AloNativeLayout.spacingSmall,
                bottom = AloBottomBarOverlayPadding
            ),
            verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingSmall)
        ) {
            // iOS's own profile: the hero (stories behind the avatar), then posts under a sticky switcher.
            // «Панель автора» lives in the settings.
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ProfileHeroPanel(
                        me = data.me,
                        stats = data.profileStats,
                        relations = data.profileRelations,
                        onOpenSettings = { onAction(AloNativeAppAction.OpenProfileSettings) },
                        onOpenAvatarSheet = { avatarSheetOpen = true },
                        onOpenCoverEditor = { isCoverEditorOpen = true }
                    )
                }
            }

            item(key = "profile-tabs") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { tabsHeightPx = it.height }
                        .padding(
                            horizontal = AloNativeLayout.pageInset,
                            vertical = AloNativeLayout.spacingSmall
                        )
                ) {
                    ProfilePostsTitle()
                }
            }

            if (!state.errorMessage.isNullOrBlank()) {
                item {
                    InlineErrorPanel(
                        message = state.errorMessage,
                        onRetry = { onAction(AloNativeAppAction.Refresh) }
                    )
                }
            }

            item {
                AloTabSwitchContent(profileTab, minHeight = tabScroll.minBody) { tab ->
                PostsPanel(
                    title = if (tab == "likes") "Нравится" else "Посты",
                    posts = if (tab == "likes") likedPosts else data.profilePosts,
                    currentUserId = data.me.id,
                    pendingLikePostId = state.pendingLikePostId,
                    pendingRepostPostId = state.pendingRepostPostId,
                    emptyTitle = if (tab == "likes") "Пока нет понравившихся постов" else "Профиль пустой",
                    emptyMessage = if (tab == "likes") "Посты, которым ты поставил лайк, появятся здесь." else "Опубликуй первый пост из вкладки ленты.",
                    onOpenComments = { onAction(AloNativeAppAction.OpenComments(it)) },
                    onToggleRepost = { onAction(AloNativeAppAction.ToggleRepost(it)) },
                    onOpenMedia = { onAction(AloNativeAppAction.OpenMedia(it)) },
                    onOpenMediaGallery = { items, startIndex ->
                        onAction(AloNativeAppAction.OpenMediaGallery(items, startIndex))
                    },
                    onLike = { onAction(AloNativeAppAction.ToggleLike(it)) },
                    onDeletePost = { onAction(AloNativeAppAction.DeletePost(it)) }
                )
                }
            }
            aloLoadMoreItem(
                key = "profile-more-${pagedList.name}",
                hasMore = pagedList !in state.pagedListsEnded,
                isLoading = pagedList in state.pagedListsLoading,
                loaded = if (profileTab == "likes") likedPosts.size else data.profilePosts.size,
                onNeedMore = { onAction(AloNativeAppAction.LoadMore(pagedList)) }
            )
        }

        AnimatedVisibility(
            visible = false,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = AloNativeLayout.pageInset, vertical = AloNativeLayout.spacingSmall)) {
                ProfilePostsTabs(
                    postsTitle = "Посты",
                    selectedTab = profileTab,
                    onSelect = selectTab,
                    backdrop = tabsBackdrop
                )
            }
        }

        if (avatarSheetOpen) {
            ProfileAvatarSheet(
                title = data.me.name,
                avatarUrls = data.me.avatarGallery.ifEmpty { listOf(data.me.avatarUrl).filter { it.isNotBlank() } },
                canUpload = true,
                onDismiss = { avatarSheetOpen = false },
                onUploadNew = {
                    avatarSheetOpen = false
                    onAction(AloNativeAppAction.PickProfileAvatar)
                },
                onOpenMediaGallery = { items, startIndex ->
                    avatarSheetOpen = false
                    onAction(AloNativeAppAction.OpenMediaGallery(items, startIndex))
                }
            )
        }

        // A page over the profile: it had no back handling at all (the system back went past it); now the back swipe
        // follows the finger, as on the other pushed pages.
        AloSwipeBackPage(visible = isCoverEditorOpen, onClosed = { isCoverEditorOpen = false }) { page ->
            ProfileCoverStyleScreen(
                title = data.me.name,
                currentStyle = data.me.coverStyle,
                onDismiss = page::close,
                onApply = { style ->
                    page.close()
                    onAction(AloNativeAppAction.UpdateProfileCoverStyle(style))
                }
            )
        }
    }
}
