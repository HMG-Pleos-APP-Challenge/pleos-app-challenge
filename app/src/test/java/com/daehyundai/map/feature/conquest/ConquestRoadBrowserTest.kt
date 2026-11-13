package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConquestRoadBrowserTest {
    private val catalog = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private val labels = conquestRoadLabels(catalog)
    @Test fun searchPreservesDirectionsAndSupportsTrimmedNameAndLabel() {
        val named = catalog.roads.first { it.name != "이름 없는 도로" }
        assertTrue(filterConquestRoads(catalog, labels, "  ${named.name}  ", "전체", emptyMap(), null).any { it.id == named.id })
        val result = filterConquestRoads(catalog, labels, labels.getValue(named.id), "전체", emptyMap(), null)
        assertTrue(result.any { it.id == named.id })
        assertTrue(filterConquestRoads(catalog, labels, "없는검색결과___", "전체", emptyMap(), null).isEmpty())
    }
    @Test fun ownershipFiltersDoNotTreatMissingTeamAsNeutralOwnership() {
        val road = catalog.roads.first()
        val owners = mapOf(road.id to "red")
        assertTrue(filterConquestRoads(catalog, labels, "", "내 팀", owners, null).isEmpty())
        assertEquals(listOf(road), filterConquestRoads(catalog, labels, "", "내 팀", owners, "red"))
        assertFalse(filterConquestRoads(catalog, labels, "", "중립", owners, "red").any { it.id == road.id })
    }
    @Test fun reverseDirectionsHaveSameGroupAndDifferentLabels() {
        assertEquals(catalog.roads.size, labels.size)
        val road = catalog.roads.first { a -> catalog.roads.any { it.fromNode == a.toNode && it.toNode == a.fromNode } }
        val reverse = catalog.roads.first { it.fromNode == road.toNode && it.toNode == road.fromNode }
        assertEquals(labels.getValue(road.id).substringBefore(" ·"), labels.getValue(reverse.id).substringBefore(" ·"))
        assertNotEquals(labels[road.id], labels[reverse.id])
    }
}
