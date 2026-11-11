package com.daehyundai.map.feature.exploration.domain

import com.daehyundai.map.core.model.*

data class CellUnlock(val userId: String, val cellId: String, val timestampUtcMs: Long, val source: LocationSourceType)
enum class CellStatus { REJECTED, OUTSIDE, OFF_ROAD, CONFIRMING, CONFIRMED }
data class CellEvaluation(val status: CellStatus, val unlock: CellUnlock? = null)
data class CellPolicy(
    val maxAccuracyMeters: Float = 25f,
    val maxRoadDistanceMeters: Double = 30.0,
    val minimumMovementMeters: Double = 8.0,
    val confirmationMillis: Long = 3_000,
    val maxGapMillis: Long = 30_000,
    val maxSpeedMetersPerSecond: Double = 60.0,
) {
    init {
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(maxRoadDistanceMeters.isFinite() && maxRoadDistanceMeters > maxAccuracyMeters)
        require(minimumMovementMeters.isFinite() && minimumMovementMeters > 0)
        require(confirmationMillis > 0 && maxGapMillis >= confirmationMillis)
        require(maxSpeedMetersPerSecond.isFinite() && maxSpeedMetersPerSecond > 0)
    }
}

class RoadCellDetector(
    private val boundary: RegionBoundary, private val catalog: RoadCellCatalog,
    private val source: LocationSourceType, private val policy: CellPolicy = CellPolicy(),
    watermarks: Map<String, Long> = emptyMap(),
) {
    init { require(boundary.id == catalog.regionId) }
    private data class Fix(val point: MeterPoint, val timestamp: Long)
    private data class Candidate(val cell: CellIndex, val first: Fix, val lastEventId: String)
    private val times = watermarks.toMutableMap()
    private val fixes = mutableMapOf<String, Fix>()
    private val candidates = mutableMapOf<String, Candidate>()

    fun evaluate(sample: LocationSample): CellEvaluation {
        val user = sample.userId
        if (sample.source != source) return CellEvaluation(CellStatus.REJECTED)
        if (user.isBlank() || sample.eventId.isBlank() || sample.timestampUtcMs < 0 ||
            !sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0) {
            candidates.remove(user); fixes.remove(user)
            return CellEvaluation(CellStatus.REJECTED)
        }
        if (sample.timestampUtcMs <= (times[user] ?: -1)) return CellEvaluation(CellStatus.REJECTED)
        times[user] = sample.timestampUtcMs
        val accuracy = sample.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy > policy.maxAccuracyMeters) {
            candidates.remove(user); fixes.remove(user)
            return CellEvaluation(CellStatus.REJECTED)
        }
        val geo = GeoPoint(sample.latitude, sample.longitude)
        if (!boundary.contains(geo, accuracy)) {
            candidates.remove(user); fixes.remove(user)
            return CellEvaluation(CellStatus.OUTSIDE)
        }
        val point = catalog.grid.project(geo)
        val previousFix = fixes[user]
        fixes[user] = Fix(point, sample.timestampUtcMs)
        val gap = previousFix?.let { sample.timestampUtcMs - it.timestamp }
        if (previousFix != null && gap!! <= policy.maxGapMillis &&
            previousFix.point.distance(point) / (gap / 1_000.0) > policy.maxSpeedMetersPerSecond) {
            candidates.remove(user)
            return CellEvaluation(CellStatus.REJECTED)
        }
        val cell = catalog.grid.index(point)
        val roadDistance = catalog.roadDistance(point, cell)
        if (roadDistance == null || roadDistance + accuracy > policy.maxRoadDistanceMeters ||
            !catalog.grid.unambiguous(point, cell, accuracy)) {
            candidates.remove(user)
            return CellEvaluation(CellStatus.OFF_ROAD)
        }
        val previous = candidates[user]
        if (previous?.lastEventId == sample.eventId) return CellEvaluation(CellStatus.REJECTED)
        val candidate = if (previous == null || previous.cell != cell || gap == null || gap > policy.maxGapMillis)
            Candidate(cell, Fix(point, sample.timestampUtcMs), sample.eventId)
        else previous.copy(lastEventId = sample.eventId)
        candidates[user] = candidate
        if (sample.timestampUtcMs - candidate.first.timestamp < policy.confirmationMillis ||
            candidate.first.point.distance(point) < policy.minimumMovementMeters)
            return CellEvaluation(CellStatus.CONFIRMING)
        return CellEvaluation(CellStatus.CONFIRMED,
            CellUnlock(user, catalog.cellId(cell), sample.timestampUtcMs, source))
    }

    internal fun <T> transaction(block: () -> T): T {
        val savedTimes = times.toMap()
        val savedFixes = fixes.toMap()
        val savedCandidates = candidates.toMap()
        return try { block() } catch (failure: Exception) {
            times.clear(); times.putAll(savedTimes)
            fixes.clear(); fixes.putAll(savedFixes)
            candidates.clear(); candidates.putAll(savedCandidates)
            throw failure
        }
    }
}
