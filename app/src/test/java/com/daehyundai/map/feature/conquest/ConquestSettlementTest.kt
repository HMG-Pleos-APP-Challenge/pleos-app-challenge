package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ConquestSettlementTest {
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private val start = utc("2026-11-09T03:00:00Z") // KST noon
    private val day = 86_400_000L
    private fun ledger() = ContributionLedger(setOf("red", "blue", "green"), setOf("ab", "ba"), LocationSourceType.MOCK)
    private fun contribute(ledger: ContributionLedger, user: String, team: String, time: Long, road: String = "ab") {
        ledger.selectTeam(user, team, start)
        assertEquals(ContributionResult.ACCEPTED, ledger.accept(
            RoadVisited("$user:$road:$time", user, road, time, LocationSourceType.MOCK), time))
    }

    @Test fun contributionIsImmediateButOwnershipChangesOnlyAtNoonAndCanBeCaptured() {
        val ledger = ledger()
        val game = ConquestSettlement(ledger, start)
        contribute(ledger, "r", "red", start + 1)
        contribute(ledger, "b", "blue", start + 1, "ba")
        assertEquals(1, game.summary("r").contributionCount)
        assertEquals(0, game.summary("r").ownedRoadCount)
        assertTrue(game.settleThrough(start + day - 1).isEmpty())
        assertEquals(1, game.settleThrough(start + day).size)
        assertEquals(mapOf("ab" to "red", "ba" to "blue"), game.currentOwners())
        assertEquals(1, game.summary("r").ownedRoadCount)
        assertTrue(game.settleThrough(start + day).isEmpty())
        contribute(ledger, "g1", "green", start + day + 1)
        contribute(ledger, "g2", "green", start + day + 2)
        game.settleThrough(start + 2 * day)
        assertEquals("green", game.currentOwners()["ab"])
        assertEquals(1, game.summary("g1").ownedRoadCount)
        assertEquals(0, game.summary("r").ownedRoadCount)
        assertEquals(0, game.summary("unknown").ownedRoadCount)
    }

    @Test fun tieKeepsOwnerOnlyIfOwnerIsAmongLeadersOtherwiseNeutral() {
        val ledger = ledger()
        val game = ConquestSettlement(ledger, start)
        contribute(ledger, "r", "red", start + 1)
        game.settleThrough(start + day)
        // Old red contribution expires; these two new contributions tie at the next noon.
        contribute(ledger, "r", "red", start + day + 1)
        contribute(ledger, "b", "blue", start + day + 1)
        game.settleThrough(start + 2 * day)
        assertEquals("red", game.currentOwners()["ab"])
        contribute(ledger, "b", "blue", start + 2 * day + 1)
        contribute(ledger, "g", "green", start + 2 * day + 1)
        game.settleThrough(start + 3 * day)
        assertNull(game.currentOwners()["ab"])
        game.settleThrough(start + 5 * day)
        assertNull(game.currentOwners()["ab"])
        assertEquals(2, game.summary("r").contributionCount) // lifetime total survives expiry
    }

    @Test fun recoveryMatchesDailySettlementAndExactNoonEventBelongsToNextRound() {
        val ledger = ledger()
        val daily = ConquestSettlement(ledger, start)
        val recovery = ConquestSettlement(ledger, start)
        contribute(ledger, "r", "red", start + 1)
        contribute(ledger, "b", "blue", start + day) // exact noon excluded from that round
        daily.settleThrough(start + day)
        assertEquals("red", daily.currentOwners()["ab"])
        daily.settleThrough(start + 2 * day)
        assertEquals("blue", daily.currentOwners()["ab"])
        daily.settleThrough(start + 3 * day)
        val recovered = recovery.settleThrough(start + 3 * day)
        assertEquals(3, recovered.size)
        assertEquals("red", recovered[0].owners["ab"])
        assertEquals("blue", recovered[1].owners["ab"])
        assertEquals(daily.currentOwners(), recovery.currentOwners())
        assertNull(recovered[2].owners["ab"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsClockRewind() {
        val game = ConquestSettlement(ledger(), start)
        game.settleThrough(start + 1)
        game.settleThrough(start)
    }
}
