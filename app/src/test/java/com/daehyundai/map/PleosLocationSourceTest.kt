package com.daehyundai.map

import com.daehyundai.map.feature.exploration.domain.*
import com.daehyundai.map.feature.exploration.data.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PleosLocationSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_794_200_000_000L
    private class Connection : LocationConnection {
        lateinit var fix: (PleosFix?) -> Unit
        lateinit var error: (Exception) -> Unit
        var closed = 0
        var failStart = false
        override fun start(onFix: (PleosFix?) -> Unit, onError: (Exception) -> Unit) {
            fix = onFix; error = onError
            if (failStart) throw SecurityException("denied")
        }
        override fun close() { closed++ }
    }
    private fun fix(time: Long = now, confidence: Float = .9f) = PleosFix(37.1995,126.831,time,confidence)
    private fun source(c: Connection) = PleosLocationSource("user", { c }, { now })

    @Test fun sdkTimestampAndSourceArePreservedWithoutInventingAccuracy() = runTest {
        val c = Connection()
        val result = async { source(c).samples().first() }
        runCurrent(); c.fix(fix(now-500)); runCurrent()
        val sample = result.await()
        assertEquals(now-500,sample.timestampUtcMs)
        assertEquals(LocationSourceType.PLEOS,sample.source)
        assertNull(sample.accuracyMeters)
        assertEquals(1,c.closed)
    }

    @Test fun unverifiedEnvironmentDoesNotInterpretConfidence() = runTest {
        for (confidence in listOf(.99f,5f,Float.NaN,Float.POSITIVE_INFINITY)) {
            val c = Connection(); val result = async { source(c).samples().first() }
            runCurrent(); c.fix(fix(confidence=confidence)); runCurrent()
            assertNull(result.await().accuracyMeters)
        }
    }

    @Test fun duplicateProviderFixKeepsSameEventIdAndTime() = runTest {
        val c=Connection(); val result=async { source(c).samples().take(2).toList() }
        runCurrent(); c.fix(fix()); c.fix(fix()); runCurrent()
        val values=result.await()
        assertEquals(values[0],values[1]); assertEquals(1,c.closed)
    }

    @Test fun nullInvalidAndStaleInputsTerminateAndRelease() = runTest {
        val values=listOf(null,fix(now-15_001),fix(now+5_001),fix(0),
            PleosFix(Double.NaN,126.8,now,1f),PleosFix(91.0,126.8,now,1f))
        for (value in values) {
            val c=Connection()
            val result=async { runCatching { source(c).samples().first() } }
            runCurrent(); c.fix(value); runCurrent()
            assertTrue(result.await().isFailure); assertEquals(1,c.closed)
        }
    }

    @Test fun callbackErrorReleasesAndSurfacesFailure() = runTest {
        val c=Connection(); val result=async { runCatching { source(c).samples().first() } }
        runCurrent(); c.error(SecurityException("revoked")); runCurrent()
        assertTrue(result.await().exceptionOrNull() is SecurityException)
        assertEquals(1,c.closed)
    }

    @Test fun startFailureStillReleasesResources() = runTest {
        val c=Connection().also { it.failStart=true }
        assertTrue(runCatching { source(c).samples().first() }.isFailure)
        assertEquals(1,c.closed)
    }

    @Test fun stopCancelsListenerAndRestartUsesFreshConnection() = runTest {
        val connections=mutableListOf<Connection>()
        val source=PleosLocationSource("user",{ Connection().also { connections.add(it) } },{ now })
        repeat(2) {
            val job=launch { source.samples().collect() }; runCurrent()
            job.cancelAndJoin(); assertEquals(1,connections.last().closed)
        }
        assertEquals(2,connections.size)
    }

    @Test fun undocumentedAccuracyCannotUnlockRegionCellsOrLandmarks() {
        val boundary=File("src/main/assets/exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
        val roads=File("src/main/assets/exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
        val landmarks=File("src/main/assets/exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
        val region=RegionEntryDetector(boundary,LocationSourceType.PLEOS)
        val cells=RoadCellDetector(boundary,roads,LocationSourceType.PLEOS)
        val dwell=LandmarkDwellDetector(boundary,landmarks,LocationSourceType.PLEOS)
        val point=landmarks.landmarks.first().point
        for (offset in 0L..130_000L step 10_000L) {
            val sample=LocationSample("sdk-$offset","user",point.latitude,point.longitude,now+offset,null,LocationSourceType.PLEOS)
            assertEquals(EntryStatus.REJECTED,region.evaluate(sample).status)
            assertNull(cells.evaluate(sample).unlock)
            assertTrue(dwell.evaluate(sample).unlocks.isEmpty())
        }
    }

    @Test fun confidenceMappingIsExplicitAndPreservesPoorAccuracy() {
        val policy=PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS
        for (value in listOf(.9f,5f,25f,75f)) assertEquals(value,policy.accuracyMeters(value))
        for (value in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
            assertNull(policy.accuracyMeters(value))
    }

    @Test fun onlyVerifiedDebugEmulatorAndExactVersionsEnableUnlocks() {
        fun choose(debug:Boolean=true,emulator:Boolean=true,sdk:String="1.1.3",service:String?="1.0.0",code:Long?=6)=
            selectPleosAccuracyPolicy(debug,emulator,sdk,service,code)
        assertEquals(PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS,choose())
        for (policy in listOf(choose(debug=false),choose(emulator=false),choose(sdk="1.1.4"),
            choose(service="1.0.1"),choose(code=7),choose(service=null,code=null))) {
            assertEquals(PleosAccuracyPolicy.UNVERIFIED,policy)
            assertNull(policy.accuracyMeters(5f))
            assertEquals("pleos",policy.recordPrefix)
        }
        assertEquals("pleos-emulator",choose().recordPrefix)
    }

    @Test fun verifiedStreamMapsRawConfidenceWithoutDroppingInvalidAccuracySamples() = runTest {
        val c=Connection()
        val values=listOf(5f,25f,75f,0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)
        val stream=PleosLocationSource("user",{ c },{ now },PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS)
        val result=async { stream.samples().take(values.size).toList() }
        runCurrent(); values.forEachIndexed { i,value -> c.fix(fix(now+i, value)) }; runCurrent()
        val samples=result.await()
        assertEquals(listOf(5f,25f,75f,null,null,null,null),samples.map { it.accuracyMeters })
        assertTrue(samples.all { it.source==LocationSourceType.PLEOS })
    }

    private fun asset(name:String)=File("src/main/assets/exploration/$name").reader()
    private fun storedFeature(root:File):LocalExplorationFeature {
        val b=asset("namyang.boundary").use(RegionBoundaryReader::read)
        val roads=asset("namyang.roadcells").use(RoadCellCatalogReader::read)
        val landmarks=asset("namyang.landmarks").use(LandmarkCatalogReader::read)
        val src=LocationSourceType.PLEOS
        return LocalExplorationFeature(b,src,FileDiscoveryStore(File(root,"pleos-emulator-discoveries.bin"),b.datasetVersion,src),
            LocalCellExploration(b,roads,src,FileCellUnlockStore(File(root,"pleos-emulator-cells.bin"),roads.version,src)),
            LocalLandmarkCollection(b,landmarks,src,FileLandmarkUnlockStore(File(root,"pleos-emulator-landmarks.bin"),landmarks.version,src)))
    }

    private suspend fun replaySdk(fixes:List<PleosFix>,feature:LocalExplorationFeature):List<DiscoveryResult> {
        var clock=now
        val connection=object:LocationConnection {
            override fun start(onFix:(PleosFix?)->Unit,onError:(Exception)->Unit) {
                fixes.forEach { clock=it.timestampMillis; onFix(it) }
            }
            override fun close()=Unit
        }
        val stream=PleosLocationSource("user",{ connection },{ clock },PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS)
        return stream.samples().take(fixes.size).map { feature.onLocation(it) }.toList()
    }

    @Test fun sdkAdapterUnlocksAllThreeFeaturesAndRestoresSeparateRecords() = runTest {
        val root=temporary.newFolder()
        val mockFile=File(root,"mock-discoveries.bin").also { it.writeText("existing mock untouched") }
        val feature=storedFeature(root)
        val road=requireNotNull(feature.cellExploration).catalog.demo
        val landmark=requireNotNull(feature.landmarkCollection).catalog.landmarks.first()
        val fixes=road.map { PleosFix(it.point.latitude,it.point.longitude,now+it.offsetMillis,5f) } +
            (0L..120_000L step 10_000L).map {
                PleosFix(landmark.point.latitude,landmark.point.longitude,now+road.last().offsetMillis+31_000+it,5f)
            }
        val results=replaySdk(fixes,feature)
        assertEquals(1,results.count { it.newlyDiscovered })
        assertEquals(4,results.sumOf { it.newlyUnlockedCells })
        assertEquals(1,results.sumOf { it.newlyCollectedLandmarks })
        val restored=storedFeature(root)
        assertEquals(1,restored.getSummary("user").discoveredRegionCount)
        assertEquals(4,restored.getSummary("user").exploredUnitCount)
        assertEquals(1,restored.landmarkCollection!!.summary("user").unlockedCount)
        assertEquals("existing mock untouched",mockFile.readText())
    }

    @Test fun poorSdkAccuracyBreaksRegionConfirmationInsteadOfBeingSilentlyDropped() = runTest {
        val feature=storedFeature(temporary.newFolder())
        val point=feature.cellExploration!!.catalog.demo.first().point
        val fixes=listOf(0L to 5f,4_000L to 75f,5_000L to 5f,8_000L to 5f).map { (time,accuracy) ->
            PleosFix(point.latitude,point.longitude,now+time,accuracy)
        }
        val results=replaySdk(fixes,feature)
        assertFalse(results[0].newlyDiscovered)
        assertEquals(EntryStatus.REJECTED,results[1].status)
        assertFalse(results[2].newlyDiscovered)
        assertTrue(results[3].newlyDiscovered)
    }

    @Test fun poorSdkAccuracyRestartsLandmarkDwell() = runTest {
        val feature=storedFeature(temporary.newFolder())
        val landmark=feature.landmarkCollection!!.catalog.landmarks.first()
        val point=landmark.point
        val times=(0L..190_000L step 10_000L).toList()
        val fixes=times.map { time -> PleosFix(point.latitude,point.longitude,now+time,if(time==60_000L) 75f else 5f) }
        val results=replaySdk(fixes,feature)
        assertTrue(results.dropLast(1).all { it.newlyCollectedLandmarks==0 })
        assertEquals(1,results.last().newlyCollectedLandmarks)
    }
}
