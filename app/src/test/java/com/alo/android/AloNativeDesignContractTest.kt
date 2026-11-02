package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloNativeDesignContractTest {
    @Test
    fun compactControlsRemainAccessible() {
        assertEquals(44f, AloNativeLayout.compactControlHeight.value)
        assertTrue(AloNativeLayout.minimumTouchTarget >= AloNativeLayout.compactControlHeight)
    }

    @Test
    fun bottomNavigationDoesNotBecomeTheDominantSurface() {
        assertEquals(58f, AloNativeLayout.bottomBarHeight.value)
        assertTrue(AloNativeLayout.bottomBarSelectionSize < AloNativeLayout.bottomBarHeight)
        assertTrue(AloNativeLayout.radiusLarge > AloNativeLayout.radiusMedium)
    }

    @Test
    fun conversationRowsKeepAStableScanHeight() {
        assertEquals(70f, AloNativeLayout.conversationRowHeight.value)
        assertTrue(AloNativeLayout.conversationRowHeight > AloNativeLayout.minimumTouchTarget)
    }
}
