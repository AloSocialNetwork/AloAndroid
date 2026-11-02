package com.alo.android

import android.os.Build
import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The content glass panels float over, recorded each frame so the panels can draw it blurred.
 * This is how Telegram's Android client blurs its bars (a snapshot of what is behind them, blurred
 * with a RenderNode effect on Android 12+), done with Compose graphics layers.
 *
 * A backdrop may gather several layers (a chat's wallpaper and its message list live in different
 * boxes); panels draw them in [aloGlassSource]'s `order`.
 *
 * Rule: a glass panel must not sit inside a source it blurs, or it would see itself.
 */
@Stable
class AloGlassBackdrop {
    internal class Source(val order: Int) {
        var layer: GraphicsLayer? = null
        var offset = Offset.Zero
    }
    internal val sources = mutableListOf<Source>()
    /** Bumped on every source draw or move, so panels redraw when what is behind them changes. */
    internal var frame by mutableIntStateOf(0)
    internal fun bump() = Snapshot.withoutReadObservation { frame++ }
}

val LocalAloGlassBackdrop = staticCompositionLocalOf<AloGlassBackdrop?> { null }

@Composable
fun rememberAloGlassBackdrop(): AloGlassBackdrop = remember { AloGlassBackdrop() }

/** Marks content that glass panels above it blur; a higher [order] is drawn over a lower one. */
fun Modifier.aloGlassSource(backdrop: AloGlassBackdrop, order: Int = 0): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val source = remember(backdrop, order) { AloGlassBackdrop.Source(order) }
    source.layer = layer
    DisposableEffect(backdrop, source) {
        backdrop.sources += source
        backdrop.sources.sortBy { it.order }
        onDispose { backdrop.sources -= source }
    }
    this
        .onGloballyPositioned {
            val offset = it.positionInWindow()
            if (offset != source.offset) {
                source.offset = offset
                backdrop.bump()
            }
        }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            // Bump without observing: reading the counter here would make the source redraw itself forever.
            backdrop.bump()
        }
}

/*
 * Telegram Android's liquid glass (Components/blur3, v12.7.3), on our backdrop layers:
 * - the content behind is blurred only 1.66dp with its saturation doubled
 *   (DownscaleScrollableNoiseSuppressor, "glass"), so it stays readable through the glass;
 * - Telegram's AGSL shader (res/raw/alo_liquid_glass.agsl, LiquidGlassEffect) bends it along an
 *   11dp rim, index 1.5 at intensity 0.75, and lays the panel colour over it at 85%;
 * - a 1dp line along the top and a ⅔dp one along the bottom (BlurredBackgroundDrawable.drawStroke),
 *   white 16%/8% in the dark theme, white in the light one, where a soft shadow sits under the glass.
 * Before Android 13 the shader is missing and the colour is laid over the blur; before 12, or with
 * nothing behind to blur, the glass is the colour alone, as in Telegram.
 */

/** Telegram's chat glass colour: the panel colour at 85% (216/255 in the light theme). */
internal val AloGlassFill: Color
    @Composable get() = if (AloNativeAppBackground.luminance() > 0.5f) {
        AloNativeAppSurface.copy(alpha = 216f / 255f)
    } else {
        AloNativeAppSurface.copy(alpha = 0.85f)
    }

/** How far past its edge the glass reads the content, so the refraction bends real content in. */
private val AloGlassReach = 32.dp

/**
 * What is behind glass is redrawn and filtered on every frame of a scroll, and a 120 Hz phone has 8 ms per frame for everything.
 * So it is rendered small and stretched, as Telegram does (DownscaleScrollableNoiseSuppressor): blurred content looks the same,
 * the GPU has a quarter (a sixteenth for the wide edge shades) of the pixels to fill. Rims and corners are drawn at
 * full resolution on top, so the edges stay sharp.
 */
private const val AloGlassScale = 0.5f
private const val AloEdgeShadeScale = 0.25f

/** No glass effects at all: the panels are their plain colour. Android's battery saver asks for it, as in Telegram. */
internal object AloGlassEffects {
    var plain by mutableStateOf(false)
}

fun Modifier.aloLiquidGlass(
    shape: Shape,
    tint: Color = Color.Unspecified,
    blurRadius: Dp = 1.66.dp,
    backdrop: AloGlassBackdrop? = null
): Modifier = composed {
    val source = backdrop ?: LocalAloGlassBackdrop.current
    // The page behind the source: glass is opaque to what it covers, so the sharp content never shows through.
    val base = AloNativeAppBackground
    val light = base.luminance() > 0.5f
    val fill = if (tint.isSpecified) tint else AloGlassFill
    val density = LocalDensity.current
    val context = LocalContext.current
    val blurPx = with(density) { blurRadius.toPx() } * AloGlassScale
    val reachPx = with(density) { AloGlassReach.toPx() }
    val thicknessPx = with(density) { 11.dp.toPx() }
    val glassLayer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val canBlur = source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AloGlassEffects.plain
    // Typed Any, not RenderEffect: Kotlin casts what remember returns, and on Android 10–11 that cast alone (even of
    // null, behind the SDK check) looks RenderEffect up and crashes with NoClassDefFoundError. Cast only where it's used.
    // remember<Any?> explicitly: with only `val blur: Any?` Kotlin still infers T from the lambda and keeps the cast.
    val blur = remember<Any?>(blurPx) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) aloGlassBlur(blurPx) else null }
    val liquid = remember<Any?> { if (Build.VERSION.SDK_INT >= 33) AloLiquidGlassEffect(context) else null }
    this
        .then(if (light) Modifier.aloGlassShadow(shape) else Modifier)
        .onGloballyPositioned { position = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            val layers = source?.sources.orEmpty().filter { it.layer != null }
            drawRect(base)
            if (canBlur && blur != null && layers.isNotEmpty()) {
                @Suppress("UNUSED_VARIABLE") val tick = source!!.frame
                val width = ((size.width + reachPx * 2) * AloGlassScale).roundToInt()
                val height = ((size.height + reachPx * 2) * AloGlassScale).roundToInt()
                glassLayer.renderEffect = if (liquid != null && Build.VERSION.SDK_INT >= 33) {
                    (liquid as AloLiquidGlassEffect).update(
                        glassSize = size * AloGlassScale,
                        offset = reachPx * AloGlassScale,
                        radii = aloCornerRadii(shape, size, layoutDirection, this).also { radii ->
                            for (index in radii.indices) radii[index] *= AloGlassScale
                        },
                        thickness = thicknessPx * AloGlassScale,
                        fill = fill,
                        blur = blur as android.graphics.RenderEffect
                    )
                } else {
                    (blur as android.graphics.RenderEffect).asComposeRenderEffect()
                }
                glassLayer.record(size = IntSize(width, height)) {
                    scale(AloGlassScale, Offset.Zero) {
                        layers.forEach { layer ->
                            translate(layer.offset.x - position.x + reachPx, layer.offset.y - position.y + reachPx) {
                                drawLayer(layer.layer!!)
                            }
                        }
                    }
                }
                translate(-reachPx, -reachPx) {
                    scale(1f / AloGlassScale, Offset.Zero) { drawLayer(glassLayer) }
                }
                if (liquid == null) drawRect(fill)
            } else {
                drawRect(fill)
            }
            drawAloGlassEdge(shape, light)
        }
}

/** Telegram's glass source: blur, with the colours under it at double saturation. */
@RequiresApi(Build.VERSION_CODES.S)
private fun aloGlassBlur(radius: Float): android.graphics.RenderEffect {
    val saturation = android.graphics.RenderEffect.createColorFilterEffect(
        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(2f) })
    )
    return android.graphics.RenderEffect.createChainEffect(
        android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP),
        saturation
    )
}

/** Telegram's LiquidGlassEffect: the shader's uniforms for one piece of glass, rebuilt when they change. */
@RequiresApi(33)
private class AloLiquidGlassEffect(context: Context) {
    private val shader = android.graphics.RuntimeShader(aloLiquidGlassSource(context))
    private var key: List<Float> = emptyList()
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    fun update(
        glassSize: Size,
        offset: Float,
        radii: FloatArray,
        thickness: Float,
        fill: Color,
        blur: android.graphics.RenderEffect
    ): androidx.compose.ui.graphics.RenderEffect {
        val height = glassSize.height
        // Telegram keeps a side's two corners within the height.
        var (leftTop, rightTop, rightBottom, leftBottom) = radii.toList()
        if (leftTop + leftBottom > height) {
            val a = leftTop / (leftTop + leftBottom)
            leftTop = height * a
            leftBottom = height * (1f - a)
        }
        if (rightTop + rightBottom > height) {
            val a = rightTop / (rightTop + rightBottom)
            rightTop = height * a
            rightBottom = height * (1f - a)
        }
        val next = listOf(
            glassSize.width, glassSize.height, offset, leftTop, rightTop, rightBottom, leftBottom, thickness,
            fill.red, fill.green, fill.blue, fill.alpha
        )
        effect?.takeIf { next == key }?.let { return it }
        key = next
        shader.setFloatUniform("resolution", glassSize.width + offset * 2, glassSize.height + offset * 2)
        shader.setFloatUniform("center", offset + glassSize.width / 2f, offset + glassSize.height / 2f)
        shader.setFloatUniform("size", glassSize.width / 2f, glassSize.height / 2f)
        shader.setFloatUniform("radius", rightBottom, rightTop, leftBottom, leftTop)
        shader.setFloatUniform("thickness", thickness)
        shader.setFloatUniform("refract_intensity", 0.75f)
        shader.setFloatUniform("refract_index", 1.5f)
        shader.setFloatUniform("foreground_color_premultiplied", fill.red * fill.alpha, fill.green * fill.alpha, fill.blue * fill.alpha, fill.alpha)
        return android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "img"),
            blur
        ).asComposeRenderEffect().also { effect = it }
    }
}

private var aloLiquidGlassCode: String? = null

private fun aloLiquidGlassSource(context: Context): String =
    aloLiquidGlassCode ?: context.resources.openRawResource(R.raw.alo_liquid_glass)
        .bufferedReader().use { it.readText() }
        .also { aloLiquidGlassCode = it }

/** Corner radii of [shape] at [size]: left-top, right-top, right-bottom, left-bottom. */
private fun aloCornerRadii(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): FloatArray =
    when (val outline = shape.createOutline(size, layoutDirection, density)) {
        is Outline.Rounded -> outline.roundRect.let {
            floatArrayOf(it.topLeftCornerRadius.x, it.topRightCornerRadius.x, it.bottomRightCornerRadius.x, it.bottomLeftCornerRadius.x)
        }
        is Outline.Rectangle -> FloatArray(4)
        is Outline.Generic -> FloatArray(4) { minOf(size.width, size.height) / 2f }
    }

/** Telegram's light-theme glass shadow: black 12.5%, 1dp soft, ⅓dp down. */
private fun Modifier.aloGlassShadow(shape: Shape): Modifier = drawBehind {
    val radii = aloCornerRadii(shape, size, layoutDirection, this)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        setShadowLayer(1.dp.toPx(), 0f, (1f / 3f).dp.toPx(), 0x20000000)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radii[0], radii[0], paint)
    }
}

/**
 * Telegram's glass edge (BlurredBackgroundDrawable.drawStroke): the outline nudged half a line
 * inwards, a 1dp line kept to the top corners and a ⅔dp one to the bottom corners.
 */
internal fun DrawScope.drawAloGlassEdge(shape: Shape, light: Boolean) {
    val topColor = if (light) Color.White else Color(0x28FFFFFF)
    val bottomColor = if (light) Color.White else Color(0x14FFFFFF)
    val topWidth = 1.dp.toPx()
    val bottomWidth = (2f / 3f).dp.toPx()
    val radii = aloCornerRadii(shape, size, layoutDirection, this)
    // Not coerceIn: glass that is growing from nothing is shorter than the line, and an empty range throws.
    val topReach = (maxOf(radii[0], radii[1]) * 2f).coerceAtLeast(topWidth).coerceAtMost(size.height)
    val bottomReach = (maxOf(radii[2], radii[3]) * 2f).coerceAtLeast(bottomWidth).coerceAtMost(size.height)
    clipRect(top = 0f, bottom = topReach) {
        val outline = shape.createOutline(Size(size.width + topWidth, size.height), layoutDirection, this)
        translate(-topWidth / 2f, topWidth / 2f) { drawOutline(outline, topColor, style = Stroke(topWidth)) }
    }
    clipRect(top = size.height - bottomReach, bottom = size.height) {
        val outline = shape.createOutline(Size(size.width + bottomWidth, size.height), layoutDirection, this)
        translate(-bottomWidth / 2f, -bottomWidth / 2f) { drawOutline(outline, bottomColor, style = Stroke(bottomWidth)) }
    }
}

/** Telegram's edge fade, as iOS's AloEdgeProfile: full at the edge, gone at the far side. */
private val AloEdgeAlpha = floatArrayOf(1f, 0.9755f, 0.901f, 0.7378f, 0.4826f, 0.24f, 0.0888f, 0.0332f, 0f)

/**
 * The dark part of an edge shade. `solid` is the fraction of the height that stays at [peak] before
 * the fade starts; `top` puts the dark edge at the top.
 */
internal fun aloEdgeBrush(top: Boolean, peak: Float, solid: Float = 0.35f, base: Color = Color.Black): Brush {
    val fade = 1f - solid
    val stops = mutableListOf(0f to base.copy(alpha = peak))
    AloEdgeAlpha.forEachIndexed { index, alpha ->
        stops += (solid + index.toFloat() / (AloEdgeAlpha.size - 1) * fade) to base.copy(alpha = peak * alpha)
    }
    val ordered = if (top) stops else stops.map { (1f - it.first) to it.second }.reversed()
    return Brush.verticalGradient(*ordered.toTypedArray())
}

/**
 * iOS's AloSocialTitleShade / AloSocialBottomShade: content under a floating header (or above the
 * tab bar) dissolves instead of being cut by a plate. Half-strength blur of the backdrop, faded out
 * away from the edge, then Telegram's darkening curve. Drawn behind the modified content.
 */
fun Modifier.aloEdgeShade(
    top: Boolean,
    peak: Float,
    solid: Float = 0.3f,
    backdrop: AloGlassBackdrop? = null
): Modifier = composed {
    val source = backdrop ?: LocalAloGlassBackdrop.current
    val blurLayer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val radiusPx = with(LocalDensity.current) { 20.dp.toPx() } * AloEdgeShadeScale
    val canBlur = source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AloGlassEffects.plain
    // The page colour: a black edge in the dark theme, a white one in the light theme.
    val base = if (AloNativeAppBackground.luminance() > 0.5f) Color.White else Color.Black
    val shade = remember(top, peak, solid, base) { aloEdgeBrush(top, peak, solid, base) }
    val mask = remember(top) {
        if (top) Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black, 1f to Color.Black)
    }
    this
        .onGloballyPositioned { position = it.positionInWindow() }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawBehind {
            val layers = source?.sources.orEmpty().filter { it.layer != null }
            if (canBlur && layers.isNotEmpty()) {
                @Suppress("UNUSED_VARIABLE") val tick = source!!.frame
                blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                blurLayer.alpha = 0.5f
                val shadeSize = IntSize(
                    (size.width * AloEdgeShadeScale).roundToInt().coerceAtLeast(1),
                    (size.height * AloEdgeShadeScale).roundToInt().coerceAtLeast(1)
                )
                blurLayer.record(size = shadeSize) {
                    scale(AloEdgeShadeScale, Offset.Zero) {
                        layers.forEach { layer ->
                            translate(layer.offset.x - position.x, layer.offset.y - position.y) { drawLayer(layer.layer!!) }
                        }
                    }
                }
                scale(1f / AloEdgeShadeScale, Offset.Zero) { drawLayer(blurLayer) }
                // Only the blur fades out; the darkening below is drawn whole.
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
            drawRect(shade)
        }
}

/**
 * Glass over an edge shade: Telegram's main tabs and top panel use the same 85% panel colour as
 * the rest of its glass, so this is that colour too.
 */
val AloGlassOverShadeTint: Color
    @Composable get() = AloGlassFill
