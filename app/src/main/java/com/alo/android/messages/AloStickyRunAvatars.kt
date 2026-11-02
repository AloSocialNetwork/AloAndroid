package com.alo.android

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

/** A group member's run of messages on screen: where its rows are (pixels from the list's top) and whose it is. */
private data class AloRunOnScreen(val start: Int, val top: Int, val bottom: Int, val message: AloChatMessage)

/**
 * Group members' avatars, one per run, drawn over the message list as in Telegram on iPhone and Android (the owner's
 * choice, 2026-10-29): the avatar stays right above the composer while the run goes on below it, and rides up with
 * the run's last message. The rows leave its column empty ([MessageBubble] with `drawsGroupAvatar = false`).
 */
@Composable
internal fun BoxScope.AloStickyRunAvatars(
    listState: LazyListState,
    messages: List<AloChatMessage>,
    meId: Int,
    dayOf: (AloChatMessage) -> String,
    onOpenProfile: (AloProfileTarget) -> Unit
) {
    val density = LocalDensity.current
    val avatarPx = with(density) { AloGroupMessageMetrics.AvatarSize.roundToPx() }
    val leftPx = with(density) { AloGroupMessageMetrics.AvatarLeft.roundToPx() }
    val runs by remember(messages, meId) {
        derivedStateOf {
            val info = listState.layoutInfo
            val stick = info.viewportSize.height - info.afterContentPadding
            val found = linkedMapOf<Int, AloRunOnScreen>()
            for (item in info.visibleItemsInfo) {
                val message = messages.getOrNull(item.index) ?: continue
                if (message.senderId == meId || aloBreaksGroupRun(message) || message.sender == null) continue
                var start = item.index
                while (start > 0 && aloContinuesGroupRun(messages[start - 1], messages[start], ::aloBreaksGroupRun, dayOf)) start -= 1
                val top = item.offset - info.viewportStartOffset
                val bottom = top + item.size
                val known = found[start]
                found[start] = if (known == null) AloRunOnScreen(start, top, bottom, message)
                else known.copy(top = minOf(known.top, top), bottom = maxOf(known.bottom, bottom))
            }
            found.values.map { run ->
                val avatarBottom = minOf(run.bottom, maxOf(run.top + avatarPx, stick))
                run to (avatarBottom - avatarPx)
            }
        }
    }
    for ((run, y) in runs) {
        val sender = run.message.sender ?: continue
        key(run.start) {
            AvatarBadge(
                modifier = Modifier.offset { IntOffset(leftPx, y) },
                name = sender.name,
                avatarUrl = sender.avatarUrl,
                verified = false,
                size = AloGroupMessageMetrics.AvatarSize,
                colorSeed = sender.id.toString(),
                onClick = if (!run.message.senderIsAnonymous && sender.id > 0) {
                    { onOpenProfile(AloProfileTarget.User(sender.id)) }
                } else null
            )
        }
    }
}
