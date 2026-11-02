package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloInteractiveDismissPolicyTest {
    @Test
    fun shortDragReturnsToConversation() {
        assertFalse(
            AloInteractiveDismissPolicy.shouldDismiss(
                offsetPx = 70f,
                velocityPxPerSecond = 0f,
                widthPx = 390f
            )
        )
    }

    @Test
    fun deliberateDragOrFlingClosesConversation() {
        assertTrue(
            AloInteractiveDismissPolicy.shouldDismiss(
                offsetPx = 130f,
                velocityPxPerSecond = 0f,
                widthPx = 390f
            )
        )
        assertTrue(
            AloInteractiveDismissPolicy.shouldDismiss(
                offsetPx = 12f,
                velocityPxPerSecond = 1_100f,
                widthPx = 390f
            )
        )
    }
}
