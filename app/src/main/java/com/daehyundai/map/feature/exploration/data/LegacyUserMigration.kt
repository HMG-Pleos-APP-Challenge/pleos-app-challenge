package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File

/** Each atomic file rewrite is idempotent. Persist identity first, then retry unfinished files on next launch. */
fun migrateLegacyExploration(root:File,boundary:RegionBoundary,roads:RoadCellCatalog,landmarks:LandmarkCatalog,userId:String) {
    require(userId.isNotBlank() && userId!="u-demo-01")
    for(prefix in listOf("mock","pleos-emulator","pleos")) {
        val source=if(prefix=="mock") LocationSourceType.MOCK else LocationSourceType.PLEOS
        val regionFile=File(root,"$prefix-discoveries.bin")
        if(regionFile.exists()) {
            val store=FileDiscoveryStore(regionFile,boundary.datasetVersion,source);val old=store.read()
            if(old.any { it.userId=="u-demo-01" }) store.write(old.map { if(it.userId=="u-demo-01") it.copy(userId=userId) else it }
                .sortedBy { it.timestampUtcMs }.distinctBy { it.userId to it.regionId })
        }
        val cellFile=File(root,"$prefix-cells.bin")
        if(cellFile.exists()) {
            val store=FileCellUnlockStore(cellFile,roads.version,source);val old=store.read()
            if(old.any { it.userId=="u-demo-01" }) store.write(old.map { if(it.userId=="u-demo-01") it.copy(userId=userId) else it }
                .sortedBy { it.timestampUtcMs }.distinctBy { it.userId to it.cellId })
        }
        val landmarkFile=File(root,"$prefix-landmarks.bin")
        if(landmarkFile.exists()) {
            val store=FileLandmarkUnlockStore(landmarkFile,landmarks.version,source);val old=store.read()
            if(old.any { it.userId=="u-demo-01" }) store.write(old.map { if(it.userId=="u-demo-01") it.copy(userId=userId) else it }
                .sortedBy { it.timestampUtcMs }.distinctBy { it.userId to it.landmarkId })
        }
    }
}
