package com.daehyundai.map

import com.daehyundai.map.core.data.FileProgressRepository
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.profile.data.InstallationIdentity
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgressPersistenceTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun award(id:String="a",amount:Long=10,user:String="user")=XpAward(id,user,amount,"test","event-$id",1000)
    @Test fun installationIdentityIsStableAcrossInstancesAndConcurrentCalls()=runTest {
        val file=File(temporary.root,"identity/id.txt")
        val ids=(0..15).map { async(Dispatchers.Default) { InstallationIdentity(file).getOrCreate() } }.awaitAll()
        assertEquals(1,ids.toSet().size);assertTrue(ids.first().startsWith("u-"))
        assertEquals(ids.first(),InstallationIdentity(file).getOrCreate())
    }
    @Test fun corruptIdentityIsNotSilentlyReplaced() {
        val file=temporary.newFile().also { it.writeText("invalid") }
        assertTrue(runCatching { InstallationIdentity(file).getOrCreate() }.isFailure)
        assertEquals("invalid",file.readText())
    }
    @Test fun xpIsDurableAndLevelBoundaryIsExact()=runTest {
        val file=File(temporary.root,"xp.bin");val repo=FileProgressRepository(file)
        assertEquals(UserProgress("user",0,1),repo.getProgress("user"))
        assertTrue(repo.awardOnce(award(amount=99)));assertFalse(repo.awardOnce(award(amount=99)))
        assertEquals(1,repo.getProgress("user").level)
        repo.awardOnce(award("next",1))
        assertEquals(UserProgress("user",100,2),FileProgressRepository(file).getProgress("user"))
        assertEquals(0,repo.getProgress("other").totalXp)
    }
    @Test fun concurrentRepositoriesDoNotLoseOrDuplicateAwards()=runTest {
        val file=File(temporary.root,"xp.bin");val repos=listOf(FileProgressRepository(file),FileProgressRepository(file))
        val awarded=(0 until 100).map { i -> async(Dispatchers.Default) { repos[i%2].awardOnce(award("a-${i%10}")) } }.awaitAll()
        assertEquals(10,awarded.count { it });assertEquals(100,repos.first().getProgress("user").totalXp)
    }
    @Test fun invalidConflictingAndOverflowAwardsKeepPreviousLedger()=runTest {
        val file=File(temporary.root,"xp.bin");val repo=FileProgressRepository(file)
        repo.awardOnce(award());val bytes=file.readBytes()
        assertTrue(runCatching { repo.awardOnce(award(amount=11)) }.isFailure)
        assertTrue(runCatching { repo.awardOnce(award("bad",-1)) }.isFailure)
        assertArrayEquals(bytes,file.readBytes())
        val maximum=FileProgressRepository(File(temporary.root,"max.bin"))
        maximum.awardOnce(award(amount=Long.MAX_VALUE))
        assertTrue(runCatching { maximum.awardOnce(award("overflow",1)) }.isFailure)
        assertEquals(Long.MAX_VALUE,maximum.getProgress("user").totalXp)
    }
    @Test fun corruptLedgerAndWriteFailureDoNotProduceSuccess()=runTest {
        val file=temporary.newFile().also { it.writeText("corrupt") }
        assertTrue(runCatching { FileProgressRepository(file).getProgress("user") }.isFailure)
        assertEquals("corrupt",file.readText())
        val blocker=temporary.newFile()
        assertTrue(runCatching { FileProgressRepository(File(blocker,"xp.bin")).awardOnce(award()) }.isFailure)
    }
    private fun asset(name:String)=File("src/main/assets/exploration/$name").reader()
    private val boundary get()=asset("namyang.boundary").use(RegionBoundaryReader::read)
    private val roads get()=asset("namyang.roadcells").use(RoadCellCatalogReader::read)
    private val landmarks get()=asset("namyang.landmarks").use(LandmarkCatalogReader::read)
    private fun seed(root:File,prefix:String="mock",source:LocationSourceType=LocationSourceType.MOCK) {
        FileDiscoveryStore(File(root,"$prefix-discoveries.bin"),boundary.datasetVersion,source)
            .write(listOf(RegionDiscovered("legacy-region","u-demo-01",boundary.id,1000,source)))
        FileCellUnlockStore(File(root,"$prefix-cells.bin"),roads.version,source)
            .write(listOf(CellUnlock("u-demo-01",roads.cellId(roads.cells.keys.first()),2000,source)))
        FileLandmarkUnlockStore(File(root,"$prefix-landmarks.bin"),landmarks.version,source)
            .write(listOf(LandmarkUnlock("u-demo-01",landmarks.landmarks.first().id,3000,source)))
    }
    private fun feature(root:File,prefix:String="mock",source:LocationSourceType=LocationSourceType.MOCK)=
        LocalExplorationFeature(boundary,source,FileDiscoveryStore(File(root,"$prefix-discoveries.bin"),boundary.datasetVersion,source),
            LocalCellExploration(boundary,roads,source,FileCellUnlockStore(File(root,"$prefix-cells.bin"),roads.version,source)),
            LocalLandmarkCollection(boundary,landmarks,source,FileLandmarkUnlockStore(File(root,"$prefix-landmarks.bin"),landmarks.version,source)))

    @Test fun legacyMigrationPreservesAllPartitionsAndIsIdempotent()=runTest {
        val root=temporary.newFolder();val id="new-user"
        seed(root);seed(root,"pleos-emulator",LocationSourceType.PLEOS);seed(root,"pleos",LocationSourceType.PLEOS)
        migrateLegacyExploration(root,boundary,roads,landmarks,id)
        val after=root.listFiles()!!.associate { it.name to it.readBytes() }
        migrateLegacyExploration(root,boundary,roads,landmarks,id)
        root.listFiles()!!.forEach { assertArrayEquals(after.getValue(it.name),it.readBytes()) }
        for(prefix in listOf("mock","pleos-emulator","pleos")) {
            val own=feature(root,prefix,if(prefix=="mock") LocationSourceType.MOCK else LocationSourceType.PLEOS)
            assertEquals(1,own.getSummary(id).discoveredRegionCount);assertEquals(1,own.getSummary(id).exploredUnitCount)
            assertEquals(1,own.landmarkCollection!!.summary(id).unlockedCount)
            assertEquals(0,own.getSummary("u-demo-01").discoveredRegionCount)
        }
    }
    @Test fun interruptedMigrationCanRetryWithSameIdentity()=runTest {
        val root=temporary.newFolder();seed(root)
        val cells=File(root,"mock-cells.bin");val valid=cells.readBytes();cells.writeText("broken")
        assertTrue(runCatching { migrateLegacyExploration(root,boundary,roads,landmarks,"new-user") }.isFailure)
        assertEquals("new-user",FileDiscoveryStore(File(root,"mock-discoveries.bin"),boundary.datasetVersion,LocationSourceType.MOCK).read().single().userId)
        cells.writeBytes(valid);migrateLegacyExploration(root,boundary,roads,landmarks,"new-user")
        assertEquals(1,feature(root).landmarkCollection!!.summary("new-user").unlockedCount)
    }
    @Test fun rewardsReconcileSavedUnlocksOnceAndPartitionProgress()=runTest {
        val root=temporary.newFolder();seed(root)
        migrateLegacyExploration(root,boundary,roads,landmarks,"new-user")
        val ledger=File(temporary.root,"mock-xp.bin");val rewards=ExplorationRewards(FileProgressRepository(ledger),"mock")
        val progress=rewards.reconcile(feature(root),"new-user")
        assertEquals(UserProgress("new-user",310,4),progress)
        val bytes=ledger.readBytes()
        assertEquals(progress,rewards.reconcile(feature(root),"new-user"));assertArrayEquals(bytes,ledger.readBytes())
        val separate=FileProgressRepository(File(temporary.root,"pleos-xp.bin"))
        assertEquals(0,separate.getProgress("new-user").totalXp)
    }
}
