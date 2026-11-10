package com.daehyundai.map.feature.exploration.cloud

import android.content.Context
import com.daehyundai.map.core.model.LocationSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudSync(context:Context,private val owner:String,private val partition:String, private val catalogVersion:String,
    private val remote:ExplorationCloud=SupabaseCloud(context)) {
    private val cache=CloudCache(context.applicationContext)
    private val mutex=Mutex()
    @Volatile var message="서버 연결 설정 필요 · 로컬 기록 보존"
        private set
    suspend fun enqueue(sample:LocationSample)=withContext(Dispatchers.IO) {
        if(SupabaseCloud.configured) try { cache.enqueue(owner,partition,sample) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { message="전송 대기열 저장 실패 · 로컬 해금 기록 보존" }
    }
    suspend fun sync():CloudSnapshot? = mutex.withLock { withContext(Dispatchers.IO) {
        if(!SupabaseCloud.configured) return@withContext null
        try {
            val (ids,payload)=cache.batch(owner,partition)
            val response=remote.synchronize(partition,payload)
            val snapshot=CloudCache.parse(response,catalogVersion) // Validate before acknowledging any input.
            cache.acknowledge(owner,partition,ids,response)
            message="서버 확정 ${snapshot.grants.count { it.kind=="cell" }}셀 · ${snapshot.xp} XP · 전송 대기 ${cache.count(owner,partition)}건"
            snapshot
        } catch(e:CancellationException) { throw e
        } catch(e:CatalogVersionMismatch) {
            message="서버 지도 업데이트 필요 · 미전송 기록 보존"
            null
        } catch(e:Exception) {
            message="오프라인·서버 연결 확인 필요 · 미전송 기록 보존"
            try { cache.snapshot(owner,partition,catalogVersion) } catch(_:CatalogVersionMismatch) { null }
        }
    } }
}
