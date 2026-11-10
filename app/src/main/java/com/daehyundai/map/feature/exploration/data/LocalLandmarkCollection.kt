package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalLandmarkCollection(
    boundary: RegionBoundary, val catalog: LandmarkCatalog,
    private val source: LocationSourceType, private val store: LandmarkUnlockStore,
) {
    private val mutex = Mutex()
    private val knownIds = catalog.landmarks.map { it.id }.toSet()
    private var records = store.read().also { loaded ->
        require(loaded.all { it.landmarkId in knownIds && it.source == source }) { "알 수 없는 랜드마크 기록" }
    }
    private val detector = LandmarkDwellDetector(boundary,catalog,source,initialWatermarks=
        records.groupBy { it.userId }.mapValues { (_,records) -> records.maxOf { it.timestampUtcMs } })

    suspend fun onLocation(sample: LocationSample, regionDiscovered: Boolean): Int = mutex.withLock {
        detector.transaction {
            val result = detector.evaluate(sample)
            val additions = if (!regionDiscovered) emptyList() else result.unlocks.filter { unlock ->
                records.none { it.userId == unlock.userId && it.landmarkId == unlock.landmarkId }
            }
            if (additions.isNotEmpty()) {
                val updated = records + additions
                store.write(updated)
                records = updated
            }
            additions.size
        }
    }

    suspend fun mergeConfirmed(userId:String, grants:List<com.daehyundai.map.feature.exploration.cloud.CloudGrant>) = mutex.withLock {
        val additions=grants.filter { it.kind=="landmark" && it.targetId in knownIds && records.none { row -> row.userId==userId && row.landmarkId==it.targetId } }
            .map { LandmarkUnlock(userId,it.targetId,it.timestamp,source) }
        if(additions.isNotEmpty()) { val updated=records+additions;store.write(updated);records=updated }
    }

    suspend fun summary(userId: String): LandmarkCollectionSummary = mutex.withLock {
        val own = records.filter { it.userId == userId }.associateBy { it.landmarkId }
        LandmarkCollectionSummary(userId,catalog.landmarks.map { landmark ->
            LandmarkCard(landmark.id,landmark.name,landmark.radiusMeters,landmark.dwellMillis,
                detector.observedMillis(userId,landmark),own[landmark.id]?.timestampUtcMs)
        })
    }
}
