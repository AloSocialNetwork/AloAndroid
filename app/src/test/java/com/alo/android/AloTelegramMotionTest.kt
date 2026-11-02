package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telegram Android's motion rules as ported: which messages enter, and how a swipe-back settles. */
class AloTelegramMotionTest {
    @Test
    fun historyOnScreenDoesNotEnter() {
        val state = AloMessageEnterState()
        state.observe(listOf(1, 2, 3)) { true }
        assertNull(state.enterFor(1))
        assertNull(state.enterFor(3))
    }

    @Test
    fun aSentMessageComesOutOfTheComposerAndAnIncomingOneRises() {
        val state = AloMessageEnterState()
        state.observe(listOf(1, 2, 3)) { false }
        state.observe(listOf(1, 2, 3, -1)) { index -> index == 3 }
        assertEquals(true, state.enterFor(-1)?.fromComposer)
        state.observe(listOf(1, 2, 3, -1, 4)) { false }
        assertEquals(false, state.enterFor(4)?.fromComposer)
    }

    @Test
    fun aConfirmedSendKeepsItsRowAndDoesNotReplay() {
        val state = AloMessageEnterState()
        state.observe(listOf(1, 2)) { false }
        state.observe(listOf(1, 2, -7)) { true }
        val enter = state.enterFor(-7)
        assertNotNull(enter)
        state.played += -7
        // The server numbered it, but the row keeps the local key, so nothing new appears.
        state.observe(listOf(1, 2, -7)) { false }
        assertNull(state.enterFor(-7))
    }

    @Test
    fun aPageOfHistoryOrACatchUpJustAppears() {
        val state = AloMessageEnterState()
        state.observe(listOf(10, 11)) { false }
        state.observe(listOf(1, 2, 3, 4, 5, 10, 11)) { false }
        assertNull(state.enterFor(1))
        state.observe(listOf(1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15)) { false }
        assertNull(state.enterFor(15))
    }

    @Test
    fun olderMessagesLoadedAboveDoNotEnter() {
        val state = AloMessageEnterState()
        state.observe(listOf(10, 11, 12, 13, 14)) { false }
        state.observe(listOf(9, 10, 11, 12, 13, 14)) { false }
        assertNull(state.enterFor(9))
    }

    @Test
    fun swipeBackClosesPastAThirdOrOnASidewaysFlick() {
        val width = 1080f
        assertFalse(aloTelegramSwipeCloses(300f, 500f, 0f, width))
        assertTrue(aloTelegramSwipeCloses(400f, 0f, 0f, width))
        assertTrue(aloTelegramSwipeCloses(100f, 4000f, 200f, width))
        // A fast but mostly vertical fling is not a swipe back.
        assertFalse(aloTelegramSwipeCloses(100f, 4000f, 5000f, width))
    }

    @Test
    fun swipeSettleTimeFollowsTheDistanceLeft() {
        assertEquals(200, aloSwipeSettleMs(1080f, 1080f, closing = true))
        assertEquals(50, aloSwipeSettleMs(100f, 1080f, closing = true))
        assertEquals(320, aloSwipeSettleMs(1080f, 1080f, closing = false))
        assertEquals(120, aloSwipeSettleMs(50f, 1080f, closing = false))
    }

    @Test
    fun telegramCurvesStartAndEndInPlace() {
        for (easing in listOf(AloTelegramDecelerate, AloTelegramAccelerateDecelerate, AloTelegramChatEasing, AloTelegramEaseOutQuint)) {
            assertEquals(0f, easing.transform(0f), 0.001f)
            assertEquals(1f, easing.transform(1f), 0.001f)
        }
        // DecelerateInterpolator(1.5) is most of the way there by the middle.
        assertEquals(0.875f, AloTelegramDecelerate.transform(0.5f), 0.001f)
    }
}
