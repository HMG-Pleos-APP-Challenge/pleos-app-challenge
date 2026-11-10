package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConquestLocationPipelineTest {
    private val catalog = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private val base = Instant.parse("2026-11-10T04:00:00Z").toEpochMilli()
    private class MemoryStore : ConquestStore {
        var value: ConquestRecord? = null
        var fail = false
        override fun read() = value
        override fun write(record: ConquestRecord) {
            if (fail) throw IOException("Disk unavailable")
            value = record
        }
    }

    private fun samples(road: DirectedRoad, offset: Long = 0): List<LocationSample> {
        val points = road.points.zipWithNext().flatMap { (a, b) ->
            (0..4).map { step ->
                val f = step / 5.0
                ConquestPoint(a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f)
            }
        } + road.points.last()
        val a = road.points[road.points.lastIndex - 1]; val b = road.points.last()
        val extension = ConquestPoint(b.latitude + (b.latitude - a.latitude) * .2,
            b.longitude + (b.longitude - a.longitude) * .2)
        return (listOf(points.first()) + points + extension).mapIndexed { i, p -> LocationSample("$offset:$i", "u", p.latitude, p.longitude,
            base + offset + i * 2000, 3f, LocationSourceType.MOCK) }
    }

    @Test fun realNamyangGeometryFlowsThroughDetectorLedgerSummaryAndRestart() = runTest {
        var now = base
        val store = MemoryStore()
        fun feature() = LocalConquestFeature(setOf("red", "blue", "green"), catalog.roads.map { it.id }.toSet(),
            catalog.version, LocationSourceType.MOCK, store, { now })
        val game = feature()
        val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK)
        pipeline.selectTeam("u", "red")
        val road = catalog.roads.first()
        val results = samples(road).flatMap { now = it.timestampUtcMs; pipeline.onSample(it) }
        assertEquals(ContributionResult.ACCEPTED, results.single().result)
        assertEquals(road.id, results.single().event.roadSegmentId)
        assertEquals(1, game.getSummary("u").contributionCount)
        // Same direction, new event IDs, same half-day: no extra contribution.
        val duplicate = samples(road, 60_000).flatMap { now = it.timestampUtcMs; pipeline.onSample(it) }
        assertEquals(ContributionResult.DUPLICATE_HALF_DAY, duplicate.single().result)
        now = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        assertEquals(1, feature().getSummary("u").ownedRoadCount)
    }

    @Test fun selectionMidRoadDoesNotRetroactivelyArmTrip() = runTest {
        var now = base
        val game = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(), catalog.version,
            LocationSourceType.MOCK, MemoryStore(), { now })
        val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK)
        val samples = samples(catalog.roads.first())
        val results = samples.flatMapIndexed { i, sample ->
            now = sample.timestampUtcMs
            if (i == 4) pipeline.selectTeam("u", "red")
            pipeline.onSample(sample)
        }
        assertTrue(results.isEmpty())
        assertEquals(0, game.getSummary("u").contributionCount)
    }

    @Test fun completedVisitCanBeRetriedAfterWriteFailureWithoutReplayingTrip() = runTest {
        var now = base
        val store = MemoryStore()
        val game = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(), catalog.version,
            LocationSourceType.MOCK, store, { now })
        val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK)
        pipeline.selectTeam("u", "red")
        val samples = samples(catalog.roads.first())
        samples.dropLast(1).forEach { now = it.timestampUtcMs; pipeline.onSample(it) }
        store.fail = true
        now = samples.last().timestampUtcMs
        try { pipeline.onSample(samples.last()); fail("Expected write failure") } catch (_: IOException) { }
        assertEquals(0, game.getSummary("u").contributionCount)
        store.fail = false
        assertEquals(ContributionResult.ACCEPTED, pipeline.onSample(samples.last()).single().result)
        assertEquals(1, game.getSummary("u").contributionCount)
    }
}
