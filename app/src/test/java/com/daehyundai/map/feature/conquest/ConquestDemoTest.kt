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

    @Test fun incompleteRoutesCannotContributeOrClaimRoads() = runTest {
        for (route in listOf(DemoRoute.MIDWAY, DemoRoute.U_TURN)) {
            val demo = demo()
            assertEquals(route.name, 0, demo.replay(route).summary.contributionCount)
            assertEquals(route.name, 0, demo.settle().summary.ownedRoadCount)
        }
    }
}
