package com.daehyundai.map

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.cloud.*
import com.daehyundai.map.feature.exploration.domain.RoadCellCatalogReader
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID

/** App-originated HTTPS test; own anonymous identity/outbox owner. Never injects emulator GPS. */
class CloudSyncProbe:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            runBlocking {
                val owner="probe-${UUID.randomUUID()}"
                val catalog=targetContext.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
                val cache=CloudCache(targetContext)
                val backend=SupabaseCloud(targetContext,owner)
                val offline=object:ExplorationCloud { override fun synchronize(partition:String,samples:String):String=throw IOException("simulated offline") }
                val first=CloudSync(targetContext,owner,"pleos-emulator",catalog.version,offline)
                val base=System.currentTimeMillis()-120000
                catalog.demo.forEach { first.enqueue(LocationSample(it.id,owner,it.point.latitude,it.point.longitude,base+it.offsetMillis,5f,LocationSourceType.PLEOS)) }
                check(first.sync()==null);check(cache.count(owner,"pleos-emulator")==catalog.demo.size)
                val lostReply=object:ExplorationCloud { override fun synchronize(partition:String,samples:String):String {
                    backend.synchronize(partition,samples);throw IOException("simulated lost response after commit")
                } }
                check(CloudSync(targetContext,owner,"pleos-emulator",catalog.version,lostReply).sync()==null)
                check(cache.count(owner,"pleos-emulator")==catalog.demo.size)
                val restarted=CloudSync(targetContext,owner,"pleos-emulator",catalog.version,backend)
                val result=requireNotNull(restarted.sync())
                check(result.grants.count { it.kind=="cell" }==4);check(result.xp==140L)
                check(cache.count(owner,"pleos-emulator")==0)
                check(restarted.sync()==result)
                cache.close()
                finish(Activity.RESULT_OK,Bundle().apply { putString("result","PASS: Android HTTPS, offline retention, process-recreated retry, lost-response retry, 4 cells / 140 XP, no duplicate rewards; emulator GPS unchanged") })
            }
        } catch(e:Exception) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","FAIL: ${e.javaClass.simpleName}: ${e.message}") }) }
    }
}
