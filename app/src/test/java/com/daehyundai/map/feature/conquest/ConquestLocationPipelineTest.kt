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
    @Test fun allowedFutureVisitsWaitThenKeepTheirOriginalNoonBoundary() = runTest {
        val noon = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        for (offset in listOf(1_000L, 5_000L)) {
            var now = noon - 500
            val store = MemoryStore()
            val game = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(),
                catalog.version, LocationSourceType.MOCK, store, { now })
            game.selectTeam("u", "red")
            val event = RoadVisited("future-$offset", "u", catalog.roads.first().id,
                now + offset, LocationSourceType.MOCK)
            val journal = MemoryPendingVisitsStore().also { it.write(listOf(event)) }
            val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK,
                pendingStore = journal, clock = { now })
            assertTrue(pipeline.retryPending().isEmpty())
            assertEquals(1, pipeline.pendingCount)
            assertEquals(event, journal.read().single())
            assertEquals(ContributionResult.FUTURE, pipeline.lastResult?.result)
            now = event.timestampUtcMs
            assertEquals(ContributionResult.ACCEPTED, pipeline.retryPending().single().result)
            assertEquals(1, game.getSummary("u").contributionCount)
            assertEquals(0, game.getSummary("u").ownedRoadCount)
            now = noon + 24 * 60 * 60 * 1000
            assertEquals(1, game.getSummary("u").ownedRoadCount)
            assertTrue(pipeline.retryPending().isEmpty())
        }
    }

    @Test fun excessiveFutureAndLateVisitsExposeFinalRejectionWithoutCounting() = runTest {
        val noon = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        var now = noon - 10_000
        val game = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(),
            catalog.version, LocationSourceType.MOCK, MemoryStore(), { now })
        game.selectTeam("u", "red")
        val journal = MemoryPendingVisitsStore()
        journal.write(listOf(RoadVisited("too-future", "u", catalog.roads.first().id, now + 5_001, LocationSourceType.MOCK)))
        val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK,
            pendingStore = journal, clock = { now })
        assertEquals(ContributionResult.FUTURE, pipeline.retryPending().single().result)
        assertEquals(0, pipeline.pendingCount)
        journal.write(listOf(RoadVisited("late", "u", catalog.roads.first().id, noon - 1, LocationSourceType.MOCK)))
        now = noon + 1
        val restored = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK,
            pendingStore = journal, clock = { now })
        assertEquals(ContributionResult.LATE, restored.retryPending().single().result)
        assertEquals(ContributionResult.LATE, restored.lastResult?.result)
        assertEquals(0, game.getSummary("u").contributionCount)
    }
    private val catalog = File("src/main/assets/conquest/namyang-legacy.segments").reader().use(ConquestCatalogReader::read)
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

    @Test fun durablePendingRecoversWithNoNewSampleAfterFeatureWriteFailure() = runTest {
        var now = base
        val folder = java.nio.file.Files.createTempDirectory("conquest-recovery").toFile()
        try {
            val store = MemoryStore()
            fun game() = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(),
                catalog.version, LocationSourceType.MOCK, store, { now })
            fun journal() = FilePendingVisitsStore(File(folder, "pending.bin"), catalog.version, LocationSourceType.MOCK, "u")
            val firstGame = game()
            val first = ConquestLocationPipeline(firstGame, catalog, LocationSourceType.MOCK, pendingStore = journal())
            first.selectTeam("u", "red")
            val fixes = samples(catalog.roads.first())
            fixes.dropLast(1).forEach { now = it.timestampUtcMs; first.onSample(it) }
            store.fail = true
            now = fixes.last().timestampUtcMs
            try { first.onSample(fixes.last()); fail("Expected failure") } catch (_: IOException) { }
            assertEquals(1, journal().read().size)
            store.fail = false
            val restoredGame = game()
            val restored = ConquestLocationPipeline(restoredGame, catalog, LocationSourceType.MOCK, pendingStore = journal())
            assertEquals(ContributionResult.ACCEPTED, restored.retryPending().single().result)
            assertEquals(1, restoredGame.getSummary("u").contributionCount)
            assertTrue(journal().read().isEmpty())
        } finally { folder.deleteRecursively() }
    }

    @Test fun journalRemovalFailureReplaysAsDuplicateAfterRestart() = runTest {
        var now = base
        val store = MemoryStore()
        val journal = MemoryPendingVisitsStore()
        val failingJournal = object : PendingVisitsStore {
            override fun read() = journal.read()
            override fun write(events: List<RoadVisited>) {
                if (events.isEmpty()) throw IOException("Cannot acknowledge")
                journal.write(events)
            }
        }
        fun game() = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(),
            catalog.version, LocationSourceType.MOCK, store, { now })
        val first = ConquestLocationPipeline(game(), catalog, LocationSourceType.MOCK, pendingStore = failingJournal)
        first.selectTeam("u", "red")
        try { samples(catalog.roads.first()).forEach { now = it.timestampUtcMs; first.onSample(it) }; fail("Expected failure") }
        catch (_: IOException) { }
        val restoredGame = game()
        val restored = ConquestLocationPipeline(restoredGame, catalog, LocationSourceType.MOCK, pendingStore = journal)
        assertEquals(ContributionResult.DUPLICATE_EVENT, restored.retryPending().single().result)
        assertEquals(1, restoredGame.getSummary("u").contributionCount)
        assertEquals(0, restored.pendingCount)
    }

    @Test fun failedJournalWriteRetainsMemoryAndDoesNotPublishContribution() = runTest {
        var now = base
        val game = LocalConquestFeature(setOf("red"), catalog.roads.map { it.id }.toSet(), catalog.version,
            LocationSourceType.MOCK, MemoryStore(), { now })
        var failing = true
        val journal = object : PendingVisitsStore {
            override fun read() = emptyList<RoadVisited>()
            override fun write(events: List<RoadVisited>) { if (failing) throw IOException("Journal unavailable") }
        }
        val pipeline = ConquestLocationPipeline(game, catalog, LocationSourceType.MOCK, pendingStore = journal)
        pipeline.selectTeam("u", "red")
        try { samples(catalog.roads.first()).forEach { now = it.timestampUtcMs; pipeline.onSample(it) }; fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(1, pipeline.pendingCount)
        assertEquals(0, game.getSummary("u").contributionCount)
        failing = false
        pipeline.retryPending()
        assertEquals(0, pipeline.pendingCount)
        assertEquals(1, game.getSummary("u").contributionCount)
    }
}
