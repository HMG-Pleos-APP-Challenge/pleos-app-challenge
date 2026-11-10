package com.daehyundai.map.feature.conquest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.ConquestSummary
import com.daehyundai.map.feature.conquest.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable fun ConquestMapScreen(summary: ConquestSummary, state: ConquestViewState? = null,
    onSelectTeam: ((String) -> Unit)? = null) {
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
    Text("남양읍 도로 점령", style = MaterialTheme.typography.headlineSmall)
    Text("남양성지로 · 테스트 도로 3구간 / 방향별 6구간")
    catalog?.let { ConquestRoadMap(it, state?.owners.orEmpty(), state?.teamIds.orEmpty()) }
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
    Text("openstreetmap.org/copyright · 주차 후 확인용 / 실제 위치 미연동",
        style = MaterialTheme.typography.labelSmall)
}

private fun teamColor(teamId: String?, teams: List<String>): Color {
    val colors = listOf(Color(0xFFEF786F), Color(0xFF6FB5F3), Color(0xFF9DE4A7), Color(0xFFF2CD6D),
        Color(0xFFBF9BEB), Color(0xFF69D4D4))
    val index = teams.indexOf(teamId)
    return if (index < 0) Color(0xFF879590) else colors[index % colors.size]
}

@Composable private fun ConquestRoadMap(catalog: ConquestCatalog, owners: Map<String, String?>, teams: List<String>) {
    val points = catalog.roads.flatMap { it.points }
    val origin = points.first()
    val xScale = cos(Math.toRadians(origin.latitude))
    val minX = points.minOf { it.longitude * xScale }; val maxX = points.maxOf { it.longitude * xScale }
    val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
    Canvas(Modifier.fillMaxWidth().height(240.dp).padding(16.dp)) {
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
                    val normal = Offset(-delta.y / length, delta.x / length) * 4.dp.toPx()
                    drawLine(color, from + normal, to + normal, strokeWidth = 3.dp.toPx())
                    if (index == (road.points.size - 2) / 2) {
                        val direction = delta / length
                        val middle = (from + to) / 2f + normal
                        val wing = Offset(-direction.y, direction.x) * 3.dp.toPx()
                        drawLine(color, middle, middle - direction * 7.dp.toPx() + wing, 2.dp.toPx())
                        drawLine(color, middle, middle - direction * 7.dp.toPx() - wing, 2.dp.toPx())
                    }
                }
            }
        }
    }
}
