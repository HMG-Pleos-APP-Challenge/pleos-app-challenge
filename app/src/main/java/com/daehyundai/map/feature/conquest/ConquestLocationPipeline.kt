package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.LocalConquestFeature
import com.daehyundai.map.feature.conquest.data.PendingVisitsStore
import com.daehyundai.map.feature.conquest.data.MemoryPendingVisitsStore
import com.daehyundai.map.feature.conquest.domain.*

data class ProcessedRoadVisit(val event: RoadVisited, val result: ContributionResult)

/** Caller supplies the same LocationSample to each feature; this class does not subscribe to an SDK.
 * Use a single instance off the UI thread. A failed disk write keeps the completed visit pending for retry.
 */
class ConquestLocationPipeline(private val feature: LocalConquestFeature, catalog: ConquestCatalog,
    source: LocationSourceType, policy: RoadDetectionPolicy = RoadDetectionPolicy(),
    private val pendingStore: PendingVisitsStore = MemoryPendingVisitsStore(),
    private val clock: () -> Long = System::currentTimeMillis) {
    private val detector = DirectedRoadDetector(catalog, source, policy)
    private val pending = ArrayDeque(pendingStore.read().also { events ->
        require(events.all { it.source == source && catalog.roads.any { road -> road.id == it.roadSegmentId } })
    })
    val pendingCount: Int @Synchronized get() = pending.size
    var lastResult: ProcessedRoadVisit? = null
        private set

    @Synchronized fun resetObservations(userId:String) { detector.reset(userId) }

    @Synchronized fun selectTeam(userId: String, teamId: String): Boolean {
        val wasUnselected = feature.teamOf(userId) == null
        val result = feature.selectTeam(userId, teamId)
        if (result && wasUnselected) detector.reset(userId)
        return result
    }

    /** Also called on open/refresh, so recovery needs no new GPS sample. */
    @Synchronized fun retryPending(): List<ProcessedRoadVisit> {
        val results = mutableListOf<ProcessedRoadVisit>()
        if (pending.isEmpty()) return results
        // A prior journal write may have failed. Persist before applying any event.
        pendingStore.write(pending.toList())
        while (pending.isNotEmpty()) {
            val event = pending.first()
            val result = feature.accept(event)
            if (result == ContributionResult.FUTURE && event.timestampUtcMs - clock() <= 5_000L) {
                lastResult = ProcessedRoadVisit(event, result)
                break
            }
            pendingStore.write(pending.drop(1))
            pending.removeFirst()
            val processed = ProcessedRoadVisit(event, result)
            lastResult = processed
            results.add(processed)
        }
        return results
    }

    @Synchronized fun onSample(sample: LocationSample): List<ProcessedRoadVisit> {
        val results = retryPending().toMutableList()
        if (feature.teamOf(sample.userId) == null) {
            detector.reset(sample.userId)
            return results
        }
        pending.addAll(detector.onSample(sample))
        results.addAll(retryPending())
        return results
    }
}
