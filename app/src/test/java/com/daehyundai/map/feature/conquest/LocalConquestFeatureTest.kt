package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.ContributionResult
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalConquestFeatureTest {
    @get:Rule val folder = TemporaryFolder()
    private val start = Instant.parse("2026-11-09T03:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private fun feature(store: ConquestStore, clock: () -> Long, source: LocationSourceType = LocationSourceType.MOCK) =
        LocalConquestFeature(setOf("red", "blue", "green"), setOf("ab", "ba"), "roads-v1", source, store, clock)

    @Test fun dailyDetailsFollowKstNoonMidnightAndPreserveLifetimeCount() {
        val noon = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        var now = noon - 1000
        val store = FileConquestStore(File(folder.root, "daily.bin"))
        val game = feature(store, { now })
        game.selectTeam("u", "red")
        fun event(id: String, road: String = "ab") = RoadVisited(id, "u", road, now, LocationSourceType.MOCK)
        assertEquals(ContributionResult.ACCEPTED, game.accept(event("am")))
        assertEquals(ContributionResult.DUPLICATE_HALF_DAY, game.accept(event("am-duplicate")))
        val morning = game.roadDetails("u").getValue("ab")
        assertEquals(java.time.LocalDate.parse("2026-11-11"), morning.dateKst)
        assertTrue(morning.morningAccepted)
        assertFalse(morning.afternoonAccepted)
        assertFalse(morning.currentAfternoon)
        now = noon
        assertEquals(ContributionResult.ACCEPTED, game.accept(event("pm")))
        val afternoon = game.roadDetails("u").getValue("ab")
        assertTrue(afternoon.morningAccepted && afternoon.afternoonAccepted && afternoon.currentAfternoon)
        assertEquals(2, afternoon.contributionCount)
        assertFalse(game.roadDetails("u").getValue("ba").afternoonAccepted)
        assertFalse(game.roadDetails("other").getValue("ab").morningAccepted)
        now = Instant.parse("2026-11-11T14:59:59.999Z").toEpochMilli()
        assertTrue(game.roadDetails("u").getValue("ab").afternoonAccepted)
        now++ // KST midnight, without any new GPS sample.
        val restarted = feature(store, { now })
        val nextDay = restarted.roadDetails("u").getValue("ab")
        assertEquals(java.time.LocalDate.parse("2026-11-12"), nextDay.dateKst)
        assertFalse(nextDay.morningAccepted || nextDay.afternoonAccepted || nextDay.currentAfternoon)
        assertEquals(2, nextDay.contributionCount)
    }

    @Test fun failedWriteAndNoTeamNeverMarkHalfDayAsAccepted() {
        var now = start + 1000
        var failWrite = false
        var saved: ConquestRecord? = null
        val store = object : ConquestStore {
            override fun read() = saved
            override fun write(record: ConquestRecord) {
                if (failWrite) throw IOException("Unavailable")
                saved = record
            }
        }
        val game = feature(store, { now })
        val event = RoadVisited("e", "u", "ab", now, LocationSourceType.MOCK)
        assertEquals(ContributionResult.NO_TEAM, game.accept(event))
        assertFalse(game.roadDetails("u").getValue("ab").afternoonAccepted)
        game.selectTeam("u", "red")
        now++
        failWrite = true
        try { game.accept(event.copy(timestampUtcMs = now)); fail("Expected write failure") } catch (_: IOException) { }
        assertFalse(game.roadDetails("u").getValue("ab").afternoonAccepted)
        assertEquals(0, game.roadDetails("u").getValue("ab").contributionCount)
    }

    @Test fun directionDetailsRetainUserCountsAndCompletedNoonAfterRestart() {
        var now = start + 1000
        val store = FileConquestStore(File(folder.root, "details.bin"))
        val game = feature(store, { now })
        game.selectTeam("u", "red")
        game.selectTeam("other", "blue")
        now++
        assertEquals(ContributionResult.ACCEPTED, game.accept(RoadVisited("a", "u", "ab", now, LocationSourceType.MOCK)))
        assertEquals(ContributionResult.ACCEPTED, game.accept(RoadVisited("b", "other", "ba", now, LocationSourceType.MOCK)))
        assertNull(game.roadDetails("u").getValue("ab").settledUtcMs)
        assertNull(game.roadDetails("u").getValue("ab").settlement)
        assertEquals(1, game.roadDetails("u").getValue("ab").contributionCount)
        assertEquals(0, game.roadDetails("u").getValue("ba").contributionCount)
        now = start + day
        val restored = feature(store, { now })
        val details = restored.roadDetails("u")
        assertEquals(now, details.getValue("ab").settledUtcMs)
        assertEquals("red", details.getValue("ab").ownerTeamId)
        assertEquals("blue", details.getValue("ba").ownerTeamId)
        assertEquals(game.roadDetails("u"), details)
        assertEquals(mapOf("red" to 1), details.getValue("ab").settlement!!.scores)
        assertEquals(mapOf("blue" to 1), details.getValue("ba").settlement!!.scores)
        assertEquals(restored.readSummary("u").contributionCount, details.values.sumOf { it.contributionCount })
        now += day * 3
        assertNull(restored.roadDetails("u").getValue("ab").ownerTeamId)
        assertEquals(1, restored.roadDetails("u").getValue("ab").contributionCount)
    }

    @Test fun contractSurvivesRestartAndRecoversMissedNoonsWithoutDuplicateContribution() = runTest {
        var now = start
        val store = FileConquestStore(File(folder.root, "mock.bin"))
        val first = feature(store, { now })
        first.selectTeam("u", "red")
        now++
        val event = RoadVisited("e", "u", "ab", now, LocationSourceType.MOCK)
        first.onRoadVisited(event)
        assertEquals(1, first.getSummary("u").contributionCount)
        assertEquals(0, first.getSummary("u").ownedRoadCount)
        now = start + day
        val second = feature(store, { now })
        assertEquals(1, second.getSummary("u").ownedRoadCount)
        assertEquals(ContributionResult.DUPLICATE_EVENT, second.accept(event))
        assertFalse(second.selectTeam("u", "blue"))
        now = start + day * 4
        val third = feature(store, { now })
        assertEquals(0, third.getSummary("u").ownedRoadCount)
        assertEquals(1, third.getSummary("u").contributionCount)
        assertEquals(ContributionResult.LATE, third.accept(event.copy(eventId = "late")))
    }

    @Test fun failedPersistenceNeverPublishesAnAcceptedContribution() = runTest {
        var failing = false
        var saved: ConquestRecord? = null
        val store = object : ConquestStore {
            override fun read() = saved
            override fun write(record: ConquestRecord) {
                if (failing) throw IOException("Disk unavailable")
                saved = record
            }
        }
        var now = start
        val game = feature(store, { now })
        game.selectTeam("u", "red")
        now++
        val event = RoadVisited("e", "u", "ab", now, LocationSourceType.MOCK)
        failing = true
        try { game.accept(event); fail("Expected write failure") } catch (_: IOException) { }
        assertEquals(0, game.getSummary("u").contributionCount)
        failing = false
        assertEquals(ContributionResult.ACCEPTED, game.accept(event))
        assertEquals(1, game.getSummary("u").contributionCount)
    }

    @Test fun mismatchedSourceAndInvalidSavedEventsDoNotResetExistingFiles() {
        val file = File(folder.root, "record.bin")
        val store = FileConquestStore(file)
        store.write(ConquestRecord("roads-v1", LocationSourceType.MOCK, start, start))
        val before = file.readBytes()
        try { feature(store, { start }, LocationSourceType.PLEOS); fail("Expected mismatch") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, file.readBytes())
        store.write(ConquestRecord("roads-v1", LocationSourceType.MOCK, start, start,
            events = listOf(RoadVisited("e", "unknown", "ab", start, LocationSourceType.MOCK))))
        try { feature(store, { start }); fail("Expected invalid event") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, store.read()!!.events.size)
    }
}
