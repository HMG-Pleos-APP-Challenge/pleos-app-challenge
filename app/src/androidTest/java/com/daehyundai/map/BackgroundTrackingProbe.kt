package com.daehyundai.map

import android.annotation.TargetApi
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.openExplorationSession
import com.daehyundai.map.feature.exploration.location.*
import com.daehyundai.map.feature.exploration.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@TargetApi(31)
class BackgroundTrackingProbe(private val context:Context,private val report:(String)->Unit) {
    suspend fun run(confirmed:Boolean):String=coroutineScope {
        check(confirmed && Build.HARDWARE in setOf("ranchu","goldfish"))
        val user="tracking-probe-${UUID.randomUUID()}"
        val manager=context.getSystemService(LocationManager::class.java)
        val voices=mutableSetOf<String>()
        val observer=launch(Dispatchers.Main) { ExplorationTracking.state.collect { voices.add(it.voice) } }
        var providerAdded=false
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,false,true,false,false,true,true,true,
                ProviderProperties.POWER_USAGE_LOW,ProviderProperties.ACCURACY_FINE)
            providerAdded=true;manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            withContext(Dispatchers.Main) { ExplorationTracking.start(context,user) }
            val state=withTimeout(20_000) { ExplorationTracking.state.first { it.running && it.session!=null && it.userId==user } }
            check(state.policy==PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS)
            report("PASS: foreground location service owns session without Activity/Compose")
            val road=state.session!!.feature.cellExploration!!.catalog.demo
            for((index,fix) in road.withIndex()) {
                if(index>0) delay(fix.offsetMillis-road[index-1].offsetMillis)
                var received=false
                for(attempt in 0..3) {
                    val time=System.currentTimeMillis()
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,Location(LocationManager.GPS_PROVIDER).apply {
                        latitude=fix.point.latitude;longitude=fix.point.longitude;accuracy=5f
                        altitude=0.0;bearing=0f;speed=0f;this.time=time
                        elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    })
                    val match=withTimeoutOrNull(2_500) { ExplorationTracking.state.first { it.lastSampleTimestamp==time || !it.running } }
                    if(match!=null) {
                        check(match.running) { match.message }
                        received=true; break
                    }
                }
                check(received) { "SDK callback missing; state=${ExplorationTracking.state.value.message}" }
            }
            report("Observed background region=${ExplorationTracking.state.value.session!!.summary.discoveredRegionCount}, cells=${ExplorationTracking.state.value.session!!.summary.exploredUnitCount}")
            check(ExplorationTracking.state.value.session!!.summary.exploredUnitCount==4)
            check(ExplorationTracking.state.value.session!!.summary.discoveredRegionCount==1)
            check(ExplorationTracking.state.value.session!!.progress.totalXp==140L)
            report("PASS: background SDK samples -> region=1, roadCells=4, XP=140")
            check(withTimeoutOrNull(30_000) { ExplorationTracking.state.first { it.voice.contains("재생 완료") } }!=null) { "TTS did not finish: ${ExplorationTracking.state.value.voice}; observed=$voices" }
            report("PASS: on-device Pleos TTS playback completed")
            val restored=withContext(Dispatchers.IO) { openExplorationSession(context,user,LocationSourceType.PLEOS,state.policy.recordPrefix) }
            withContext(Dispatchers.Main) { restored.refresh() }
            check(restored.summary.discoveredRegionCount==1 && restored.summary.exploredUnitCount==4)
            check(restored.progress.totalXp==140L && restored.progress.level==2)
            withContext(Dispatchers.Main) { ExplorationTracking.stop(context) }
            withTimeout(5_000) { ExplorationTracking.state.first { !it.running } }
            val count=ExplorationTracking.state.value.receivedCount
            delay(2_000);check(ExplorationTracking.state.value.receivedCount==count)
            "PASS: background records restored; TTS done; explicit stop released tracking; voiceStates=${voices.size}"
        } finally {
            withContext(Dispatchers.Main) { ExplorationTracking.stop(context) }
            observer.cancelAndJoin()
            if(providerAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }
    }
}
