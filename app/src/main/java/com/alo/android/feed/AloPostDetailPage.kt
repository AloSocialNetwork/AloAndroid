package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS's «Публикация»: the post, then «Комментарии N» with Instagram's one-level threads,
// «Ответить», likes on the right and «@» suggestions above the field.

private val AloCommentLikeRed = Color(0xFFFF526B)

/** The handle typed after the last «@», when the text ends inside it (iOS's AloMentionQuery). */
internal fun aloMentionQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0) {
        val before = text[at - 1]
        if (!before.isWhitespace() && before !in "([{«\"") return null
    }
    val handle = text.substring(at + 1)
    if (handle.length > 32 || !handle.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) return null
    return handle
}

/**
 * Adds a new comment, or replaces it when it is already in the list. The realtime event and the reply to «Отправить» both
 * bring the same comment, in either order; appending it twice gave two list items with one key and the app fell
 * («Key "c-…" was already used», 0.1.5–0.1.7).
 */
internal fun aloUpsertComment(comments: List<AloPostComment>, comment: AloPostComment): List<AloPostComment> =
    if (comments.any { it.id == comment.id }) comments.map { if (it.id == comment.id) comment else it } else comments + comment

internal fun aloInsertMention(username: String, text: String): String {
    val at = text.lastIndexOf('@')
    return if (aloMentionQuery(text) != null && at >= 0) text.substring(0, at) + "@$username " else "$text@$username "
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AloPostDetailPage(
    post: AloPost?,
    comments: List<AloPostComment>,
    focusCommentId: Int = 0,
    value: () -> String,
    meId: Int,
    editingCommentId: Int?,
    replyTo: AloPostComment?,
    mentionSuggestions: List<AloEntitySummary>,
    isLoading: Boolean,
    isSubmitting: Boolean,
    pendingCommentLikeIds: Set<Int>,
    onBack: () -> Unit,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onLikePost: () -> Unit,
    onRepostPost: () -> Unit,
    onLikeComment: (AloPostComment) -> Unit,
    onReply: (AloPostComment?) -> Unit,
    onEditComment: (AloPostComment) -> Unit,
    onDeleteComment: (AloPostComment) -> Unit,
    onReportComment: (AloPostComment) -> Unit,
    onMentionQuery: (String?) -> Unit,
    onOpenAuthorProfile: (AloEntitySummary) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit
) {
    BackHandler(onBack = onBack)
    // One row per comment id, whatever the list brings: LazyColumn falls on a repeated key.
    val shown = remember(comments) { comments.distinctBy { it.id } }
    val clipboard = LocalClipboardManager.current
    var expandedThreads by remember { mutableStateOf(setOf<Int>()) }
    var menuFor by remember { mutableStateOf<AloPostComment?>(null) }
    val ids = shown.map { it.id }.toSet()
    val roots = shown.filter { it.parentId == 0 || it.parentId !in ids }
    val listState = rememberLazyListState()
    val focused = shown.firstOrNull { it.id == focusCommentId }
    LaunchedEffect(focusCommentId, shown) {
        focused?.let { if (it.parentId > 0) expandedThreads = expandedThreads + it.parentId }
    }
    LaunchedEffect(focusCommentId, shown, expandedThreads) {
        if (focused == null) return@LaunchedEffect
        val keys = buildList {
            if (post != null) { add("post"); add("heading") }
            roots.forEach { root ->
                add("c-${root.id}")
                val thread = shown.filter { it.parentId == root.id }
                if (thread.isNotEmpty()) {
                    if (root.id in expandedThreads) thread.forEach { add("c-${it.id}") }
                    add("t-${root.id}")
                }
            }
        }
        val index = keys.indexOf("c-$focusCommentId")
        if (index >= 0) listState.animateScrollToItem(index)
    }
    // Recomposes the page only when the @name being typed changes, not with every letter.
    val currentValue by rememberUpdatedState(value)
    val mentionQuery by remember { derivedStateOf { aloMentionQuery(currentValue()) } }
    LaunchedEffect(mentionQuery) { onMentionQuery(mentionQuery) }
    LaunchedEffect(replyTo?.parentId, replyTo?.id) {
        val root = replyTo?.let { if (it.parentId > 0) it.parentId else it.id }
        if (root != null) expandedThreads = expandedThreads + root
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        // iOS pushes «Публикация» on the system navigation bar: «‹ Назад» and a centred title.
        AloSettingsNavigationBar(title = "Публикация", onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            post?.let { current ->
                item(key = "post") {
                    AloTimelinePostRow(
                        post = current,
                        isMine = current.creatorId == meId || current.author.id == meId,
                        isLikePending = false,
                        isRepostPending = false,
                        onAuthor = { onOpenAuthorProfile(current.author) },
                        onComments = {},
                        onRepost = onRepostPost,
                        onLike = onLikePost,
                        onOpenMedia = onOpenMediaGallery,
                        onDelete = null,
                        onReaderAction = null,
                        expanded = true
                    )
                }
                item(key = "heading") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Комментарии", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(9.dp))
                        Text(formatCompactCount(current.commentCount), color = AloNativeAppMuted, fontSize = 15.sp)
                    }
                }
            }
            when {
                isLoading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AloNativeAppAccent)
                    }
                }
                shown.isEmpty() -> item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Комментариев пока нет", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Будь первым в обсуждении.", color = AloNativeAppMuted, fontSize = 15.sp)
                    }
                }
                else -> roots.forEach { root ->
                    item(key = "c-${root.id}") {
                        AloCommentRow(root, false, root.author.id == meId, pendingCommentLikeIds,
                            onLike = { onLikeComment(root) }, onReply = { onReply(root) },
                            onAuthor = { onOpenAuthorProfile(root.author) }, onDelete = { onDeleteComment(root) },
                            onLongPress = { menuFor = root }, onOpenMedia = onOpenMediaGallery)
                    }
                    val thread = shown.filter { it.parentId == root.id }
                    if (thread.isNotEmpty()) {
                        val open = root.id in expandedThreads
                        if (open) {
                            thread.forEach { reply ->
                                item(key = "c-${reply.id}") {
                                    Box(Modifier.padding(start = 52.dp)) {
                                        AloCommentRow(reply, true, reply.author.id == meId, pendingCommentLikeIds,
                                            onLike = { onLikeComment(reply) }, onReply = { onReply(reply) },
                                            onAuthor = { onOpenAuthorProfile(reply.author) }, onDelete = { onDeleteComment(reply) },
                                            onLongPress = { menuFor = reply }, onOpenMedia = onOpenMediaGallery)
                                    }
                                }
                            }
                        }
                        item(key = "t-${root.id}") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedThreads = if (open) expandedThreads - root.id else expandedThreads + root.id }
                                    .padding(start = 68.dp, top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(24.dp).height(1.dp).background(AloNativeAppMuted.copy(alpha = 0.5f)))
                                Spacer(Modifier.width(10.dp))
                                Text(if (open) "Скрыть ответы" else "Посмотреть ответы (${thread.size})",
                                    color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    item(key = "d-${root.id}") {
                        HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.15f))
                    }
                }
            }
        }

        AloLiveText(value) { text ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(AloNativeAppBackground.copy(alpha = 0.98f))
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(mentionQuery != null && mentionSuggestions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurfaceRaised)) {
                    mentionSuggestions.take(5).forEach { person ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onValueChange(aloInsertMention(person.username, text)) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AvatarBadge(name = person.name, avatarUrl = person.avatarUrl, verified = false, size = 32.dp, colorSeed = person.id.toString())
                            Column {
                                Text(person.name, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("@${person.username}", color = AloNativeAppMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            replyTo?.let { target ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ответ для " + (target.author.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: target.author.name),
                        color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Rounded.Close, "Отменить ответ", tint = AloNativeAppMuted,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onReply(null) }.padding(6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(AloSquircle(22.dp))
                        .aloLiquidGlass(AloSquircle(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp),
                    cursorBrush = SolidColor(AloNativeAppAccent),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    when {
                                        editingCommentId != null -> "Изменить комментарий"
                                        replyTo != null -> "Ваш ответ…"
                                        else -> "Ваш комментарий…"
                                    },
                                    color = AloNativeAppMuted, fontSize = 17.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                val canSend = text.isNotBlank() && !isSubmitting
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (canSend) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.ArrowUpward, "Отправить комментарий", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        }
    }

    menuFor?.let { comment ->
        val mine = comment.author.id == meId
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text(comment.author.name, color = AloNativeAppText) },
            text = {
                Column {
                    listOfNotNull(
                        "Ответить" to { onReply(comment) },
                        ("Скопировать" to { clipboard.setText(AnnotatedString(comment.body)) }).takeIf { comment.body.isNotBlank() },
                        ("Изменить" to { onEditComment(comment) }).takeIf { mine },
                        ("Удалить" to { onDeleteComment(comment) }).takeIf { mine },
                        ("Пожаловаться" to { onReportComment(comment) }).takeIf { !mine }
                    ).forEach { (title, action) ->
                        Text(
                            title,
                            color = if (title == "Удалить" || title == "Пожаловаться") Color(0xFFFF453A) else AloNativeAppText,
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable { menuFor = null; action() }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AloCommentRow(
    comment: AloPostComment,
    isReply: Boolean,
    isMine: Boolean,
    pendingLikes: Set<Int>,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onAuthor: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    onOpenMedia: (List<AloMediaItem>, Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 12.dp, top = if (isReply) 6.dp else 10.dp, bottom = if (isReply) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AvatarBadge(name = comment.author.name, avatarUrl = comment.author.avatarUrl, verified = false,
            size = if (isReply) 30.dp else 42.dp, colorSeed = comment.author.id.toString(), onClick = onAuthor)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.clickable(onClick = onAuthor)) {
                Text(comment.author.name, color = AloNativeAppText, fontSize = if (isReply) 15.sp else 16.sp, fontWeight = FontWeight.Bold)
                if (comment.author.verified) Icon(Icons.Rounded.Verified, null, tint = AloNativeAppAccent, modifier = Modifier.size(13.dp))
            }
            if (comment.body.isNotBlank()) {
                AloLinkedText(text = comment.body, color = AloNativeAppText,
                    style = TextStyle(fontSize = if (isReply) 15.sp else 16.sp, lineHeight = 21.sp))
            }
            if (comment.media.isNotEmpty()) {
                AloTimelineMediaGrid(media = comment.media, modifier = Modifier.padding(top = 6.dp)) { onOpenMedia(comment.media, it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    formatPostTimestamp(comment.createdAt) + if (comment.editedAt.isNotBlank()) " · изменено" else "",
                    color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text("Ответить", color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onReply).padding(vertical = 8.dp))
                if (isMine) {
                    Text("Удалить", color = Color(0xFFFF453A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onDelete).padding(vertical = 8.dp))
                }
            }
        }
        Column(
            Modifier.width(36.dp).heightIn(min = 44.dp).clickable(enabled = comment.id !in pendingLikes, onClick = onLike),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(if (comment.viewerLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Лайк комментария",
                tint = if (comment.viewerLiked) AloCommentLikeRed else AloNativeAppMuted, modifier = Modifier.size(16.dp))
            if (comment.likeCount > 0) {
                Text(formatCompactCount(comment.likeCount), color = if (comment.viewerLiked) AloCommentLikeRed else AloNativeAppMuted, fontSize = 11.sp)
            }
        }
    }
}
