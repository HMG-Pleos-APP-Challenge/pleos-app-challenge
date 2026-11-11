package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.contract.ExplorationFeature
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscoveryResult(val status: EntryStatus, val newlyDiscovered: Boolean,
    val newlyUnlockedCells: Int = 0, val cellStatus: CellStatus? = null, val newlyCollectedLandmarks: Int = 0)

class LocalExplorationFeature(
    val boundary: RegionBoundary,
    private val source: LocationSourceType,
    private val store: DiscoveryStore,
    val cellExploration: LocalCellExploration? = null,
    val landmarkCollection: LocalLandmarkCollection? = null,
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
            // A failed cell write may leave a valid region discovery saved. Both operations are idempotent.
            // Keep detector rollback around the entire call so the exact input can safely retry the cell write.
            val discovery = records.firstOrNull { it.userId == sample.userId }
            val cell = if (discovery == null || sample.timestampUtcMs >= discovery.timestampUtcMs)
                cellExploration?.onLocation(sample, discovery != null) else null
            val collected = if (discovery == null || sample.timestampUtcMs >= discovery.timestampUtcMs)
                landmarkCollection?.onLocation(sample, discovery != null) ?: 0 else 0
            DiscoveryResult(evaluation.status, added, if (cell?.newlyUnlocked == true) 1 else 0, cell?.status, collected)
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

    suspend fun mergeConfirmed(userId:String, grants:List<com.daehyundai.map.feature.exploration.cloud.CloudGrant>) = mutex.withLock {
        grants.filter { it.kind=="region" && it.targetId==boundary.id }.forEach {
            persist(RegionDiscovered("cloud:${it.targetId}:$userId",userId,it.targetId,it.timestamp,source))
        }
        cellExploration?.mergeConfirmed(userId,grants)
        landmarkCollection?.mergeConfirmed(userId,grants)
    }

    override suspend fun getSummary(userId: String): ExplorationSummary = mutex.withLock {
        val own = records.filter { it.userId == userId }
        val cells = cellExploration?.progress(userId)
        // A cell is not a road segment. unlockedRoadCount stays zero until a shared road-ID integration exists.
        ExplorationSummary(userId, own.size, 0, cells?.unlocked ?: 0, cells?.total ?: 0,
            listOfNotNull(own.maxOfOrNull { it.timestampUtcMs }, cells?.lastUpdatedUtcMs).maxOrNull())
    }

    suspend fun discoveries(userId: String): List<RegionDiscovered> = mutex.withLock {
        records.filter { it.userId == userId }.toList()
    }
}
