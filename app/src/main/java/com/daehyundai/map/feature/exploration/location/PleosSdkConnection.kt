package com.daehyundai.map.feature.exploration.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

const val PLEOS_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

/** Read the SDK AAR metadata so a future dependency update cannot silently reuse old semantics. */
fun pleosAccuracyPolicy(context: Context): PleosAccuracyPolicy {
    val service = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("ai.pleos.playground.service.location.fused", 0)
    }.getOrNull()
    val sdkVersion = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("ai.pleos.playground.sdk.location.fused.current_version_name")
    }.getOrNull()
    return selectPleosAccuracyPolicy(
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        Build.HARDWARE in setOf("ranchu", "goldfish"), sdkVersion.orEmpty(), service?.versionName, service?.longVersionCode,
    )
}

/** A new SDK instance per foreground collection; release is never reused. */
class PleosSdkConnection(private val context: Context) : LocationConnection {
    private val sdk = FusedLocation(context.applicationContext)
    private var initialized = false
    private var listener: FusedLocationListener? = null

    @SuppressLint("MissingPermission") // Custom dangerous permission checked immediately before use.
    override fun start(onFix: (PleosFix?) -> Unit, onError: (Exception) -> Unit) {
        check(sdk.isSupported()) { "이 기기는 Pleos 위치 서비스를 지원하지 않습니다" }
        check(context.checkSelfPermission(PLEOS_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Pleos 위치 권한이 필요합니다"
        }
        initialized = true
        sdk.initialize()
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                onFix(location?.let { PleosFix(it.latitude, it.longitude, it.timestampMillis, it.confidence) })
            }
            override fun onError(exception: Exception) = onError(exception)
        }
        listener = callback
        sdk.registerFusedLocationCallback(callback)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        listener?.let { runCatching { sdk.unregisterFusedLocationCallback(it) } }
        listener = null
        if (initialized) { initialized = false; sdk.release() }
    }
}
