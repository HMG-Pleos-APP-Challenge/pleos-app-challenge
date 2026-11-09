package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.contract.ExplorationFeature
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscoveryResult(val status: EntryStatus, val newlyDiscovered: Boolean)

class LocalExplorationFeature(
    val boundary: RegionBoundary,
    private val source: LocationSourceType,
    private val store: DiscoveryStore,
) : ExplorationFeature {
    private val mutex = Mutex()
    private var records = store.read().also { records ->
        require(records.all { it.regionId == boundary.id && it.source == source }) { "다른 지역/출처의 저장 데이터" }
    }
    private val detector = RegionEntryDetector(boundary, source, initialWatermarks =
        records.groupBy { it.userId }.mapValues { (_, records) -> records.maxOf { it.timestampUtcMs } })

    suspend fun onLocation(sample: LocationSample): DiscoveryResult = mutex.withLock {
        detector.transaction {
            val evaluation = detector.evaluate(sample)
            val added = evaluation.event?.let { persist(it) } ?: false
            DiscoveryResult(evaluation.status, added)
        }
    }

    override suspend fun onRegionDiscovered(event: RegionDiscovered) = mutex.withLock {
        // Trusted integration events use the existing contract. This is not a GPS validation API.
        require(event.regionId == boundary.id && event.source == source)
        require(event.userId.isNotBlank() && event.eventId.isNotBlank() && event.timestampUtcMs >= 0)
        persist(event)
        Unit
    }

    private fun persist(event: RegionDiscovered): Boolean {
        if (records.any { it.userId == event.userId && it.regionId == event.regionId }) return false
        val updated = records + event
        store.write(updated) // Publish only after durable write. Retrying after failure is safe.
        records = updated
        return true
    }

    override suspend fun getSummary(userId: String): ExplorationSummary = mutex.withLock {
        val own = records.filter { it.userId == userId }
        // Discovery is separate from coverage. Stage 2 road/cell dataset is not yet installed.
        ExplorationSummary(userId, own.size, 0, 0, 0, own.maxOfOrNull { it.timestampUtcMs })
    }

    suspend fun discoveries(userId: String): List<RegionDiscovered> = mutex.withLock {
        records.filter { it.userId == userId }.toList()
    }
}
