package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CellResult(val status: CellStatus, val newlyUnlocked: Boolean)
data class CellProgress(val unlocked: Int, val total: Int, val lastUpdatedUtcMs: Long?)

class LocalCellExploration(
    boundary: RegionBoundary, val catalog: RoadCellCatalog,
    private val source: LocationSourceType, private val store: CellUnlockStore,
) {
    private val mutex = Mutex()
    private val knownIds = catalog.cells.keys.associateBy(catalog::cellId)
    private var records = store.read().also { loaded ->
        require(loaded.all { it.cellId in knownIds && it.source == source }) { "카탈로그에 없는 셀 기록" }
    }
    private val detector = RoadCellDetector(boundary, catalog, source, watermarks =
        records.groupBy { it.userId }.mapValues { (_, records) -> records.maxOf { it.timestampUtcMs } })

    suspend fun onLocation(sample: LocationSample, regionDiscovered: Boolean): CellResult = mutex.withLock {
        detector.transaction {
            val result = detector.evaluate(sample)
            val unlock = result.unlock
            var added = false
            if (unlock != null && regionDiscovered && records.none { it.userId == unlock.userId && it.cellId == unlock.cellId }) {
                val updated = records + unlock
                store.write(updated)
                records = updated
                added = true
            }
            CellResult(result.status, added)
        }
    }

    suspend fun progress(userId: String): CellProgress = mutex.withLock {
        val own = records.filter { it.userId == userId }
        CellProgress(own.size, catalog.cells.size, own.maxOfOrNull { it.timestampUtcMs })
    }
    suspend fun earnedRecords(userId:String):List<CellUnlock> = mutex.withLock { records.filter { it.userId==userId } }

    suspend fun unlockedCells(userId: String): Set<CellIndex> = mutex.withLock {
        records.filter { it.userId == userId }.map { knownIds.getValue(it.cellId) }.toSet()
    }
}
