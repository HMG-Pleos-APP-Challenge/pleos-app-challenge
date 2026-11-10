package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.LocalConquestFeature
import com.daehyundai.map.feature.conquest.domain.*

data class ProcessedRoadVisit(val event: RoadVisited, val result: ContributionResult)

/** Caller supplies the same LocationSample to each feature; this class does not subscribe to an SDK.
 * Use a single instance off the UI thread. A failed disk write keeps the completed visit pending for retry.
 */
class ConquestLocationPipeline(private val feature: LocalConquestFeature, catalog: ConquestCatalog,
    source: LocationSourceType, policy: RoadDetectionPolicy = RoadDetectionPolicy()) {
    private val detector = DirectedRoadDetector(catalog, source, policy)
    private val pending = ArrayDeque<RoadVisited>()

    @Synchronized fun selectTeam(userId: String, teamId: String): Boolean {
        val wasUnselected = feature.teamOf(userId) == null
        val result = feature.selectTeam(userId, teamId)
        if (result && wasUnselected) detector.reset(userId)
        return result
    }

    @Synchronized fun onSample(sample: LocationSample): List<ProcessedRoadVisit> {
        val results = mutableListOf<ProcessedRoadVisit>()
        fun drain() {
            while (pending.isNotEmpty()) {
                val event = pending.first()
                val result = feature.accept(event) // On failure, leave the event in the queue.
                pending.removeFirst()
                results.add(ProcessedRoadVisit(event, result))
            }
        }
        drain()
        if (feature.teamOf(sample.userId) == null) {
            detector.reset(sample.userId)
            return results
        }
        pending.addAll(detector.onSample(sample))
        drain()
        return results
    }
}
