package com.alo.android

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji panel's data and keys, and the numbers that keep the chat's overlays and the screens' headers where they were put. */
class AloChatPolishTest {
    @Test fun backspaceRemovesOneWholeEmoji() {
        assertEquals("ab", aloDropLastGrapheme("abc"))
        assertEquals("a", aloDropLastGrapheme("a😀"))
        assertEquals("a", aloDropLastGrapheme("a👍🏽"))
        assertEquals("a", aloDropLastGrapheme("a❤️"))
        assertEquals("a", aloDropLastGrapheme("a👨‍👩‍👧"))
        assertEquals("", aloDropLastGrapheme("😀"))
        assertEquals("", aloDropLastGrapheme(""))
    }

    @Test fun everyCategoryHasEmojiWithoutRepeats() {
        AloEmojiCatalog.categories.forEach { category ->
            assertTrue("${category.id} is empty", category.items.isNotEmpty())
            assertEquals("${category.id} repeats an emoji", category.items.size, category.items.toSet().size)
        }
        assertEquals(AloEmojiCatalog.categories.size, AloEmojiCatalog.categories.map { it.id }.toSet().size)
    }

    @Test fun searchFindsByWordCategoryAndEmoji() {
        assertTrue("😀" in AloEmojiCatalog.search("smile"))
        assertTrue("☕" in AloEmojiCatalog.search("кофе"))
        assertTrue("☕" in AloEmojiCatalog.search("КОФЕ"))
        assertTrue("🐶" in AloEmojiCatalog.search("животные"))
        assertTrue("🔥" in AloEmojiCatalog.search("🔥"))
        assertTrue(AloEmojiCatalog.search("   ").isEmpty())
        assertTrue(AloEmojiCatalog.search("zzzzqqq").isEmpty())
        assertEquals(AloEmojiCatalog.search("smile"), AloEmojiCatalog.search("smile").distinct())
    }

    @Test fun attachSheetStaysBetweenAStubAndTheWholeScreen() {
        assertEquals(420.dp, AloAttachMetrics.sheetHeight(500))
        assertEquals(640.dp, AloAttachMetrics.sheetHeight(1200))
        val phone = AloAttachMetrics.sheetHeight(800)
        assertTrue(phone > 420.dp && phone < 640.dp)
    }

    @Test fun headerActionCirclesEndAtTheSideInset() {
        // A 44dp touch box around a 38dp circle: the box ends 3dp closer to the edge than the circle, which is 16dp from it.
        assertEquals(AloScreenChrome.SideInset, AloScreenChrome.ActionBoxEnd + (44.dp - AloScreenChrome.ActionDiameter) / 2)
        assertEquals(AloScreenChrome.BackStart + AloScreenChrome.BackDiameter + 12.dp, AloScreenChrome.TitleSideReserve)
    }

    @Test fun pinnedBarLeavesRoomUnderTheHeader() {
        assertTrue(AloChatOverlayMetrics.OverlayGap >= 10.dp)
        assertEquals(AloChatOverlayMetrics.HeaderBottom + AloChatOverlayMetrics.OverlayGap, AloChatOverlayMetrics.OverlayTop)
        assertFalse(AloChatOverlayMetrics.PinnedBlock < 46.dp)
    }

    @Test fun popupMenusShareOneRoundedShape() {
        assertTrue(AloPopupMenuMetrics.Radius >= 16.dp)
        assertTrue(AloPopupMenuMetrics.MinWidth >= 160.dp)
    }
}
