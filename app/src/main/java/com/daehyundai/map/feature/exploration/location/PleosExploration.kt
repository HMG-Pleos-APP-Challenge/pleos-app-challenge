package com.daehyundai.map.feature.exploration.location

import androidx.compose.runtime.*
import com.daehyundai.map.feature.exploration.tracking.ExplorationTracking
import com.daehyundai.map.feature.exploration.tracking.TrackingSnapshot

/** Observe the service-owned session; leaving composition must not stop tracking. */
@Composable fun rememberPleosExploration():TrackingSnapshot {
    val state by ExplorationTracking.state.collectAsState()
    return state
}
