package com.alo.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opens the editor of a post; null where nothing can edit (previews). Set once at the top of the app, read by every post's menu. */
internal val LocalAloPostEditor = staticCompositionLocalOf<((AloPost) -> Unit)?> { null }

internal const val AloPostMaxLength = 1200

/** «Изменить» on a post is available to the person who published it. */
internal fun AloPost.canBeEditedBy(userId: Int): Boolean = userId > 0 && (creatorId == userId || author.id == userId && !author.isChannel)

@Composable
internal fun AloPostEditHost(onAction: (AloNativeAppAction) -> Unit, content: @Composable () -> Unit) {
    var editing by remember { mutableStateOf<AloPost?>(null) }
    CompositionLocalProvider(LocalAloPostEditor provides { post -> editing = post }, content = content)
    editing?.let { post -> AloPostEditSheet(post = post, onDismiss = { editing = null }, onAction = onAction) }
}

@Composable
private fun AloPostEditSheet(post: AloPost, onDismiss: () -> Unit, onAction: (AloNativeAppAction) -> Unit) {
    var text by remember(post.id) { mutableStateOf(post.body) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var saving by remember(post.id) { mutableStateOf(false) }
    val canSave = !saving && text.trim() != post.body.trim() && (text.isNotBlank() || post.media.isNotEmpty())
    AloFormSheet(
        title = "Изменить пост",
        onDismiss = onDismiss,
        leading = "Отмена",
        trailing = "Готово",
        trailingEnabled = canSave,
        onTrailing = {
            error = null
            saving = true
            onAction(AloNativeAppAction.EditPost(post.id, text) { problem ->
                saving = false
                if (problem == null) onDismiss() else error = problem
            })
        },
        fullHeight = false
    ) {
        AloFormSection(footer = "${text.length} из $AloPostMaxLength. Время публикации не изменится, у поста появится пометка «изменено».") {
            AloFormTextField(
                value = text,
                onChange = { text = it.take(AloPostMaxLength) },
                placeholder = "Текст поста",
                minLines = 4,
                maxLines = 12
            )
        }
        error?.let {
            Text(it, color = AloNativeAppDanger, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 36.dp))
    }
}
