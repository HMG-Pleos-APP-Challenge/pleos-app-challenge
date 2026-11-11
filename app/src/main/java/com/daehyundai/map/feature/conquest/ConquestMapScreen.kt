package com.daehyundai.map.feature.conquest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    Text("남양읍 도로 점령", style = MaterialTheme.typography.headlineSmall)
    Text("남양성지로 · 테스트 도로 3구간 / 방향별 6구간")
    if (session?.demoEnabled == true && state != null) ConquestDemoControls(session, onReplayDemo)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    catalog?.let { roads ->
        val selected = selectedId?.takeIf { id -> roads.roads.any { it.id == id } } ?: roads.roads.first().id
        ConquestMapPreview(roads, state?.owners.orEmpty(), state?.teamIds.orEmpty(), selected)
        Text("방향별 구간 선택", style = MaterialTheme.typography.titleMedium)
        roads.roads.forEach { road ->
            OutlinedButton(onClick = { selectedId = road.id }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (road.id == selected) "선택 · " else ""}${roadLabel(roads, road)} · ${state?.owners?.get(road.id) ?: "중립"}")
            }
        }
        val road = roads.roads.first { it.id == selected }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(roadLabel(roads, road), style = MaterialTheme.typography.titleMedium)
                Text("${road.name} · 약 ${road.lengthMeters.toInt()}m")
                Text("교차로 노드 ${road.fromNode} → ${road.toNode}")
                val detail = state?.roadDetails?.get(selected)
                if (detail == null) Text("상세 기록을 불러오는 중입니다.") else {
                    Text("현재 소유 팀: ${detail.ownerTeamId ?: "중립"}")
                    Text("마지막 정산: ${detail.settledUtcMs?.let(::formatSettlement) ?: "첫 정산 전"}")
                    Text("이 방향 내 누적 인정 기여: ${detail.contributionCount}회")
                    Text("완주 기여 인정 후, 소유 결과는 낮 12시 정산에 반영됩니다.")
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
    selectedRoadId: String? = null) {
    val points = catalog.roads.flatMap { it.points }
    val origin = points.first()
    val xScale = cos(Math.toRadians(origin.latitude))
    val minX = points.minOf { it.longitude * xScale }; val maxX = points.maxOf { it.longitude * xScale }
    val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
    Canvas(Modifier.fillMaxWidth().height(200.dp)
        .background(Color(0xFF192326), MaterialTheme.shapes.medium).padding(20.dp)) {
        val rangeX = max(maxX - minX, .00001); val rangeY = max(maxY - minY, .00001)
        val scale = min(size.width / rangeX, size.height / rangeY)
        val left = (size.width - rangeX * scale) / 2; val top = (size.height - rangeY * scale) / 2
        fun screen(p: ConquestPoint) = Offset((left + (p.longitude * xScale - minX) * scale).toFloat(),
            (top + (maxY - p.latitude) * scale).toFloat())
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
                        val label = roadLabel(catalog, road)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = 11.dp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val halfWidth = paint.measureText(label) / 2
                        val labelX = labelPosition.x.coerceIn(halfWidth, max(halfWidth, size.width - halfWidth))
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

private fun roadLabel(catalog: ConquestCatalog, road: DirectedRoad): String {
    fun key(item: DirectedRoad) = listOf(item.fromNode, item.toNode).sorted().joinToString(":")
    val groups = catalog.roads.map(::key).distinct()
    val group = groups.indexOf(key(road)) + 1
    val forward = catalog.roads.first { key(it) == key(road) }
    return "구간 $group · ${if (forward.id == road.id) "A → B" else "B → A"}"
}

private fun formatSettlement(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'")
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(timestamp))
