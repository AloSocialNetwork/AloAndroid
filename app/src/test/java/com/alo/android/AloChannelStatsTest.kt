package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AloChannelStatsTest {
    @Test
    fun axisStepsMatchSwiftCharts() {
        assertEquals(5 to 20, niceScale(20))
        assertEquals(1_000 to 3_000, niceScale(2_400))
        assertEquals(1 to 1, niceScale(0))
    }

    @Test
    fun everyDayOfThePeriodIsCountedWithZeros() {
        val today = LocalDate.of(2026, 10, 16)
        val stats = AloChannelStats(
            days = 3, subscribers = 10, posts = 2, views = 300, likes = 0, comments = 0, reposts = 0,
            growth = listOf("2026-10-16" to 4, "2026-10-14" to 1),
            publications = listOf(AloChannelStats.Publication(1, "2026-10-15T09:00:00Z", 120, 0, 0, 0))
        )
        assertEquals(
            listOf(Triple("2026-10-14", 1, 0), Triple("2026-10-15", 0, 120), Triple("2026-10-16", 4, 0)),
            stats.byDay(today)
        )
        assertEquals(150, stats.viewsPerPost)
        assertEquals(5, stats.joinedInPeriod)
    }
}
