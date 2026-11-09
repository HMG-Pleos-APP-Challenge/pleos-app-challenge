package com.daehyundai.map.feature.exploration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.model.ExplorationSummary
import com.daehyundai.map.feature.exploration.domain.RegionBoundary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos

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
        RegionOutline(it.feature.boundary, summary.discoveredRegionCount > 0)
        Text(it.status)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !it.busy, onClick = { onReplay("inside") }) { Text("지역 진입 재생") }
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("outside") }) { Text("경계 밖 재생") }
            OutlinedButton(enabled = !it.busy, onClick = { onReplay("inaccurate") }) { Text("정확도 불량 재생") }
        }
    }
    Text("발견 지역 ${summary.discoveredRegionCount}개 · 내부 탐험률 ${explorationRate(summary)}")
    Text(if (summary.discoveredRegionCount > 0) "남양읍 도감 열림 · 세부 탐험과 랜드마크는 다음 단계입니다."
        else "남양읍 도감 잠김 · 최초 지역 진입을 기다립니다.")
    summary.lastUpdatedUtcMs?.let { Text("최초 발견: ${formatDiscoveryTime(it)}") }
    Text("지역 발견은 전체 탐험 완료가 아닙니다. 도로·셀 분모는 2차 구현에서 연결합니다.")
    Text("경계: SGIS 원자료 · vuski/admdongkor 가공 · 2026-10-01 · CC BY 4.0")
}

fun formatDiscoveryTime(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(timestamp)) + " KST"

@Composable private fun RegionOutline(boundary: RegionBoundary, discovered: Boolean) {
    val points = boundary.polygons.flatMap { it.rings.flatten() }
    val minLon = points.minOf { it.longitude }
    val minLat = points.minOf { it.latitude }
    val cosLat = cos(Math.toRadians(points.map { it.latitude }.average()))
    val width = (points.maxOf { it.longitude } - minLon) * cosLat
    val height = points.maxOf { it.latitude } - minLat
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val scale = minOf((size.width - 24.dp.toPx()) / width, (size.height - 24.dp.toPx()) / height).toFloat()
        val left = (size.width - width.toFloat() * scale) / 2
        val top = (size.height - height.toFloat() * scale) / 2
        boundary.polygons.forEach { polygon ->
            polygon.rings.forEach { ring ->
                val path = Path()
                ring.forEachIndexed { index, p ->
                    val x = left + ((p.longitude - minLon) * cosLat).toFloat() * scale
                    val y = top + (height - (p.latitude - minLat)).toFloat() * scale
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, if (discovered) Color(0xFF9DE4CF) else Color.Gray, style = Stroke(3.dp.toPx()))
            }
        }
        // Marker is the synthetic replay point, not the user's current GPS position.
        drawCircle(Color(0xFFFFCF80), 5.dp.toPx(), Offset(
            left + ((126.8310 - minLon) * cosLat).toFloat() * scale,
            top + (height - (37.1995 - minLat)).toFloat() * scale,
        ))
    }
    Text("남양읍 실제 경계 윤곽 · 점은 모의 진입 지점 / 배경지도·실제 GPS 미연동")
}
