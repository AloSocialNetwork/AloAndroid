package com.alo.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * The ink of text that sits on a bubble of the light chat. The values are picked so that text on the pale outgoing and the white incoming
 * bubbles keeps a WCAG contrast of at least 4.5 (see AloChatContrastTest).
 */
internal object AloChatInk {
    /** Message text on a pale outgoing bubble. */
    val onLightBubble = Color(0xFF0D2D4D)

    /** Time and read mark on a pale outgoing bubble (was 67% of the text colour, 4.4:1 on the default bubble). */
    val metaOnLightBubble = Color(0xFF3C5A78)

    /** Time on the white incoming bubble. */
    val metaOnLightIncoming = Color(0xFF5E6B7A)
}

/** WCAG 2.x contrast ratio of [foreground] over an opaque [background] (a translucent foreground is flattened onto it first). */
internal fun aloContrastRatio(foreground: Color, background: Color): Float {
    val flatBackground = background.copy(alpha = 1f)
    val flat = if (foreground.alpha < 1f) {
        val a = foreground.alpha
        Color(
            red = foreground.red * a + flatBackground.red * (1 - a),
            green = foreground.green * a + flatBackground.green * (1 - a),
            blue = foreground.blue * a + flatBackground.blue * (1 - a)
        )
    } else foreground
    val l1 = flat.luminance()
    val l2 = flatBackground.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}
