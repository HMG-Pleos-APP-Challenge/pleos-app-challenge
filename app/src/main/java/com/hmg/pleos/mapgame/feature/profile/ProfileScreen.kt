package com.hmg.pleos.mapgame.feature.profile

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.hmg.pleos.mapgame.core.model.*
import com.hmg.pleos.mapgame.feature.exploration.explorationRate

@Composable fun ProfileScreen(conquest: ConquestSummary,
    exploration: ExplorationSummary, progress: UserProgress) {
    Text("프로필 · 유 담당", style = MaterialTheme.typography.headlineSmall)
    Text("사용자 ${progress.userId} · 레벨 ${progress.level} · XP ${progress.totalXp}")
    Text("모의 탐험 ${explorationRate(exploration)} · 모의 기여 ${conquest.contributionCount}건")
    Text("XP는 고정 미리보기 값입니다. 경험치 저장소 담당·레벨 산식은 공동 확인 후 구현합니다.")
}
