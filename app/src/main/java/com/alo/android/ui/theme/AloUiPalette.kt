package com.alo.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AloUiPalette(
    val background: Color,
    val backgroundSecondary: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInteractive: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val separator: Color,
    val accent: Color,
    val accentStrong: Color,
    val success: Color,
    val danger: Color,
    val like: Color,
    val buttonText: Color,
    val glow: Color,
    val chatOutgoing: Color,
    val chatOutgoingBorder: Color,
    val chatIncoming: Color,
    val chatIncomingBorder: Color,
    val chatComposer: Color,
    val chatAccentBlue: Color
)

val AloDarkUiPalette = AloUiPalette(
    // Quiet product chrome: content and the single blue action colour own the hierarchy.
    background = Color(0xFF000000),
    backgroundSecondary = Color(0xFF0E0E10),
    surface = Color(0xFF1D1D1F),
    surfaceRaised = Color(0xFF28282A),
    surfaceInteractive = Color(0xFF363639),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFF9E9EA6),
    border = Color(0x17FFFFFF),
    separator = Color(0x21FFFFFF),
    accent = Color(0xFF0A84FF),
    accentStrong = Color(0xFF0A84FF),
    success = Color(0xFF30D158),
    danger = Color(0xFFFF453A),
    like = Color(0xFFFF5A5F),
    buttonText = Color(0xFFFFFFFF),
    glow = Color(0x120A84FF),
    chatOutgoing = Color(0xFF2375C6),
    chatOutgoingBorder = Color(0xFF5697D3),
    chatIncoming = Color(0xFF242426),
    chatIncomingBorder = Color(0x17FFFFFF),
    chatComposer = Color(0xFF242426),
    chatAccentBlue = Color(0xFF0A84FF)
)

val AloLightUiPalette = AloUiPalette(
    background = Color(0xFFF3F4F6),
    backgroundSecondary = Color(0xFFEDEFF3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF3F4F8),
    surfaceInteractive = Color(0xFFE8EBF0),
    text = Color(0xFF1A1A1A),
    muted = Color(0xA61A1A1A),
    border = Color(0x0C0E1620),
    separator = Color(0x1A0E1620),
    accent = Color(0xFF0080FF),
    accentStrong = Color(0xFF0069D1),
    success = Color(0xFF34A853),
    danger = Color(0xFFD93F4C),
    like = Color(0xFFE64E6B),
    buttonText = Color(0xFFFFFFFF),
    glow = Color(0x0D0080FF),
    chatOutgoing = Color(0xFFD8EBFF),
    chatOutgoingBorder = Color(0xFFB5D7FF),
    chatIncoming = Color(0xF7FFFFFF),
    chatIncomingBorder = Color(0x120E1A2B),
    chatComposer = Color(0xFFFFFFFF),
    chatAccentBlue = Color(0xFF0080FF)
)

val LocalAloUiPalette = staticCompositionLocalOf { AloDarkUiPalette }
