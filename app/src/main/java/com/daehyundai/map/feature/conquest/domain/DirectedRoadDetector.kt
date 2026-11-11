package com.daehyundai.map.feature.conquest.domain

import com.daehyundai.map.core.model.*
import kotlin.math.max

data class RoadDetectionPolicy(
    val maxAccuracyMeters: Float = 10f,
    val corridorMeters: Double = 20.0,
    val entranceMeters: Double = 12.0,
    val maxGapMillis: Long = 15_000,
    val maxStepMeters: Double = 60.0,
    val maxSpeedMetersPerSecond: Double = 40.0,
    val reverseToleranceMeters: Double = 8.0,
) {
    init {
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0 && maxAccuracyMeters < entranceMeters)
        require(corridorMeters.isFinite() && corridorMeters > maxAccuracyMeters)
        require(entranceMeters.isFinite() && entranceMeters in 1.0..20.0)
        require(maxGapMillis > 0 && maxStepMeters.isFinite() && maxStepMeters > 0)
        require(maxSpeedMetersPerSecond.isFinite() && maxSpeedMetersPerSecond > 0)
        require(reverseToleranceMeters.isFinite() && reverseToleranceMeters >= 0)
    }
}

/** Consumes the existing location model. Returns only conquest events; exploration keeps its own detector.
 * Temporary in-progress trips are intentionally not persisted: restart mid-road cannot grant a visit.
 */
class DirectedRoadDetector(private val catalog: ConquestCatalog, private val source: LocationSourceType,
    private val policy: RoadDetectionPolicy = RoadDetectionPolicy()) {
    private data class Trip(val maxProgress: Double, val observations: Int, val reachedExit: Boolean = false)
    private class UserState {
        var previous: LocationSample? = null
        var trips = mutableMapOf<String, Trip>()
    }
    private val users = mutableMapOf<String, UserState>()

    @Synchronized fun reset(userId: String) { users.remove(userId) }

    @Synchronized fun onSample(sample: LocationSample): List<RoadVisited> {
        if (sample.userId.isBlank()) return emptyList()
        val state = users.getOrPut(sample.userId) { UserState() }
        val previous = state.previous
        if (previous?.eventId == sample.eventId && previous == sample) return emptyList()
        val accuracy = sample.accuracyMeters
        if (sample.eventId.isBlank() || sample.timestampUtcMs < 0 || sample.source != source ||
            !sample.latitude.isFinite() || sample.latitude !in -80.0..80.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 ||
            accuracy == null || !accuracy.isFinite() || accuracy !in 0f..policy.maxAccuracyMeters ||
            (previous != null && sample.timestampUtcMs <= previous.timestampUtcMs)) {
            state.trips.clear()
            return emptyList()
        }
        val point = ConquestPoint(sample.latitude, sample.longitude)
        state.previous = sample
        if (previous != null) {
            val elapsed = sample.timestampUtcMs - previous.timestampUtcMs
            val distance = catalog.roads.first().distance(point, ConquestPoint(previous.latitude, previous.longitude))
            if (elapsed > policy.maxGapMillis || distance > policy.maxStepMeters ||
                distance > policy.maxSpeedMetersPerSecond * elapsed / 1000.0) {
                state.trips.clear()
                return emptyList()
            }
        }
        val next = mutableMapOf<String, Trip>()
        val completed = mutableListOf<DirectedRoad>()
        for (road in catalog.roads) {
            val position = road.locate(point)
            val trip = state.trips[road.id]
            val distanceToExit = road.distance(point, road.points.last())
            // An observed exit followed by departure may turn at the intersection, but must not retreat along the road.
            if (trip?.reachedExit == true && trip.observations >= 3 &&
                distanceToExit > policy.entranceMeters + accuracy &&
                position.progressMeters >= road.lengthMeters - policy.reverseToleranceMeters) {
                completed.add(road)
                continue
            }
            if (position.distanceMeters + accuracy > policy.corridorMeters) continue
            if (trip == null) {
                // Starting in the middle never arms a trip; uncertainty must fit within the entrance gate.
                if (position.progressMeters >= -policy.entranceMeters + accuracy &&
                    position.progressMeters <= policy.entranceMeters - accuracy) {
                    next[road.id] = Trip(position.progressMeters, 1)
                }
            } else if (position.progressMeters >= trip.maxProgress - policy.reverseToleranceMeters) {
                val progress = max(trip.maxProgress, position.progressMeters)
                if (position.progressMeters >= road.lengthMeters + max(2.0, accuracy.toDouble()) && trip.observations >= 3) {
                    completed.add(road)
                } else {
                    val reachedExit = trip.reachedExit ||
                        (position.progressMeters >= road.lengthMeters - accuracy &&
                            distanceToExit <= policy.entranceMeters - accuracy)
                    next[road.id] = Trip(progress, trip.observations + 1, reachedExit)
                }
            }
        }
        state.trips = next
        // Overlapping indistinguishable roads must not produce multiple contributions from one movement.
        if (completed.size != 1) return emptyList()
        return completed.map { road -> RoadVisited("${sample.eventId}:conquest:${road.id}", sample.userId,
            road.id, sample.timestampUtcMs, sample.source) }
    }
}
