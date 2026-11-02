package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held mic reads drags as the iOS app (AloRecordingDrag) and Telegram do. */
class AloRecordingDragTest {
    @Test
    fun slidingLeftPast150Cancels() {
        assertFalse(AloRecordingDrag(dx = -140f).shouldCancel)
        assertTrue(AloRecordingDrag(dx = -160f).shouldCancel)
        assertEquals(0.5f, AloRecordingDrag(dx = -75f).cancelProgress, 0.001f)
    }

    @Test
    fun slidingUpPast110LocksWhenMostlyVertical() {
        assertTrue(AloRecordingDrag(dx = -20f, dy = -120f).shouldLock)
        assertFalse(AloRecordingDrag(dx = -110f, dy = -120f).shouldLock)
        // A lock wins over a cancel reached at the same time.
        assertFalse(AloRecordingDrag(dx = -10f, dy = -200f).shouldCancel)
    }

    @Test
    fun theOrbStaysWithinReach() {
        val drag = AloRecordingDrag(dx = -400f, dy = 50f)
        assertEquals(-150f, drag.orbX, 0.001f)
        assertEquals(0f, drag.orbY, 0.001f)
    }

    @Test
    fun releaseSendsUnlessTheFingerMeantOtherwise() {
        assertEquals(AloRecordingDrag.Release.Finish, AloRecordingDrag(dx = -30f, dy = -10f).release(0f, 0f))
        assertEquals(AloRecordingDrag.Release.Cancel, AloRecordingDrag(dx = -110f, dy = -5f).release(0f, 0f))
        assertEquals(AloRecordingDrag.Release.Cancel, AloRecordingDrag(dx = -20f).release(-600f, 0f))
        assertEquals(AloRecordingDrag.Release.Lock, AloRecordingDrag(dx = -5f, dy = -70f).release(0f, 0f))
        assertEquals(AloRecordingDrag.Release.Lock, AloRecordingDrag(dy = -10f).release(0f, -500f))
    }

    @Test
    fun typingDotsSwellAndRest() {
        assertEquals(0f, aloTypingDotSwell(0f), 0.001f)
        assertEquals(1f, aloTypingDotSwell(320f), 0.001f)
        assertEquals(0f, aloTypingDotSwell(640f), 0.001f)
        assertEquals(0f, aloTypingDotSwell(720f), 0.001f)
        assertEquals(0.75f, aloTypingDotSwell(160f), 0.001f)
    }
}
