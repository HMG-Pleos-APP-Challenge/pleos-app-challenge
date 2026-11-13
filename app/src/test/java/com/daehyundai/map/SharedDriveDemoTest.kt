package com.daehyundai.map

import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.feature.conquest.ConquestDemo
import com.daehyundai.map.feature.conquest.DemoRoute
import com.daehyundai.map.feature.conquest.domain.ConquestCatalogReader
import com.daehyundai.map.feature.exploration.domain.RegionBoundaryReader
import com.daehyundai.map.feature.exploration.domain.RoadCellCatalogReader
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SharedDriveDemoTest {
    private val root = File("src/main/assets")
    private val roads = File(root, "conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private val boundary = File(root, "exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
    private val cells = File(root, "exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)

    @Test fun identicalMemoryRouteFeedsBothFeaturesAndReplayIsDeterministic() = runTest {
        val traces = mutableListOf<List<LocationSample>>()
        repeat(2) {
            val exploration = createDemoExploration("u", boundary, cells)
            val conquest = ConquestDemo("u", roads, listOf("red", "blue"), "red")
            val trace = mutableListOf<LocationSample>()
            val before = conquest.replay(DemoRoute.COMPLETE) { sample ->
                trace.add(sample)
                exploration.feature.onLocation(sample)
            }
            exploration.refresh(false)
            assertEquals(1, before.summary.contributionCount)
            assertEquals(1, exploration.summary.discoveredRegionCount)
            assertTrue(exploration.summary.exploredUnitCount > 0)
            assertEquals(1, conquest.settle().summary.ownedRoadCount)
            traces.add(trace)
        }
        assertEquals(traces[0], traces[1])
        val fresh = createDemoExploration("u", boundary, cells)
        fresh.refresh(false)
        assertEquals(0, fresh.summary.discoveredRegionCount)
        assertEquals(0, fresh.summary.exploredUnitCount)
    }

    @Test fun incompleteConquestRoutesStillPassSamplesToExplorationWithoutClaimingRoads() = runTest {
        for (route in listOf(DemoRoute.MIDWAY, DemoRoute.U_TURN)) {
            val exploration = createDemoExploration("u", boundary, cells)
            val conquest = ConquestDemo("u", roads, listOf("red", "blue"), "red")
            var samples = 0
            val state = conquest.replay(route) { sample -> samples++;exploration.feature.onLocation(sample) }
            exploration.refresh(false)
            assertTrue(samples > 0)
            assertEquals(0, state.summary.contributionCount)
            assertEquals(0, conquest.settle().summary.ownedRoadCount)
        }
    }
}
