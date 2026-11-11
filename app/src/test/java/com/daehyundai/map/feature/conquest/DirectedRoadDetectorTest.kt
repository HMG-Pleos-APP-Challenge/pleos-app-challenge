package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import org.junit.Assert.*
import org.junit.Test

class DirectedRoadDetectorTest {
    private val a = ConquestPoint(37.2, 126.8)
    private val b = ConquestPoint(37.2, 126.802)
    private fun catalog() = ConquestCatalog("v1", listOf(
        DirectedRoad("ab", "road", "a", "b", listOf(a, b)),
        DirectedRoad("ba", "road", "b", "a", listOf(b, a))))
    private fun detector() = DirectedRoadDetector(catalog(), LocationSourceType.MOCK)
    private fun sample(index: Int, fraction: Double, user: String = "u") = LocationSample("e$index", user,
        a.latitude, a.longitude + (b.longitude - a.longitude) * fraction,
        1_000_000L + index * 2000, 3f, LocationSourceType.MOCK)
    private val forward = listOf(0.0, .2, .4, .6, .8, 1.0, 1.06)

    @Test fun completeForwardAndReverseTripsEmitDifferentDirectedIds() {
        val detector = detector()
        val events = forward.flatMapIndexed { i, fraction -> detector.onSample(sample(i, fraction)) }
        assertEquals(listOf("ab"), events.map { it.roadSegmentId })
        val reverse = forward.flatMapIndexed { i, fraction -> detector.onSample(sample(i + 10, 1 - fraction, "v")) }
        assertEquals(listOf("ba"), reverse.map { it.roadSegmentId })
        assertEquals("v", reverse.single().userId)
    }

    @Test fun middleStartAndMerelyReachingExitNeverCompleteATrip() {
        val middle = detector()
        assertTrue(forward.drop(2).flatMapIndexed { i, f -> middle.onSample(sample(i, f)) }.isEmpty())
        val entrance = detector()
        assertTrue(forward.dropLast(1).flatMapIndexed { i, f -> entrance.onSample(sample(i, f)) }.isEmpty())
    }

    @Test fun uTurnDeparturePoorAccuracyGapAndJumpCancelIncompleteTrips() {
        val cases = listOf<(LocationSample) -> LocationSample>(
            { it.copy(longitude = a.longitude + .01) },
            { it.copy(accuracyMeters = null) },
            { it.copy(accuracyMeters = 50f) },
            { it.copy(timestampUtcMs = it.timestampUtcMs + 60_000) },
            { it.copy(latitude = Double.NaN) },
            { it.copy(source = LocationSourceType.PLEOS) },
        )
        for (change in cases) {
            val detector = detector()
            val result = forward.flatMapIndexed { i, fraction ->
                detector.onSample(if (i == 3) change(sample(i, fraction)) else sample(i, fraction))
            }
            assertTrue(result.isEmpty())
        }
        val uturn = detector()
        val fractions = listOf(0.0, .2, .4, .2, .4, .6, .8, 1.06)
        assertTrue(fractions.flatMapIndexed { i, f -> uturn.onSample(sample(i, f)) }.isEmpty())
    }

    @Test fun duplicatesAreIgnoredButOutOfOrderSamplesCancelTheTrip() {
        val detector = detector()
        val result = forward.flatMapIndexed { i, f ->
            val sample = sample(i, f)
            detector.onSample(sample) + detector.onSample(sample)
        }
        assertEquals(1, result.size)
        val reordered = detector()
        reordered.onSample(sample(0, 0.0))
        reordered.onSample(sample(2, .2))
        reordered.onSample(sample(1, .1))
        assertTrue(forward.drop(2).flatMapIndexed { i, f -> reordered.onSample(sample(i + 3, f)) }.isEmpty())
    }

    @Test fun ambiguousOverlappingRoadsDoNotDoubleAward() {
        val road = DirectedRoad("another", "overlap", "a", "b", listOf(a, b))
        val detector = DirectedRoadDetector(ConquestCatalog("v1", catalog().roads + road), LocationSourceType.MOCK)
        assertTrue(forward.flatMapIndexed { i, f -> detector.onSample(sample(i, f)) }.isEmpty())
    }

    @Test fun turnAfterObservedExitCompletesButTurnBackIntoRoadDoesNot() {
        val detector = detector()
        forward.dropLast(1).forEachIndexed { i, f -> detector.onSample(sample(i, f)) }
        val turn = sample(7, 1.0).copy(latitude = a.latitude + .0002)
        assertEquals("ab", detector.onSample(turn).single().roadSegmentId)
        val back = detector()
        forward.dropLast(1).forEachIndexed { i, f -> back.onSample(sample(i, f)) }
        assertTrue(back.onSample(sample(7, .85)).isEmpty())
    }
}
