package com.daehyundai.map.feature.profile

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.explorationRate
import com.daehyundai.map.feature.exploration.formatDiscoveryTime

@Composable fun ProfileScreen(conquest: ConquestSummary,
    exploration: ExplorationSummary, progress: UserProgress) {
    Text("프로필", style = MaterialTheme.typography.headlineSmall)
    Text("개발용 사용자 ${progress.userId}")
    Text("발견한 지역 ${exploration.discoveredRegionCount}개 · 내부 탐험률 ${explorationRate(exploration)}")
    Text("해금 도로 셀 ${exploration.exploredUnitCount} / ${exploration.totalExplorableUnitCount}개")
    Text("해금 도로 ${exploration.unlockedRoadCount}개 · 모의 점령 기여 ${conquest.contributionCount}건")
    Text(if (exploration.discoveredRegionCount > 0) "지역 도감: 남양읍 개방" else "지역 도감: 남양읍 미발견")
    exploration.lastUpdatedUtcMs?.let { Text("최근 탐험 기록: ${formatDiscoveryTime(it)}") }
    Text("레벨 ${progress.level} · XP ${progress.totalXp} (고정 미리보기)")
    Text("공통 경험치 지급·레벨 규칙은 공동 합의 후 연결합니다. 탐험 발견 기록은 앱 재시작 후에도 유지됩니다.")
}
