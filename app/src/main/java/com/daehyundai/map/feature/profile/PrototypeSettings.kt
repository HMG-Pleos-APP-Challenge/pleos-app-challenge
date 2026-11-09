package com.daehyundai.map.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daehyundai.map.feature.exploration.ExplorationSession
import com.daehyundai.map.feature.exploration.tracking.TrackingSnapshot

@Composable fun PrototypeSettings(recording:Boolean,developerEnabled:Boolean,onStop:()->Unit,onSources:()->Unit,onDeveloper:()->Unit) {
    Text("탐험 기록",style=MaterialTheme.typography.titleLarge)
    Text(if(recording) "기록 중 · 화면 밖에서도 탐험을 기록해요." else "현재 탐험을 기록하고 있지 않아요.")
    if(recording) OutlinedButton(onClick=onStop) { Text("기록 중지") }
    Text("새 해금은 음성으로 안내해요. 다른 오디오가 우선인 경우 안내가 생략될 수 있어요.")
    HorizontalDivider()
    Text("진행도 안내",style=MaterialTheme.typography.titleLarge)
    Text("첫 지역 발견 100 XP · 도로 셀 10 XP · 명소 수집 200 XP")
    Text("100 XP마다 레벨이 올라요. 현재는 남양읍 데이터로 동작하는 프로토타입이에요.")
    TextButton(onClick=onSources) { Text("지도·데이터 출처") }
    if(developerEnabled) OutlinedButton(onClick=onDeveloper) { Text("개발자 도구") }
}

@Composable fun DeveloperTools(userId:String,sdkSelected:Boolean,state:TrackingSnapshot,mock:ExplorationSession?,
    onSource:(Boolean)->Unit,onReplay:(String)->Unit) {
    Text("현재 표시할 기록",style=MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        FilterChip(selected=!sdkSelected,onClick={onSource(false)},label={Text("모의 재생 기록")})
        FilterChip(selected=sdkSelected,onClick={onSource(true)},label={Text("SDK 기록")})
    }
    Text("표시할 기록을 바꿔도 저장된 데이터나 진행 중인 SDK 수집은 지우거나 중지하지 않습니다.")
    Text("로컬 사용자 ID: $userId")
    Text("SDK: ${state.message}")
    Text(state.policy.description)
    Text("음성: ${state.voice}")
    Text("공통 계약 v0.1 공동 승인 대기 · 실차·주차 검증 미완료")
    HorizontalDivider()
    Text("개인 탐험 모의 재생",style=MaterialTheme.typography.titleLarge)
    Text("실제 위치·체류가 아닌 입력 시각 재생입니다. SDK/TTS 검증은 별도로 수행합니다.")
    listOf("inside" to "지역 진입 재생","outside" to "경계 밖 재생","inaccurate" to "정확도 불량 재생",
        "road" to "도로 이동 재생 · 셀 해금","landmark-short" to "짧은 방문 재생",
        "landmark-dwell" to "2분 모의 체류 재생","landmark-exit" to "이탈 후 복귀 재생").forEach { (key,title) ->
        OutlinedButton(enabled=mock!=null && !mock.busy,onClick={onSource(false);onReplay(key)},modifier=Modifier.heightIn(min=48.dp)) { Text(title) }
    }
    mock?.let { Text(it.status) }
}

@Composable fun MapSources() {
    Text("지도 데이터",style=MaterialTheme.typography.titleLarge)
    Text("도로·명소: © OpenStreetMap contributors / Geofabrik · 2026-11-08 · ODbL 1.0")
    Text("https://www.openstreetmap.org/copyright")
    Text("https://opendatacommons.org/licenses/odbl/1-0/")
    Text("행정 경계: SGIS 원자료 / vuski·admdongkor 가공 · 2026-10-01 · CC BY 4.0")
    Text("https://github.com/vuski/admdongkor")
    Text("배경지도 타일 없이 공개 경계·도로를 그린 지도입니다. 탐험률은 수집한 공개 도로 기반 대상 셀을 기준으로 합니다.")
}
