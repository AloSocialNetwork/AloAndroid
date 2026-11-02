package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloReactionEffectsTest {
    @Test
    fun theEffectBelongsToTheChipOfTheReactionJustChosen() {
        AloReactionEffects.request(messageId = 7, emoji = "🔥")
        assertFalse("another message", AloReactionEffects.take(8, "🔥"))
        assertFalse("another emoji", AloReactionEffects.take(7, "👍"))
        assertTrue(AloReactionEffects.take(7, "🔥"))
    }

    @Test
    fun anEffectPlaysOnceOnly() {
        AloReactionEffects.request(messageId = 9, emoji = "❤️")
        assertTrue(AloReactionEffects.take(9, "❤️"))
        assertFalse(AloReactionEffects.take(9, "❤️"))
    }

    @Test
    fun aChipThatWasNeverRequestedStaysQuiet() {
        // Opening a chat shows reactions people made earlier: none of them plays.
        assertFalse(AloReactionEffects.take(1234, "😂"))
    }
}
