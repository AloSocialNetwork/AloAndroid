package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alo.android.ui.theme.AloUiPalette
import com.alo.android.ui.theme.LocalAloUiPalette
import java.util.Locale

data class AloChatPaletteColors(
    val outgoing: Color,
    val outgoingBorder: Color,
    val incoming: Color,
    val incomingBorder: Color
)

object AloNativeLayout {
    val radiusSmall: Dp = 12.dp
    val radiusMedium: Dp = 18.dp
    val radiusLarge: Dp = 24.dp
    val spacingSmall: Dp = 8.dp
    val spacingMedium: Dp = 12.dp
    val spacingLarge: Dp = 20.dp
    val pageInset: Dp = 14.dp
    val sectionGap: Dp = 24.dp
    val minimumTouchTarget: Dp = 48.dp
    val compactControlHeight: Dp = 44.dp
    val conversationRowHeight: Dp = 70.dp
    val bottomBarHeight: Dp = 58.dp
    val bottomBarSelectionSize: Dp = 48.dp
}

val LocalAloNativeChatAppearance = staticCompositionLocalOf { AloChatAppearance() }

@Composable
fun currentChatPaletteColors(): AloChatPaletteColors {
    val ui = LocalAloUiPalette.current
    return aloChatPaletteColors(LocalAloNativeChatAppearance.current, ui.background.luminance() > 0.5f, ui)
}

/**
 * The bubbles of a chat for [appearance]. A dark app theme keeps the dark bubbles; a light one gets pale tints of the same palette with
 * white incoming bubbles, so the chat and the rest of the app agree and dark text stays readable on both (checked in AloChatContrastTest).
 */
fun aloChatPaletteColors(appearance: AloChatAppearance, light: Boolean, ui: AloUiPalette): AloChatPaletteColors {
    val customBubbleHue = appearance.bubbleHue.takeIf { it >= 0f }
    if (light) {
        if (customBubbleHue != null) {
            return AloChatPaletteColors(
                outgoing = Color.hsv(customBubbleHue, 0.20f, 1f),
                outgoingBorder = Color.hsv(customBubbleHue, 0.32f, 0.92f),
                incoming = Color.hsv(customBubbleHue, 0.04f, 1f),
                incomingBorder = Color.hsv(customBubbleHue, 0.12f, 0.90f)
            )
        }
        val dark = aloChatPaletteColors(appearance, light = false, ui = ui)
        val named = appearance.palette.trim().lowercase(Locale.ROOT)
        return AloChatPaletteColors(
            outgoing = if (named == "blue" || named.isBlank()) ui.chatOutgoing else lerp(dark.outgoing, Color.White, 0.80f),
            outgoingBorder = if (named == "blue" || named.isBlank()) ui.chatOutgoingBorder else lerp(dark.outgoingBorder, Color.White, 0.45f),
            incoming = ui.chatIncoming,
            incomingBorder = ui.chatIncomingBorder
        )
    }
    if (customBubbleHue != null) {
        return AloChatPaletteColors(
            outgoing = Color.hsv(customBubbleHue, 0.78f, appearance.outgoingBrightness),
            outgoingBorder = Color.hsv(customBubbleHue, 0.45f, minOf(1f, appearance.outgoingBrightness + 0.12f)),
            incoming = Color.hsv(customBubbleHue, 0.10f, appearance.incomingBrightness),
            incomingBorder = Color.hsv(customBubbleHue, 0.18f, minOf(1f, appearance.incomingBrightness + 0.08f))
        )
    }
    return when (appearance.palette.trim().lowercase(Locale.ROOT)) {
        "emerald" -> AloChatPaletteColors(
            outgoing = Color(0xFF2B7863),
            outgoingBorder = Color(0xFF57957F),
            incoming = Color(0xFF242426),
            incomingBorder = Color(0xFF343438)
        )
        "violet" -> AloChatPaletteColors(
            outgoing = Color(0xFF5A5A82),
            outgoingBorder = Color(0xFF7A7AA1),
            incoming = Color(0xFF242426),
            incomingBorder = Color(0xFF343438)
        )
        "sunset" -> AloChatPaletteColors(
            outgoing = Color(0xFF8E6247),
            outgoingBorder = Color(0xFFB78868),
            incoming = Color(0xFF242426),
            incomingBorder = Color(0xFF343438)
        )
        "graphite" -> AloChatPaletteColors(
            outgoing = Color(0xFF4A5568),
            outgoingBorder = Color(0xFF7C8BA3),
            incoming = Color(0xFF1A1C22),
            incomingBorder = Color(0xFF2D323D)
        )
        else -> AloChatPaletteColors(
            outgoing = Color(0xFF2375C6),
            outgoingBorder = Color(0xFF5697D3),
            incoming = Color(0xFF242426),
            incomingBorder = Color(0xFF343438)
        )
    }
}

val AloNativeAppBackground: Color
    @Composable get() = LocalAloUiPalette.current.background
val AloNativeAppBackgroundSecondary: Color
    @Composable get() = LocalAloUiPalette.current.backgroundSecondary
val AloNativeAppSurface: Color
    @Composable get() = LocalAloUiPalette.current.surface
val AloNativeAppSurfaceRaised: Color
    @Composable get() = LocalAloUiPalette.current.surfaceRaised
val AloNativeAppSurfaceInteractive: Color
    @Composable get() = LocalAloUiPalette.current.surfaceInteractive
val AloNativeAppText: Color
    @Composable get() = LocalAloUiPalette.current.text
val AloNativeAppMuted: Color
    @Composable get() = LocalAloUiPalette.current.muted
val AloNativeAppBorder: Color
    @Composable get() = LocalAloUiPalette.current.border
val AloNativeAppSeparator: Color
    @Composable get() = LocalAloUiPalette.current.separator
val AloNativeAppAccent: Color
    @Composable get() = LocalAloUiPalette.current.accent
val AloNativeAppAccentStrong: Color
    @Composable get() = LocalAloUiPalette.current.accentStrong
val AloNativeAppSuccess: Color
    @Composable get() = LocalAloUiPalette.current.success
val AloNativeAppDanger: Color
    @Composable get() = LocalAloUiPalette.current.danger
val AloNativeAppLike: Color
    @Composable get() = LocalAloUiPalette.current.like
val AloNativeAppButtonText: Color
    @Composable get() = LocalAloUiPalette.current.buttonText
val AloNativeAppGlow: Color
    @Composable get() = LocalAloUiPalette.current.glow
val AloNativeChatOutgoing: Color
    @Composable get() = currentChatPaletteColors().outgoing
val AloNativeChatOutgoingBorder: Color
    @Composable get() = currentChatPaletteColors().outgoingBorder
/**
 * The chat's colour for controls and marks (send, recording, «Изменение», reply bars): the own bubble's colour while it is
 * dark, the accent once it is pale (the light theme's #D8EBFF), on which a white send arrow or a label could not be seen.
 */
val AloNativeChatSendAccent: Color
    @Composable get() = AloNativeChatOutgoing.let { if (it.luminance() > 0.5f) AloNativeChatAccentBlue else it }
val AloNativeChatIncoming: Color
    @Composable get() = currentChatPaletteColors().incoming
val AloNativeChatIncomingBorder: Color
    @Composable get() = currentChatPaletteColors().incomingBorder
val AloNativeChatComposer: Color
    @Composable get() = LocalAloUiPalette.current.chatComposer
val AloNativeChatAccentBlue: Color
    @Composable get() = LocalAloUiPalette.current.chatAccentBlue
val AloNativeChatGlassSurface: Color
    @Composable get() = AloNativeAppSurface

@Composable
fun AloNativeGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape,
    tint: Color = AloNativeChatGlassSurface,
    borderColor: Color = AloNativeAppBorder.copy(alpha = 0.035f),
    content: @Composable BoxScope.() -> Unit
) {
    // Inside a screen that provides a backdrop (the chat), panels are real glass, as on iOS.
    val backdrop = LocalAloGlassBackdrop.current
    val light = AloNativeAppBackground.luminance() > 0.5f
    Box(
        modifier = modifier
            .then(
                if (backdrop != null) {
                    Modifier
                        .aloLiquidGlass(shape, backdrop = backdrop)
                } else {
                    // Nothing to blur: the tint under the same lit rim, so it still reads as glass.
                    Modifier
                        .clip(shape)
                        .background(tint)
                        .drawBehind { drawAloGlassEdge(shape, light) }
                }
            )
    ) {
        content()
    }
}
