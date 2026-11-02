package com.alo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sending files across the chat: their progress, which are paused, and the pause / resume toggle. */
internal class AloUploadsState(
    val progress: Map<Int, AloUploadProgress>,
    val paused: Set<Int>,
    val onToggle: (Int) -> Unit
)

internal val LocalAloUploads = compositionLocalOf<AloUploadsState?> { null }

/**
 * iOS's AloFileAttachmentRow (Telegram's file row): a round icon in the accent colour, the name,
 * then type and size. While the message is sending, the icon is the upload ring with pause / resume.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AloFileAttachmentRow(
    name: String,
    sizeBytes: Long?,
    isOutgoing: Boolean,
    textColor: Color,
    uploadLocalId: Int?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val uploads = LocalAloUploads.current
    // White circle and ring only on the dark theme's blue own bubble; the light theme's pale one takes the accent.
    val onBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val accent = if (onBlue) Color.White else AloNativeChatAccentBlue
    val glyph = if (onBlue) Color.Black.copy(alpha = 0.6f) else Color.White
    val extension = name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() && it.length <= 6 }
    val details = listOfNotNull(extension ?: "Файл", sizeBytes?.let(::aloFileSize)).joinToString(" · ")
    val progress = uploadLocalId?.let { uploads?.progress?.get(it) }
    val paused = uploadLocalId != null && uploads?.paused?.contains(uploadLocalId) == true
    Row(
        modifier
            .widthIn(min = 200.dp)
            .combinedClickable(onClick = { if (uploadLocalId == null) onOpen() }, onLongClick = onLongPress)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uploadLocalId != null) {
            val fraction by animateFloatAsState((progress?.fraction ?: 0.02f).coerceAtLeast(0.02f), tween(250), label = "uploadRing")
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable { uploads?.onToggle?.invoke(uploadLocalId) }
                    .semantics {
                        contentDescription = if (paused) "Продолжить загрузку" else "Приостановить загрузку"
                        stateDescription = "${((progress?.fraction ?: 0f) * 100).toInt()} процентов"
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = 2.5.dp.toPx()
                    drawCircle(glyph.copy(alpha = if (isOutgoing) 0.18f else 0.3f), style = Stroke(stroke))
                    drawArc(glyph, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                AnimatedContent(paused, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }, label = "uploadGlyph") { isPaused ->
                    Icon(if (isPaused) Icons.Rounded.ArrowUpward else Icons.Rounded.Pause, null, tint = glyph, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Box(Modifier.size(46.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.InsertDriveFile, null, tint = glyph, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name.ifBlank { "Файл" }, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                when {
                    uploadLocalId == null -> details
                    progress == null -> "Подготовка…"
                    paused -> "Пауза · ${progress.label}"
                    else -> progress.label
                },
                color = textColor.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1
            )
        }
    }
}
