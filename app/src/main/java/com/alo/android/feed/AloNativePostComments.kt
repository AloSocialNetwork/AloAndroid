package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PostCommentsSheet(
    post: AloPost?,
    comments: List<AloPostComment>,
    value: String,
    meId: Int,
    editingCommentId: Int?,
    isLoading: Boolean,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onEditComment: (AloPostComment) -> Unit,
    onDeleteComment: (AloPostComment) -> Unit,
    onOpenAuthorProfile: (AloEntitySummary) -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = AloNativeAppText,
        lineHeight = 20.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val composerHeight = 46.dp
    val sendButtonColor by animateColorAsState(
        targetValue = if (value.isNotBlank() && !isSubmitting) AloNativeChatAccentBlue else AloNativeAppSurfaceRaised,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "commentSendButtonColor"
    )
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dismissDragOffset by remember { mutableStateOf(0f) }
    var selectedCommentForMenu by remember { mutableStateOf<AloPostComment?>(null) }
    val entranceOffset by animateFloatAsState(
        targetValue = if (visible) 0f else 220f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "commentsSheetEntrance"
    )
    val animatedOffset by animateFloatAsState(
        targetValue = dismissDragOffset,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "commentsSheetOffset"
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "commentsSheetScrim"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    fun dismissWithAnimation() {
        visible = false
        dismissDragOffset = 0f
        scope.launch {
            delay(200)
            onDismiss()
        }
    }

    BackHandler {
        dismissWithAnimation()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f * scrimAlpha))
            .clickable(onClick = ::dismissWithAnimation)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .graphicsLayer {
                    translationY = entranceOffset + animatedOffset
                    alpha = 1f - ((entranceOffset + animatedOffset) / 1400f).coerceIn(0f, 0.35f)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            dismissDragOffset = (dismissDragOffset + dragAmount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dismissDragOffset > 180f) {
                                dismissWithAnimation()
                            } else {
                                dismissDragOffset = 0f
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            color = AloNativeAppSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(AloNativeAppMuted.copy(alpha = 0.45f))
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Комментарии",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                }

                post?.let { currentPost ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        shape = AloSquircle(20.dp),
                        color = AloNativeAppBackgroundSecondary,
                        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.03f))
                    ) {
                        CommentPostPreview(
                            post = currentPost,
                            onOpenAuthorProfile = { onOpenAuthorProfile(currentPost.author) },
                            onOpenMedia = onOpenMedia,
                            onOpenMediaGallery = onOpenMediaGallery
                        )
                    }
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AloNativeChatAccentBlue)
                        }
                    }

                    comments.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Комментариев пока нет",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = AloNativeAppText
                                )
                                Text(
                                    text = "Начни обсуждение первым.",
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AloNativeAppMuted
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(comments, key = { it.id }) { comment ->
                                CommentRow(
                                    comment = comment,
                                    onOpenAuthorProfile = { onOpenAuthorProfile(comment.author) },
                                    onOpenMedia = onOpenMedia,
                                    onOpenMediaGallery = onOpenMediaGallery,
                                    onLongPress = { selectedCommentForMenu = comment }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .imePadding()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(composerHeight),
                        shape = RoundedCornerShape(999.dp),
                        color = AloNativeChatComposer,
                        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp),
                            textStyle = composerTextStyle,
                            cursorBrush = SolidColor(AloNativeChatAccentBlue),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (value.isBlank()) {
                                        Text(
                                            text = if (editingCommentId != null) "Редактировать" else "Комментарий",
                                            style = composerTextStyle,
                                            color = AloNativeAppMuted
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Surface(
                        modifier = Modifier.size(composerHeight),
                        shape = CircleShape,
                        color = sendButtonColor
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .clickable(enabled = value.isNotBlank() && !isSubmitting, onClick = onSend),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = AloNativeAppButtonText
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    tint = if (value.isNotBlank()) AloNativeAppButtonText else AloNativeAppMuted,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .offset(x = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedCommentForMenu?.let { selectedComment ->
            CommentActionSheet(
                comment = selectedComment,
                isMine = selectedComment.author.id == meId,
                onDismiss = { selectedCommentForMenu = null },
                onCopy = {
                    clipboardManager.setText(AnnotatedString(selectedComment.body))
                    selectedCommentForMenu = null
                },
                onEdit = {
                    onEditComment(selectedComment)
                    selectedCommentForMenu = null
                },
                onDelete = {
                    onDeleteComment(selectedComment)
                    selectedCommentForMenu = null
                }
            )
        }
    }
}

@Composable
private fun CommentPostPreview(
    post: AloPost,
    onOpenAuthorProfile: () -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpenAuthorProfile),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            AvatarBadge(
                name = post.author.name,
                avatarUrl = post.author.avatarUrl,
                verified = post.author.verified,
                size = 34.dp,
                colorSeed = post.author.id.toString()
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = post.author.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                    Text(
                        text = formatFeedDate(post.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted
                    )
                }
                if (post.body.isNotBlank()) {
                    AloLinkedText(
                        text = post.body,
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AloNativeAppText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        post.media.firstOrNull()?.let { media ->
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.08f)
                    .clip(AloSquircle(16.dp))
                    .clickable {
                        onOpenMediaGallery(post.media, post.media.indexOf(media).coerceAtLeast(0))
                    }
            ) {
                AsyncImage(
                    model = if (media.type.startsWith("video")) media.posterUrl.ifBlank { media.url } else media.url,
                    contentDescription = media.alt.ifBlank { post.body },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (media.type.startsWith("video")) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = CircleShape,
                        color = Color(0xA8000000)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: AloPostComment,
    onOpenAuthorProfile: () -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            AvatarBadge(
                name = comment.author.name,
                avatarUrl = comment.author.avatarUrl,
                verified = comment.author.verified,
                size = 34.dp,
                colorSeed = comment.author.id.toString(),
                modifier = Modifier.clickable(onClick = onOpenAuthorProfile)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 1.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = comment.author.name,
                        modifier = Modifier.clickable(onClick = onOpenAuthorProfile),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                    Text(
                        text = buildMessageMetaLabel(comment.createdAt, comment.editedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted
                    )
                }

                if (comment.body.isNotBlank()) {
                    Text(
                        text = comment.body,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AloNativeAppText
                    )
                }

                comment.media.firstOrNull()?.let { media ->
                    AsyncImage(
                        model = if (media.type.startsWith("video")) media.posterUrl.ifBlank { media.url } else media.url,
                        contentDescription = media.alt.ifBlank { comment.body },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .aspectRatio(1.18f)
                            .clip(AloSquircle(16.dp))
                            .clickable {
                                onOpenMediaGallery(comment.media, comment.media.indexOf(media).coerceAtLeast(0))
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        HorizontalDivider(color = AloNativeAppBorder.copy(alpha = 0.04f))
    }
}

@Composable
private fun CommentActionSheet(
    comment: AloPostComment,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clickable(onClick = {}),
            shape = AloSquircle(26.dp),
            color = AloNativeAppSurface,
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 2.dp, bottom = 8.dp)
                        .width(42.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(AloNativeAppMuted.copy(alpha = 0.5f))
                )
                ChatMessageActionRow("Копировать", enabled = comment.body.isNotBlank(), onClick = onCopy)
                ChatMessageActionRow("Изменить", enabled = isMine, onClick = onEdit)
                ChatMessageActionRow("Удалить", enabled = isMine, destructive = true, onClick = onDelete)
            }
        }
    }
}
