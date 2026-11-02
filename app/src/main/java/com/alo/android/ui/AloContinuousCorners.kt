package com.alo.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * iOS's continuous corners (SwiftUI `RoundedRectangle(style: .continuous)`): the edge starts to curve
 * well before the corner and eases into it, instead of meeting a plain circular arc. Built as in
 * Figma's corner smoothing, which Apple's shape matches at about 60%: each corner is two Bézier
 * easings around a shorter circular arc, spanning (1 + smoothing) × radius along each edge.
 */
internal class AloContinuousCornerShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val budget = min(size.width, size.height) / 2f
        if (r <= 0f || budget <= 0f) return Outline.Rectangle(Rect(Offset.Zero, size))
        // A radius that fills the short side is a plain circle, which has nothing to smooth.
        if (r >= budget) return Outline.Rounded(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, size), androidx.compose.ui.geometry.CornerRadius(budget)))
        return Outline.Generic(continuousRoundedRectPath(size.width, size.height, r, smoothing))
    }
}

/** A drop-in for `RoundedCornerShape(radius)` that corners the way iOS does. */
internal fun AloSquircle(radius: Dp): Shape = AloContinuousCornerShape(radius)

internal fun continuousRoundedRectPath(width: Float, height: Float, radius: Float, smoothing: Float): Path {
    val budget = min(width, height) / 2f
    val r = min(radius, budget)
    var xi = smoothing.coerceIn(0f, 1f)
    var p = (1f + xi) * r
    if (p > budget) {
        xi = budget / r - 1f
        p = budget
    }
    val arcMeasure = 90f * (1f - xi)
    val arcLength = sin(Math.toRadians((arcMeasure / 2f).toDouble())).toFloat() * r * sqrt(2f)
    val alpha = (90f - arcMeasure) / 2f
    val p3ToP4 = r * tan(Math.toRadians((alpha / 2f).toDouble())).toFloat()
    val beta = 45f * xi
    val betaRad = Math.toRadians(beta.toDouble())
    val c = p3ToP4 * cos(betaRad).toFloat()
    val d = c * tan(betaRad).toFloat()
    val b = (p - arcLength - c - d) / 3f
    val a = 2f * b

    val path = Path()
    // Corners clockwise from the top-left. A corner is drawn in its own (s, t) frame: s runs from the
    // corner along the edge it arrives on, t along the edge it leaves on.
    class Corner(val px: Float, val py: Float, val ex: Offset, val ey: Offset, val startAngle: Float)
    val corners = listOf(
        Corner(0f, 0f, Offset(0f, 1f), Offset(1f, 0f), beta + 180f),
        Corner(width, 0f, Offset(-1f, 0f), Offset(0f, 1f), beta - 90f),
        Corner(width, height, Offset(0f, -1f), Offset(-1f, 0f), beta),
        Corner(0f, height, Offset(1f, 0f), Offset(0f, -1f), beta + 90f)
    )
    fun at(corner: Corner, s: Float, t: Float) = Offset(
        corner.px + s * corner.ex.x + t * corner.ey.x,
        corner.py + s * corner.ex.y + t * corner.ey.y
    )
    val sinB = sin(betaRad).toFloat()
    val cosB = cos(betaRad).toFloat()
    corners.forEachIndexed { index, corner ->
        val start = at(corner, p, 0f)
        if (index == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
        val arcStartS = p - a - b - c
        val c1 = at(corner, p - a, 0f)
        val c2 = at(corner, p - a - b, 0f)
        val arcStart = at(corner, arcStartS, d)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, arcStart.x, arcStart.y)
        // The circular middle: its centre sits r from the arc's start, towards the inside of the corner.
        val centre = at(corner, arcStartS + r * sinB, d + r * cosB)
        path.arcTo(Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r), corner.startAngle, arcMeasure, false)
        val arcEndS = arcStartS - arcLength
        val arcEndT = d + arcLength
        val e1 = at(corner, arcEndS - d, arcEndT + c)
        val e2 = at(corner, arcEndS - d, arcEndT + b + c)
        val end = at(corner, 0f, p)
        path.cubicTo(e1.x, e1.y, e2.x, e2.y, end.x, end.y)
    }
    path.close()
    return path
}
