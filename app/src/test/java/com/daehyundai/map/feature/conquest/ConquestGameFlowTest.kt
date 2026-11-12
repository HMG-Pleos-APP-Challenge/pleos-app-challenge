package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real catalog coordinates through detection, persisted contributions, settlement and display queries. */
class ConquestGameFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalog = File("src/main/assets/conquest/namyang-legacy.segments").reader().use(ConquestCatalogReader::read)

    @Test fun directionalCompetitionSurvivesReplayRestartTieAndExpiry() = runTest {
        val start = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        val day = 86_400_000L
        var now = start
        val teams = listOf("red", "blue")
        val file = File(folder.root, "flow.bin")
        fun open() = LocalConquestFeature(teams.toSet(), catalog.roads.map { it.id }.toSet(),
            catalog.version, LocationSourceType.MOCK, FileConquestStore(file), { now })
        var feature = open()
        val pipeline = ConquestLocationPipeline(feature, catalog, LocationSourceType.MOCK, clock = { now })
        for (user in listOf("r1", "r2", "r3", "r4", "b1", "b2", "b3"))
            assertTrue(pipeline.selectTeam(user, if (user.startsWith("r")) "red" else "blue"))
        // User observations are independent; delivery clocks stay monotonic while route times can overlap.
        suspend fun replayAtCurrentTime(user: String, offset: Long, reverse: Boolean = false): List<LocationSample> {
            val previousClock = now
            val roads = if (reverse) ConquestCatalog(catalog.version, catalog.roads.drop(1) + catalog.roads.first()) else catalog
            val trace = mutableListOf<LocationSample>()
            var firstSample = true
            ConquestDemo(user, roads, teams, if (user.startsWith("r")) "red" else "blue", roadId = roads.roads.first().id)
                .replay(DemoRoute.COMPLETE) { original ->
                    val sample = original.copy(eventId = "$user:$offset:$reverse:${original.eventId}",
                        timestampUtcMs = original.timestampUtcMs + offset)
                    if (firstSample) {
                        // Observe the entrance before moving; a long gap invalidates the previous trip.
                        val entrance = sample.copy(eventId = "${sample.eventId}:entrance",
                            timestampUtcMs = sample.timestampUtcMs - 2_000)
                        now = maxOf(previousClock, entrance.timestampUtcMs)
                        pipeline.onSample(entrance)
                        firstSample = false
                    }
                    now = maxOf(previousClock, sample.timestampUtcMs)
                    pipeline.onSample(sample)
                    trace.add(sample)
                }
            return trace
        }
        val trace = replayAtCurrentTime("r1", 0)
        trace.forEach { pipeline.onSample(it) }
        assertEquals(1, feature.readSummary("r1").contributionCount)
        replayAtCurrentTime("r1", 120_000, true)
        replayAtCurrentTime("r2", 0)
        replayAtCurrentTime("b1", 0)
        assertEquals(0, feature.readSummary("r1").ownedRoadCount)
        now = start + 3 * 3_600_000L
        val forward = catalog.roads[0].id
        val reverse = catalog.roads[1].id
        val first = feature.roadDetails("r1")
        assertEquals(mapOf("red" to 2, "blue" to 1), first.getValue(forward).settlement!!.scores)
        assertEquals(2, feature.readSummary("r1").ownedRoadCount)
        assertEquals(1, first.getValue(reverse).contributionCount)
        assertEquals(first, open().roadDetails("r1"))
        replayAtCurrentTime("b2", 2 * day)
        replayAtCurrentTime("b3", 2 * day)
        now = start + 2 * day + 3 * 3_600_000L
        assertEquals(SettlementReason.TAKEN_OVER, feature.roadDetails("r1").getValue(forward).settlement!!.reason)
        assertNull(feature.owners()[reverse])
        replayAtCurrentTime("r3", 3 * day)
        replayAtCurrentTime("r4", 3 * day)
        now = start + 3 * day + 3 * 3_600_000L
        val tied = feature.roadDetails("r1").getValue(forward).settlement!!
        assertEquals(mapOf("red" to 2, "blue" to 2), tied.scores)
        assertEquals(SettlementReason.TIE_RETAINED, tied.reason)
        assertEquals("blue", tied.ownerTeamId)
        now = start + 6 * day
        val beforeRestart = feature.roadDetails("r1")
        feature = open()
        assertEquals(beforeRestart, feature.roadDetails("r1"))
        assertTrue(feature.owners().values.all { it == null })
        assertEquals(2, feature.readSummary("r1").contributionCount)
        assertEquals(SettlementReason.NO_CONTRIBUTIONS, feature.roadDetails("r1").getValue(forward).settlement!!.reason)
        assertEquals(0, pipeline.pendingCount)
    }
}
