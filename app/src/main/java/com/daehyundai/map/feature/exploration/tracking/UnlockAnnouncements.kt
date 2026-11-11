package com.daehyundai.map.feature.exploration.tracking

import com.daehyundai.map.feature.exploration.data.DiscoveryResult
import kotlinx.coroutines.*

/** Only newly persisted unlocks; batch road updates to avoid speaking for every GPS fix. */
class UnlockAnnouncements(private val scope: CoroutineScope, private val speak: (String) -> Unit,
    private val batchMillis: Long = 10_000, private val minIntervalMillis:Long=60_000,
    private val nowMillis:()->Long={System.nanoTime()/1_000_000}) {
    init { require(batchMillis>0 && minIntervalMillis>=0) }
    private var lastSpoken:Long?=null
    private var regions=0
    private var cells=0
    private var landmarks=0
    private var pending:Job?=null
    fun record(result:DiscoveryResult) {
        if(result.newlyDiscovered) regions++
        cells+=result.newlyUnlockedCells
        landmarks+=result.newlyCollectedLandmarks
        if(regions+cells+landmarks==0 || pending!=null) return
        val remaining=lastSpoken?.let { (it+minIntervalMillis-nowMillis()).coerceAtLeast(0) } ?: 0
        pending=scope.launch {
            delay(maxOf(batchMillis,remaining))
            val text=buildList {
                if(regions>0) add("새로운 지역이 해금되었습니다.")
                if(cells>0) add("새로운 탐험 구역 ${cells}개를 해금했어요.")
                if(landmarks>0) add("랜드마크 도감 ${landmarks}개가 해금되었습니다.")
            }.joinToString(" ")
            regions=0; cells=0; landmarks=0; pending=null
            lastSpoken=nowMillis()
            speak(text)
        }
    }
    fun cancel() { pending?.cancel(); pending=null; regions=0; cells=0; landmarks=0 }
}
