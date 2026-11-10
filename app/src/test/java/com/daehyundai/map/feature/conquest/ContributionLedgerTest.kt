package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ContributionLedgerTest {
    private val t = Instant.parse("2026-11-10T04:00:00Z").toEpochMilli()
    private fun ledger() = ContributionLedger(setOf("red", "blue", "green"), setOf("ab", "ba"), LocationSourceType.MOCK)
    private fun event(id: String = "e1", user: String = "u", road: String = "ab", time: Long = t) =
        RoadVisited(id, user, road, time, LocationSourceType.MOCK)

    @Test fun deduplicatesEventsAndHalfDaysButSeparatesDirectionAndUser() {
        val ledger = ledger()
        ledger.selectTeam("u", "red", t - 1)
        ledger.selectTeam("v", "green", t - 1)
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(event(), t))
        assertEquals(ContributionResult.DUPLICATE_EVENT, ledger.accept(event(), t))
        assertEquals(ContributionResult.DUPLICATE_HALF_DAY, ledger.accept(event("e2"), t))
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(event("e3", road = "ba"), t))
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(event(user = "v"), t))
        val midnight = Instant.parse("2026-11-10T15:00:00Z").toEpochMilli()
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(event("e4", time = midnight), midnight))
        assertEquals(4, ledger.contributions().size)
    }

    @Test fun membershipCannotChangeAndEarlierUnselectedTripsAreNotReplayed() {
        val ledger = ledger()
        assertEquals(ContributionResult.NO_TEAM, ledger.accept(event(), t))
        assertTrue(ledger.selectTeam("u", "blue", t + 1))
        assertFalse(ledger.selectTeam("u", "red", t + 2))
        assertEquals(ContributionResult.BEFORE_TEAM_SELECTION, ledger.accept(event(), t + 2))
        assertEquals("blue", ledger.teamOf("u"))
        assertTrue(ledger.contributions().isEmpty())
    }

    @Test fun rejectsLateFutureWrongSourceAndUnknownSegmentsWithoutCountingThem() {
        val ledger = ledger()
        ledger.selectTeam("u", "red", 0)
        assertEquals(ContributionResult.LATE, ledger.accept(event(time = t - 7_200_000), t))
        assertEquals(ContributionResult.FUTURE, ledger.accept(event(time = t + 1), t))
        assertEquals(ContributionResult.WRONG_SOURCE, ledger.accept(event().copy(source = LocationSourceType.PLEOS), t))
        assertEquals(ContributionResult.UNKNOWN_SEGMENT, ledger.accept(event(road = "unknown"), t))
        assertEquals(ContributionResult.INVALID, ledger.accept(event().copy(eventId = ""), t))
        assertTrue(ledger.contributions().isEmpty())
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(event(), t))
    }
}
