package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloCallMinimizePolicyTest {
    @Test
    fun minimizesAfterVerticalSwipeInEitherDirection() {
        assertTrue(AloCallMinimizePolicy.shouldMinimize(84f, 72f))
        assertTrue(AloCallMinimizePolicy.shouldMinimize(-84f, 72f))
    }

    @Test
    fun returnsAfterShortVerticalDrag() {
        assertFalse(AloCallMinimizePolicy.shouldMinimize(30f, 72f))
        assertFalse(AloCallMinimizePolicy.shouldMinimize(-30f, 72f))
    }
}
