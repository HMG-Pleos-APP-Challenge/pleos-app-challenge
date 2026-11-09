package com.daehyundai.map.feature.exploration.domain

import com.daehyundai.map.core.contract.VisitDetector
import com.daehyundai.map.core.model.*

data class EntryPolicy(
    val maxAccuracyMeters: Float = 25f,
    val confirmationMillis: Long = 3_000L,
    val maxGapMillis: Long = 30_000L,
) {
    init {
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(confirmationMillis > 0 && maxGapMillis >= confirmationMillis)
    }
}

enum class EntryStatus { REJECTED, OUTSIDE, CONFIRMING, CONFIRMED }
data class EntryEvaluation(val status: EntryStatus, val event: RegionDiscovered? = null)

/** One instance is consumed serially by the exploration repository, never a second SDK subscriber. */
class RegionEntryDetector(
    private val boundary: RegionBoundary,
    private val acceptedSource: LocationSourceType,
    private val policy: EntryPolicy = EntryPolicy(),
    initialWatermarks: Map<String, Long> = emptyMap(),
) : VisitDetector {
    private data class Candidate(val first: Long, val last: Long, val lastEventId: String)
    private val watermarks = initialWatermarks.toMutableMap()
    private val candidates = mutableMapOf<String, Candidate>()

    fun evaluate(sample: LocationSample): EntryEvaluation {
        val user = sample.userId
        if (user.isBlank() || sample.eventId.isBlank() || sample.source != acceptedSource ||
            sample.timestampUtcMs < 0 || !sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0)
        {
            if (sample.source == acceptedSource) candidates.remove(user)
            return EntryEvaluation(EntryStatus.REJECTED)
        }
        if (sample.timestampUtcMs <= (watermarks[user] ?: -1L)) return EntryEvaluation(EntryStatus.REJECTED)
        watermarks[user] = sample.timestampUtcMs
        val accuracy = sample.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy > policy.maxAccuracyMeters) {
            candidates.remove(user)
            return EntryEvaluation(EntryStatus.REJECTED)
        }
        if (!boundary.contains(GeoPoint(sample.latitude, sample.longitude), accuracy)) {
            candidates.remove(user)
            return EntryEvaluation(EntryStatus.OUTSIDE)
        }
        val previous = candidates[user]
        if (previous != null && previous.lastEventId == sample.eventId) return EntryEvaluation(EntryStatus.REJECTED)
        val first = if (previous == null || sample.timestampUtcMs - previous.last > policy.maxGapMillis)
            sample.timestampUtcMs else previous.first
        candidates[user] = Candidate(first, sample.timestampUtcMs, sample.eventId)
        if (sample.timestampUtcMs - first < policy.confirmationMillis) return EntryEvaluation(EntryStatus.CONFIRMING)
        return EntryEvaluation(EntryStatus.CONFIRMED, RegionDiscovered(
            "exploration:${boundary.datasetVersion}:${boundary.id}:$user", user, boundary.id,
            sample.timestampUtcMs, sample.source,
        ))
    }

    internal fun <T> transaction(block: () -> T): T {
        val savedWatermarks = watermarks.toMap()
        val savedCandidates = candidates.toMap()
        return try { block() } catch (failure: Exception) {
            watermarks.clear(); watermarks.putAll(savedWatermarks)
            candidates.clear(); candidates.putAll(savedCandidates)
            throw failure
        }
    }

    override suspend fun onSample(sample: LocationSample): VisitBatch =
        VisitBatch(emptyList(), listOfNotNull(evaluate(sample).event))
}
