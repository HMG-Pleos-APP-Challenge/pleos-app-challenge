package com.daehyundai.map

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.openExplorationSession
import com.daehyundai.map.feature.exploration.domain.GeoPoint
import com.daehyundai.map.feature.exploration.location.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Manual emulator-only end-to-end check. Uses real SDK callbacks and isolated cache files. */
@TargetApi(31)
class SdkUnlockProbe : Instrumentation() {
    private var confidenceOnly=false
    private var confirmed=false
    override fun onCreate(arguments:Bundle?) {
        super.onCreate(arguments)
        confirmed=arguments?.getString("syntheticEmulator")=="true"
        confidenceOnly=arguments?.getString("mode")=="confidence"
        start()
    }
    override fun onStart() {
        val result=runCatching {
            if(confidenceOnly) ConfidenceProbe(targetContext,::runOnMainSync,::report).run(confirmed)
            else runBlocking { verify() }
        }
        finish(if(result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("stream",result.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" })
        })
    }
    private fun report(message:String)=sendStatus(0,Bundle().apply { putString("stream",message+"\n") })

    @SuppressLint("MissingPermission") // Custom Pleos permission and emulator-only gate checked below.
    private suspend fun verify():String=coroutineScope {
        check(Build.VERSION.SDK_INT>=31 && confirmed && Build.HARDWARE in setOf("ranchu","goldfish")) {
            "Requires syntheticEmulator=true on an API31+ emulator"
        }
        check(targetContext.checkSelfPermission(PLEOS_LOCATION_PERMISSION)==PackageManager.PERMISSION_GRANTED)
        val policy=pleosAccuracyPolicy(targetContext)
        check(policy==PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS) { "Unverified SDK/service combination" }
        val manager=targetContext.getSystemService(LocationManager::class.java)
        val root=File(targetContext.cacheDir,"sdk-unlock-check-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated=object:ContextWrapper(targetContext) { override fun getFilesDir():File=root }
        val user="sdk-integration-check"
        val session=withContext(Dispatchers.IO) {
            openExplorationSession(isolated,user,LocationSourceType.PLEOS,policy.recordPrefix)
        }
        val samples=Channel<LocationSample>(64)
        val collector=launch(Dispatchers.Main) {
            try {
                PleosLocationSource(user,{ PleosSdkConnection(targetContext) },accuracyPolicy=policy)
                    .samples().collect { samples.send(it) }
            } catch(cancelled:CancellationException) { throw cancelled
            } catch(error:Exception) { samples.close(error) }
        }
        var providerAdded=false
        var accepted=0
        suspend fun feed(point:GeoPoint,accuracy:Float):LocationSample {
            repeat(4) {
                val time=System.currentTimeMillis()
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,Location(LocationManager.GPS_PROVIDER).apply {
                    latitude=point.latitude; longitude=point.longitude; this.accuracy=accuracy
                    altitude=0.0; bearing=0f; speed=0f; this.time=time
                    elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                })
                val sample=withTimeoutOrNull(2_500) {
                    while(true) {
                        val next=samples.receive()
                        if(next.timestampUtcMs==time && next.latitude==point.latitude && next.longitude==point.longitude)
                            return@withTimeoutOrNull next
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                if(sample!=null) {
                    check(sample.accuracyMeters==accuracy && sample.source==LocationSourceType.PLEOS)
                    withContext(Dispatchers.Main) { session.acceptSdk(sample) }
                    accepted++
                    return sample
                }
            }
            error("No matching SDK callback for injected location")
        }
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,false,true,false,false,true,true,true,
                ProviderProperties.POWER_USAGE_LOW,ProviderProperties.ACCURACY_FINE)
            providerAdded=true
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            val road=requireNotNull(session.feature.cellExploration).catalog.demo
            val landmark=requireNotNull(session.feature.landmarkCollection).catalog.landmarks.first()
            feed(road.first().point,75f); delay(4_000); feed(road.first().point,75f)
            check(session.summary.discoveredRegionCount==0)
            report("PASS: poor SDK accuracy grants no region")
            feed(road.first().point,5f); delay(4_000); feed(road.first().point,5f)
            check(session.summary.discoveredRegionCount==1)
            report("PASS: real SDK callback -> region discovery")
            val roadStart=SystemClock.elapsedRealtime()
            for(fix in road) {
                delay((roadStart+fix.offsetMillis-SystemClock.elapsedRealtime()).coerceAtLeast(0))
                feed(fix.point,5f)
            }
            check(session.summary.exploredUnitCount==4) { "Expected 4 road cells, got ${session.summary.exploredUnitCount}" }
            report("PASS: real SDK callbacks -> 4 road cells / 1579")
            val first=feed(landmark.point,5f)
            val dwellStart=SystemClock.elapsedRealtime()
            var last=first
            for(step in 1..25) {
                delay((dwellStart+step*5_000-SystemClock.elapsedRealtime()).coerceAtLeast(0))
                last=feed(landmark.point,5f)
                if(last.timestampUtcMs-first.timestampUtcMs<120_000)
                    check(session.landmarkSummary!!.unlockedCount==0) { "Landmark granted before 120 seconds" }
                if(step%6==0) report("SDK dwell observation: ${last.timestampUtcMs-first.timestampUtcMs} ms")
            }
            check(session.landmarkSummary!!.unlockedCount==1) { "Landmark was not granted after continuous dwell" }
            check(last.timestampUtcMs-first.timestampUtcMs>=120_000)
            report("PASS: landmark collected after actual elapsed two-minute SDK observation")
            val restored=withContext(Dispatchers.IO) {
                openExplorationSession(isolated,user,LocationSourceType.PLEOS,policy.recordPrefix)
            }
            withContext(Dispatchers.Main) { restored.refresh() }
            check(restored.summary.discoveredRegionCount==1 && restored.summary.exploredUnitCount==4)
            check(restored.landmarkSummary!!.unlockedCount==1)
            check(!File(root,"exploration/mock-discoveries.bin").exists())
            "PASS: SDK fixes=$accepted; region=1; cells=4/1579; landmarks=1; restored=true; isolatedStorage=true"
        } finally {
            try { collector.cancelAndJoin() } finally {
                try { if(providerAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER) } finally {
                    samples.close()
                    check(root.parentFile?.canonicalFile==targetContext.cacheDir.canonicalFile)
                    check(root.deleteRecursively()) { "Could not remove isolated test cache" }
                }
            }
        }
    }
}
