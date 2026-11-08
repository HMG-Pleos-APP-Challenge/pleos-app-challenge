package com.daehyundai.map

import com.daehyundai.map.core.data.*
import com.daehyundai.map.core.location.*
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.explorationRate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MockIntegrationTest {
    @Test fun replayHasStableInputAndDoesNotDoubleCountAcrossBothConsumers() = runTest {
        val source = MockLocationSource("user-a")
        assertEquals(source.samples().toList(), source.samples().toList())
        val conquest = PreviewConquestFeature()
        val exploration = PreviewExplorationFeature()
        repeat(2) {
            source.samples().collect { sample ->
                val batch = MockVisitDetector().onSample(sample)
                batch.roads.forEach { conquest.onRoadVisited(it) }
                batch.regions.forEach { exploration.onRegionDiscovered(it) }
            }
        }
        assertEquals(3, conquest.getSummary("user-a").contributionCount)
        assertEquals(2, exploration.getSummary("user-a").discoveredRegionCount)
        assertEquals(0, exploration.getSummary("user-b").discoveredRegionCount)
    }
    @Test fun invalidOrRealInputsCannotAccidentallyProduceMockVisits() = runTest {
        val sample = MockLocationSource("user-a").samples().toList().first()
        for (invalid in listOf(sample.copy(latitude = Double.NaN), sample.copy(longitude = 181.0),
            sample.copy(source = LocationSourceType.PLEOS), sample.copy(accuracyMeters = -1f))) {
            assertEquals(VisitBatch(emptyList(), emptyList()), MockVisitDetector().onSample(invalid))
        }
    }
    @Test fun missingDenominatorIsNotDisplayedAsZeroPercent() {
        assertEquals("집계 없음", explorationRate(ExplorationSummary("user", 0, 0, 0, 0, null)))
    }
}
