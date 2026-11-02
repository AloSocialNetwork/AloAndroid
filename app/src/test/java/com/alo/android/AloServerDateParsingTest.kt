package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AloServerDateParsingTest {
    private fun instant(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun readsWhatTheServerSends() {
        assertEquals(instant("2026-11-02T03:31:05.123Z"), parseServerDateMillis("2026-11-02T03:31:05.123Z"))
        assertEquals(instant("2026-11-02T03:31:05Z"), parseServerDateMillis("2026-11-02T03:31:05Z"))
        assertEquals(instant("2026-11-02T03:31:05Z"), parseServerDateMillis("2026-11-02 03:31:05"))
        assertEquals(instant("2026-11-02T00:00:00Z"), parseServerDateMillis("2026-11-02"))
        assertEquals(instant("2026-11-02T00:31:05Z"), parseServerDateMillis("2026-11-02T03:31:05+03:00"))
        assertEquals(instant("2026-11-02T06:31:05Z"), parseServerDateMillis("2026-11-02T03:31:05-0300"))
        assertEquals(instant("2026-11-02T00:31:05Z"), parseServerDateMillis("2026-11-02T03:31:05+03"))
        // Longer fractions keep milliseconds, shorter ones are not mistaken for them.
        assertEquals(instant("2026-11-02T03:31:05.123Z"), parseServerDateMillis("2026-11-02T03:31:05.123456Z"))
        assertEquals(instant("2026-11-02T03:31:05.500Z"), parseServerDateMillis("2026-11-02T03:31:05.5Z"))
    }

    @Test
    fun agreesWithTheCalendarAcrossYearsAndLeapDays() {
        for (text in listOf("1970-01-01T00:00:00Z", "1999-12-31T23:59:59Z", "2000-02-29T12:00:00Z", "2024-02-29T00:00:01Z", "2100-03-01T00:00:00Z", "1969-07-20T20:17:40Z")) {
            assertEquals(text, instant(text), parseServerDateMillis(text))
        }
    }

    @Test
    fun refusesWhatIsNotADate() {
        for (text in listOf("", "вчера", "2026-13-01", "2026-02-30", "2023-02-29", "2026-11-02T25:00:00Z", "2026-11-02T03:31:05", "2026-11-02T03:31:05.Z", "2026-11-02T03:31:05Zz", "2026-11-02 03:31")) {
            assertNull(text, parseServerDateMillis(text))
        }
    }
}
