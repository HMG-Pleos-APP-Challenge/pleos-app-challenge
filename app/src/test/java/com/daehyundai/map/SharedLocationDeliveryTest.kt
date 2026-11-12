package com.daehyundai.map

import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.data.DiscoveryResult
import com.daehyundai.map.feature.exploration.domain.EntryStatus
import com.daehyundai.map.feature.exploration.tracking.deliverLocationSample
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SharedLocationDeliveryTest {
    private fun sample(id: String) = LocationSample(id, "u", 37.2, 126.8, 1_000, 3f, LocationSourceType.PLEOS)
    private val discovery = DiscoveryResult(EntryStatus.CONFIRMED, false)

    @Test fun explorationFailureStillDeliversSameSampleToConquestAndNextSamplesInOrder() = runTest {
        val calls = mutableListOf<String>()
        for (id in listOf("first", "second")) {
            val input = sample(id)
            val result = deliverLocationSample(input, {
                calls.add("exploration-${it.eventId}")
                delay(10)
                if (it.eventId == "first") throw IOException("Exploration disk failed")
                discovery
            }, {
                assertSame(input, it)
                calls.add("conquest-${it.eventId}")
            })
            assertEquals(id == "first", result.exploration.isFailure)
            assertTrue(result.conquest.isSuccess)
        }
        assertEquals(listOf("exploration-first", "conquest-first", "exploration-second", "conquest-second"), calls)
    }

    @Test fun conquestFailurePreservesExplorationResult() = runTest {
        val result = deliverLocationSample(sample("one"), { discovery }, { throw IOException("Conquest disk failed") })
        assertSame(discovery, result.exploration.getOrThrow())
        assertTrue(result.conquest.isFailure)
    }

    @Test fun cancellationPropagatesBeforeConquestReceivesInput() = runTest {
        var conquestCalled = false
        try {
            deliverLocationSample(sample("one"), { throw CancellationException("Tracking stopped") }, { conquestCalled = true })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(conquestCalled)
    }

    @Test fun bothFailuresAreReportedIndependently() = runTest {
        val result = deliverLocationSample(sample("one"),
            { throw IllegalStateException("Exploration unavailable") },
            { throw IllegalStateException("Conquest unavailable") })
        assertTrue(result.exploration.isFailure)
        assertTrue(result.conquest.isFailure)
    }
}
