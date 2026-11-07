package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held mic reads drags as Telegram Android's ChatActivityEnterView does. */
class AloRecordingDragTest {
    @Test
    fun slidingLeftCancelsAtThirtyFivePercentOfTheWidthCappedAt140dp() {
        assertEquals(140f, AloRecordingDrag.cancelDistanceFor(411f), 0.001f)
        assertEquals(112f, AloRecordingDrag.cancelDistanceFor(320f), 0.001f)
        assertFalse(AloRecordingDrag(dx = -139f).shouldCancel)
        assertTrue(AloRecordingDrag(dx = -140f).shouldCancel)
        assertTrue(AloRecordingDrag(dx = -112f, cancelDistance = 112f).shouldCancel)
        assertEquals(0.5f, AloRecordingDrag(dx = -70f).cancelProgress, 0.001f)
    }

    @Test
    fun slidingUp57dpLocksOnlyBeforeThirtyPercentTowardsCancel() {
        assertFalse(AloRecordingDrag(dy = -56f).shouldLock)
        assertTrue(AloRecordingDrag(dx = -20f, dy = -57f).shouldLock)
        assertFalse(AloRecordingDrag(dx = -50f, dy = -80f).shouldLock)
        assertFalse(AloRecordingDrag(dx = -10f, dy = -200f).shouldCancel)
    }

    @Test
    fun theCircleRisesToTheLockAndShrinksTowardsCancel() {
        val drag = AloRecordingDrag(dx = -400f, dy = 50f)
        assertEquals(0f, drag.orbY, 0.001f)
        assertEquals(-57f, AloRecordingDrag(dy = -90f).orbY, 0.001f)
        assertEquals(0.7f, drag.orbScale, 0.001f)
        assertEquals(1f, AloRecordingDrag().orbScale, 0.001f)
    }

    @Test
    fun releasingSends() {
        assertEquals(AloRecordingDrag.Release.Finish, AloRecordingDrag(dx = -30f, dy = -10f).release())
        assertEquals(AloRecordingDrag.Release.Finish, AloRecordingDrag(dx = -100f, dy = -5f).release())
        assertEquals(AloRecordingDrag.Release.Lock, AloRecordingDrag(dx = -5f, dy = -60f).release())
    }
}
