package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.ConquestCatalogReader
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConquestDemoTest {
    private val catalog = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private fun demo() = ConquestDemo("tester", catalog, listOf("team-red", "team-blue"), "team-red")

    @Test fun completeRouteOnlyChangesOwnershipAfterNoon() = runTest {
        val demo = demo()
        val before = demo.replay(DemoRoute.COMPLETE)
        assertEquals(1, before.summary.contributionCount)
        assertEquals(0, before.summary.ownedRoadCount)
        assertTrue(before.owners.values.all { it == null })
        val after = demo.settle()
        assertEquals(1, after.summary.contributionCount)
        assertEquals(1, after.summary.ownedRoadCount)
        assertEquals(mapOf(catalog.roads.first().id to "team-red"), after.owners.filterValues { it != null })
        assertEquals(after, demo.settle())
    }

    @Test fun competitionStagesUseIsolatedMockContributionsAndNeverChangeMyTeamOrLifetimeTotal() = runTest {
        for (ownTeam in listOf("team-red", "team-blue")) {
            val demo = ConquestDemo("tester", catalog, listOf("team-red", "team-blue", "third"), ownTeam)
            val road = catalog.roads.first().id
            demo.replay(DemoRoute.COMPLETE)
            assertFalse(demo.canCompete)
            demo.settle()
            assertTrue(demo.canCompete)
            val opponent = if (ownTeam == "team-red") "team-blue" else "team-red"
            val takeover = demo.advanceCompetition()
            assertEquals(opponent, takeover.owners[road])
            assertEquals(mapOf(ownTeam to 1, opponent to 2), takeover.roadDetails.getValue(road).settlement!!.scores)
            assertEquals(com.daehyundai.map.feature.conquest.domain.SettlementReason.TAKEN_OVER,
                takeover.roadDetails.getValue(road).settlement!!.reason)
            val tied = demo.advanceCompetition()
            assertEquals(mapOf(ownTeam to 2, opponent to 2), tied.roadDetails.getValue(road).settlement!!.scores)
            assertEquals(opponent, tied.owners[road])
            assertEquals(com.daehyundai.map.feature.conquest.domain.SettlementReason.TIE_RETAINED,
                tied.roadDetails.getValue(road).settlement!!.reason)
            val expired = demo.advanceCompetition()
            assertTrue(expired.owners.values.all { it == null })
            assertEquals(ownTeam, expired.summary.teamId)
            assertEquals(1, expired.summary.contributionCount)
            assertEquals(0, expired.summary.ownedRoadCount)
            assertFalse(demo.canCompete)
            assertEquals(expired, demo.advanceCompetition())
            assertEquals(expired, demo.settle()) // no virtual clock rewind on repeated settlement
            val fresh = ConquestDemo("tester", catalog, listOf("team-red", "team-blue"), ownTeam)
            assertEquals(1, fresh.replay(DemoRoute.COMPLETE).summary.contributionCount)
            assertEquals(0, fresh.competitionStep)
        }
    }

    @Test fun singleTeamOrIncompleteRouteCannotAdvanceCompetition() = runTest {
        val single = ConquestDemo("tester", catalog, listOf("only"), "only")
        single.replay(DemoRoute.COMPLETE)
        val settled = single.settle()
        assertFalse(single.canCompete)
        assertEquals(settled, single.advanceCompetition())
        val incomplete = demo()
        incomplete.replay(DemoRoute.U_TURN)
        val empty = incomplete.settle()
        assertFalse(incomplete.canCompete)
        assertEquals(empty, incomplete.advanceCompetition())
    }

    @Test fun incompleteRoutesCannotContributeOrClaimRoads() = runTest {
        for (route in listOf(DemoRoute.MIDWAY, DemoRoute.U_TURN)) {
            val demo = demo()
            assertEquals(route.name, 0, demo.replay(route).summary.contributionCount)
            assertEquals(route.name, 0, demo.settle().summary.ownedRoadCount)
        }
    }
}
