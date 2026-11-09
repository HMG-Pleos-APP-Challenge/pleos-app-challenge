package com.daehyundai.map.core.location

import com.daehyundai.map.core.contract.LocationSource
import com.daehyundai.map.core.contract.VisitDetector
import com.daehyundai.map.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** Fixed synthetic fixtures. No real road matching or Pleos API integration is claimed. */
class MockLocationSource(private val userId: String) : LocationSource {
    override suspend fun samples(): Flow<LocationSample> = (1..3).map { index ->
        LocationSample("mock-$index", userId, 37.5 + index * 0.001, 127.0,
            1_783_296_000_000L + index * 1_000L, 5f, LocationSourceType.MOCK)
    }.asFlow()
}

/** Explicit VisitBatch fixture fallback from contract v0.1, not a spatial algorithm. */
class MockVisitDetector : VisitDetector {
    override suspend fun onSample(sample: LocationSample): VisitBatch {
        val index = listOf("mock-1", "mock-2", "mock-3").indexOf(sample.eventId) + 1
        if (sample.source != LocationSourceType.MOCK || index == 0 ||
            !sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 ||
            sample.userId.isBlank() || sample.timestampUtcMs < 0 ||
            sample.accuracyMeters?.let { !it.isFinite() || it < 0 } == true
        ) return VisitBatch(emptyList(), emptyList())
        return VisitBatch(
            listOf(RoadVisited("${sample.eventId}:road", sample.userId, "demo:road:00$index",
                sample.timestampUtcMs, sample.source)),
            listOf(RegionDiscovered("${sample.eventId}:region", sample.userId,
                if (index < 3) "KR:demo:region-01" else "KR:demo:region-02",
                sample.timestampUtcMs, sample.source)),
        )
    }
}
