package com.daehyundai.map.feature.exploration

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoadCellExplorationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = LocationSourceType.MOCK
    private val grid = LocalGrid(126.8, 37.2, 100.0)
    private fun boundary() = RegionBoundary("test-region", "test-region", "test-boundary",
        listOf(BoundaryPolygon(listOf(listOf(GeoPoint(37.18,126.78), GeoPoint(37.18,126.82),
            GeoPoint(37.22,126.82), GeoPoint(37.22,126.78), GeoPoint(37.18,126.78))))))
    private fun catalog(empty: Boolean = false) = RoadCellCatalog("test-grid100-v1", "test-region", grid,
        listOf(RoadPath("test-road", listOf(MeterPoint(10.0,50.0),MeterPoint(390.0,50.0)))),
        if (empty) emptyMap() else (0..3).associate { CellIndex(it,0) to listOf(0) })
    private fun sample(x: Double = 20.0, y: Double = 50.0, t: Long = 10_000, user: String = "u-a"): LocationSample {
        val point = grid.unproject(MeterPoint(x,y))
        return LocationSample("event-$t",user,point.latitude,point.longitude,t,5f,source)
    }
    private fun detector() = RoadCellDetector(boundary(),catalog(),source)
    private fun store(file: File = File(temporary.root,"cells.bin"), version: String = catalog().version) =
        FileCellUnlockStore(file,version,source)
    private fun cells() = LocalCellExploration(boundary(),catalog(),source,store())

    @Test fun localGridUsesMetresAndFloorsNegativeIndices() {
        assertEquals(CellIndex(-1,-1),grid.index(MeterPoint(-.1,-.1)))
        assertEquals(CellIndex(1,0),grid.index(MeterPoint(100.0,50.0)))
        val point=MeterPoint(150.0,-350.0)
        assertTrue(grid.project(grid.unproject(point)).distance(point)<0.000001)
    }

    @Test fun movementOnRoadConfirmsOnlyTheCurrentCell() {
        val d=detector()
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample()).status)
        assertEquals(CellStatus.CONFIRMED,d.evaluate(sample(x=40.0,t=15_000)).status)
        val result=d.evaluate(sample(x=60.0,t=20_000))
        assertEquals(catalog().cellId(CellIndex(0,0)),requireNotNull(result.unlock).cellId)
    }

    @Test fun stationaryFixDoesNotUnlock() {
        val d=detector()
        d.evaluate(sample())
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(t=15_000)).status)
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(t=20_000)).status)
    }

    @Test fun neighboringCellsAreNotGrantedOrInterpolated() = runTest {
        val feature=cells()
        feature.onLocation(sample(),true)
        feature.onLocation(sample(x=40.0,t=15_000),true)
        feature.onLocation(sample(x=320.0,t=25_000),true)
        feature.onLocation(sample(x=340.0,t=30_000),true)
        assertEquals(setOf(CellIndex(0,0),CellIndex(3,0)),feature.unlockedCells("u-a"))
        assertEquals(2,feature.progress("u-a").unlocked)
        assertEquals(4,feature.progress("u-a").total)
    }

    @Test fun offRoadCellEdgeAndUncatalogedCellsDoNotUnlock() {
        for (p in listOf(MeterPoint(50.0,80.0),MeterPoint(99.0,50.0),MeterPoint(450.0,50.0))) {
            val d=detector()
            assertEquals(CellStatus.OFF_ROAD,d.evaluate(sample(x=p.x,y=p.y)).status)
            assertNull(d.evaluate(sample(x=p.x,y=p.y,t=15_000)).unlock)
        }
    }

    @Test fun poorAccuracyBreaksConfirmationAndOtherSourceCannotUnlock() {
        for (accuracy in listOf(null,-1f,Float.NaN,26f)) {
            val d=detector()
            d.evaluate(sample())
            assertEquals(CellStatus.REJECTED,d.evaluate(sample(x=30.0,t=12_000).copy(accuracyMeters=accuracy)).status)
            assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(x=40.0,t=15_000)).status)
        }
        assertEquals(CellStatus.REJECTED,detector().evaluate(sample().copy(source=LocationSourceType.PLEOS)).status)
    }

    @Test fun jumpsGapsAndSwitchingCellsRequireFreshConfirmation() {
        val d=detector()
        d.evaluate(sample())
        assertEquals(CellStatus.REJECTED,d.evaluate(sample(x=140.0,t=11_000)).status)
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(x=150.0,t=16_000)).status)
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(x=170.0,t=47_001)).status)
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(x=220.0,t=52_001)).status)
        assertEquals(CellStatus.CONFIRMED,d.evaluate(sample(x=240.0,t=57_001)).status)
    }

    @Test fun duplicateAndBackwardsInputDoesNotConfirmAndUsersAreIndependent() {
        val d=detector()
        d.evaluate(sample())
        assertEquals(CellStatus.REJECTED,d.evaluate(sample()).status)
        assertEquals(CellStatus.REJECTED,d.evaluate(sample(t=9_000)).status)
        assertEquals(CellStatus.REJECTED,d.evaluate(sample(x=40.0,t=15_000).copy(eventId="event-10000")).status)
        assertEquals(CellStatus.CONFIRMING,d.evaluate(sample(x=40.0,t=15_000,user="u-b")).status)
        assertEquals(CellStatus.CONFIRMED,d.evaluate(sample(x=50.0,t=20_000)).status)
    }

    @Test fun regionMustOpenBeforeCellRecordCanBePublished() = runTest {
        val feature=cells()
        feature.onLocation(sample(),false)
        assertFalse(feature.onLocation(sample(x=40.0,t=15_000),false).newlyUnlocked)
        assertEquals(0,feature.progress("u-a").unlocked)
        assertTrue(feature.onLocation(sample(x=60.0,t=20_000),true).newlyUnlocked)
    }

    @Test fun repeatedReplaySurvivesReopenWithoutDuplicatingFirstUnlock() = runTest {
        val feature=cells()
        feature.onLocation(sample(),true)
        feature.onLocation(sample(x=40.0,t=15_000),true)
        val reopened=cells()
        assertEquals(1,reopened.progress("u-a").unlocked)
        assertFalse(reopened.onLocation(sample(x=40.0,t=15_000),true).newlyUnlocked)
        reopened.onLocation(sample(x=20.0,t=20_000),true)
        assertFalse(reopened.onLocation(sample(x=40.0,t=25_000),true).newlyUnlocked)
        assertEquals(15_000L,reopened.progress("u-a").lastUpdatedUtcMs)
        assertEquals(0,reopened.progress("u-b").unlocked)
    }

    @Test fun failedCellWriteCanRetryTheExactSameFix() = runTest {
        var failing=true
        val durable=store()
        val faulting=object:CellUnlockStore {
            override fun read()=durable.read()
            override fun write(records:List<CellUnlock>) {
                if (failing) throw IOException("disk full")
                durable.write(records)
            }
        }
        val feature=LocalCellExploration(boundary(),catalog(),source,faulting)
        feature.onLocation(sample(),true)
        try { feature.onLocation(sample(x=40.0,t=15_000),true);fail("write must fail") } catch (_:IOException) { }
        assertEquals(0,feature.progress("u-a").unlocked)
        failing=false
        assertTrue(feature.onLocation(sample(x=40.0,t=15_000),true).newlyUnlocked)
        assertEquals(1,durable.read().size)
    }

    @Test fun stageOneFileIsPreservedWhileSummaryUsesCellsAndRoadCountStaysZero() = runTest {
        val regionFile=File(temporary.root,"discoveries.bin")
        val regions=FileDiscoveryStore(regionFile,boundary().datasetVersion,source)
        regions.write(listOf(RegionDiscovered("first","u-a",boundary().id,1_000,source)))
        val original=regionFile.readBytes()
        val feature=LocalExplorationFeature(boundary(),source,regions,cells())
        feature.onLocation(sample())
        feature.onLocation(sample(x=40.0,t=15_000))
        val summary=feature.getSummary("u-a")
        assertArrayEquals(original,regionFile.readBytes())
        assertEquals(1,summary.discoveredRegionCount)
        assertEquals(1,summary.exploredUnitCount)
        assertEquals(4,summary.totalExplorableUnitCount)
        assertEquals(0,summary.unlockedRoadCount)
        assertEquals("25.0%",explorationRate(summary))
        assertEquals(15_000L,summary.lastUpdatedUtcMs)
        assertEquals(1_000L,feature.discoveries("u-a").single().timestampUtcMs)
    }

    @Test fun historicalFixesBeforeDiscoveryCannotUnlockAfterUpgrade() = runTest {
        val regions=FileDiscoveryStore(File(temporary.root,"discoveries.bin"),boundary().datasetVersion,source)
        regions.write(listOf(RegionDiscovered("first","u-a",boundary().id,20_000,source)))
        val feature=LocalExplorationFeature(boundary(),source,regions,cells())
        feature.onLocation(sample())
        feature.onLocation(sample(x=40.0,t=15_000))
        assertEquals(0,feature.getSummary("u-a").exploredUnitCount)
    }

    @Test fun combinedRetryDoesNotLoseRegionDiscoveryWhenCellWriteFails() = runTest {
        var failing=true
        val durable=store()
        val faulting=object:CellUnlockStore {
            override fun read()=durable.read()
            override fun write(records:List<CellUnlock>) {
                if (failing) throw IOException("disk full")
                durable.write(records)
            }
        }
        val feature=LocalExplorationFeature(boundary(),source,
            FileDiscoveryStore(File(temporary.root,"regions.bin"),boundary().datasetVersion,source),
            LocalCellExploration(boundary(),catalog(),source,faulting))
        feature.onLocation(sample())
        try { feature.onLocation(sample(x=40.0,t=15_000));fail("write must fail") } catch (_:IOException) { }
        assertEquals(1,feature.getSummary("u-a").discoveredRegionCount)
        assertEquals(0,feature.getSummary("u-a").exploredUnitCount)
        failing=false
        assertEquals(1,feature.onLocation(sample(x=40.0,t=15_000)).newlyUnlockedCells)
        assertEquals(1,feature.getSummary("u-a").exploredUnitCount)
    }

    @Test fun incompatibleCatalogOrCorruptionNeverErasesExistingCellFile() {
        val file=File(temporary.root,"versioned-cells.bin")
        store(file).write(listOf(CellUnlock("u-a",catalog().cellId(CellIndex(0,0)),10_000,source)))
        val original=file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store(file,"new-version").read() }
        assertArrayEquals(original,file.readBytes())
        file.writeText("broken")
        assertThrows(Exception::class.java) { store(file).read() }
        assertEquals("broken",file.readText())
    }

    @Test fun emptyCatalogHasNoInventedDenominator() = runTest {
        val feature=LocalCellExploration(boundary(),catalog(empty=true),source,store())
        assertEquals(0,feature.progress("u-a").total)
        assertFalse(feature.onLocation(sample(),true).newlyUnlocked)
    }

    @Test fun actualRoadDemoUnlocksExactlyItsVisitedCellsAndNoNeighbors() = runTest {
        val realBoundary=File("src/main/assets/exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
        val realCatalog=File("src/main/assets/exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
        assertEquals(realBoundary.id,realCatalog.regionId)
        assertTrue(realCatalog.cells.size>4)
        assertEquals(4,realCatalog.demo.map { realCatalog.grid.index(realCatalog.grid.project(it.point)) }.distinct().size)
        val realStore=FileCellUnlockStore(File(temporary.root,"real-cells.bin"),realCatalog.version,source)
        val feature=LocalCellExploration(realBoundary,realCatalog,source,realStore)
        val expected=realCatalog.demo.map { realCatalog.grid.index(realCatalog.grid.project(it.point)) }.toSet()
        for (fix in realCatalog.demo) {
            val p=realCatalog.grid.project(fix.point)
            val cell=realCatalog.grid.index(p)
            assertTrue(realBoundary.contains(fix.point,5f))
            assertTrue(requireNotNull(realCatalog.roadDistance(p,cell))<.01)
            feature.onLocation(LocationSample(fix.id,"u-a",fix.point.latitude,fix.point.longitude,
                10_000+fix.offsetMillis,5f,source),true)
        }
        assertEquals(expected,feature.unlockedCells("u-a"))
        assertEquals(4,realStore.read().size)
    }
}
