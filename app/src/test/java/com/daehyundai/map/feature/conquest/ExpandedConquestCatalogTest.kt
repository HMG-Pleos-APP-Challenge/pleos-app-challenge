package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Reads the offline expansion with the production reader without switching runtime assets. */
class ExpandedConquestCatalogTest {
    private fun expanded() = File("../docs/data/namyang-conquest-full-v1.segments")
        .reader().use(ConquestCatalogReader::read)

    @Test fun runtimeAssetMatchesValidatedExpansion() {
        val runtime = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
        val expected = expanded()
        assertEquals(expected.version, runtime.version)
        assertEquals(expected.roads.map { it.id to it.points }, runtime.roads.map { it.id to it.points })
    }

    @Test fun productionReaderAcceptsAllExpandedDirections() {
        val catalog = expanded()
        assertEquals("namyang-conquest-osm-20261108-full-v1", catalog.version)
        assertEquals(1453, catalog.roads.size)
        assertEquals(catalog.roads.size, catalog.roads.map { it.id }.toSet().size)
        assertTrue(catalog.roads.all { it.lengthMeters.isFinite() && it.lengthMeters >= 50.0 })
        assertTrue(catalog.roads.any { it.lengthMeters > 450.0 })
        assertTrue(catalog.roads.all { it.fromNode != it.toNode && it.name.isNotBlank() })
    }

    @Test fun legacyDirectionalIdentityAndGeometryArePreserved() {
        val legacy = File("src/main/assets/conquest/namyang-legacy.segments")
            .reader().use(ConquestCatalogReader::read)
        val next = expanded().roads.associateBy { it.id }
        assertEquals(6, legacy.roads.size)
        for (old in legacy.roads) {
            val road = requireNotNull(next[old.id]) { "Missing legacy road ${old.id}" }
            assertEquals(old.fromNode, road.fromNode)
            assertEquals(old.toNode, road.toNode)
            assertEquals(old.points, road.points)
            assertEquals(old.lengthMeters, road.lengthMeters, 0.0)
        }
    }

    @Test fun bidirectionalGeometryIsReversedAndOneWayRoadsAreNotDuplicated() {
        val groups = expanded().roads.groupBy { road ->
            minOf(road.points.joinToString(";"), road.points.reversed().joinToString(";"))
        }
        assertEquals(801, groups.size)
        assertEquals(149, groups.values.count { it.size == 1 })
        assertEquals(652, groups.values.count { it.size == 2 })
        for (pair in groups.values.filter { it.size == 2 }) {
            assertNotEquals(pair[0].id, pair[1].id)
            assertEquals(pair[0].fromNode, pair[1].toNode)
            assertEquals(pair[0].toNode, pair[1].fromNode)
            assertEquals(pair[0].points.reversed(), pair[1].points)
        }
    }
}
