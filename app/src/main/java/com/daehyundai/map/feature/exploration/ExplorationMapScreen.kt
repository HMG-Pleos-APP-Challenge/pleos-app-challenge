package com.daehyundai.map.feature.exploration

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.daehyundai.map.core.model.ExplorationSummary
import java.util.Locale

fun explorationRate(summary: ExplorationSummary): String =
    if (summary.totalExplorableUnitCount <= 0) "집계 없음"
    else String.format(Locale.ROOT, "%.1f%%",
        100.0 * summary.exploredUnitCount / summary.totalExplorableUnitCount)

@Composable fun ExplorationMapScreen(summary: ExplorationSummary) {
    Text("개인 지도 탐험 · 유 담당", style = MaterialTheme.typography.headlineSmall)
    Text("통합용 빈 화면입니다. 지도·실제 지역 경계·도로 해금 규칙은 구현 전입니다.")
    Text("모의 지역 ${summary.discoveredRegionCount}개 · ${explorationRate(summary)}")
}
