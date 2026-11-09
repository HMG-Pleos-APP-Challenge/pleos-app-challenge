package com.daehyundai.map.feature.conquest

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.daehyundai.map.core.model.ConquestSummary

@Composable fun ConquestMapScreen(summary: ConquestSummary) {
    Text("도로 점령 지도 · 최 담당", style = MaterialTheme.typography.headlineSmall)
    Text("통합용 빈 화면입니다. 지도 SDK, 점령 판정, 팀 경쟁은 아직 구현되지 않았습니다.")
    Text("모의 기여 ${summary.contributionCount}건 · 소유 도로 ${summary.ownedRoadCount}개")
}
