package com.daehyundai.map.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.domain.LandmarkCollectionSummary
import com.daehyundai.map.feature.exploration.explorationRate

private val ProfileMint = Color(0xFF9DE4CF)

/** Presentation only: retain the shared progress contract, reward rules and navigation callbacks. */
@Composable fun ProfileScreen(conquest:ConquestSummary,exploration:ExplorationSummary,progress:UserProgress,
    landmarks:LandmarkCollectionSummary?=null,onRegion:()->Unit={},onCells:()->Unit={},onLandmark:(String)->Unit={},onSettings:()->Unit={},conquestDemo:Boolean=false,onConquest:()->Unit={}) {
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF203B36)),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                ExplorerEmblem()
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("나의 탐험 여권",style=MaterialTheme.typography.labelLarge,color=ProfileMint)
                    Text("탐험가",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                    Text(if(exploration.discoveredRegionCount>0) "익숙한 길에서, 새로운 발견을." else "첫 발견이 나의 여정이 됩니다.",
                        style=MaterialTheme.typography.bodyMedium,color=Color(0xFFD0E3DC))
                }
                Surface(color=ProfileMint,shape=RoundedCornerShape(14.dp)) {
                    Text("Lv. ${progress.level}",Modifier.padding(horizontal=16.dp,vertical=12.dp),
                        color=Color(0xFF15372D),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("다음 레벨까지 ${100-progress.totalXp%100} XP",style=MaterialTheme.typography.bodyMedium)
                    Text("누적 ${progress.totalXp} XP",style=MaterialTheme.typography.labelLarge,color=ProfileMint)
                }
                LinearProgressIndicator(progress={ (progress.totalXp%100).toFloat()/100f },
                    modifier=Modifier.fillMaxWidth().height(8.dp),color=ProfileMint,trackColor=Color(0xFF39534A))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(maxWidth>=600.dp) {
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                ProfileMetric("발견한 지역","${exploration.discoveredRegionCount}곳","지역 지도 보기",onRegion,Modifier.weight(1f))
                ProfileMetric("탐험한 구역","${exploration.exploredUnitCount}곳","기록 보기",onCells,Modifier.weight(1f))
                ProfileMetric("남양읍 탐험률",explorationRate(exploration),"도로 기반 탐험률",onRegion,Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ProfileMetric("발견한 지역","${exploration.discoveredRegionCount}곳","지역 지도 보기",onRegion)
                ProfileMetric("탐험한 구역","${exploration.exploredUnitCount}곳","기록 보기",onCells)
                ProfileMetric("남양읍 탐험률",explorationRate(exploration),"도로 기반 탐험률",onRegion)
            }
        }
    }
    ProfileMetric("도로 점령"+(if(conquestDemo) " · 시연" else " · 기기 내 기록"),"${conquest.ownedRoadCount}구간",
        "기여 ${conquest.contributionCount}회 · "+(conquest.teamId ?: "팀 미선택")+(if(conquestDemo) " · 저장되지 않음" else ""),onConquest)
    Text("나의 발자취",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
    Text("직접 발견하고 모은 기록이에요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Milestone("첫 지역 발견",exploration.discoveredRegionCount>0,"새로운 지역을 처음 발견하면 열려요.")
            HorizontalDivider()
            Milestone("새로운 길의 시작",exploration.exploredUnitCount>0,"첫 도로 구역을 탐험하면 열려요.")
            HorizontalDivider()
            Milestone("명소 수집가",(landmarks?.unlockedCount ?: 0)>0,"첫 명소 도감을 수집하면 열려요.")
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text("명소 도감",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        landmarks?.let { Text("${it.unlockedCount} / ${it.totalCount} 수집",color=ProfileMint) }
    }
    if(landmarks==null) {
        Text("명소 기록을 불러오고 있어요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else if(landmarks.cards.isEmpty()) {
        Text("이 지역의 명소를 준비하고 있어요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        landmarks.cards.forEach { card ->
            val collected=card.unlockedUtcMs!=null
            Card(onClick={onLandmark(card.id)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(52.dp).background(if(collected) Color(0xFF51452D) else Color(0xFF343C3A),CircleShape),contentAlignment=Alignment.Center) {
                        Text(if(collected) "★" else "◇",style=MaterialTheme.typography.headlineSmall,
                            color=if(collected) Color(0xFFFFD58C) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(card.name,style=MaterialTheme.typography.titleMedium)
                        Text(if(collected) "수집한 명소 · 나의 도감에 보관 중" else "아직 발견하지 않은 이야기",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("보기 →",color=ProfileMint,style=MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Text("지역 발견·도로 탐험·명소 수집으로 경험치가 쌓여요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick=onSettings,modifier=Modifier.heightIn(min=56.dp),contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp)) { Text("기록 및 앱 설정") }
}

@Composable private fun ProfileMetric(label:String,value:String,hint:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    Card(onClick=onClick,modifier=modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            Text("$hint →",style=MaterialTheme.typography.bodyMedium,color=ProfileMint)
        }
    }
}

@Composable private fun Milestone(title:String,earned:Boolean,description:String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(if(earned) "✓" else "○",color=if(earned) ProfileMint else MaterialTheme.colorScheme.onSurfaceVariant,
            style=MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(if(earned) "달성한 발자취" else description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if(earned) "달성" else "미달성",style=MaterialTheme.typography.labelLarge,
            color=if(earned) ProfileMint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ExplorerEmblem() {
    // Decorative compass, not an editable avatar or a fabricated user account.
    Canvas(Modifier.size(72.dp)) {
        val radius=size.minDimension/2
        drawCircle(Color(0xFF355B50))
        drawCircle(ProfileMint.copy(alpha=.25f),radius*.8f,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val north=Path().apply {
            moveTo(center.x,center.y-radius*.64f)
            lineTo(center.x+radius*.27f,center.y+radius*.18f)
            lineTo(center.x,center.y)
            lineTo(center.x-radius*.27f,center.y+radius*.18f)
            close()
        }
        drawPath(north,ProfileMint)
        drawLine(ProfileMint.copy(alpha=.45f),Offset(center.x,center.y+radius*.15f),Offset(center.x,center.y+radius*.58f),2.dp.toPx())
    }
}
