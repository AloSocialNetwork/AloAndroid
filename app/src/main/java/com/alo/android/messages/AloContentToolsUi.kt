package com.alo.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TranscriptRed = Color(0xFFFF8A98)

/**
 * Telegram's «Aa» next to a voice message or circle: a small rounded pill that turns into dots while the text is on its way and into an
 * arrow once the text is open (the next tap folds it).
 */
@Composable
internal fun AloTranscriptButton(
    state: AloTranscriptState?,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // White only on a dark own bubble: on the light theme's pale one a white «Aa» could not be seen.
    val tint = if (isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f) Color.White else AloNativeChatAccentBlue
    val phase = when {
        state is AloTranscriptState.Loading -> 1
        state is AloTranscriptState.Ready && state.expanded -> 2
        else -> 0
    }
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 24.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = if (isOutgoing) 0.2f else 0.16f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Расшифровать в текст" },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = phase, animationSpec = tween(160), label = "transcriptButton") { value ->
            when (value) {
                1 -> AloTypingDots(color = tint)
                2 -> Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Свернуть расшифровку", tint = tint, modifier = Modifier.size(20.dp))
                else -> Text("Aa", color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
            }
        }
    }
}

/**
 * The text under a voice message or circle. It opens by growing out of the button's line; a text that has just arrived is written in
 * letter by letter (the rest of it is already laid out, invisible, so the bubble does not jump while it fills).
 */
@Composable
internal fun AloTranscriptBlock(
    state: AloTranscriptState?,
    isOutgoing: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val visible = (state is AloTranscriptState.Ready && state.expanded) || state is AloTranscriptState.Failed
    // Keeps what it showed while it folds away, instead of collapsing around an empty box.
    val shown = remember { arrayOfNulls<AloTranscriptState>(1) }
    if (state != null && (state is AloTranscriptState.Failed || (state is AloTranscriptState.Ready))) shown[0] = state
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), Alignment.Top) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200), Alignment.Top) + fadeOut(tween(120))
    ) {
        when (val value = state ?: shown[0]) {
            is AloTranscriptState.Ready -> TranscriptText(value.text, value.fresh, textColor)
            is AloTranscriptState.Failed -> Text(
                text = value.message,
                color = if (isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f) Color.White.copy(alpha = 0.92f) else TranscriptRed,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            else -> Unit
        }
    }
}

@Composable
private fun TranscriptText(text: String, fresh: Boolean, color: Color) {
    var revealed by remember(text) { mutableIntStateOf(if (fresh) 0 else text.length) }
    LaunchedEffect(text, fresh) {
        if (!fresh) return@LaunchedEffect
        val steps = text.length.coerceAtLeast(1)
        val duration = (steps * 14L).coerceIn(250L, 1_600L)
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (revealed < text.length) {
            val elapsed = androidx.compose.runtime.withFrameMillis { it } - start
            revealed = ((elapsed.toFloat() / duration) * text.length).toInt().coerceIn(0, text.length)
        }
    }
    val value: AnnotatedString = remember(text, revealed, color) {
        buildAnnotatedString {
            append(text.substring(0, revealed))
            withStyle(SpanStyle(color = Color.Transparent)) { append(text.substring(revealed)) }
        }
    }
    Text(
        text = value,
        color = color,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** «Перевод · English» and the translated text, under the original (the menu's «Скрыть перевод» takes it away). */
@Composable
internal fun AloTranslationBlock(
    state: AloTranslationState?,
    isOutgoing: Boolean,
    textColor: Color,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f) Color.White.copy(alpha = 0.92f) else AloNativeChatAccentBlue
    val shown = remember { arrayOfNulls<AloTranslationState>(1) }
    if (state != null) shown[0] = state
    AnimatedVisibility(
        visible = state != null,
        modifier = modifier,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), Alignment.Top) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200), Alignment.Top) + fadeOut(tween(120))
    ) {
        val value = state ?: shown[0]
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(accent)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (value) {
                    is AloTranslationState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Перевод · ${value.languageName}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            AloTypingDots(color = accent)
                        }
                    }
                    is AloTranslationState.Ready -> {
                        Text("Перевод · ${value.languageName}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = value.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.sp)
                        )
                    }
                    is AloTranslationState.Failed -> {
                        Text(
                            text = value.message,
                            color = if (isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f) Color.White.copy(alpha = 0.92f) else TranscriptRed,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.clickable(onClick = onDismissError)
                        )
                    }
                    null -> Unit
                }
            }
        }
    }
}

/** «Перевести на…»: the server's languages, the one used last on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AloTranslationLanguageSheet(
    languages: List<AloContentLanguage>,
    onDismiss: () -> Unit,
    onPick: (AloContentLanguage) -> Unit
) {
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AloNativeAppBackground
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            AloSheetTitle("Перевести на…", bleed = 16.dp)
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                items(languages, key = { it.code }) { language ->
                    AloSheetItem(
                        title = language.name,
                        subtitle = language.english.takeIf { it.isNotBlank() && !it.equals(language.name, ignoreCase = true) },
                        icon = Icons.Rounded.Translate,
                        bleed = 16.dp,
                        onClick = { onPick(language) }
                    )
                }
            }
        }
    }
}

/** The one-time question before a message leaves for the translation or transcription service. */
@Composable
internal fun AloContentToolsConsentDialog(
    transcription: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (transcription) "Расшифровать голосовое?" else "Перевести сообщение?") },
        text = {
            Text(
                (if (transcription) "Звук этого сообщения будет отправлен на сервер Alo и в сервис расшифровки. " else
                    "Текст этого сообщения будет отправлен на сервер Alo и в сервис перевода. ") +
                    "Остальная переписка не передаётся, сервер ничего не хранит. Согласие можно отозвать в настройках: «Данные и память»."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Разрешить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
