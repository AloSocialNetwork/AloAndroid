package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same expectations as iOS's AloChatMosaicTests (worked out by hand from Telegram's
 * `chatMessageBubbleMosaicLayout` for the 300 × 380 box with 1 pt gaps), so both apps lay out an
 * album identically, plus the post column rules.
 */
class AloMediaMosaicTest {
    private fun chat(vararg ratios: Float) = AloMediaMosaic.layout(ratios.toList(), 300f, 380f, 1f)

    @Test
    fun twoPortraitPhotosSitSideBySide() {
        val (frames, size) = chat(0.75f, 0.75f)
        assertEquals(AloMosaicFrame(0f, 0f, 150f, 199f), frames[0])
        assertEquals(AloMosaicFrame(151f, 0f, 149f, 199f), frames[1])
        assertEquals(300f to 199f, size)
    }

    @Test
    fun twoLandscapePhotosStack() {
        val (frames, size) = chat(1920f / 1080f, 1920f / 1080f)
        assertEquals(AloMosaicFrame(0f, 0f, 300f, 168f), frames[0])
        assertEquals(AloMosaicFrame(0f, 169f, 300f, 168f), frames[1])
        assertEquals(300f to 337f, size)
    }

    @Test
    fun threeWithPortraitFirstUseALeftColumn() {
        val (frames, _) = chat(1080f / 1920f, 1f, 1f)
        assertEquals(AloMosaicFrame(0f, 0f, 150f, 380f), frames[0])
        assertEquals(AloMosaicFrame(151f, 0f, 149.5f, 229f), frames[1])
        assertEquals(AloMosaicFrame(151f, 230f, 149.5f, 150f), frames[2])
    }

    @Test
    fun fiveSquaresUseTelegramsRowSearch() {
        val (frames, size) = chat(1f, 1f, 1f, 1f, 1f)
        assertEquals(5, frames.size)
        assertEquals(150f, frames[0].height)
        assertEquals(151f, frames[2].y)
        assertEquals(100f, frames[2].height)
        assertEquals(frames[1].maxX, frames[4].maxX)
        assertEquals(251f, size.second)
    }

    @Test
    fun postPhotoKeepsPhonePortraitAndWideProportions() {
        assertEquals(0.75f, AloPostMediaSizing.ratio(1080, 1440), 0.001f)
        assertEquals(1920f / 1080f, AloPostMediaSizing.ratio(1920, 1080), 0.001f)
        assertEquals(0.75f, AloPostMediaSizing.ratio(1080, 1920), 0.001f)
        assertEquals(2f, AloPostMediaSizing.ratio(3000, 1000), 0.001f)
        assertEquals(0.8f, AloPostMediaSizing.ratio(0, 0), 0.001f)
    }

    @Test
    fun postAlbumsSpanTheColumnWithoutOverlaps() {
        val width = 361f
        val albums = listOf(
            listOf(0.75f, 1.78f), listOf(1.78f, 1.78f), listOf(0.5625f, 1f, 1.33f), listOf(1.33f, 1f, 0.75f),
            listOf(1.78f, 0.8f, 0.8f, 0.8f), listOf(0.75f, 1f, 1f, 1f), listOf(1f, 0.75f, 1.78f, 1f, 1.33f),
            listOf(3f, 1f), List(10) { 1f }, listOf(0f, 0f, 0f)
        )
        for (album in albums) {
            val (frames, size) = AloPostMediaSizing.album(album, width)
            assertEquals(width, size.first)
            assertEquals(album.size, frames.size)
            assertEquals(width, frames.maxOf { it.maxX }, 1f)
            for (frame in frames) {
                assertTrue(frame.x >= 0f && frame.maxX <= width + 0.5f)
                assertTrue(frame.width > 40f && frame.height > 40f)
            }
            for (i in frames.indices) for (j in frames.indices) if (j > i) {
                val a = frames[i]
                val b = frames[j]
                val overlap = a.x + 0.5f < b.maxX - 0.5f && b.x + 0.5f < a.maxX - 0.5f &&
                    a.y + 0.5f < b.maxY - 0.5f && b.y + 0.5f < a.maxY - 0.5f
                assertFalse("tiles never overlap", overlap)
            }
        }
    }
}
