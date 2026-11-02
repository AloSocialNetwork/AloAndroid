package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloCrashReporterTest {
    @Test
    fun messagesLoseWhatCouldIdentifySomeone() {
        val scrubbed = AloCrashReporter.scrub("Failed for test@example.com at https://x.test/a?token=1 alo_session=abc; ${"a1".repeat(20)}")
        assertFalse(scrubbed.contains("test@example.com"))
        assertFalse(scrubbed.contains("token=1"))
        assertFalse(scrubbed.contains("abc"))
        assertFalse(scrubbed.contains("a1a1"))
        assertEquals(200, AloCrashReporter.scrub("x".repeat(500)).length)
    }

    @Test
    fun theStackNamesTheCauseAndItsFrames() {
        val error = IllegalStateException("outer", IllegalArgumentException("Padding must be non-negative"))
        val stack = AloCrashReporter.stackOf(error)
        assertTrue(stack.startsWith("java.lang.IllegalStateException: outer\n"))
        assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: Padding must be non-negative"))
        assertTrue(stack.contains("AloCrashReporterTest"))
    }

    @Test
    fun anAnrReportKeepsOnlyTheMainThread() {
        val dump = "----- pid 1 -----\n\"Worker\" tid=9\n  at a.b\n\n\"main\" prio=5 tid=1\n  at com.alo.android.Slow.run\n\n\"Other\" tid=2\n"
        assertEquals("\"main\" prio=5 tid=1\n  at com.alo.android.Slow.run", AloCrashReporter.mainThreadOf(dump))
        assertEquals("", AloCrashReporter.mainThreadOf("no threads here"))
    }
}
