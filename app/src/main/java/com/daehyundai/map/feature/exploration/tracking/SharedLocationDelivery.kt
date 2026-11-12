package com.daehyundai.map.feature.exploration.tracking

import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.feature.exploration.data.DiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class LocationDeliveryResult(
    val exploration: Result<DiscoveryResult>,
    val conquest: Result<Unit>,
)

/** The collector awaits both consumers before delivering the next sample. */
internal suspend fun deliverLocationSample(
    sample: LocationSample,
    exploration: suspend (LocationSample) -> DiscoveryResult,
    conquest: suspend (LocationSample) -> Unit,
): LocationDeliveryResult {
    val explorationResult = receiveIndependently { exploration(sample) }
    val conquestResult = receiveIndependently { conquest(sample) }
    return LocationDeliveryResult(explorationResult, conquestResult)
}

private suspend fun <T> receiveIndependently(action: suspend () -> T): Result<T> {
    currentCoroutineContext().ensureActive()
    return try {
        Result.success(action())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(failure)
    }
}
