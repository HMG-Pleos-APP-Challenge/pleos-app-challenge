package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExpandedRegionalRoutesTest {
    @Test fun separatedRegionalRoutesCompleteAndRejectMidwayAndUTurn() = runTest {
        val catalog = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
        val candidates = catalog.roads.filter { it.lengthMeters in 100.0..250.0 }.sortedBy { it.points.first().latitude }
        val roads = listOf(candidates.first(), candidates[candidates.size/2], candidates.last())
        assertTrue(roads.first().distance(roads.first().points.first(), roads.last().points.first()) > 1000)
        for (road in roads) {
            val demo = ConquestDemo("regional", catalog, listOf("red", "blue"), "red", road.id)
            val completed = demo.replay(DemoRoute.COMPLETE)
            assertEquals("Selected road must be credited: ${road.id}", 1, completed.roadDetails.getValue(road.id).contributionCount)
            val settled = demo.settle()
            assertEquals("red", settled.owners[road.id])
            for (invalid in listOf(DemoRoute.MIDWAY, DemoRoute.U_TURN)) {
                val rejected = ConquestDemo("regional", catalog, listOf("red", "blue"), "red", road.id).replay(invalid)
                assertEquals("Invalid route credited: ${road.id}", 0, rejected.roadDetails.getValue(road.id).contributionCount)
            }
        }
    }
}
