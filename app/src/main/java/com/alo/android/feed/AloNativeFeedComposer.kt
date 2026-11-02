package com.alo.android

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposerPanel(
    me: AloCurrentUser,
    value: String,
    isRefreshing: Boolean,
    isSubmitting: Boolean,
    onValueChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(AloNativeLayout.radiusLarge),
        color = AloNativeAppSurface,
        border = BorderStroke(1.dp, AloNativeAppSeparator)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarBadge(
                    name = me.name,
                    avatarUrl = me.avatarUrl,
                    verified = me.verified,
                    size = 44.dp,
                    colorSeed = me.id.toString()
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = AloNativeAppText),
                    cursorBrush = SolidColor(AloNativeAppAccentStrong),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(AloNativeAppSurface.copy(alpha = 0.96f))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (value.isBlank()) {
                                Text(
                                    text = "Что нового?",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = AloNativeAppMuted
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSubmit,
                    enabled = !isSubmitting && value.isNotBlank(),
                    modifier = Modifier.height(AloNativeLayout.compactControlHeight),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AloNativeAppAccentStrong,
                        contentColor = Color.White,
                        disabledContainerColor = AloNativeAppSurfaceRaised,
                        disabledContentColor = AloNativeAppMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = "Опубликовать",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostComposerSheet(
    me: AloCurrentUser,
    value: String,
    isSubmitting: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // Request focus only after the bottom sheet enters composition. This
        // avoids the sheet's drag layer swallowing the first tap on the input.
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AloSheetShape,
        containerColor = AloNativeAppBackgroundSecondary,
        contentColor = AloNativeAppText,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(AloSquircle(99.dp))
                    .background(AloNativeAppMuted.copy(alpha = 0.55f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AloNativeLayout.pageInset)
                .imePadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Закрыть создание поста",
                        tint = AloNativeAppAccent
                    )
                }
                Text(
                    text = "Новый пост",
                    modifier = Modifier.weight(1f),
                    fontSize = AloTelegramMetrics.Sheets.TitleSize,
                    fontWeight = FontWeight.Medium,
                    color = AloNativeAppText
                )
                TextButton(
                    onClick = onSubmit,
                    enabled = value.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AloNativeAppAccent
                        )
                    } else {
                        Text(
                            text = "Опубликовать",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = if (value.isNotBlank()) AloNativeAppAccent else AloNativeAppMuted
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                AvatarBadge(
                    name = me.name,
                    avatarUrl = me.avatarUrl,
                    verified = me.verified,
                    size = 42.dp,
                    colorSeed = me.id.toString()
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = me.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                    ComposerInput(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 148.dp, max = 196.dp)
                            .padding(top = 8.dp)
                            .focusRequester(focusRequester)
                    )
                }
            }

        }
    }
}

@Composable
internal fun ComposerInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AloNativeAppText),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences
        ),
        cursorBrush = SolidColor(AloNativeAppAccentStrong),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp)
            ) {
                if (value.isBlank()) {
                    Text(
                        text = "Что нового?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AloNativeAppMuted
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
internal fun FeedPanel(
    posts: List<AloPost>,
    currentUserId: Int,
    pendingLikePostId: Int?,
    pendingRepostPostId: Int?,
    errorMessage: String?,
    onRetry: () -> Unit,
    onOpenAuthorProfile: (AloEntitySummary) -> Unit,
    onOpenComments: (Int) -> Unit,
    onToggleRepost: (Int) -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onLike: (Int) -> Unit,
    onDeletePost: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!errorMessage.isNullOrBlank()) {
            InlineErrorPanel(
                message = errorMessage,
                onRetry = onRetry
            )
        }

        if (posts.isEmpty()) {
            AloPanel {
                EmptyPanelBody(
                    title = "Пока пусто",
                    message = "Когда появятся посты, они будут здесь."
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                posts.forEach { post ->
                    FeedPostRow(
                        post = post,
                        currentUserId = currentUserId,
                        isLikePending = pendingLikePostId == post.id,
                        isRepostPending = pendingRepostPostId == post.id,
                        onOpenAuthorProfile = { onOpenAuthorProfile(post.author) },
                        onOpenComments = { onOpenComments(post.id) },
                        onToggleRepost = { onToggleRepost(post.id) },
                        onOpenMedia = onOpenMedia,
                        onOpenMediaGallery = onOpenMediaGallery,
                        onLike = { onLike(post.id) },
                        onDeletePost = { onDeletePost(post.id) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun FeedModeSwitcher(
    feedMode: AloFeedMode,
    onSelectMode: (AloFeedMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
        color = AloNativeAppSurface,
        border = BorderStroke(1.dp, AloNativeAppBorder)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            val targetOffset = if (feedMode == AloFeedMode.Subscriptions) maxWidth / 2 else 0.dp
            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                label = "feedModeIndicator"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .fillMaxWidth(0.5f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(AloNativeLayout.radiusSmall))
                    .background(AloNativeAppSurfaceInteractive)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                FeedModeTab(
                    title = "Для вас",
                    selected = feedMode == AloFeedMode.Popular,
                    onClick = { onSelectMode(AloFeedMode.Popular) },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    drawSelectedBackground = false
                )
                FeedModeTab(
                    title = "Подписки",
                    selected = feedMode == AloFeedMode.Subscriptions,
                    onClick = { onSelectMode(AloFeedMode.Subscriptions) },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    drawSelectedBackground = false
                )
            }
        }
    }
}

@Composable
internal fun FeedModeTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    drawSelectedBackground: Boolean = true
) {
    val textColor = if (selected) AloNativeAppText else AloNativeAppMuted

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AloNativeLayout.radiusSmall))
            .background(
                if (drawSelectedBackground && selected) {
                    AloNativeAppSurfaceInteractive
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = textColor
        )
    }
}
