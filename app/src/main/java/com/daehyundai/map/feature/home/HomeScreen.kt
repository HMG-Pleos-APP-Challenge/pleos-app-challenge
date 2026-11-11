package com.daehyundai.map.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.*
import com.daehyundai.map.navigation.Routes

/** Entrances only. Each feature supplies its existing map renderer, not a second map implementation. */
@Composable fun HomeScreen(conquest:ConquestSummary,exploration:ExplorationSummary,onNavigate:(String)->Unit,
    explorationPreview:@Composable ()->Unit={},conquestPreview:@Composable ()->Unit={},conquestDemo:Boolean=false) {
    Text("오늘은 어디를 탐험할까요?",style=MaterialTheme.typography.titleLarge)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun Exploration(modifier:Modifier) {
            Card(modifier) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("지역 탐험",style=MaterialTheme.typography.headlineSmall)
                explorationPreview()
                Text("남양읍 · 서버 확정 구역 ${exploration.exploredUnitCount} / ${exploration.totalExplorableUnitCount}")
                Button(onClick={onNavigate(Routes.EXPLORATION_MAP)},modifier=Modifier.fillMaxWidth()) { Text("탐험하기") }
            } }
        }
        @Composable fun Conquest(modifier:Modifier) {
            Card(modifier) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("도로 점령",style=MaterialTheme.typography.headlineSmall)
                conquestPreview()
                Text("${if(conquestDemo) "시연 · 저장되지 않음" else "기기 내 기록"} · 기여 ${conquest.contributionCount}회 · 팀 점령 ${conquest.ownedRoadCount}구간")
                OutlinedButton(onClick={onNavigate(Routes.CONQUEST_MAP)},modifier=Modifier.fillMaxWidth()) { Text("점령 보기") }
            } }
        }
        if(maxWidth>=650.dp) Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Exploration(Modifier.weight(1f));Conquest(Modifier.weight(1f))
        } else Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Exploration(Modifier.fillMaxWidth());Conquest(Modifier.fillMaxWidth())
        }
    }
    OutlinedButton(onClick={onNavigate(Routes.PROFILE)},modifier=Modifier.fillMaxWidth()) { Text("내 프로필") }
}
