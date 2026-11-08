package com.daehyundai.map.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.*
import com.daehyundai.map.navigation.Routes

@Composable
fun HomeScreen(conquest: ConquestSummary, exploration: ExplorationSummary,
    onNavigate: (String) -> Unit, onReplay: () -> Unit) {
    Text("HOME · 최 담당 진입점", style = MaterialTheme.typography.headlineSmall)
    Text("모의 도로 기여 ${conquest.contributionCount}건 · 모의 지역 발견 ${exploration.discoveredRegionCount}개")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onNavigate(Routes.CONQUEST_MAP) }) { Text("도로 점령") }
        Button(onClick = { onNavigate(Routes.EXPLORATION_MAP) }) { Text("개인 탐험") }
        Button(onClick = { onNavigate(Routes.PROFILE) }) { Text("프로필") }
    }
    OutlinedButton(onClick = onReplay, modifier = Modifier.padding(top = 12.dp)) {
        Text("고정 Mock 경로 재생 · 주차 상태의 개발 테스트 전용")
    }
}
