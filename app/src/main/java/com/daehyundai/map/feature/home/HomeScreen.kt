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
    onNavigate: (String) -> Unit, onReplay: () -> Unit,
    explorationPreview: @Composable () -> Unit = {}) {
    Text("오늘은 어디를 탐험할까요?", style=MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("지역 탐험",style=MaterialTheme.typography.headlineSmall)
                explorationPreview()
                Text("화성시 남양읍 · 발견한 지역 ${exploration.discoveredRegionCount}개")
                Button(onClick={ onNavigate(Routes.EXPLORATION_MAP) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("탐험하기")
                }
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("도로 점령",style=MaterialTheme.typography.headlineSmall)
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    Text("도로 점령 화면으로 이동합니다.",modifier=Modifier.padding(top=60.dp))
                }
                Text("기존 점령 화면")
                OutlinedButton(onClick={ onNavigate(Routes.CONQUEST_MAP) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("점령 보기")
                }
            }
        }
    }
}
