package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.domain.*
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/** 100m -> 500m, with a versioned backup before atomic replacement. Safe to retry per partition. */
fun migrateGrid500(root:File, catalog:RoadCellCatalog) {
    if(catalog.version!=GRID500_VERSION) return
    for(prefix in listOf("mock","pleos-emulator","pleos")) {
        val file=File(root,"$prefix-cells.bin")
        if(!file.exists()) continue
        val version=DataInputStream(FileInputStream(file)).use { input ->
            require(input.readUTF()=="PLEOS_CELLS_V1");input.readUTF()
        }
        if(version==catalog.version) continue
        require(version==GRID100_VERSION) { "지원하지 않는 셀 버전 · 기존 기록 보존" }
        val source=if(prefix=="mock") LocationSourceType.MOCK else LocationSourceType.PLEOS
        val old=FileCellUnlockStore(file,version,source).read()
        val mapped=old.map { record ->
            val parts=record.cellId.split(':')
            require(parts.size==3 && parts[0]==GRID100_VERSION)
            val cell=CellIndex(Math.floorDiv(parts[1].toInt(),5),Math.floorDiv(parts[2].toInt(),5))
            require(cell in catalog.cells) { "변환할 수 없는 셀 · 기존 기록 보존" }
            record.copy(cellId=catalog.cellId(cell))
        }.sortedBy { it.timestampUtcMs }.distinctBy { it.userId to it.cellId }
        val backup=FileCellUnlockStore(File(root,"$prefix-cells-grid100-v1.backup.bin"),version,source)
        val backupFile=File(root,"$prefix-cells-grid100-v1.backup.bin")
        if(backupFile.exists()) require(backup.read()==old) { "기존 백업 확인 필요" } else backup.write(old)
        FileCellUnlockStore(file,catalog.version,source).write(mapped)
    }
}

const val GRID100_VERSION="osm-geofabrik-20261108-namyang-grid100-v1"
const val GRID500_VERSION="osm-geofabrik-20261108-namyang-grid500-v2"
