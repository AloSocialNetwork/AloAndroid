package com.alo.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun FeedPostRow(
    post: AloPost,
    currentUserId: Int,
    isLikePending: Boolean,
    isRepostPending: Boolean,
    onOpenAuthorProfile: (() -> Unit)? = null,
    onOpenComments: () -> Unit,
    onToggleRepost: () -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onLike: () -> Unit,
    onDeletePost: (() -> Unit)? = null
) {
    val hasVisualMedia = post.media.isNotEmpty()
    val canDelete = onDeletePost != null && currentUserId > 0 &&
        (post.creatorId == currentUserId || post.author.id == currentUserId)
    var menuExpanded by remember(post.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(post.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AloNativeLayout.pageInset,
                    vertical = if (hasVisualMedia) 10.dp else 12.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(AloSquircle(12.dp))
                        .let { base ->
                            if (onOpenAuthorProfile != null) {
                                base.clickable(onClick = onOpenAuthorProfile)
                            } else {
                                base
                            }
                        }
                        .padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AvatarBadge(
                        name = post.author.name,
                        avatarUrl = post.author.avatarUrl,
                        verified = post.author.verified,
                        size = 30.dp,
                        colorSeed = post.author.id.toString()
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = post.author.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = AloNativeAppText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (post.author.verified) {
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = AloNativeAppAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = buildPostMeta(post),
                            modifier = Modifier.padding(top = 1.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = AloNativeAppMuted
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = "Действия поста",
                            tint = AloNativeAppMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    AloDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = AloNativeAppSurface,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Скопировать ссылку") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = AloNativeAppMuted
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                clipboard.setText(AnnotatedString(AloDeepLinkTarget.postUrl(post)))
                            }
                        )
                        val editPost = LocalAloPostEditor.current
                        if (canDelete && editPost != null) {
                            DropdownMenuItem(
                                text = { Text("Изменить") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                        tint = AloNativeAppMuted
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    editPost(post)
                                }
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text("Удалить пост") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = Color(0xFFFF4D5F)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }
            }

            if (post.body.isNotBlank()) {
                AloLinkedText(
                    text = post.body,
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AloNativeAppText
                )
            }

            val heroMedia = post.media.firstOrNull()
            if (heroMedia != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .aspectRatio(1.08f)
                        .clip(AloSquircle(10.dp))
                        .clickable {
                            onOpenMediaGallery(post.media, post.media.indexOf(heroMedia).coerceAtLeast(0))
                        }
                ) {
                    AloRemoteMediaImage(
                        model = if (heroMedia.type.startsWith("video")) {
                            heroMedia.posterUrl.ifBlank { heroMedia.url }
                        } else {
                            heroMedia.url
                        },
                        contentDescription = heroMedia.alt.ifBlank { post.body },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        isVideo = heroMedia.type.startsWith("video")
                    )
                    if (heroMedia.type.startsWith("video")) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center),
                            shape = CircleShape,
                            color = Color(0xA8000000)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            if (post.authorSignature.isNotBlank()) {
                Text(
                    text = post.authorSignature,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AloNativeAppMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (heroMedia != null) 6.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PostMetricButton(
                    icon = if (post.viewerLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    tint = if (post.viewerLiked) AloNativeAppLike else AloNativeAppMuted,
                    text = post.likeCount.toString(),
                    onClick = onLike,
                    loading = isLikePending,
                    emphasized = post.viewerLiked
                )
                Spacer(modifier = Modifier.width(14.dp))
                PostMetricButton(
                    icon = if (post.commentsEnabled) {
                        Icons.Rounded.ChatBubbleOutline
                    } else {
                        Icons.Rounded.Lock
                    },
                    tint = AloNativeAppMuted,
                    text = post.commentCount.toString(),
                    onClick = if (post.commentsEnabled) onOpenComments else null
                )
                Spacer(modifier = Modifier.width(14.dp))
                PostMetricButton(
                    icon = Icons.Rounded.Repeat,
                    tint = if (post.viewerReposted) AloNativeAppAccentStrong else AloNativeAppMuted,
                    text = post.repostCount.toString(),
                    onClick = onToggleRepost,
                    loading = isRepostPending
                )
                Spacer(modifier = Modifier.weight(1f))
                PostMetricButton(
                    icon = Icons.Rounded.Visibility,
                    tint = AloNativeAppMuted,
                    text = post.viewCount.toString()
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color.White.copy(alpha = 0.14f)
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = AloNativeAppBackgroundSecondary,
            titleContentColor = AloNativeAppText,
            textContentColor = AloNativeAppMuted,
            title = { Text("Удалить пост?") },
            text = { Text("Пост исчезнет из ленты и профиля. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeletePost?.invoke()
                    }
                ) {
                    Text("Удалить", color = Color(0xFFFF4D5F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Отмена", color = AloNativeAppMuted, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun PostMetricButton(
    icon: ImageVector,
    tint: Color,
    text: String,
    onClick: (() -> Unit)? = null,
    loading: Boolean = false,
    emphasized: Boolean = false
) {
    val iconScale by animateFloatAsState(
        targetValue = if (emphasized && !loading) 1.14f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "postMetricIconScale"
    )
    Row(
        modifier = Modifier
            .clip(AloSquircle(16.dp))
            .let { base ->
                if (onClick != null) {
                    base.clickable(onClick = onClick)
                } else {
                    base
                }
            }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = tint
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = tint.copy(alpha = 0.92f)
        )
    }
}

@Composable
internal fun PostsPanel(
    title: String,
    posts: List<AloPost>,
    currentUserId: Int,
    pendingLikePostId: Int?,
    pendingRepostPostId: Int?,
    emptyTitle: String,
    emptyMessage: String,
    onOpenComments: (Int) -> Unit,
    onToggleRepost: (Int) -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onLike: (Int) -> Unit,
    onDeletePost: (Int) -> Unit = {}
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (posts.isEmpty()) {
            AloPanel {
                EmptyPanelBody(
                    title = emptyTitle,
                    message = emptyMessage
                )
            }
        } else {
            // The same post as in the feed and on the iPhone profile: the avatar at the edge, the text and photos in the column beside it.
            posts.forEach { post ->
                val isMine = currentUserId > 0 && (post.creatorId == currentUserId || post.author.id == currentUserId)
                AloTimelinePostRow(
                    post = post,
                    isMine = isMine,
                    isLikePending = pendingLikePostId == post.id,
                    isRepostPending = pendingRepostPostId == post.id,
                    onAuthor = {},
                    onComments = { onOpenComments(post.id) },
                    onRepost = { onToggleRepost(post.id) },
                    onLike = { onLike(post.id) },
                    onOpenMedia = onOpenMediaGallery,
                    onDelete = if (isMine) ({ onDeletePost(post.id) }) else null,
                    onReaderAction = null
                )
            }
        }
    }
}
