package com.daehyundai.map.feature.conquest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.ConquestSummary
import com.daehyundai.map.feature.conquest.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable fun ConquestMapScreen(summary: ConquestSummary, state: ConquestViewState? = null,
    onSelectTeam: ((String) -> Unit)? = null, session: ConquestSession? = null,
    onReplayDemo: ((DemoRoute) -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    var baseCatalog by remember { mutableStateOf<ConquestCatalog?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(state?.catalog) {
        if (state == null) {
            try {
                baseCatalog = withContext(Dispatchers.IO) {
                    context.assets.open("conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
                }
            } catch (_: java.io.IOException) { loadFailed = true }
            catch (_: IllegalArgumentException) { loadFailed = true }
        }
    }
    val catalog = state?.catalog ?: baseCatalog
    val current = state?.summary ?: summary
    session?.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    if (session?.demoLabel == null) session?.visitStatus?.let { Text(it) }
    if (session?.sourceType == com.daehyundai.map.core.model.LocationSourceType.PLEOS && session.demoLabel == null) {
        val connectionScope = rememberCoroutineScope()
        session.serverConnection?.let { connection ->
            val membership by connection.state.collectAsState()
            Text(membership.message)
            if (!membership.loading && membership.issue != null) {
                OutlinedButton(onClick={ connectionScope.launch { session.reconnectServer() } }) { Text("서버 소속 다시 확인") }
            }
            if (membership.needsTeam) state?.teamIds.orEmpty().forEach { team ->
                OutlinedButton(onClick={ connectionScope.launch { connection.selectTeam(team) } }) { Text("서버 팀 선택: $team") }
            }
        }
        val tracking = session.serverTracking
        if (tracking == null) Text(com.daehyundai.map.feature.conquest.cloud.ConquestTransferState().message)
        else {
            val transfer by tracking.state.collectAsState()
            Text(transfer.message)
            if (transfer.collecting && transfer.issue != null) {
                OutlinedButton(onClick={ tracking.requestRetry() }) { Text("점령 저장·전송 상태 다시 확인") }
            }
        }
    }
    Text("남양읍 도로 점령", style = MaterialTheme.typography.headlineSmall)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(session?.demoLabel) {
        if (session?.demoLabel != null) {
            val demoId = "osm:way:55055096:v24:414929156:4641680981"
            if (catalog?.roads?.any { it.id == demoId } == true) selectedId = demoId
        }
    }
    catalog?.let { roads ->
        val labels = remember(roads) { conquestRoadLabels(roads) }
        Text("남양읍 일반 도로 · 방향별 ${roads.roads.size}구간")
        val selected = selectedId?.takeIf { id -> roads.roads.any { it.id == id } } ?: roads.roads.first().id
        ConquestMapPreview(roads, state?.owners.orEmpty(), state?.teamIds.orEmpty(), selected, interactive = true)
        Text("방향별 구간 선택", style = MaterialTheme.typography.titleMedium)
        var query by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableStateOf("전체") }
        OutlinedTextField(query, { query = it }, label = { Text("도로 이름·구간 번호 검색") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("전체", "내 팀", "중립").forEach { value ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
            }
        }
        val filtered = remember(roads, query, filter, state?.owners, current.teamId) {
            filterConquestRoads(roads, labels, query, filter, state?.owners.orEmpty(), current.teamId)
        }
        Text("검색 결과 ${filtered.size}방향 · 선택한 구간은 아래 상세에서 확인")
        if (filtered.isEmpty()) Text(if (filter == "내 팀" && current.teamId == null)
            "팀 선택 후 내 팀 도로를 확인할 수 있습니다." else "조건에 맞는 도로가 없습니다.")
        else LazyColumn(Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.id }) { item ->
                FilterChip(selected = selected == item.id, onClick = { selectedId = item.id },
                    label = { Text("${labels[item.id]} · ${item.name}") }, modifier = Modifier.fillMaxWidth())
            }
        }
        var detailsExpanded by rememberSaveable(selected) { mutableStateOf(false) }
        val road = roads.roads.first { it.id == selected }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(labels.getValue(road.id), style = MaterialTheme.typography.titleMedium)
                Text("${road.name} · 약 ${road.lengthMeters.toInt()}m")
                Text("A/B는 지도에 표시된 구간의 양 끝입니다. 화살표 방향으로 완주하면 해당 방향에 기여합니다.",
                    style = MaterialTheme.typography.bodySmall)
                val detail = state?.roadDetails?.get(selected)
                if (detail == null) Text("상세 기록을 불러오는 중입니다.") else {
                    Text("현재 소유 팀: ${detail.ownerTeamId ?: "중립"}")
                    Text("이 방향 내 누적 인정 기여: ${detail.contributionCount}회")
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Text(if (detailsExpanded) "기여·정산 상세 접기" else "기여·정산 상세 보기")
                    }
                    if (detailsExpanded) {
                    Text("마지막 정산: ${detail.settledUtcMs?.let(::formatSettlement) ?: "첫 정산 전"}")
                    detail.settlement?.let { result ->
                        HorizontalDivider()
                        Text("마지막 정산 결과", style = MaterialTheme.typography.titleSmall)
                        Text("집계 시작 포함: ${formatSettlement(result.timestampUtcMs - ConquestTime.WINDOW_MILLIS)}")
                        Text("집계 종료 제외: ${formatSettlement(result.timestampUtcMs)}")
                        state.teamIds.forEach { team -> Text("$team · ${result.scores[team] ?: 0}점") }
                        Text("${result.previousOwnerTeamId ?: "중립"} → ${result.ownerTeamId ?: "중립"}")
                        Text(result.reason.label)
                        Text("이 기기에서 인정된 기여 기준 · 진행 중인 다음 정산 점수는 포함하지 않습니다.")
                    }
                    HorizontalDivider()
                    Text("${detail.dateKst} · KST 오늘 기여", style = MaterialTheme.typography.titleSmall)
                    Text("오전 00:00–12:00 · ${if (detail.morningAccepted) "인정 완료" else "인정 기록 없음"}")
                    Text("오후 12:00–24:00 · ${when {
                        detail.afternoonAccepted -> "인정 완료"
                        !detail.currentAfternoon -> "아직 시작 전"
                        else -> "인정 기록 없음"
                    }}")
                    Text("같은 방향은 오전·오후 각각 1회만 인정됩니다. 인정 완료된 반일에는 추가 기여가 없습니다.")
                    if (current.teamId == null) Text("팀 선택 후 통과부터 기여가 인정됩니다.")
                    if (session?.demoLabel == null && (session?.pendingVisitCount ?: 0) > 0)
                        Text("미처리 기록 ${session?.pendingVisitCount}건 · 인정 현황은 저장 완료된 기여 기준입니다.")

                    Text("완주 기여 인정 후, 소유 결과는 낮 12시 정산에 반영됩니다.")
                    }
                }
            }
        }
    }
    if (loadFailed) Text("도로 데이터를 불러오지 못했습니다.")
    if (state == null) {
        Text("도로 데이터 미리보기 · 점령 기록 연결 전")
        Text("기존 모의 기여 ${current.contributionCount}건")
    } else {
        Text("누적 인정 기여 ${current.contributionCount}회 · 내 팀 점령 구간 ${current.ownedRoadCount}개")
        if (current.teamId == null) {
            Text("기여를 기록하려면 팀을 먼저 선택하세요. 선택 후에는 변경할 수 없습니다.")
            if (onSelectTeam != null) state.teamIds.forEach { team ->
                OutlinedButton(onClick = { onSelectTeam(team) }) { Text(team) }
            }
        } else Text("내 팀: ${current.teamId}")
        Text("기여는 자동 기록되며 점령 결과는 매일 낮 12시에 정산됩니다.")
        state.teamIds.forEach { Text("● $it", color = teamColor(it, state.teamIds)) }
        Text("회색: 중립 · 화살표: 진행 방향")
    }
    if (session?.demoEnabled == true && state != null) ConquestDemoControls(session, onReplayDemo)
    Text("© OpenStreetMap contributors · ODbL 1.0", style = MaterialTheme.typography.labelSmall)
    Text(if(session?.sourceType==com.daehyundai.map.core.model.LocationSourceType.PLEOS)
        "SDK 위치 사용 · 이 기기의 기록이며 서버 경쟁과 연동되지 않습니다." else "모의·시연 기록 · 실제 위치 기록과 분리됩니다.")
    Text("openstreetmap.org/copyright · 주차 후 확인용",
        style = MaterialTheme.typography.labelSmall)
}

@Composable private fun ConquestDemoControls(session: ConquestSession, onReplay: ((DemoRoute) -> Unit)?) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("남양읍 주행 시연", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DemoRoute.entries.forEach { route ->
                OutlinedButton(onClick = { if (onReplay != null) onReplay(route) else scope.launch { session.replayDemo(route) } },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text(route.label) }
            }
        }
        session.demoLabel?.let { label ->
            Text("$label · 시연 기록", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { scope.launch { session.settleDemo() } }, enabled = !session.demoSettled) {
                Text(if (session.demoSettled) "낮 12시 정산 완료" else "낮 12시 정산")
            }
            session.demoTimeUtcMs?.let { Text("시연 가상 시각: ${formatSettlement(it)}") }
            if (session.demoSettled && label == DemoRoute.COMPLETE.label) {
                Text(when(session.demoCompetitionStep) {
                    0 -> "경쟁 1/4 · 내 팀 점령"
                    1 -> "경쟁 2/4 · 상대 팀 탈환"
                    2 -> "경쟁 3/4 · 동점으로 상대 팀 유지"
                    else -> "경쟁 4/4 · 기여 만료로 중립"
                })
                Text("추가 참여자 기여는 모의 이벤트입니다. 탐험은 처음 재생한 내 경로를 유지합니다.")
                OutlinedButton(onClick = { scope.launch { session.advanceDemoCompetition() } }, enabled = session.demoCanCompete && !session.demoCompetitionBusy) {
                    Text(when(session.demoCompetitionStep) {
                        0 -> "상대 팀 탈환 시연"
                        1 -> "동점 유지 시연"
                        2 -> "기여 만료 시연"
                        else -> "경쟁 시연 완료"
                    })
                }
            }
            TextButton(onClick = { scope.launch { session.exitDemo() } }) { Text("시연 종료") }
        }
    }
}

private fun teamColor(teamId: String?, teams: List<String>): Color {
    val colors = listOf(Color(0xFFEF786F), Color(0xFF6FB5F3), Color(0xFF9DE4A7), Color(0xFFF2CD6D),
        Color(0xFFBF9BEB), Color(0xFF69D4D4))
    val index = teams.indexOf(teamId)
    return if (index < 0) Color(0xFF879590) else colors[index % colors.size]
}

@Composable fun ConquestMapPreview(catalog: ConquestCatalog, owners: Map<String, String?>, teams: List<String>,
    selectedRoadId: String? = null, interactive: Boolean = false) {
    var zoom by remember(catalog) { mutableStateOf(1f) }
    var pan by remember(catalog) { mutableStateOf(Offset.Zero) }
    var focusId by remember(catalog) { mutableStateOf<String?>(null) }
    val labels = remember(catalog) { conquestRoadLabels(catalog) }
    val textPixels = with(LocalDensity.current) { 11.dp.toPx() * fontScale }
    val points = remember(catalog, focusId) {
        catalog.roads.firstOrNull { it.id == focusId }?.points ?: catalog.roads.flatMap { it.points }
    }
    val origin = points.first()
    val xScale = cos(Math.toRadians(origin.latitude))
    val minX = points.minOf { it.longitude * xScale }; val maxX = points.maxOf { it.longitude * xScale }
    val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
    if (interactive) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { focusId = null; zoom = 1f; pan = Offset.Zero }) { Text("전체 보기") }
            TextButton(onClick = { focusId = selectedRoadId; zoom = 1f; pan = Offset.Zero }, enabled = selectedRoadId != null) { Text("선택 구간 보기") }
            TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(40f) }) { Text("확대") }
            TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }) { Text("축소") }
        }
        Text("두 손가락으로 확대·이동 · 선택 방향은 흰 테두리와 화살표로 표시")
    }
    Canvas(Modifier.fillMaxWidth().height(if (interactive) 280.dp else 200.dp)
        .then(if (interactive) Modifier.pointerInput(catalog, focusId) {
            detectTransformGestures { _, delta, factor, _ ->
                zoom = (zoom * factor).coerceIn(1f, 40f)
                pan = Offset((pan.x + delta.x).coerceIn(-20000f, 20000f), (pan.y + delta.y).coerceIn(-20000f, 20000f))
            }
        } else Modifier)
        .background(Color(0xFF192326), MaterialTheme.shapes.medium).padding(20.dp)) {
        val rangeX = max(maxX - minX, .00001); val rangeY = max(maxY - minY, .00001)
        val scale = min(size.width / rangeX, size.height / rangeY)
        val left = (size.width - rangeX * scale) / 2; val top = (size.height - rangeY * scale) / 2
        fun screen(p: ConquestPoint): Offset {
            val base = Offset((left + (p.longitude * xScale - minX) * scale).toFloat(),
                (top + (maxY - p.latitude) * scale).toFloat())
            return (base - center) * zoom + center + pan
        }
        clipRect {
        for (road in catalog.roads) {
            val color = teamColor(owners[road.id], teams)
            road.points.zipWithNext().forEachIndexed { index, (a, b) ->
                val from = screen(a); val to = screen(b)
                val delta = to - from
                val length = delta.getDistance()
                if (length > 0) {
                    val normal = Offset(-delta.y / length, delta.x / length) * 6.dp.toPx()
                    if (road.id == selectedRoadId) drawLine(Color.White, from + normal, to + normal, 9.dp.toPx())
                    drawLine(color, from + normal, to + normal, strokeWidth = 4.dp.toPx())
                    if (index == (road.points.size - 2) / 2) {
                        val direction = delta / length
                        val middle = (from + to) / 2f + normal
                        val labelPosition = middle + normal * 3f
                        val label = labels.getValue(road.id)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = textPixels
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val halfWidth = paint.measureText(label) / 2
                        val labelX = labelPosition.x.coerceIn(halfWidth, max(halfWidth, size.width - halfWidth))
                        if (road.id == selectedRoadId || catalog.roads.size <= 12)
                            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelPosition.y, paint)
                        val wing = Offset(-direction.y, direction.x) * 4.dp.toPx()
                        drawLine(color, middle, middle - direction * 9.dp.toPx() + wing, 3.dp.toPx())
                        drawLine(color, middle, middle - direction * 9.dp.toPx() - wing, 3.dp.toPx())
                    }
                }
            }
        }
        }
    }
}

private fun formatSettlement(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'")
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(timestamp))
