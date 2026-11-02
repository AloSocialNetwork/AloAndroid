package com.alo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.pow

/** The look of story text (iOS's AloStoryTextStyle): typeface, colour and the plate behind it. */
internal data class AloStoryTextLook(val typeface: Int = 0, val colorIndex: Int = 0, val plate: Int = 0) {
    companion object {
        val colors = listOf(
            Color.White, Color.Black, Color(0xFFFF453A), Color(0xFFFF9E0A),
            Color(0xFFFFD60A), Color(0xFF33C759), Color(0xFF0A85FF), Color(0xFFB052DE)
        )
        val gradients = listOf(
            listOf(Color(0xFF0A84FF), Color(0xFF6B45ED)),
            listOf(Color(0xFFFF6B66), Color(0xFFFFA83D)),
            listOf(Color(0xFF12998F), Color(0xFF33D180)),
            listOf(Color(0xFF212329), Color(0xFF525466)),
            listOf(Color(0xFFED5C9E), Color(0xFF8C52F2))
        )
        val plateTitles = listOf("Без подложки", "Тёмная подложка", "Цветная подложка", "Светлая подложка", "Подложка-рамка")
        const val TYPEFACES = 3
        const val TEXT_SIZE = 28f
        const val EMOJI_SIZE = 72f
        /** Text wraps at this share of the page width (times the item's size). */
        const val TEXT_WRAP = 0.78f
    }

    val baseColor: Color get() = colors[colorIndex]

    val textColor: Color
        get() = when (plate) {
            // A coloured plate takes the colour; the text turns white, or black on white.
            2 -> if (colorIndex == 0) Color.Black else Color.White
            // White text would vanish on the light plate.
            3 -> if (colorIndex == 0) Color.Black else baseColor
            else -> baseColor
        }

    val plateFill: Color?
        get() = when (plate) {
            1 -> Color.Black.copy(alpha = 0.38f)
            2 -> baseColor
            3 -> Color.White.copy(alpha = 0.88f)
            else -> null
        }

    val plateStroke: Color? get() = if (plate == 4) baseColor else null
    val hasPlate: Boolean get() = plate != 0

    val fontFamily: FontFamily get() = when (typeface) { 1 -> FontFamily.Serif; 2 -> FontFamily.Monospace; else -> FontFamily.Default }
    val fontWeight: FontWeight get() = when (typeface) { 1 -> FontWeight.SemiBold; 2 -> FontWeight.Medium; else -> FontWeight.Bold }
    val sizeFactor: Float get() = if (typeface == 2) 0.86f else 1f

    fun androidTypeface(): Typeface = when (typeface) {
        1 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        2 -> Typeface.MONOSPACE
        else -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}

/** An emoji or a block of styled text on a page; the position is relative (0…1), the size a factor. */
internal data class AloStoryItem(
    val id: Long,
    val emoji: String? = null,
    val text: String = "",
    val look: AloStoryTextLook = AloStoryTextLook(),
    val x: Float = 0.5f,
    val y: Float = 0.45f,
    val scale: Float = 1f,
    val rotation: Float = 0f
) {
    val isText: Boolean get() = emoji == null
}

/** A brush stroke: points relative to the page, width relative to its width. */
internal data class AloStoryStroke(val points: List<Offset>, val color: Color, val width: Float)

internal data class AloStoryPageEdits(
    val strokes: List<AloStoryStroke> = emptyList(),
    val items: List<AloStoryItem> = emptyList()
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && items.isEmpty()
}

internal data class AloStoryPickedMedia(val uri: Uri, val isVideo: Boolean)

/** What one story page uploads: a flattened picture, or a photo/video sent as it is. */
class AloStoryUploadPage(val bytes: ByteArray?, val uri: Uri?, val mime: String, val type: String)

internal object AloStoryBrush {
    /** Telegram/iOS brush range in dp; finer steps at the thin end. */
    fun widthDp(level: Float): Float = 2f + level.coerceIn(0f, 1f).pow(1.6f) * 38f
    val colors = AloStoryTextLook.colors
}

/** Angles of turned items: folded into -180…180 and snapped straight near a right angle. */
internal object AloStoryRotation {
    private const val SNAP = 3f
    fun normalized(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value <= -180f) value += 360f
        return value
    }
    fun snapped(degrees: Float): Float {
        val value = normalized(degrees)
        val nearest = Math.round(value / 90f) * 90f
        return if (kotlin.math.abs(value - nearest) < SNAP) normalized(nearest) else value
    }
}

/**
 * Flattens a page into a 1080 × 1920 JPEG: background (gradient or photo, filled), the drawing and the
 * items, laid out at the on-screen canvas's coordinates scaled up, as iOS's AloStoryRenderedPage.
 */
internal object AloStoryRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    fun render(
        context: Context,
        background: Uri?,
        gradientIndex: Int,
        edits: AloStoryPageEdits,
        canvasWidthPx: Float,
        density: Float
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val factor = WIDTH / canvasWidthPx
        val photo = background?.let { decode(context, it) }
        if (photo != null) {
            val scale = max(WIDTH / photo.width.toFloat(), HEIGHT / photo.height.toFloat())
            val w = photo.width * scale
            val h = photo.height * scale
            canvas.drawBitmap(photo, null, RectF((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
        } else {
            val colors = AloStoryTextLook.gradients[gradientIndex % AloStoryTextLook.gradients.size]
            canvas.drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), colors[0].toArgb(), colors[1].toArgb(), Shader.TileMode.CLAMP)
            })
        }
        edits.strokes.forEach { stroke -> drawStroke(canvas, stroke) }
        edits.items.forEach { item -> drawItem(canvas, item, canvasWidthPx * factor, density * factor) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH && bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun drawStroke(canvas: Canvas, stroke: AloStoryStroke) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = stroke.width * WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(strokePath(stroke.points, WIDTH.toFloat(), HEIGHT.toFloat()), paint)
    }

    /** A smooth path through the points (midpoint quadratic curves), as a finger drew it. */
    fun strokePath(points: List<Offset>, width: Float, height: Float): Path {
        val path = Path()
        val first = points.first()
        path.moveTo(first.x * width, first.y * height)
        if (points.size == 1) {
            path.lineTo(first.x * width + 0.1f, first.y * height)
            return path
        }
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val midX = (previous.x + current.x) / 2 * width
            val midY = (previous.y + current.y) / 2 * height
            path.quadTo(previous.x * width, previous.y * height, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x * width, last.y * height)
        return path
    }

    /** One item at page scale: [unit] is how many output pixels a dp is. */
    private fun drawItem(canvas: Canvas, item: AloStoryItem, pageWidth: Float, unit: Float) {
        canvas.save()
        canvas.translate(item.x * WIDTH, item.y * HEIGHT)
        canvas.rotate(AloStoryRotation.snapped(item.rotation))
        val scale = item.scale
        if (item.emoji != null) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = AloStoryTextLook.EMOJI_SIZE * unit * scale; textAlign = Paint.Align.CENTER }
            val metrics = paint.fontMetrics
            canvas.drawText(item.emoji, 0f, -(metrics.ascent + metrics.descent) / 2, paint)
        } else {
            val look = item.look
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = AloStoryTextLook.TEXT_SIZE * look.sizeFactor * unit * scale
                typeface = look.androidTypeface()
                color = look.textColor.toArgb()
            }
            val wrap = (pageWidth * AloStoryTextLook.TEXT_WRAP * scale).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, wrap)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            val lineWidth = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            val w = lineWidth
            val h = layout.height.toFloat()
            if (look.hasPlate) {
                val padX = 14f * unit * scale
                val padY = 8f * unit * scale
                val radius = 12f * unit * scale
                val rect = RectF(-w / 2 - padX, -h / 2 - padY, w / 2 + padX, h / 2 + padY)
                look.plateFill?.let { fill ->
                    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() })
                }
                look.plateStroke?.let { stroke ->
                    val inset = 1.25f * unit * scale
                    rect.inset(inset, inset)
                    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = stroke.toArgb(); style = Paint.Style.STROKE; strokeWidth = 2.5f * unit * scale
                    })
                }
            }
            // The layout is centred inside its wrap width; shift it so the text block is centred on the item.
            canvas.translate(-wrap / 2f, -h / 2)
            layout.draw(canvas)
        }
        canvas.restore()
    }
}
