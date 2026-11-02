package com.alo.android

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A tile of an album, in the units the layout was asked for (dp). */
internal data class AloMosaicFrame(val x: Float, val y: Float, val width: Float, val height: Float) {
    val maxX get() = x + width
    val maxY get() = y + height
}

/**
 * Telegram's album mosaic (`chatMessageBubbleMosaicLayout`, the same as iOS's AloChatMosaic):
 * hand-tuned schemes for 2–4 items, and Telegram's row search for 5–10 items or any panorama wider
 * than 2:1. Every photo keeps close to its own proportions.
 */
internal object AloMediaMosaic {
    const val MAXIMUM_ITEMS = 10
    private const val MIN_WIDTH = 68f
    private const val MIN_HEIGHT = 81f

    /** Ratios are width / height; 0 or unknown counts as square. */
    fun layout(ratiosIn: List<Float>, width: Float, height: Float, spacing: Float): Pair<List<AloMosaicFrame>, Pair<Float, Float>> {
        val ratios = ratiosIn.take(MAXIMUM_ITEMS).map { if (it > 0f) it.coerceIn(0.1f, 10f) else 1f }
        if (ratios.size < 2) {
            val h = width / (ratios.firstOrNull() ?: 1f)
            return listOf(AloMosaicFrame(0f, 0f, width, h)) to (width to h)
        }
        // Telegram's average starts from 1 before adding every ratio.
        val average = (1f + ratios.sum()) / ratios.size
        val frames = if (ratios.size <= 4 && ratios.none { it > 2f }) {
            special(ratios, average, width, height, spacing)
        } else {
            general(ratios, average, width, spacing)
        }
        val w = (frames.maxOfOrNull { it.maxX } ?: width).roundToInt().toFloat()
        val h = (frames.maxOfOrNull { it.maxY } ?: height).roundToInt().toFloat()
        return frames to (w to h)
    }

    private fun special(r: List<Float>, average: Float, W: Float, H: Float, s: Float): List<AloMosaicFrame> {
        val proportions = r.joinToString("") { if (it > 1.2f) "w" else if (it < 0.8f) "n" else "q" }
        return when (r.size) {
            2 -> when {
                proportions == "ww" && average > 1.4f * (W / H) && abs(r[0] - r[1]) < 0.2f -> {
                    val h = floor(min(W / r[0], min(W / r[1], (H - s) / 2)))
                    listOf(AloMosaicFrame(0f, 0f, W, h), AloMosaicFrame(0f, h + s, W, h))
                }
                proportions == "ww" || proportions == "qq" -> {
                    val w = (W - s) / 2
                    val h = floor(min(w / r[0], min(w / r[1], H)))
                    listOf(AloMosaicFrame(0f, 0f, w, h), AloMosaicFrame(w + s, 0f, w, h))
                }
                else -> {
                    val second = floor(min(0.5f * (W - s), ((W - s) / r[0] / (1 / r[0] + 1 / r[1])).roundToInt().toFloat()))
                    val first = W - second - s
                    val h = floor(min(H, min(first / r[0], second / r[1]).roundToInt().toFloat()))
                    listOf(AloMosaicFrame(0f, 0f, first, h), AloMosaicFrame(first + s, 0f, second, h))
                }
            }
            3 -> if (proportions.startsWith("n")) {
                val thirdH = min((H - s) * 0.5f, (r[1] * (W - s) / (r[2] + r[1])).roundToInt().toFloat())
                val secondH = H - thirdH - s
                val rightW = max(MIN_WIDTH, min((W - s) * 0.5f, min(thirdH * r[2], secondH * r[1]).roundToInt().toFloat()))
                val leftW = min(H * r[0], W - s - rightW).roundToInt().toFloat()
                listOf(
                    AloMosaicFrame(0f, 0f, leftW, H),
                    AloMosaicFrame(leftW + s, 0f, rightW, secondH),
                    AloMosaicFrame(leftW + s, secondH + s, rightW, thirdH)
                )
            } else {
                val firstH = floor(min(W / r[0], (H - s) * 0.66f))
                val half = (W - s) / 2
                val secondH = min(H - firstH - s, min(half / r[1], half / r[2]).roundToInt().toFloat())
                listOf(
                    AloMosaicFrame(0f, 0f, W, firstH),
                    AloMosaicFrame(0f, firstH + s, half, secondH),
                    AloMosaicFrame(half + s, firstH + s, half, secondH)
                )
            }
            else -> if (proportions.startsWith("w")) {
                val h0 = min(W / r[0], (H - s) * 0.66f).roundToInt().toFloat()
                var h = ((W - 2 * s) / (r[1] + r[2] + r[3])).roundToInt().toFloat()
                val w0 = max(MIN_WIDTH, min((W - 2 * s) * 0.4f, h * r[1]))
                val w2 = max(max(MIN_WIDTH, (W - 2 * s) * 0.33f), h * r[3])
                val w1 = W - w0 - w2 - 2 * s
                h = max(MIN_HEIGHT, min(H - h0 - s, h))
                listOf(
                    AloMosaicFrame(0f, 0f, W, h0),
                    AloMosaicFrame(0f, h0 + s, w0, h),
                    AloMosaicFrame(w0 + s, h0 + s, w1, h),
                    AloMosaicFrame(w0 + w1 + 2 * s, h0 + s, w2, h)
                )
            } else {
                val w0 = min(H * r[0], (W - s) * 0.6f).roundToInt().toFloat()
                var w = ((H - 2 * s) / (1 / r[1] + 1 / r[2] + 1 / r[3])).roundToInt().toFloat()
                val h0 = floor(w / r[1])
                val h1 = floor(w / r[2])
                val h2 = H - h0 - h1 - 2 * s
                w = max(MIN_WIDTH, min(W - w0 - s, w))
                listOf(
                    AloMosaicFrame(0f, 0f, w0, H),
                    AloMosaicFrame(w0 + s, 0f, w, h0),
                    AloMosaicFrame(w0 + s, h0 + s, w, h1),
                    AloMosaicFrame(w0 + s, h0 + h1 + 2 * s, w, h2)
                )
            }
        }
    }

    /**
     * Telegram's search over 2-, 3- and 4-row splits: rows fill the width, the total height should
     * be near 4/3 of the width, and rows that shrink downwards or are too low lose.
     */
    private fun general(ratios: List<Float>, average: Float, W: Float, s: Float): List<AloMosaicFrame> {
        val cropped = ratios.map { ratio ->
            val value = if (average > 1.1f) max(1f, ratio) else min(1f, ratio)
            value.coerceIn(0.66667f, 1.7f)
        }
        val n = cropped.size
        fun rowHeight(from: Int, to: Int) = (W - (to - from - 1) * s) / cropped.subList(from, to).sum()
        val attempts = mutableListOf<List<Int>>()
        for (first in 1 until n) if (first <= 3 && n - first <= 3) attempts += listOf(first, n - first)
        if (n >= 3) for (first in 1..n - 2) for (second in 1..n - first - 1) {
            val third = n - first - second
            if (first <= 3 && second <= (if (average < 0.85f) 4 else 3) && third <= 3) attempts += listOf(first, second, third)
        }
        if (n >= 4) for (first in 1..n - 3) for (second in 1..n - first - 2) for (third in 1..n - first - second - 1) {
            val fourth = n - first - second - third
            if (first <= 3 && second <= 3 && third <= 3 && fourth <= 3) attempts += listOf(first, second, third, fourth)
        }
        val maxHeight = floor(W / 3 * 4)
        var best: Triple<List<Int>, List<Float>, Float>? = null
        for (counts in attempts) {
            val heights = mutableListOf<Float>()
            var start = 0
            for (count in counts) {
                heights += rowHeight(start, start + count)
                start += count
            }
            var total = s * (counts.size - 1)
            var minLine = Float.MAX_VALUE
            for (h in heights) {
                total += floor(h)
                minLine = min(minLine, total)
            }
            var diff = abs(total - maxHeight)
            if (counts.size > 1 && (counts[0] > counts[1] || (counts.size > 2 && counts[1] > counts[2]) || (counts.size > 3 && counts[2] > counts[3]))) {
                diff *= 1.5f
            }
            if (minLine < MIN_WIDTH) diff *= 1.5f
            if (best == null || diff < best.third) best = Triple(counts, heights, diff)
        }
        val chosen = best ?: return emptyList()
        val frames = mutableListOf<AloMosaicFrame>()
        val lastInRow = mutableListOf<Int>()
        var index = 0
        var y = 0f
        chosen.first.forEachIndexed { row, count ->
            val h = ceil(chosen.second[row])
            var x = 0f
            repeat(count) {
                val w = ceil(cropped[index] * h)
                frames += AloMosaicFrame(x, y, w, h)
                x += w + s
                index += 1
            }
            lastInRow += index - 1
            y += h + s
        }
        // Every row ends on the same right edge.
        val maxRight = lastInRow.maxOfOrNull { frames[it].maxX } ?: W
        for (last in lastInRow) {
            val frame = frames[last]
            frames[last] = frame.copy(width = max(frame.width, maxRight - frame.x))
        }
        return frames
    }
}

/**
 * Post media across the full column, with Telegram's limits (as on iOS): a photo is at most 4/3 of
 * the width tall, so a 3:4 phone photo shows whole, and a landscape one keeps its proportions up to
 * 2:1. Unknown size uses a tall 4:5 frame. Albums use the mosaic in Telegram's 300 × 380 box
 * proportions, scaled to the column so every album lines up with the text above it.
 */
internal object AloPostMediaSizing {
    const val DEFAULT_RATIO = 0.8f
    const val MINIMUM_RATIO = 0.75f
    const val MAXIMUM_RATIO = 2f
    const val ALBUM_HEIGHT_RATIO = 380f / 300f
    const val ALBUM_SPACING = 2f

    fun ratio(width: Int, height: Int): Float =
        if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(MINIMUM_RATIO, MAXIMUM_RATIO) else DEFAULT_RATIO

    fun album(ratios: List<Float>, width: Float): Pair<List<AloMosaicFrame>, Pair<Float, Float>> {
        val w = max(1f, width)
        val (frames, size) = AloMediaMosaic.layout(ratios, w, (w * ALBUM_HEIGHT_RATIO).roundToInt().toFloat(), ALBUM_SPACING)
        if (size.first <= 0f || abs(size.first - w) <= 0.5f) return frames to size
        val scale = w / size.first
        val scaled = frames.map { frame ->
            val left = (frame.x * scale).roundToInt().toFloat()
            val right = (frame.maxX * scale).roundToInt().toFloat()
            frame.copy(x = left, width = right - left)
        }
        return scaled to (w to size.second)
    }
}
