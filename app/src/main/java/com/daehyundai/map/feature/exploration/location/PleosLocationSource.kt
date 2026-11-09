package com.daehyundai.map.feature.exploration.location

import com.daehyundai.map.core.contract.LocationSource
import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.core.model.LocationSourceType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Provider data. Interpretation is selected separately from the raw SDK value. */
data class PleosFix(val latitude: Double, val longitude: Double, val timestampMillis: Long, val confidence: Float)

interface LocationConnection {
    fun start(onFix: (PleosFix?) -> Unit, onError: (Exception) -> Unit)
    fun close()
}

class PleosLocationSource(
    private val userId: String,
    private val connect: () -> LocationConnection,
    private val now: () -> Long = System::currentTimeMillis,
    private val accuracyPolicy: PleosAccuracyPolicy = PleosAccuracyPolicy.UNVERIFIED,
) : LocationSource {
    override suspend fun samples(): Flow<LocationSample> = callbackFlow {
        val connection = connect()
        try {
            connection.start({ fix ->
                if (fix == null) {
                    close(IllegalStateException("위치 데이터 없음 · 위치 공유 설정을 확인하세요"))
                } else {
                    val time = now()
                    if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
                        !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
                        fix.timestampMillis <= 0 || fix.timestampMillis < time - 15_000 ||
                        fix.timestampMillis > time + 5_000) {
                        close(IllegalStateException("오래되었거나 유효하지 않은 SDK 위치 · 수신을 다시 시작하세요"))
                    } else {
                        // Keep the provider timestamp, including repeats. Receipt time cannot prove dwell.
                        val sample = LocationSample(
                            "pleos:${fix.timestampMillis}:${fix.latitude}:${fix.longitude}", userId,
                            fix.latitude, fix.longitude, fix.timestampMillis, accuracyPolicy.accuracyMeters(fix.confidence), LocationSourceType.PLEOS,
                        )
                        if (trySend(sample).isFailure) close(IllegalStateException("위치 처리 지연 · 수신을 다시 시작하세요"))
                    }
                }
            }, { close(it) })
            awaitClose { }
        } finally { connection.close() }
    }
}
