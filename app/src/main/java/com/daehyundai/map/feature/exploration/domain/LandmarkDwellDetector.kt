package com.daehyundai.map.feature.exploration.domain

import com.daehyundai.map.core.model.*

data class DwellEvaluation(val accepted: Boolean, val unlocks: List<LandmarkUnlock> = emptyList())

/** Counts only uninterrupted valid observations. No wall-clock timer can complete this detector. */
class LandmarkDwellDetector(
    private val boundary: RegionBoundary, private val catalog: LandmarkCatalog,
    private val source: LocationSourceType, private val maxAccuracyMeters: Float = 25f,
    initialWatermarks: Map<String, Long> = emptyMap(),
) {
    init {
        require(boundary.id == catalog.regionId)
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(catalog.landmarks.all { boundary.contains(it.point, 0f) })
    }
    private data class Candidate(val first: Long, val last: Long, val samples: Int)
    private val times = initialWatermarks.toMutableMap()
    private val candidates = mutableMapOf<Pair<String,String>, Candidate>()
    private val recentIds = mutableMapOf<String, LinkedHashSet<String>>()

    private fun reset(userId: String) { candidates.keys.removeAll { it.first == userId } }

    fun evaluate(sample: LocationSample): DwellEvaluation {
        val user = sample.userId
        if (sample.source != source) return DwellEvaluation(false)
        if (user.isBlank() || sample.eventId.isBlank() || sample.timestampUtcMs < 0 ||
            !sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0) {
            reset(user)
            return DwellEvaluation(false)
        }
        if (sample.timestampUtcMs <= (times[user] ?: -1L)) return DwellEvaluation(false)
        times[user] = sample.timestampUtcMs
        val seen = recentIds.getOrPut(user) { linkedSetOf() }
        if (!seen.add(sample.eventId)) return DwellEvaluation(false)
        if (seen.size > 256) seen.remove(seen.first())
        val accuracy = sample.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy > maxAccuracyMeters) {
            reset(user)
            return DwellEvaluation(false)
        }
        val point = GeoPoint(sample.latitude,sample.longitude)
        if (!boundary.contains(point,accuracy)) {
            reset(user)
            return DwellEvaluation(false)
        }
        val unlocked = mutableListOf<LandmarkUnlock>()
        for (landmark in catalog.landmarks) {
            val key = user to landmark.id
            if (landmark.distanceMeters(point) + accuracy > landmark.radiusMeters) {
                candidates.remove(key)
                continue
            }
            val previous = candidates[key]
            val current = if (previous == null || sample.timestampUtcMs - previous.last > landmark.maxGapMillis)
                Candidate(sample.timestampUtcMs,sample.timestampUtcMs,1)
            else previous.copy(last=sample.timestampUtcMs,samples=previous.samples+1)
            candidates[key] = current
            if (current.last - current.first >= landmark.dwellMillis && current.samples >= 3)
                unlocked.add(LandmarkUnlock(user,landmark.id,sample.timestampUtcMs,source))
        }
        return DwellEvaluation(true,unlocked)
    }

    fun observedMillis(userId: String, landmark: LandmarkDefinition): Long =
        candidates[userId to landmark.id]?.let { (it.last-it.first).coerceAtMost(landmark.dwellMillis) } ?: 0L

    internal fun <T> transaction(block: () -> T): T {
        val oldTimes = times.toMap()
        val oldCandidates = candidates.toMap()
        val oldIds = recentIds.mapValues { LinkedHashSet(it.value) }
        return try { block() } catch (failure: Exception) {
            times.clear();times.putAll(oldTimes)
            candidates.clear();candidates.putAll(oldCandidates)
            recentIds.clear();recentIds.putAll(oldIds)
            throw failure
        }
    }
}
