package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RoadCandidateIndexTest {
    private fun catalog() = File("../docs/data/namyang-conquest-full-v1.segments")
        .reader().use(ConquestCatalogReader::read)

    @Test fun indexedDetectorMatchesFullScanOnExpandedRoadsAndExitDepartures() {
        val catalog = catalog()
        val indexed = DirectedRoadDetector(catalog, LocationSourceType.MOCK)
        val baseline = DirectedRoadDetector(catalog, LocationSourceType.MOCK, useSpatialIndex = false)
        var stamp = 1_800_000_000_000L
        val indexedEvents = mutableListOf<RoadVisited>()
        val baselineEvents = mutableListOf<RoadVisited>()
        for (road in catalog.roads.filterIndexed { i, _ -> i % 20 == 0 }) {
            indexed.reset("u"); baseline.reset("u")
            val path = road.points.zipWithNext().flatMap { (a, b) ->
                val count = kotlin.math.ceil(road.distance(a, b) / 15).toInt().coerceAtLeast(1)
                (0 until count).map { i -> ConquestPoint(a.latitude+(b.latitude-a.latitude)*i/count,
                    a.longitude+(b.longitude-a.longitude)*i/count) }
            } + road.points.last()
            val before = road.points[road.points.lastIndex-1]
            val end = road.points.last()
            val distance = road.distance(before, end)
            val exit = ConquestPoint(end.latitude+(end.latitude-before.latitude)*25/distance,
                end.longitude+(end.longitude-before.longitude)*25/distance)
            for (point in path + exit) {
                stamp += 2_000
                val sample = LocationSample("e$stamp", "u", point.latitude, point.longitude, stamp, 3f, LocationSourceType.MOCK)
                indexedEvents += indexed.onSample(sample)
                baselineEvents += baseline.onSample(sample)
            }
        }
        assertTrue("Must exercise completed trips", baselineEvents.isNotEmpty())
        assertEquals(baselineEvents, indexedEvents)
    }

    @Test fun farAwayActiveTripIsRetainedAndNearbyCandidatesAreReduced() {
        val catalog = catalog()
        val index = RoadCandidateIndex(catalog, 62.0)
        val road = catalog.roads.first()
        assertTrue(index.candidates(road.points.first(), emptySet()).size < catalog.roads.size / 4)
        assertTrue(index.candidates(ConquestPoint(0.0, 0.0), setOf(road.id)).any { it.id == road.id })
        assertTrue(index.candidates(ConquestPoint(0.0, 0.0), emptySet()).isEmpty())
    }
}
