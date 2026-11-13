package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConquestCatalogTest {
    @Test fun namyangCatalogHasThreeRoadsAndIndependentReverseDirections() {
        val catalog = File("src/main/assets/conquest/namyang-legacy.segments").reader().use(ConquestCatalogReader::read)
        assertEquals(6, catalog.roads.size)
        assertEquals(3, catalog.roads.groupBy { setOf(it.fromNode, it.toNode) }.size)
        for (road in catalog.roads) {
            val reverse = catalog.roads.single { it.fromNode == road.toNode && it.toNode == road.fromNode }
            assertEquals(road.points.reversed(), reverse.points)
            assertTrue(road.lengthMeters in 100.0..450.0)
            assertEquals(0.0, road.locate(road.points.first()).progressMeters, 0.01)
            assertEquals(road.lengthMeters, road.locate(road.points.last()).progressMeters, 0.01)
        }
    }

    @Test fun localProjectionMeasuresMetresAndDetectsExitCrossing() {
        val road = DirectedRoad("ab", "test", "a", "b", listOf(ConquestPoint(37.2, 126.8), ConquestPoint(37.2, 126.802)))
        assertTrue(road.lengthMeters in 175.0..180.0)
        assertEquals(road.lengthMeters / 2, road.locate(ConquestPoint(37.2, 126.801)).progressMeters, .01)
        assertTrue(road.locate(ConquestPoint(37.2, 126.8021)).progressMeters > road.lengthMeters)
        assertTrue(road.locate(ConquestPoint(37.201, 126.801)).distanceMeters > 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedCatalogIsNotSilentlyAccepted() {
        ConquestCatalogReader.read("PLEOS_CONQUEST_SEGMENTS_V1\nv1\nbad".reader())
    }
}
