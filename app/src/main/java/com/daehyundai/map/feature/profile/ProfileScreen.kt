package com.daehyundai.map.feature.profile

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.LandmarkCollectionSummary
import com.daehyundai.map.feature.exploration.explorationRate
import com.daehyundai.map.feature.exploration.formatDiscoveryTime

@Composable fun ProfileScreen(conquest: ConquestSummary,
    exploration: ExplorationSummary, progress: UserProgress, landmarks: LandmarkCollectionSummary? = null) {
    Text("프로필", style = MaterialTheme.typography.headlineSmall)
    Text("로컬 사용자 ${progress.userId}")
    Text("발견한 지역 ${exploration.discoveredRegionCount}개 · 내부 탐험률 ${explorationRate(exploration)}")
    Text("해금 도로 셀 ${exploration.exploredUnitCount} / ${exploration.totalExplorableUnitCount}개")
    Text("해금 도로 ${exploration.unlockedRoadCount}개 · 모의 점령 기여 ${conquest.contributionCount}건")
    Text(if (exploration.discoveredRegionCount > 0) "지역 도감: 남양읍 개방" else "지역 도감: 남양읍 미발견")
    exploration.lastUpdatedUtcMs?.let { Text("최근 탐험 기록: ${formatDiscoveryTime(it)}") }
    landmarks?.let { collection ->
        Text("랜드마크 도감 ${collection.unlockedCount} / ${collection.totalCount}개 · 도로 셀과 별도 집계")
        collection.cards.filter { it.unlockedUtcMs != null }.forEach { card ->
            Text("${card.name} · ${formatDiscoveryTime(requireNotNull(card.unlockedUtcMs))}")
        }
    }
    Text("레벨 ${progress.level} · 누적 XP ${progress.totalXp}")
    LinearProgressIndicator(progress={ (progress.totalXp%100).toFloat()/100f })
    Text("다음 레벨까지 ${100-progress.totalXp%100} XP")
    Text("개발용 보상: 지역 100 · 도로 셀 10 · 명소 200 XP. 100 XP마다 레벨 상승. 기록 출처별로 분리해 저장합니다.")
}
