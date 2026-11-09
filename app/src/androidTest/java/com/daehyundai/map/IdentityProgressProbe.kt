package com.daehyundai.map
import android.content.Context
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.profile.data.InstallationIdentity
import com.daehyundai.map.feature.exploration.openExplorationSession
import com.daehyundai.map.feature.exploration.data.migrateLegacyExploration
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.*
class IdentityProgressProbe(private val context:Context) {
    suspend fun run():String {
        val idFile=File(context.filesDir,"identity/installation-user.txt")
        val id=withContext(Dispatchers.IO) { InstallationIdentity(idFile).getOrCreate() }
        val before=withContext(Dispatchers.IO) {
            val legacy=openExplorationSession(context,"u-demo-01").feature.getSummary("u-demo-01")
            val current=openExplorationSession(context,id).feature.getSummary(id)
            if(legacy.discoveredRegionCount>0) legacy else current
        }
        withContext(Dispatchers.IO) {
            val b=context.assets.open("exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
            val roads=context.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
            val landmarks=context.assets.open("exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
            migrateLegacyExploration(File(context.filesDir,"exploration"),b,roads,landmarks,id)
        }
        val session=withContext(Dispatchers.IO) { openExplorationSession(context,id,LocationSourceType.MOCK) }
        withContext(Dispatchers.Main) { session.refresh() }
        check(session.summary.discoveredRegionCount==before.discoveredRegionCount)
        check(session.summary.exploredUnitCount==before.exploredUnitCount)
        val expected=session.summary.discoveredRegionCount*100L+session.summary.exploredUnitCount*10L+session.landmarkSummary!!.unlockedCount*200L
        check(session.progress.totalXp==expected && session.progress.level==1+(expected/100).toInt())
        val restored=withContext(Dispatchers.IO) { openExplorationSession(context,id) }
        withContext(Dispatchers.Main) { restored.refresh() }
        check(restored.progress==session.progress)
        check(withContext(Dispatchers.IO) { InstallationIdentity(idFile).getOrCreate() }==id)
        return "PASS: stable UUID; legacy records preserved; XP=$expected; level=${session.progress.level}; duplicate/reopen unchanged"
    }
}
