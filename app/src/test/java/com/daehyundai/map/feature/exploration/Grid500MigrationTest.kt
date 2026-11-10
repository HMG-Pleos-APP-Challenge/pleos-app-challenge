package com.daehyundai.map.feature.exploration

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Grid500MigrationTest {
    @get:Rule val temp=TemporaryFolder()
    private fun catalog()=File("src/main/assets/exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
    @Test fun actualCatalogHas243RoadCellsAt500Metres() {
        val c=catalog();assertEquals(500.0,c.grid.sizeMeters,0.0);assertEquals(243,c.cells.size)
        assertEquals(GRID500_VERSION,c.version)
    }
    @Test fun groupsCellsKeepsEarliestTimeAndUsesFloorForNegativeRows() {
        val c=catalog();val source=LocationSourceType.PLEOS;val file=File(temp.root,"pleos-emulator-cells.bin")
        val old=listOf(CellUnlock("a","$GRID100_VERSION:35:-28",2000,source),
            CellUnlock("a","$GRID100_VERSION:36:-29",1000,source),
            CellUnlock("b","$GRID100_VERSION:35:-28",3000,source))
        FileCellUnlockStore(file,GRID100_VERSION,source).write(old);val original=file.readBytes()
        migrateGrid500(temp.root,c)
        val result=FileCellUnlockStore(file,c.version,source).read()
        assertEquals(2,result.size);assertTrue(result.all { it.cellId=="$GRID500_VERSION:7:-6" })
        assertEquals(1000,result.first { it.userId=="a" }.timestampUtcMs)
        assertArrayEquals(original,File(temp.root,"pleos-emulator-cells-grid100-v1.backup.bin").readBytes())
        val migrated=file.readBytes();migrateGrid500(temp.root,c);assertArrayEquals(migrated,file.readBytes())
    }
    @Test fun unknownVersionDoesNotAlterFileOrCreateBackup() {
        val file=File(temp.root,"mock-cells.bin")
        FileCellUnlockStore(file,"unknown",LocationSourceType.MOCK).write(emptyList())
        val before=file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { migrateGrid500(temp.root,catalog()) }
        assertArrayEquals(before,file.readBytes());assertFalse(File(temp.root,"mock-cells-grid100-v1.backup.bin").exists())
    }
}
