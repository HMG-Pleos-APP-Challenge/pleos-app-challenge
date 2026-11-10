package com.daehyundai.map.feature.exploration.cloud

import com.daehyundai.map.core.model.LocationSample

/** Provider-independent boundary: a future HTTP server replaces only this adapter. */
interface ExplorationCloud {
    fun synchronize(partition: String, samples: String): String
}

class CatalogVersionMismatch:IllegalStateException("서버 지도 버전 업데이트 필요")

data class CloudGrant(val kind: String, val targetId: String, val timestamp: Long)

/** Server claims are distinct from locally detected, provisional achievements. */
data class CloudSnapshot(val grants: List<CloudGrant>, val xp: Long)
