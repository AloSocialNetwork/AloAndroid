package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

internal object AloBackButtonDefaults {
    val Size: Dp = 38.dp
    val IconSize: Dp = 29.dp
    val HeaderInnerStart: Dp = 9.dp
    val HeaderInnerTop: Dp = 8.dp
    val ScreenStart: Dp = 19.dp
    val ScreenTop: Dp = 21.dp
    val PaneStart: Dp = 11.dp
    val PaneTop: Dp = 13.dp
    // iOS ProfileView: the close chevron 20 pt in, 18 pt down.
    val ProfileHeroStart: Dp = 20.dp
    val ProfileHeroTop: Dp = 18.dp
}

/**
 * One standard for the top of every full screen, so switching screens never moves the controls: a 56dp header row,
 * 16dp from the sides, a 42dp round back button 12dp from the edge (the chat header's circles), and 44dp fields and pills (the chat composer is 44dp too)
 * 6dp under the header.
 */
internal object AloScreenChrome {
    val HeaderHeight: Dp = 56.dp
    val SideInset: Dp = 16.dp
    val BackStart: Dp = 12.dp
    val BackDiameter: Dp = 42.dp
    val FieldHeight: Dp = 44.dp
    val FieldGap: Dp = 6.dp

    /** The glass circles on the right of a header (calls, compose, settings) are 38dp; their right edge is [SideInset] from the screen edge. */
    val ActionDiameter: Dp = 38.dp

    /** Where a tappable 44dp box around a 38dp action circle ends, so that the visible circle's edge is [SideInset] from the screen edge. */
    val ActionBoxEnd: Dp = SideInset - (44.dp - ActionDiameter) / 2

    /** A centred header title keeps clear of the back circle on the left (12 + 42 + 12) and of the same width on the right. */
    val TitleSideReserve: Dp = BackStart + BackDiameter + 12.dp
}

/** The back button every full screen uses: a glass circle whose centre sits at the middle of the 56dp header row. */
@Composable
internal fun AloScreenBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Назад", tag: String? = null) {
    Box(
        modifier.padding(start = AloScreenChrome.BackStart).height(AloScreenChrome.HeaderHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        AloGlassCircleButton(
            onClick = onClick,
            description = description,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            diameter = AloScreenChrome.BackDiameter
        )
    }
}

@Composable
internal fun GhostCircleButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    loading: Boolean = false,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp
) {
    val isLightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = AloNativeAppBackgroundSecondary.copy(alpha = if (isLightSurface) 0.84f else 0.8f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AloNativeAppAccent
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AloNativeAppMuted,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * The iOS app's plain back chevron (ProfileView's close button): SF `chevron.left` at 30 pt bold,
 * muted, in a 42 × 46 pt box. [size] and [iconSize] are kept for older callers and ignored.
 */
@Composable
internal fun PlainBackHitButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") size: Dp = AloBackButtonDefaults.Size,
    @Suppress("UNUSED_PARAMETER") iconSize: Dp = AloBackButtonDefaults.IconSize
) {
    Box(
        modifier = modifier
            .size(width = 42.dp, height = 46.dp)
            .clip(AloSquircle(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Назад" },
        contentAlignment = Alignment.Center
    ) {
        AloChevronLeft(30.dp, AloNativeAppMuted, weight = AloChevronWeight.Bold)
    }
}

/**
 * The iOS app's round glass button (AloChatCircleButton): a 44 pt glass circle with a 21 pt regular
 * SF symbol — the chevron for back, or a cross for close.
 */
@Composable
internal fun AloGlassCircleButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    close: Boolean = false,
    diameter: Dp = 44.dp,
    iconPointSize: Dp = 21.dp
) {
    Box(
        modifier = modifier
            .size(diameter)
            .aloLiquidGlass(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (close) AloCross(iconPointSize, AloNativeAppText) else AloChevronLeft(iconPointSize, AloNativeAppText, weight = AloChevronWeight.Regular)
    }
}

/** SF `xmark` at [pointSize] (regular): two strokes about 0.62 of the point size across. */
@Composable
internal fun AloCross(pointSize: Dp, color: Color, modifier: Modifier = Modifier) {
    val side = pointSize * 0.62f
    Canvas(modifier.size(side)) {
        val stroke = pointSize.toPx() * 0.095f
        val inset = stroke / 2f
        drawLine(color, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(size.width - inset, size.height - inset), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width - inset, inset), androidx.compose.ui.geometry.Offset(inset, size.height - inset), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
internal fun SavedMessagesAvatar(
    modifier: Modifier = Modifier,
    size: Dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF72D5FD), Color(0xFF2A9EF1))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.52f)) {
            fun x(value: Float) = this.size.width * value / 30f
            fun y(value: Float) = this.size.height * value / 30f

            val bookmark = Path().apply {
                moveTo(x(8f), y(2f))
                lineTo(x(22f), y(2f))
                cubicTo(x(24.210938f), y(2f), x(26f), y(3.789062f), x(26f), y(6f))
                lineTo(x(26f), y(27.378906f))
                cubicTo(x(26f), y(28.207031f), x(25.328125f), y(28.878906f), x(24.5f), y(28.878906f))
                cubicTo(x(24.101562f), y(28.878906f), x(23.71875f), y(28.71875f), x(23.4375f), y(28.4375f))
                lineTo(x(15.707031f), y(20.707031f))
                cubicTo(x(15.316406f), y(20.316406f), x(14.683594f), y(20.316406f), x(14.292969f), y(20.707031f))
                lineTo(x(6.5625f), y(28.4375f))
                cubicTo(x(5.976562f), y(29.023438f), x(5.023438f), y(29.023438f), x(4.4375f), y(28.4375f))
                cubicTo(x(4.15625f), y(28.15625f), x(4f), y(27.777344f), x(4f), y(27.378906f))
                lineTo(x(4f), y(6f))
                cubicTo(x(4f), y(3.789062f), x(5.789062f), y(2f), x(8f), y(2f))
                close()
            }
            drawPath(bookmark, color = Color.White)
        }
    }
}

@Composable
internal fun AvatarBadge(
    modifier: Modifier = Modifier,
    name: String,
    avatarUrl: String,
    verified: Boolean,
    size: Dp,
    colorSeed: String = name,
    onClick: (() -> Unit)? = null
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarGradient(colorSeed))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            val letterSize = (size.value * 0.34f).coerceIn(18f, 52f).sp
            Text(
                text = name.trim().take(1).ifBlank { "A" }.uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = letterSize,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                color = Color.White
            )

            if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier
                        .fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            }
        }

        if (verified) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.22f).dp),
                shape = CircleShape,
                color = AloNativeAppAccent
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        tint = AloNativeAppButtonText,
                        modifier = Modifier.size((size.value * 0.11f).dp)
                    )
                }
            }
        }
    }
}

private val aloAvatarGradients = listOf(
    listOf(Color(0xFFFF885E), Color(0xFFFF516A)),
    listOf(Color(0xFFFFCD6A), Color(0xFFFFA85C)),
    listOf(Color(0xFF82B1FF), Color(0xFF665FFF)),
    listOf(Color(0xFFA0DE7E), Color(0xFF54CB68)),
    listOf(Color(0xFF00FCFD), Color(0xFF4ACCCD)),
    listOf(Color(0xFF72D5FD), Color(0xFF2A9EF1)),
    listOf(Color(0xFFE0A2F3), Color(0xFFD669ED))
)

private fun avatarGradient(seed: String): Brush {
    return Brush.verticalGradient(aloAvatarGradients[stableAvatarColorIndex(seed)])
}

/**
 * A sender's name colour in a group chat: the entry of our avatar palette that the same [seed] gives the avatar,
 * so the name and the avatar match. The bright end suits a dark bubble; on a light one it is darkened to stay readable.
 */
internal fun aloSenderNameColor(seed: String, lightSurface: Boolean): Color {
    val colors = aloAvatarGradients[stableAvatarColorIndex(seed)]
    return if (lightSurface) androidx.compose.ui.graphics.lerp(colors[1], Color.Black, 0.3f) else colors[0]
}

private fun stableAvatarColorIndex(seed: String): Int {
    val normalized = seed.trim().lowercase()
    normalized.toLongOrNull()?.let { numeric ->
        return Math.floorMod(numeric, aloAvatarGradients.size.toLong()).toInt()
    }

    var hash = 1_469_598_103_934_665_603L
    normalized.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 1_099_511_628_211L
    }
    return Math.floorMod(hash, aloAvatarGradients.size.toLong()).toInt()
}
