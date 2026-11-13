package com.daehyundai.map

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.ExplorationSession
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*

/** Composition-root assembly using existing feature constructors and memory-only stores. */
internal fun createDemoExploration(userId: String, boundary: RegionBoundary,
    catalog: RoadCellCatalog): ExplorationSession {
    val discoveries = object : DiscoveryStore {
        private var records = emptyList<RegionDiscovered>()
        override fun read() = records.toList()
        override fun write(records: List<RegionDiscovered>) { this.records = records.toList() }
    }
    val cells = object : CellUnlockStore {
        private var records = emptyList<CellUnlock>()
        override fun read() = records.toList()
        override fun write(records: List<CellUnlock>) { this.records = records.toList() }
    }
    val feature = LocalExplorationFeature(boundary, LocationSourceType.MOCK, discoveries,
        LocalCellExploration(boundary, catalog, LocationSourceType.MOCK, cells))
    return ExplorationSession(userId, feature)
}
