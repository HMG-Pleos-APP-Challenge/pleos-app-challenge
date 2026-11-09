package com.daehyundai.map.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.LandmarkCollectionSummary
import com.daehyundai.map.feature.exploration.explorationRate

@Composable fun ProfileScreen(conquest:ConquestSummary,exploration:ExplorationSummary,progress:UserProgress,
    landmarks:LandmarkCollectionSummary?=null,onRegion:()->Unit={},onCells:()->Unit={},onLandmark:(String)->Unit={},onSettings:()->Unit={}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("레벨 ${progress.level}",style=MaterialTheme.typography.headlineMedium)
            Text("누적 ${progress.totalXp} XP")
            LinearProgressIndicator(progress={ (progress.totalXp%100).toFloat()/100f },modifier=Modifier.fillMaxWidth())
            Text("다음 레벨까지 ${100-progress.totalXp%100} XP")
        }
    }
    ListItem(headlineContent={Text("발견한 지역 ${exploration.discoveredRegionCount}개")},supportingContent={Text("남양읍 · 탐험률 ${explorationRate(exploration)}")},
        trailingContent={Text("지역 보기 →")},modifier=Modifier.clickable(onClick=onRegion))
    ListItem(headlineContent={Text("해금한 도로 셀 ${exploration.exploredUnitCount}개")},trailingContent={Text("상세 →")},modifier=Modifier.clickable(onClick=onCells))
    landmarks?.let { collection ->
        Text("명소 도감 ${collection.unlockedCount} / ${collection.totalCount}",style=MaterialTheme.typography.titleLarge)
        collection.cards.forEach { card ->
            ListItem(headlineContent={Text(card.name)},supportingContent={Text(if(card.unlockedUtcMs!=null) "수집 완료" else "아직 수집하지 않았어요")},
                trailingContent={Text("상세 →")},modifier=Modifier.clickable { onLandmark(card.id) })
        }
    }
    Text("지역 발견·도로 탐험·명소 수집으로 경험치를 얻어요.")
    OutlinedButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)) { Text("설정") }
}
