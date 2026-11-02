package com.alo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutIdParentData
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** Where a strip of chips sits: in a message bubble, or loose on the wallpaper and on video circles. */
internal enum class AloReactionChipStyle { Bubble, Floating }

/**
 * Telegram's reaction chips (`ReactionsLayoutInBubble.ReactionButton`): a pill with no outline. A reaction nobody of ours chose is the accent at
 * 15.6% with an accent-coloured counter; the one we chose is the accent solid with a contrasting counter. [accent] is white on our own (blue) bubble.
 */
private class AloReactionChipPalette(
    val rest: Color,
    val chosen: Color,
    val restText: Color,
    val chosenText: Color,
    /** The opaque colour behind the chip, for the ring that separates overlapping voter avatars. */
    val under: Color
) {
    companion object {
        fun of(style: AloReactionChipStyle, accent: Color, bubble: Color): AloReactionChipPalette = when (style) {
            AloReactionChipStyle.Floating -> AloReactionChipPalette(
                rest = Color.Black.copy(alpha = 0.34f),
                chosen = Color.White.copy(alpha = 0.92f),
                restText = Color.White,
                chosenText = Color(0xFF1B1B1D),
                under = Color(0xFF1E1E20)
            )
            AloReactionChipStyle.Bubble -> {
                val opaqueBubble = bubble.copy(alpha = 1f)
                val onAccent = when {
                    accent.luminance() <= 0.55f -> Color.White
                    opaqueBubble.luminance() < 0.5f -> opaqueBubble
                    else -> Color(0xFF1B1B1D)
                }
                AloReactionChipPalette(
                    rest = accent.copy(alpha = AloReactionChipMetrics.RestAlpha),
                    chosen = accent,
                    restText = accent,
                    chosenText = onAccent,
                    under = opaqueBubble
                )
            }
        }
    }
}

/**
 * The chips of a message. With [meta] (the time and read mark) the strip lays it out the way Telegram does: on the last row of chips, at the
 * bubble's right edge, when it fits, otherwise on a line of its own below. For that the strip must be as wide as the bubble, so a caller that
 * passes [meta] puts the strip in a column sized with `IntrinsicSize.Max`.
 */
@Composable
internal fun MessageReactionStrip(
    reactions: List<AloMessageReaction>,
    isOutgoing: Boolean,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A group shows the counter even for one reaction; a private chat leaves it out (Telegram's `ReactionButton`). */
    alwaysShowCount: Boolean = false,
    /** The message the chips belong to: a reaction just chosen on it plays Telegram's effect on its chip. */
    messageId: Int = 0,
    style: AloReactionChipStyle = AloReactionChipStyle.Bubble,
    /** The chip colour on an incoming bubble (a group uses the sender's colour, as Telegram uses the peer colour); our own bubble gets white. */
    accent: Color = Color.Unspecified,
    bubbleColor: Color = Color.Unspecified,
    meta: (@Composable () -> Unit)? = null
) {
    val bubble = bubbleColor.takeOrElse { if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming }
    val tint = when {
        // White chips belong on a dark own bubble; on the light theme's pale one (#D8EBFF) a white «❤️ 2» could not be
        // read, so a light bubble takes the accent, as Telegram tints the chips with the bubble's own accent.
        isOutgoing && bubble.copy(alpha = 1f).luminance() > 0.5f -> AloNativeChatAccentBlue
        isOutgoing -> Color.White
        else -> accent.takeOrElse { AloNativeChatAccentBlue }
    }
    val palette = remember(style, tint, bubble) { AloReactionChipPalette.of(style, tint, bubble) }
    // Telegram shows the people in a group only while at most three reacted in all; a private chat always.
    val votersAllowed = !alwaysShowCount || reactions.sumOf { it.count } <= 3
    Layout(
        modifier = modifier,
        measurePolicy = ReactionFlowPolicy,
        content = {
            reactions.forEach { reaction ->
                key(reaction.emoji) {
                    ReactionChip(
                        reaction = reaction,
                        showCountAlways = alwaysShowCount,
                        votersAllowed = votersAllowed,
                        palette = palette,
                        messageId = messageId,
                        onReact = onReact
                    )
                }
            }
            if (meta != null) {
                Box(modifier = Modifier.layoutId(ReactionMetaId)) { meta() }
            }
        }
    )
}

private const val ReactionMetaId = "reactionMeta"

@Composable
private fun ReactionChip(
    reaction: AloMessageReaction,
    showCountAlways: Boolean,
    votersAllowed: Boolean,
    palette: AloReactionChipPalette,
    messageId: Int,
    onReact: (String) -> Unit
) {
    val interactionSource = remember(reaction.emoji) { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Telegram's `ButtonBounce`: the chip sinks under the finger and springs back.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "reactionScale"
    )
    val chosen = reaction.reactedByMe
    val background by animateColorAsState(if (chosen) palette.chosen else palette.rest, tween(200), label = "reactionBackground")
    val content by animateColorAsState(if (chosen) palette.chosenText else palette.restText, tween(200), label = "reactionContent")
    val showVoters = votersAllowed && AloReactionChipMetrics.showsVoters(reaction)
    val showCount = !showVoters && (showCountAlways || reaction.count > 1)
    var burst by remember(reaction.emoji) { mutableStateOf(0) }
    LaunchedEffect(reaction.reactedByMe, reaction.emoji) {
        if (messageId > 0 && reaction.reactedByMe && AloReactionEffects.take(messageId, reaction.emoji)) burst += 1
    }
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .height(AloReactionChipMetrics.Height)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interactionSource, indication = null, onClick = { onReact(reaction.emoji) })
            .padding(
                start = if (showCount || showVoters) AloReactionChipMetrics.SidePadding else AloReactionChipMetrics.SidePaddingWithoutCounter,
                end = when {
                    showVoters -> AloReactionChipMetrics.VoterEndPadding
                    showCount -> AloReactionChipMetrics.SidePadding
                    else -> AloReactionChipMetrics.SidePaddingWithoutCounter
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(AloReactionChipMetrics.EmojiToCounter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AloReactionBurst(emoji = reaction.emoji, trigger = burst)
        Box(modifier = Modifier.size(AloReactionChipMetrics.EmojiBox), contentAlignment = Alignment.Center) {
            Text(
                text = reaction.emoji,
                style = TextStyle(
                    fontSize = AloReactionChipMetrics.EmojiSize,
                    lineHeight = AloReactionChipMetrics.EmojiBox.value.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
        if (showVoters) {
            val ring = AloReactionChipMetrics.VoterRing
            val under = background.compositeOver(palette.under)
            Box(
                Modifier.size(
                    width = AloReactionChipMetrics.VoterAvatar + AloReactionChipMetrics.VoterAvatarStep * (reaction.users.size - 1),
                    height = AloReactionChipMetrics.VoterAvatar
                )
            ) {
                reaction.users.forEachIndexed { index, voter ->
                    // Each avatar sits in a ring of the chip's own colour, which cuts it out of the one underneath (Telegram's `AvatarsDrawable`).
                    Box(
                        modifier = Modifier
                            .offset(x = AloReactionChipMetrics.VoterAvatarStep * index - ring, y = -ring)
                            .requiredSize(AloReactionChipMetrics.VoterAvatar + ring * 2)
                            .background(under, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarBadge(
                            name = voter.name,
                            avatarUrl = voter.avatarUrl,
                            verified = false,
                            size = AloReactionChipMetrics.VoterAvatar,
                            colorSeed = voter.id.toString()
                        )
                    }
                }
            }
        }
        if (showCount) {
            // Telegram's counter rolls: a bigger number slides in from below, a smaller one from above.
            AnimatedContent(
                targetState = reaction.count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(220)) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(220)) { if (up) -it else it } + fadeOut(tween(120)))
                },
                label = "reactionCount"
            ) { count ->
                Text(
                    text = count.toString(),
                    style = TextStyle(
                        fontSize = AloReactionChipMetrics.CounterSize,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = content
                )
            }
        }
    }
}

/** Chips in rows [AloReactionChipMetrics.Gap] apart; the optional time is placed as [MessageReactionStrip] describes. */
private object ReactionFlowPolicy : MeasurePolicy {
    private fun IntrinsicMeasurable.isMeta() = (parentData as? LayoutIdParentData)?.layoutId == ReactionMetaId

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = AloReactionChipMetrics.Gap.roundToPx()
        val chips = measurables.filterNot { it.isMeta() }
        val meta = measurables.firstOrNull { it.isMeta() }
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = chips.map { it.measure(loose) }

        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > constraints.maxWidth) {
                widest = max(widest, x - gap)
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width + gap
            rowHeight = max(rowHeight, placeable.height)
        }
        val lastLineEnd = if (placeables.isEmpty()) 0 else x - gap
        widest = max(widest, lastLineEnd)
        val lastRowTop = y
        val lastRowHeight = rowHeight
        var width = widest
        var height = y + rowHeight

        val metaPlaceable = meta?.measure(loose)
        var metaX = 0
        var metaY = 0
        if (metaPlaceable != null) {
            val margin = AloReactionChipMetrics.MetaMargin.roundToPx()
            width = if (constraints.hasBoundedWidth) constraints.maxWidth else widest + margin + metaPlaceable.width
            metaX = width - metaPlaceable.width
            if (lastLineEnd + margin + metaPlaceable.width <= width) {
                metaY = lastRowTop + (lastRowHeight - metaPlaceable.height) / 2
            } else {
                metaY = height + AloReactionChipMetrics.MetaBelowGap.roundToPx()
                height = metaY + metaPlaceable.height
            }
        }
        return layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(xs[index], ys[index]) }
            metaPlaceable?.placeRelative(metaX, metaY)
        }
    }

    // A parent sized with IntrinsicSize.Max asks for one row of chips plus the time: Telegram's bubble grows to
    // `lastLineX + timeWidth` so the time shares the chips' row (ChatMessageCell.calcBackgroundWidth); the parent's
    // maximum width still caps it, and then the time takes a line of its own.
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val chips = measurables.filterNot { it.isMeta() }
        if (chips.isEmpty()) return 0
        val row = chips.sumOf { it.maxIntrinsicWidth(height) } + AloReactionChipMetrics.Gap.roundToPx() * (chips.size - 1)
        val meta = measurables.firstOrNull { it.isMeta() } ?: return row
        return row + AloReactionChipMetrics.MetaMargin.roundToPx() + meta.maxIntrinsicWidth(height)
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.filterNot { it.isMeta() }.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        rowsHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        rowsHeight(measurables, width)

    private fun IntrinsicMeasureScope.rowsHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val gap = AloReactionChipMetrics.Gap.roundToPx()
        val chips = measurables.filterNot { it.isMeta() }
        if (chips.isEmpty()) return 0
        var x = 0
        var y = 0
        var rowHeight = 0
        chips.forEach { chip ->
            val w = chip.maxIntrinsicWidth(Constraints.Infinity)
            if (x > 0 && x + w > width) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            x += w + gap
            rowHeight = max(rowHeight, chip.minIntrinsicHeight(w))
        }
        return y + rowHeight
    }
}
