package com.alo.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** SF Symbols weights the iOS app gives its chevrons. */
internal enum class AloChevronWeight(val strokeRatio: Float) {
    Regular(0.095f),
    Semibold(0.132f),
    Bold(0.142f)
}

/**
 * The iOS app's back chevron (SF Symbols `chevron.left`), drawn rather than taken from Material,
 * whose arrow is heavier and differently proportioned. Measured from the symbol on macOS: the ink is
 * about 0.9 of the point size tall and 0.58 as wide as it is tall, the stroke 0.095 / 0.132 / 0.142 of
 * the point size for regular / semibold / bold, with round ends. [pointSize] is the iOS font size.
 */
@Composable
internal fun AloChevronLeft(
    pointSize: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    weight: AloChevronWeight = AloChevronWeight.Semibold
) {
    val inkHeight = pointSize * 0.9f
    val inkWidth = inkHeight * 0.58f
    Canvas(modifier.size(inkWidth, inkHeight)) {
        val stroke = pointSize.toPx() * weight.strokeRatio
        val half = stroke / 2f
        val path = Path().apply {
            moveTo(size.width - half, half)
            lineTo(half, size.height / 2f)
            lineTo(size.width - half, size.height - half)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The same chevron pointing right, for rows that open something. */
@Composable
internal fun AloChevronRight(
    pointSize: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    weight: AloChevronWeight = AloChevronWeight.Semibold
) {
    val inkHeight = pointSize * 0.9f
    val inkWidth = inkHeight * 0.58f
    Canvas(modifier.size(inkWidth, inkHeight)) {
        val stroke = pointSize.toPx() * weight.strokeRatio
        val half = stroke / 2f
        val path = Path().apply {
            moveTo(half, half)
            lineTo(size.width - half, size.height / 2f)
            lineTo(half, size.height - half)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
