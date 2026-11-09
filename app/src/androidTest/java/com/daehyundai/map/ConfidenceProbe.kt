package com.daehyundai.map

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import com.daehyundai.map.feature.exploration.location.PleosAccuracyPolicy
import com.daehyundai.map.feature.exploration.location.pleosAccuracyPolicy
import android.os.Build
import android.os.SystemClock
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Manual diagnostic only. Replaces GPS temporarily on an explicitly selected emulator. */
@TargetApi(31)
class ConfidenceProbe(
    private val targetContext: Context,
    private val onMain: (Runnable) -> Unit,
    private val report: (String) -> Unit,
) {
    private fun runOnMainSync(block: () -> Unit) = onMain(Runnable { block() })
    @SuppressLint("MissingPermission") // Custom Pleos permission checked below; mock-location AppOp is external and temporary.
    fun run(confirmed: Boolean): String {
        check(Build.VERSION.SDK_INT >= 31) { "Requires API 31+" }
        check(targetContext.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED) {
            "Grant the SDK permission through the app first"
        }
        check(confirmed && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
            "Requires explicit syntheticEmulator=true on an Android emulator"
        }
        check(pleosAccuracyPolicy(targetContext) == PleosAccuracyPolicy.VERIFIED_EMULATOR_METERS) {
            "Installed SDK metadata/service must select verified emulator policy"
        }
        val manager = targetContext.getSystemService(LocationManager::class.java)
        val sdk = FusedLocation(targetContext)
        check(sdk.isSupported()) { "SDK not supported" }
        val queue = LinkedBlockingQueue<LocationData>()
        val errors = LinkedBlockingQueue<Exception>()
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { if (location != null) queue.offer(location) }
            override fun onError(exception: Exception) { errors.offer(exception) }
        }
        var providerAdded = false
        var initialized = false
        val rows = mutableListOf<String>()
        try {
            runOnMainSync {
                sdk.initialize(); initialized = true
                sdk.registerFusedLocationCallback(listener)
            }
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false,
                true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
            providerAdded = true
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            for ((index,inputAccuracy) in listOf(5f,25f,75f,5f).withIndex()) {
                queue.clear()
                val timestamps = mutableSetOf<Long>()
                val latitude = 37.1995+index*.001
                val longitude = 126.831
                val deadline = SystemClock.elapsedRealtime()+15_000
                var nextInjection = 0L
                var observed: LocationData? = null
                while (SystemClock.elapsedRealtime()<deadline) {
                    errors.poll()?.let { throw it }
                    if (SystemClock.elapsedRealtime() >= nextInjection) {
                        val timestamp = System.currentTimeMillis()
                        timestamps.add(timestamp)
                        val input = Location(LocationManager.GPS_PROVIDER).apply {
                            this.latitude=latitude; this.longitude=longitude
                            accuracy=inputAccuracy; altitude=0.0; bearing=0f; speed=0f
                            time=timestamp; elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                        }
                        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,input)
                        nextInjection = SystemClock.elapsedRealtime()+2_000
                    }
                    val next = queue.poll(1,TimeUnit.SECONDS) ?: continue
                    if (next.timestampMillis in timestamps && next.latitude==latitude && next.longitude==longitude) {
                        observed=next; break
                    }
                }
                val row = "injectedAccuracyMeters=$inputAccuracy -> " +
                    (observed?.let { "sdkConfidence=${it.confidence}; timestampMatched=true" } ?: "NO_MATCH_WITHIN_15S")
                rows.add(row)
                report(row)
            }
            return rows.joinToString("\n")
        } finally {
            try {
                if (providerAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } finally {
                if (initialized) runOnMainSync {
                    runCatching { sdk.unregisterFusedLocationCallback(listener) }
                    sdk.release()
                }
            }
        }
    }
}
