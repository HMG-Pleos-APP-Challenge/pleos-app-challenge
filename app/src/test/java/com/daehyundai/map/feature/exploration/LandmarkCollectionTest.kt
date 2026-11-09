package com.daehyundai.map.feature.exploration

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LandmarkCollectionTest {
    @get:Rule val temporary=TemporaryFolder()
    private val source=LocationSourceType.MOCK
    private fun boundary()=RegionBoundary("region","region","boundary-v1",listOf(BoundaryPolygon(listOf(
        listOf(GeoPoint(37.18,126.78),GeoPoint(37.18,126.82),GeoPoint(37.22,126.82),
            GeoPoint(37.22,126.78),GeoPoint(37.18,126.78))))))
    private fun landmark(id:String="landmark-a",lon:Double=126.8)=LandmarkDefinition(id,id,GeoPoint(37.2,lon),
        100.0,120_000,15_000,"https://www.openstreetmap.org/")
    private fun catalog()=LandmarkCatalog("landmarks-v1","region",listOf(landmark()))
    private fun sample(t:Long=0,user:String="u-a",point:GeoPoint=landmark().point)=
        LocationSample("event-$t",user,point.latitude,point.longitude,t,5f,source)
    private fun detector()=LandmarkDwellDetector(boundary(),catalog(),source)
    private fun store(file:File=File(temporary.root,"landmarks.bin"),version:String=catalog().version)=
        FileLandmarkUnlockStore(file,version,source)
    private fun collection()=LocalLandmarkCollection(boundary(),catalog(),source,store())

    @Test fun exactTwoMinuteBoundaryRequiresAllValidObservations() {
        val d=detector()
        for(t in 0L..110_000L step 10_000L) assertTrue(d.evaluate(sample(t)).unlocks.isEmpty())
        assertTrue(d.evaluate(sample(119_999)).unlocks.isEmpty())
        val unlocked=d.evaluate(sample(120_000)).unlocks.single()
        assertEquals(120_000L,unlocked.timestampUtcMs)
        assertEquals("landmark-a",unlocked.landmarkId)
    }

    @Test fun oneFixAndJumpingTheClockCannotCreateDwell() {
        val d=detector()
        assertTrue(d.evaluate(sample()).unlocks.isEmpty())
        assertTrue(d.evaluate(sample(120_000)).unlocks.isEmpty())
        assertEquals(0L,d.observedMillis("u-a",landmark()))
    }

    @Test fun exactGapIsAllowedButOneMillisecondMoreRestarts() {
        val accepted=detector()
        var result=DwellEvaluation(false)
        for(t in 0L..120_000L step 15_000L) result=accepted.evaluate(sample(t))
        assertEquals(1,result.unlocks.size)
        val broken=detector()
        broken.evaluate(sample())
        broken.evaluate(sample(15_001))
        assertEquals(0L,broken.observedMillis("u-a",landmark()))
    }

    @Test fun leavingAndReturningDoesNotAddSeparatedVisitsTogether() {
        val d=detector()
        for(t in 0L..60_000L step 10_000L) d.evaluate(sample(t))
        d.evaluate(sample(70_000,point=GeoPoint(37.2,126.803)))
        for(t in 80_000L..140_000L step 10_000L) assertTrue(d.evaluate(sample(t)).unlocks.isEmpty())
        assertEquals(60_000L,d.observedMillis("u-a",landmark()))
    }

    @Test fun unknownPoorOrInvalidAccuracyBreaksDwell() {
        for(accuracy in listOf(null,26f,Float.NaN,-1f,Float.POSITIVE_INFINITY)) {
            val d=detector()
            for(t in 0L..60_000L step 10_000L) d.evaluate(sample(t))
            assertFalse(d.evaluate(sample(70_000).copy(accuracyMeters=accuracy)).accepted)
            d.evaluate(sample(80_000))
            assertEquals(0L,d.observedMillis("u-a",landmark()))
        }
    }

    @Test fun accuracyCircleMustFitInsideLandmarkRadius() {
        val target=landmark()
        val grid=LocalGrid(126.8,37.2,100.0)
        val nearEdge=grid.unproject(MeterPoint(98.0,0.0))
        assertTrue(target.distanceMeters(nearEdge)<100.0)
        val d=detector()
        for(t in 0L..120_000L step 10_000L) assertTrue(d.evaluate(sample(t,point=nearEdge)).unlocks.isEmpty())
        assertEquals(0L,d.observedMillis("u-a",target))
    }

    @Test fun invalidCoordinatesAndRegionExitResetUnfinishedDwell() {
        for(invalid in listOf(sample(70_000).copy(latitude=Double.NaN),sample(70_000).copy(longitude=181.0),
            sample(70_000).copy(eventId=""),sample(70_000).copy(timestampUtcMs=-1),
            sample(70_000,point=GeoPoint(37.55,126.98)))) {
            val d=detector()
            for(t in 0L..60_000L step 10_000L) d.evaluate(sample(t))
            assertFalse(d.evaluate(invalid).accepted)
            d.evaluate(sample(80_000))
            assertEquals(0L,d.observedMillis("u-a",landmark()))
        }
    }

    @Test fun duplicateIdsEvenWithAlteredTimeCannotAdvanceDwell() {
        val d=detector()
        d.evaluate(sample())
        d.evaluate(sample(10_000))
        assertFalse(d.evaluate(sample(20_000).copy(eventId="event-0")).accepted)
        assertEquals(10_000L,d.observedMillis("u-a",landmark()))
        d.evaluate(sample(30_000))
        assertEquals(0L,d.observedMillis("u-a",landmark()))
    }

    @Test fun backwardsTimestampAndOtherSourceCannotCompleteTheTimer() {
        val d=detector()
        d.evaluate(sample(10_000))
        assertFalse(d.evaluate(sample(9_000)).accepted)
        assertFalse(d.evaluate(sample(20_000).copy(source=LocationSourceType.PLEOS)).accepted)
        assertTrue(d.evaluate(sample(120_000)).unlocks.isEmpty())
    }

    @Test fun usersAndLandmarksHaveIndependentDwellWindows() {
        val second=landmark("landmark-b",126.805)
        val c=LandmarkCatalog("two","region",listOf(landmark(),second))
        val d=LandmarkDwellDetector(boundary(),c,source)
        for(t in 0L..120_000L step 10_000L) {
            val a=d.evaluate(sample(t))
            if(t==120_000L) assertEquals("landmark-a",a.unlocks.single().landmarkId)
            assertTrue(d.evaluate(sample(t,user="u-b",point=second.point).copy(eventId="b-$t")).unlocks.isEmpty() || t==120_000L)
        }
        assertEquals(0L,d.observedMillis("u-a",second))
        assertEquals(0L,d.observedMillis("u-b",landmark()))
    }

    @Test fun regionMustBeDiscoveredBeforePublishingACollection() = runTest {
        val repo=collection()
        for(t in 0L..120_000L step 10_000L) assertEquals(0,repo.onLocation(sample(t),false))
        assertEquals(0,repo.summary("u-a").unlockedCount)
        assertEquals(1,repo.onLocation(sample(130_000),true))
    }

    @Test fun earnedCollectionSurvivesRestartAndNeverChangesFirstEarnedTime() = runTest {
        val repo=collection()
        for(t in 0L..120_000L step 10_000L) repo.onLocation(sample(t),true)
        val reopened=collection()
        assertEquals(1,reopened.summary("u-a").unlockedCount)
        assertEquals(0,reopened.summary("u-b").unlockedCount)
        for(t in 160_000L..280_000L step 10_000L) assertEquals(0,reopened.onLocation(sample(t),true))
        assertEquals(120_000L,reopened.summary("u-a").cards.single().unlockedUtcMs)
        assertEquals(1,store().read().size)
    }

    @Test fun unfinishedDwellIsNotRestoredOrCountedWhileAppIsClosed() = runTest {
        val first=collection()
        for(t in 0L..60_000L step 10_000L) first.onLocation(sample(t),true)
        assertEquals(60_000L,first.summary("u-a").cards.single().observedMillis)
        val reopened=collection()
        assertEquals(0L,reopened.summary("u-a").cards.single().observedMillis)
        for(t in 70_000L..120_000L step 10_000L) reopened.onLocation(sample(t),true)
        assertEquals(0,reopened.summary("u-a").unlockedCount)
        assertEquals(50_000L,reopened.summary("u-a").cards.single().observedMillis)
    }

    @Test fun failedWriteCanRetryTheExactFinalFixWithoutLosingDwell() = runTest {
        var failing=true
        val durable=store()
        val faulting=object:LandmarkUnlockStore {
            override fun read()=durable.read()
            override fun write(records:List<LandmarkUnlock>) {
                if(failing) throw IOException("disk full")
                durable.write(records)
            }
        }
        val repo=LocalLandmarkCollection(boundary(),catalog(),source,faulting)
        for(t in 0L..110_000L step 10_000L) repo.onLocation(sample(t),true)
        try { repo.onLocation(sample(120_000),true);fail("write must fail") } catch(_:IOException) { }
        assertEquals(0,repo.summary("u-a").unlockedCount)
        assertEquals(110_000L,repo.summary("u-a").cards.single().observedMillis)
        failing=false
        assertEquals(1,repo.onLocation(sample(120_000),true))
        assertEquals(1,durable.read().size)
    }

    @Test fun catalogMismatchCorruptionAndUnknownLandmarkDoNotEraseFiles() {
        val file=File(temporary.root,"versioned.bin")
        store(file).write(listOf(LandmarkUnlock("u-a","landmark-a",120_000,source)))
        val original=file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store(file,"new-version").read() }
        assertArrayEquals(original,file.readBytes())
        store(file).write(listOf(LandmarkUnlock("u-a","unknown",120_000,source)))
        assertThrows(IllegalArgumentException::class.java) { LocalLandmarkCollection(boundary(),catalog(),source,store(file)) }
        file.writeText("broken")
        assertThrows(Exception::class.java) { store(file).read() }
        assertEquals("broken",file.readText())
    }

    @Test fun emptyCatalogDoesNotInventCollectibles() = runTest {
        val repo=LocalLandmarkCollection(boundary(),LandmarkCatalog("empty","region",emptyList()),source,store())
        assertEquals(0,repo.summary("u-a").totalCount)
        assertEquals(0,repo.onLocation(sample(),true))
    }

    @Test fun actualLandmarkDwellDoesNotChangeRoadCellRateOrStageOneFile() = runTest {
        val actual=File("src/main/assets/exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
        val roads=File("src/main/assets/exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
        val landmarks=File("src/main/assets/exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
        val target=landmarks.landmarks.single()
        assertEquals("osm:node:13851756178",target.id)
        assertTrue(actual.contains(target.point,5f))
        val regionFile=File(temporary.root,"regions.bin")
        val regions=FileDiscoveryStore(regionFile,actual.datasetVersion,source)
        regions.write(listOf(RegionDiscovered("first","u-a",actual.id,1_000,source)))
        val original=regionFile.readBytes()
        val collection=LocalLandmarkCollection(actual,landmarks,source,
            FileLandmarkUnlockStore(File(temporary.root,"real-landmarks.bin"),landmarks.version,source))
        val feature=LocalExplorationFeature(actual,source,regions,
            LocalCellExploration(actual,roads,source,FileCellUnlockStore(File(temporary.root,"real-cells.bin"),roads.version,source)),collection)
        val before=feature.getSummary("u-a")
        for(t in 0L..120_000L step 10_000L) feature.onLocation(LocationSample("real-$t","u-a",
            target.point.latitude,target.point.longitude,10_000+t,5f,source))
        assertEquals(1,collection.summary("u-a").unlockedCount)
        assertEquals(before,feature.getSummary("u-a"))
        assertArrayEquals(original,regionFile.readBytes())
    }
}
