package com.daehyundai.map.feature.exploration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.ExplorationSummary
import com.daehyundai.map.feature.exploration.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun explorationRate(summary: ExplorationSummary): String =
    if (summary.totalExplorableUnitCount <= 0) "집계 없음"
    else String.format(Locale.ROOT, "%.1f%%",
        100.0 * summary.exploredUnitCount / summary.totalExplorableUnitCount)

@Composable fun ExplorationMapScreen(
    summary: ExplorationSummary,
    session: ExplorationSession? = null,
    onReplay: (String) -> Unit = {},
) {
    Text("개인 지도 탐험", style = MaterialTheme.typography.headlineSmall)
    Text("경기도 · 화성시 만세구 · 남양읍 / 위치 입력은 모의 데이터")
    session?.let {
        RegionOutline(it.feature.boundary, summary.discoveredRegionCount > 0, it.feature.cellExploration?.catalog, it.unlockedCells,
            it.feature.landmarkCollection?.catalog, it.landmarkSummary?.cards.orEmpty().filter { card -> card.unlockedUtcMs != null }.map { card -> card.id }.toSet())
        Text(it.status)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !it.busy, onClick = { onReplay("inside") }) { Text("지역 진입 재생") }
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("outside") }) { Text("경계 밖 재생") }
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("inaccurate") }) { Text("정확도 불량 재생") }
        }
        Button(enabled = !it.busy, onClick = { onReplay("road") }) { Text("도로 이동 재생 · 셀 해금") }
        Text("랜드마크 체류 도감 · 도로 탐험률과 별도 집계", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("landmark-short") }) { Text("짧은 방문 재생") }
            Button(enabled = !it.busy, onClick = { onReplay("landmark-dwell") }) { Text("2분 모의 체류 재생") }
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("landmark-exit") }) { Text("이탈 후 복귀 재생") }
        }
        it.landmarkSummary?.let { collection ->
            Text("랜드마크 도감 ${collection.unlockedCount} / ${collection.totalCount}개")
            collection.cards.forEach { card ->
                Text(if (card.unlockedUtcMs != null) "${card.name} · 수집 완료 · ${formatDiscoveryTime(card.unlockedUtcMs)}"
                    else "${card.name} · 미수집 · 최근 연속 관측 ${card.observedMillis / 1_000} / ${card.requiredMillis / 1_000}초")
                Text("반경 ${card.radiusMeters.toInt()}m · 정확도 25m 이하 · 위치 단절 15초 초과 시 다시 시작")
            }
        }
        Text("기록된 시각을 재생하는 모의 입력입니다. 주차·실제 방문을 증명하지 않습니다.")
    }
    Text("해금한 도로 셀 ${summary.exploredUnitCount} / ${summary.totalExplorableUnitCount}개")
    Text("발견 지역 ${summary.discoveredRegionCount}개 · 내부 탐험률 ${explorationRate(summary)}")
    Text(if (summary.discoveredRegionCount > 0) "남양읍 도감 열림 · 도로 이동으로 세부 탐험을 늘릴 수 있습니다."
        else "남양읍 도감 잠김 · 최초 지역 진입을 기다립니다.")
    summary.lastUpdatedUtcMs?.let { Text("최근 탐험: ${formatDiscoveryTime(it)}") }
    Text("탐험률은 수집된 공개 도로의 100m 대상 셀 기준입니다. 전체 국토나 모든 도로의 완주율이 아닙니다.")
    Text("회색: 대상 셀 · 초록: 해금 셀 · 보라/금색: 미수집/수집 명소 · 주황: 모의 경로 시작점")
    Text("도로: © OpenStreetMap contributors / Geofabrik · 2026-11-08 · ODbL 1.0")
    Text("https://www.openstreetmap.org/copyright · https://opendatacommons.org/licenses/odbl/1-0/")
    Text("경계: SGIS 원자료 · vuski/admdongkor 가공 · 2026-10-01 · CC BY 4.0")
}

fun formatDiscoveryTime(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(timestamp)) + " KST"

@Composable private fun RegionOutline(
    boundary: RegionBoundary, discovered: Boolean, catalog: RoadCellCatalog?, unlocked: Set<CellIndex>,
    landmarks: LandmarkCatalog?, collected: Set<String>,
) {
    val grid = catalog?.grid ?: LocalGrid(126.8, 37.2, 100.0)
    val points = boundary.polygons.flatMap { it.rings.flatten() }.map(grid::project)
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val width = points.maxOf { it.x } - minX
    val height = points.maxOf { it.y } - minY
    Canvas(Modifier.fillMaxWidth().height(280.dp)) {
        val scale = minOf((size.width - 24.dp.toPx()) / width, (size.height - 24.dp.toPx()) / height).toFloat()
        val left = (size.width - width.toFloat() * scale) / 2
        val top = (size.height - height.toFloat() * scale) / 2
        fun screen(p: MeterPoint) = Offset(left + (p.x - minX).toFloat() * scale,
            top + (height - (p.y - minY)).toFloat() * scale)
        val outline = Path().apply { fillType = PathFillType.EvenOdd }
        boundary.polygons.forEach { polygon -> polygon.rings.forEach { ring ->
            ring.forEachIndexed { index, p ->
                val at = screen(grid.project(p))
                if (index == 0) outline.moveTo(at.x, at.y) else outline.lineTo(at.x, at.y)
            }
            outline.close()
        } }
        clipPath(outline) {
            catalog?.let { data ->
                data.cells.keys.forEach { cell ->
                    val origin = screen(MeterPoint(cell.col * grid.sizeMeters, (cell.row + 1) * grid.sizeMeters))
                    val length = (grid.sizeMeters * scale).toFloat()
                    drawRect(if (cell in unlocked) Color(0xFF62E4AF) else Color(0xFF747C85).copy(alpha = .25f),
                        origin, Size(length, length))
                }
                data.roads.forEach { road ->
                    val path = Path()
                    road.points.forEachIndexed { index, p ->
                        val at = screen(p)
                        if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                    }
                    drawPath(path, Color(0xFFB4B9C1).copy(alpha = .4f), style = Stroke(0.7.dp.toPx()))
                }
            }
        }
        drawPath(outline, if (discovered) Color(0xFF9DE4CF) else Color.Gray, style = Stroke(2.dp.toPx()))
        landmarks?.landmarks?.forEach { landmark ->
            drawCircle(if (landmark.id in collected) Color(0xFFFFD56F) else Color(0xFFC3A9F0),
                4.dp.toPx(),screen(grid.project(landmark.point)))
        }
        catalog?.demo?.firstOrNull()?.let { fix ->
            drawCircle(Color(0xFFFFCF80), 4.dp.toPx(), screen(grid.project(fix.point)))
        }
    }
    Text("남양읍 실제 경계·도로 데이터 · 모의 위치 시연 / 배경지도·실제 GPS 미연동")
}
