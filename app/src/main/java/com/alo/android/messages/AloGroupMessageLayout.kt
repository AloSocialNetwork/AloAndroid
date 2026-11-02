package com.alo.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Telegram Android 13.0.3 (`Cells/ChatMessageCell.java`): the sender's avatar and name in a group chat. */
internal object AloGroupMessageMetrics {
    /** `avatarImage.setRoundRadius(dp(21))`. */
    val AvatarSize = 42.dp

    /** `avatarImage.setImageCoords(dp(6), …)`: from the screen edge. */
    val AvatarLeft = 6.dp

    /** `drawAvatar ? 52 : 0`: where an incoming bubble starts when the avatar is drawn. */
    val ContentOffset = 52.dp

    /** The chat list's own side padding, which the avatar slot has to make up to [ContentOffset]. */
    val ListSidePadding = 12.dp
    val GutterWidth = ContentOffset - ListSidePadding

    /** The avatar's left edge relative to the list content (it starts left of the list's padding). */
    val AvatarOffsetInList = AvatarLeft - ListSidePadding

    /** `chat_namePaint`: (2 · fontSize + 10) / 3 at the default 16. */
    val NameSize = 14.sp
    val NameLineHeight = 18.sp

    /** `chat_adminPaint`: the name's size less 1. */
    val RoleSize = 13.sp
    val RoleLineHeight = 16.sp

    /** `adminWidth += dp(12)`: the tag's text sits 6dp from each end of its pill. */
    val RoleHorizontalPadding = 6.dp
}

/**
 * Where a message sits in a run of consecutive messages from one sender (Telegram's pinned top / pinned bottom):
 * the first shows the name, the last shows the avatar.
 */
internal data class AloGroupRunPosition(val startsRun: Boolean, val endsRun: Boolean) {
    companion object {
        val Single = AloGroupRunPosition(startsRun = true, endsRun = true)
    }
}

/** A system line or a call record interrupts a run, as does a new day or another forum topic. */
internal fun aloBreaksGroupRun(message: AloChatMessage): Boolean =
    AloChatSystemEventCodec.decode(message.body) != null || message.toCallLogMeta()?.scope == "group"

internal fun aloContinuesGroupRun(
    earlier: AloChatMessage?,
    later: AloChatMessage?,
    breaksRun: (AloChatMessage) -> Boolean,
    dayOf: (AloChatMessage) -> String
): Boolean =
    earlier != null && later != null &&
        earlier.senderId == later.senderId &&
        earlier.senderIsAnonymous == later.senderIsAnonymous &&
        earlier.topicId == later.topicId &&
        !breaksRun(earlier) && !breaksRun(later) &&
        dayOf(earlier) == dayOf(later)

internal fun aloGroupRunPosition(
    previous: AloChatMessage?,
    current: AloChatMessage,
    next: AloChatMessage?,
    breaksRun: (AloChatMessage) -> Boolean,
    dayOf: (AloChatMessage) -> String
) = AloGroupRunPosition(
    startsRun = !aloContinuesGroupRun(previous, current, breaksRun, dayOf),
    endsRun = !aloContinuesGroupRun(current, next, breaksRun, dayOf)
)

/**
 * The tag at the right end of the sender's name row (Telegram's `ChatTagOwner` / `ChatTagAdmin`): the admin's own title
 * if they set one, otherwise «Владелец» or «Админ». Empty for everyone else.
 */
internal fun aloGroupSenderRoleLabel(message: AloChatMessage, members: List<AloGroupMember>): String {
    if (message.senderIsAnonymous) return message.senderAdminTitle.trim()
    val member = members.firstOrNull { it.user.id == message.senderId }
    val title = message.senderAdminTitle.ifBlank { member?.adminTitle.orEmpty() }.trim()
    return when (member?.role) {
        "owner" -> title.ifBlank { "Владелец" }
        "admin" -> title.ifBlank { "Админ" }
        else -> title
    }
}

/**
 * Telegram Android 13.0.3, bubbles in a run of one sender's messages (`Theme.MessageDrawable`: `isTopNear` and `isBottomNear`
 * make a corner `nearRad = dp(min(6, bubbleRadius))`; `Cells/ChatMessageCell.java` pulls the neighbours together).
 */
internal object AloSeriesMetrics {
    /** The corner next to a neighbour on the tail side. */
    val NearCorner = 6.dp

    /** The gap between two messages of one run; separate runs keep the chat's own spacing. */
    val GapInRun = 2.dp
}

/** `Components/Reactions/ReactionsLayoutInBubble.java`: a reaction chip under a message. */
internal object AloReactionChipMetrics {
    /** `button.height = dp(26)`; the chip is a pill (`rad = height / 2`). */
    val Height = 26.dp

    /** `currentX += width + dp(4)` and `currentY += height + dp(4)`: chips wrap onto further lines. */
    val Gap = 4.dp

    /** `width = dp(8) + dp(20) + dp(4) + counter + dp(8)`. */
    val SidePadding = 8.dp
    val EmojiBox = 20.dp
    val EmojiToCounter = 4.dp

    /** A chip with no counter is 31dp wide (`width -= dp(1)`), so its emoji sits centred. */
    val SidePaddingWithoutCounter = 5.5.dp
    val CounterSize = 13.sp
    val EmojiSize = 16.sp

    /** `AvatarsDrawable`: 20dp avatars, each next one 0.8 of its width further along, so they overlap. */
    val VoterAvatar = 20.dp
    val VoterAvatarStep = 16.dp

    /** `width = … + dp(2) + avatars + dp(1)` against avatars at `x + 32`: a chip of avatars is 3dp short of them on the right, 3dp above and below. */
    val VoterEndPadding = 3.dp

    /** The ring around an avatar that cuts it out of the one under it. */
    val VoterRing = 1.dp

    /** `backgroundColor alpha * 0.156f`: a reaction nobody of ours chose is the accent colour this faint. */
    const val RestAlpha = 0.156f

    /** The time shares the last row of chips with at least this much space (Telegram checks `lastLineX + timeWidth + dp(24) > backgroundWidth`). */
    val MetaMargin = 8.dp

    /** When the time does not fit beside the chips it takes a line below them (Telegram: `totalHeight += dp(12 or 16)`). */
    val MetaBelowGap = 2.dp

    /** The text above the chips and the bubble's bottom edge: chips end 10dp above it (`getBackgroundDrawableBottom() - dp(10) - height`), 7dp of it is the bubble's own padding. */
    val TopGap = 6.dp
    val BottomGap = 3.dp

    /** The chip shows the people instead of the counter when all of them are known (at most three); a group also needs at most three in all (see MessageReactionStrip). */
    fun showsVoters(reaction: AloMessageReaction): Boolean =
        reaction.users.isNotEmpty() && reaction.users.size == reaction.count && reaction.count <= 3
}

/**
 * Telegram Android 13.0.3 (`Cells/ChatMessageCell.java`, `Components/ReplyMessageLine.java`): the quote of the message being answered.
 * Its block is about 41dp tall for a one-line text: 3dp above the name, a 14sp name, 1.5dp, a 14sp line of text, and the rest below.
 */
internal object AloReplyQuoteMetrics {
    /** `lineRight = rect.left + max(dp(3), …)`: the coloured bar at the block's left edge, clipped by its corners. */
    val BarWidth = 3.dp

    /** `bottomRad = min(4, bubbleRadius)`: the bar's corners, and the far ones when a name sits above the quote. */
    val Corner = 4.dp

    /** `rightRad = bubbleRadius * .6` when the quote is the first thing in the bubble. */
    val FarCornerWithoutName = 10.dp

    /** The text starts 11dp from the block's edge (`offset = dp(10)`, less 1), 8dp after the bar. */
    val TextStart = 8.dp
    val TextEnd = 8.dp
    val NameTop = 3.dp
    val TextTopGap = 1.5.dp
    val BottomPadding = 4.dp

    /** `replyStartY = dp(12)` against the text's own 10dp: the quote sits 2dp lower when it opens the bubble. */
    val TopWhenFirst = 2.dp

    /** `namesOffset += dp(14 + 4) + …`: the message text follows 3dp below the block, plus its own leading. */
    val GapBelow = 4.dp
    val TextSize = 14.sp
    val LineHeight = 16.sp
}

/** The author's colour in a quote and its bar: the same palette entry as their name and avatar; a quote whose source is gone keeps [fallback]. */
internal fun aloReplyQuoteColor(source: AloChatMessage?, lightSurface: Boolean, fallback: Color): Color =
    if (source == null) fallback else aloSenderNameColor(source.senderId.toString(), lightSurface)
