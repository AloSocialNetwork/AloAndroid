package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloDirectCallPhaseTest {
    @Test
    fun onlyCompletedPhasesAreTerminal() {
        assertFalse(AloDirectCallPhase.Outgoing.isTerminal)
        assertFalse(AloDirectCallPhase.Reconnecting.isTerminal)
        assertFalse(AloDirectCallPhase.Active.isTerminal)
        assertTrue(AloDirectCallPhase.NoAnswer.isTerminal)
        assertTrue(AloDirectCallPhase.Interrupted.isTerminal)
        assertTrue(AloDirectCallPhase.Busy.isTerminal)
        assertTrue(AloDirectCallPhase.Declined.isTerminal)
    }
}
