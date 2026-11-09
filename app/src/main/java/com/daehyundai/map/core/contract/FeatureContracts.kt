package com.daehyundai.map.core.contract

import com.daehyundai.map.core.model.*
import kotlinx.coroutines.flow.Flow

interface LocationSource { suspend fun samples(): Flow<LocationSample> }
interface VisitDetector { suspend fun onSample(sample: LocationSample): VisitBatch }
interface ConquestFeature {
    suspend fun onRoadVisited(event: RoadVisited)
    suspend fun getSummary(userId: String): ConquestSummary
}
interface ExplorationFeature {
    suspend fun onRegionDiscovered(event: RegionDiscovered)
    suspend fun getSummary(userId: String): ExplorationSummary
}
interface ProgressRepository {
    suspend fun awardOnce(award: XpAward): Boolean
    suspend fun getProgress(userId: String): UserProgress
}
