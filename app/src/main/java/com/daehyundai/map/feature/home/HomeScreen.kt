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
    explorationPreview:@Composable ()->Unit={},conquestPreview:@Composable ()->Unit={},conquestDemo:Boolean=false,
    conquestAvailable:Boolean=true,conquestMessage:String?=null,explorationDemo:Boolean=false) {
    Text("오늘은 어디를 탐험할까요?",style=MaterialTheme.typography.titleLarge)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun Exploration(modifier:Modifier) {
            Card(modifier) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("지역 탐험",style=MaterialTheme.typography.headlineSmall)
                explorationPreview()
                Text("남양읍 · ${if(explorationDemo) "시연 탐험 구역" else "서버 확정 구역"} ${exploration.exploredUnitCount} / ${exploration.totalExplorableUnitCount}")
                Button(onClick={onNavigate(Routes.EXPLORATION_MAP)},modifier=Modifier.fillMaxWidth()) { Text("탐험하기") }
            } }
        }
        @Composable fun Conquest(modifier:Modifier) {
            Card(modifier) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("도로 점령",style=MaterialTheme.typography.headlineSmall)
                if (conquestAvailable) {
                    Text(if(conquestDemo) "시연 · 저장되지 않음" else "이 기기의 점령 기록",
                        style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("내 팀 · ${conquest.teamId ?: "팀 미선택"}",style=MaterialTheme.typography.titleMedium)
                    conquestPreview()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ConquestStat("내 누적 인정 기여", "${conquest.contributionCount}회", Modifier.weight(1f))
                        ConquestStat("내 팀 소유 방향 구간", "${conquest.ownedRoadCount}개", Modifier.weight(1f))
                    }
                    if(conquest.teamId==null) Text("점령 화면에서 팀을 선택하면 이후 유효 통과부터 기여가 기록됩니다.")
                    Text("기여는 완주 후 인정 · 팀 소유는 매일 낮 12시 정산",
                        style=MaterialTheme.typography.bodySmall)
                    Text("같은 도로도 진행 방향별로 집계합니다.",style=MaterialTheme.typography.bodySmall)
                }
                conquestMessage?.let { Text(it) }
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

@Composable private fun ConquestStat(label:String,value:String,modifier:Modifier) {
    Surface(modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.labelLarge)
            Text(value,style=MaterialTheme.typography.headlineSmall)
        }
    }
}
