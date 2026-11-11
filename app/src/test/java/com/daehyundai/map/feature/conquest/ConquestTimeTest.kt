package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.ConquestTime
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ConquestTimeTest {
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun halfDaysUseKstMidnightAndNoonRegardlessOfDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val midnight = utc("2026-11-09T15:00:00Z")
            val noon = utc("2026-11-10T03:00:00Z")
            assertEquals(LocalDate.of(2026, 11, 9), ConquestTime.halfDay(midnight - 1).date)
            assertEquals(ConquestTime.HalfDay(LocalDate.of(2026, 11, 10), false), ConquestTime.halfDay(midnight))
            assertFalse(ConquestTime.halfDay(noon - 1).afternoon)
            assertTrue(ConquestTime.halfDay(noon).afternoon)
            assertEquals(noon - 86_400_000, ConquestTime.latestSettlement(noon - 1))
            assertEquals(noon, ConquestTime.latestSettlement(noon))
            assertEquals(utc("2026-11-11T03:00:00Z"), ConquestTime.nextSettlement(noon))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun thirtySixHourWindowIncludesStartAndExcludesNoon() {
        val end = utc("2026-11-10T03:00:00Z")
        val start = utc("2026-11-08T15:00:00Z")
        assertFalse(ConquestTime.inWindow(start - 1, end))
        assertTrue(ConquestTime.inWindow(start, end))
        assertTrue(ConquestTime.inWindow(end - 1, end))
        assertFalse(ConquestTime.inWindow(end, end))
    }
}
