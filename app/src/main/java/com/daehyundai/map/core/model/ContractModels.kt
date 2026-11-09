package com.daehyundai.map.core.model

// Contract v0.1 is awaiting joint approval. Do not independently change these fields.
enum class LocationSourceType { PLEOS, MOCK }

data class LocationSample(
    val eventId: String, val userId: String,
    val latitude: Double, val longitude: Double,
    val timestampUtcMs: Long, val accuracyMeters: Float?, val source: LocationSourceType,
)
data class RoadVisited(
    val eventId: String, val userId: String, val roadSegmentId: String,
    val timestampUtcMs: Long, val source: LocationSourceType,
)
data class RegionDiscovered(
    val eventId: String, val userId: String, val regionId: String,
    val timestampUtcMs: Long, val source: LocationSourceType,
)
data class ConquestSummary(
    val userId: String, val teamId: String?, val ownedRoadCount: Int,
    val contributionCount: Int, val lastUpdatedUtcMs: Long?,
)
data class ExplorationSummary(
    val userId: String, val discoveredRegionCount: Int, val unlockedRoadCount: Int,
    val exploredUnitCount: Int, val totalExplorableUnitCount: Int,
    val lastUpdatedUtcMs: Long?,
)
data class UserProgress(val userId: String, val totalXp: Long, val level: Int)
data class XpAward(
    val awardId: String, val userId: String, val amount: Long, val reason: String,
    val sourceEventId: String, val occurredUtcMs: Long,
)
data class VisitBatch(val roads: List<RoadVisited>, val regions: List<RegionDiscovered>)
