package com.hmg.pleos.mapgame.core.data

import com.hmg.pleos.mapgame.core.contract.*
import com.hmg.pleos.mapgame.core.model.*

/** Integration-only in-memory doubles; not production rules or persistent storage. */
class PreviewConquestFeature : ConquestFeature {
    private val events = mutableMapOf<Pair<String, String>, RoadVisited>()
    override suspend fun onRoadVisited(event: RoadVisited) {
        events.putIfAbsent(event.userId to event.eventId, event)
    }
    override suspend fun getSummary(userId: String): ConquestSummary {
        val own = events.values.filter { it.userId == userId }
        return ConquestSummary(userId, null, 0, own.size, own.maxOfOrNull { it.timestampUtcMs })
    }
}
class PreviewExplorationFeature : ExplorationFeature {
    private val events = mutableMapOf<Pair<String, String>, RegionDiscovered>()
    override suspend fun onRegionDiscovered(event: RegionDiscovered) {
        events.putIfAbsent(event.userId to event.eventId, event)
    }
    override suspend fun getSummary(userId: String): ExplorationSummary {
        val own = events.values.filter { it.userId == userId }
        // Counting unit = synthetic regions. Real exploration denominator is not decided here.
        return ExplorationSummary(userId, own.map { it.regionId }.distinct().size,
            0, own.map { it.regionId }.distinct().size, 2,
            own.maxOfOrNull { it.timestampUtcMs })
    }
}
