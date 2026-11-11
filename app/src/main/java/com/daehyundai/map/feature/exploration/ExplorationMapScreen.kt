package com.daehyundai.map.feature.exploration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import com.daehyundai.map.core.model.ExplorationSummary
import com.daehyundai.map.feature.exploration.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun explorationRate(summary: ExplorationSummary): String =
    if (summary.totalExplorableUnitCount <= 0) "집계 없음"
    else String.format(Locale.ROOT, "%.1f%%",100.0 * summary.exploredUnitCount / summary.totalExplorableUnitCount)

fun formatDiscoveryTime(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    .withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(timestamp)) + " KST"

@Composable fun ExplorationMapScreen(summary:ExplorationSummary,session:ExplorationSession?,onRegion:()->Unit,onSources:()->Unit) {
    Text("익숙한 길에서 시작하는 새로운 탐험",style=MaterialTheme.typography.titleLarge)
    Text("나의 발자취가 쌓인 지역을 확인해 보세요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    val syncScope=rememberCoroutineScope()
    session?.let {
        Text(it.cloudStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(it.usesServerProgress)
            Column {
                Text("탐험률·경험치는 서버 확정 기준이에요. 기기에만 남은 구역 ${it.pendingCells.size}개는 지도에서 별도로 확인할 수 있어요.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={syncScope.launch { it.syncCloud() }}) { Text("기록 동기화") }
            } else Text("탐험률은 이 기기의 기록을 기준으로 계산해요.")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun MapCard(modifier:Modifier) {
            Card(onClick=onRegion,modifier=modifier,colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("경기도 화성시",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("남양읍",style=MaterialTheme.typography.headlineMedium)
                        }
                        Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Text(if(session?.regionDiscovered==true) "발견한 지역" else "첫 발견을 기다려요",Modifier.padding(12.dp),style=MaterialTheme.typography.labelLarge)
                        }
                    }
                    ExplorationMapPreview(session,height=380,showDetails=session?.regionDiscovered==true)
                    if(session?.regionDiscovered==true) ExplorationLegend(session.usesServerProgress)
                    Text("지역 자세히 보기 →",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleMedium)
                }
            }
        }
        @Composable fun Overview(modifier:Modifier) {
            Column(modifier,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("나의 남양읍 탐험",style=MaterialTheme.typography.titleMedium)
                        Text(explorationRate(summary),style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(progress={if(summary.totalExplorableUnitCount>0) (summary.exploredUnitCount.toFloat()/summary.totalExplorableUnitCount).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth())
                        Text("탐험한 구역 ${summary.exploredUnitCount} / ${summary.totalExplorableUnitCount}",style=MaterialTheme.typography.bodyLarge)
                        Text("수집한 도로 데이터를 기준으로 계산해요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("지나온 길은 기록으로, 새로운 발견은 음성으로.",style=MaterialTheme.typography.titleMedium)
                Text("위치 권한을 허용하면 탐험이 자동으로 기록돼요. 지도와 도감은 주차 후 편하게 확인해 주세요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick=onRegion,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("남양읍 탐험 열기") }
            }
        }
        if(maxWidth>=720.dp) {
            Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) { MapCard(Modifier.weight(1.6f)); Overview(Modifier.weight(1f)) }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(20.dp)) { MapCard(Modifier.fillMaxWidth()); Overview(Modifier.fillMaxWidth()) }
        }
    }
    MapAttribution(onSources)
}

@Composable fun RegionDetailScreen(session:ExplorationSession?,recordingStatus:String,
    onCells:()->Unit,onLandmark:(String)->Unit,onSources:()->Unit,onCell:(CellIndex)->Unit={}) {
    if(session==null) { Text("탐험 기록을 불러오고 있어요.");return }
    val summary=session.summary
    Text(recordingStatus, style=MaterialTheme.typography.titleMedium)
    if (!session.regionDiscovered) {
        ExplorationMapPreview(session, height=320)
        Text("남양읍에 처음 진입하면 지역 도감이 열려요.")
        MapAttribution(onSources)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun RegionMap(modifier:Modifier) {
            Card(modifier,colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ExplorationMapPreview(session,height=420,showDetails=true,onLandmark=onLandmark,onCell=onCell)
                    ExplorationLegend(session.usesServerProgress)
                    Text("구역이나 명소를 선택하면 상세 기록을 볼 수 있어요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        @Composable fun RegionProgress(modifier:Modifier) {
            Column(modifier,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("나의 남양읍 기록",style=MaterialTheme.typography.titleLarge)
                Text(explorationRate(summary),style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
                Text("탐험한 구역 ${summary.exploredUnitCount} / ${summary.totalExplorableUnitCount}")
                OutlinedButton(onClick=onCells,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("탐험한 구역 보기 →") }
                Text("상세 기록은 주차 후 확인해 주세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(maxWidth>=720.dp) {
            Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) { RegionMap(Modifier.weight(1.6f)); RegionProgress(Modifier.weight(1f)) }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(20.dp)) { RegionProgress(Modifier.fillMaxWidth()); RegionMap(Modifier.fillMaxWidth()) }
        }
    }
    session.landmarkSummary?.let { collection ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("발견할 명소",style=MaterialTheme.typography.titleLarge)
            Text("${collection.unlockedCount} / ${collection.totalCount} 수집",color=MaterialTheme.colorScheme.primary)
        }
        collection.cards.forEach { card ->
            Card(onClick={onLandmark(card.id)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF252D2B)),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if(card.unlockedUtcMs!=null) "★" else "◇",color=if(card.unlockedUtcMs!=null) Color(0xFFFFD56F) else Color(0xFFC3A9F0),style=MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(card.name,style=MaterialTheme.typography.titleMedium)
                        Text(if(card.unlockedUtcMs!=null) "나의 도감에 보관 중" else "주변에서 머무르며 발견해 보세요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("상세 →",color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    MapAttribution(onSources)
}

@Composable private fun ExplorationLegend(serverProgress:Boolean) {
    Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        if(serverProgress) Text("■ 동기화 대기",color=Color(0xFFF4A261),style=MaterialTheme.typography.labelLarge)
        Text(if(serverProgress) "■ 서버 확정" else "■ 탐험한 구역",color=Color(0xFF62E4AF),style=MaterialTheme.typography.labelLarge)
        Text("□ 미탐험 구역",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelLarge)
        Text("● 명소",color=Color(0xFFC3A9F0),style=MaterialTheme.typography.labelLarge)
    }
}

@Composable fun CellsScreen(session:ExplorationSession?,onCell:(CellIndex)->Unit) {
    if(session==null) { Text("탐험 기록을 불러오는 중이에요.");return }
    Text("해금한 도로 셀 ${session.summary.exploredUnitCount} / ${session.summary.totalExplorableUnitCount}개",style=MaterialTheme.typography.titleLarge)
    Text("도로를 따라 이동하면 지나간 구역이 열려요. 현재 지역의 모든 면적을 계산하는 비율은 아니에요.")
    if(session.unlockedCells.isEmpty()) Text("아직 해금한 도로 셀이 없어요.")
    session.unlockedCells.sortedWith(compareBy({it.col},{it.row})).take(30).forEachIndexed { index,cell ->
        ListItem(headlineContent={Text("탐험한 구역 ${index+1}")},supportingContent={Text(session.cellLabel(cell))},
            trailingContent={Text("지도 보기 →")},modifier=Modifier.clickable { onCell(cell) })
    }
    if(session.unlockedCells.size>30) Text("목록은 30개까지 표시해요. 전체 개수는 탐험률에 포함됩니다.")
}

@Composable fun CellDetailScreen(session:ExplorationSession?,cell:CellIndex?) {
    val catalog=session?.feature?.cellExploration?.catalog
    if(session==null || cell==null || catalog==null || cell !in catalog.cells) { Text("선택한 구역을 확인할 수 없어요.");return }
    val point=catalog.grid.unproject(MeterPoint((cell.col+.5)*catalog.grid.sizeMeters,(cell.row+.5)*catalog.grid.sizeMeters))
    ExplorationMapPreview(session,height=320,showDetails=true,focus=point,highlight=cell)
    Text(session.cellLabel(cell),style=MaterialTheme.typography.titleLarge)
    Text("도로 위 이동으로 해금하는 약 500m × 500m 크기의 탐험 구역이에요.")
    Text("처음 해금하면 10 XP를 얻어요. 같은 구역은 다시 지급하지 않아요.")
}

@Composable fun LandmarkDetailScreen(session:ExplorationSession?,id:String?,onSources:()->Unit) {
    val definition=session?.feature?.landmarkCollection?.catalog?.landmarks?.find { it.id==id }
    val card=session?.landmarkSummary?.cards?.find { it.id==id }
    if(definition==null || card==null) { Text("명소 정보를 불러올 수 없어요.");return }
    ExplorationMapPreview(session,height=300,showDetails=true,focus=definition.point)
    Text(card.name,style=MaterialTheme.typography.headlineSmall)
    if(card.unlockedUtcMs!=null) {
        Text("도감 수집 완료",style=MaterialTheme.typography.titleLarge)
        Text("수집 시각 ${formatDiscoveryTime(card.unlockedUtcMs)}")
    } else {
        Text("명소 주변에서 연속으로 ${card.requiredMillis/60_000}분 머무르면 도감이 열려요.")
        LinearProgressIndicator(progress={ (card.observedMillis.toFloat()/card.requiredMillis).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
        Text("최근 연속 관측 ${card.observedMillis/1000} / ${card.requiredMillis/1000}초")
    }
    Text("반경 ${card.radiusMeters.toInt()}m · 위치가 끊기거나 벗어나면 다시 시작해요.")
    Text("주차나 시설 입장을 확인하는 기능은 아니에요.")
    MapAttribution(onSources)
}

@Composable fun MapAttribution(onSources:()->Unit) {
    TextButton(onClick=onSources) { Text("지도 출처 · © OpenStreetMap contributors · SGIS / vuski") }
}

/** Geometry preview from bundled data; no background tiles or map-provider SDK. */
@Composable fun ExplorationMapPreview(session:ExplorationSession?,height:Int=150,showDetails:Boolean=false,
    focus:GeoPoint?=null,highlight:CellIndex?=null,
    onLandmark:((String)->Unit)?=null,onCell:((CellIndex)->Unit)?=null) {
    if(session==null) { Box(Modifier.fillMaxWidth().height(height.dp)) { Text("지도 불러오는 중…",Modifier.padding(20.dp)) };return }
    val boundary=session.feature.boundary
    val catalog=session.feature.cellExploration?.catalog
    val grid=catalog?.grid ?: LocalGrid(126.8,37.2,500.0)
    val points=boundary.polygons.flatMap { it.rings.flatten() }.map(grid::project)
    val center=focus?.let(grid::project)
    val minX=center?.let { it.x-500 } ?: points.minOf { it.x }
    val minY=center?.let { it.y-500 } ?: points.minOf { it.y }
    val width=if(center!=null) 1000.0 else points.maxOf { it.x }-minX
    val mapHeight=if(center!=null) 1000.0 else points.maxOf { it.y }-minY
    val collected=session.landmarkSummary?.cards.orEmpty().filter { it.unlockedUtcMs!=null }.map {it.id}.toSet()
    val landmarkAction=rememberUpdatedState(onLandmark)
    val cellAction=rememberUpdatedState(onCell)
    val taps=if(onLandmark!=null || onCell!=null) Modifier.pointerInput(grid,minX,minY,width,mapHeight) {
        detectTapGestures { tap ->
            val scale=minOf(size.width/width,size.height/mapHeight)
            val left=(size.width-width*scale)/2
            val top=(size.height-mapHeight*scale)/2
            val landmarks=session.feature.landmarkCollection?.catalog?.landmarks.orEmpty()
            val nearest=landmarks.minByOrNull { landmark ->
                val p=grid.project(landmark.point)
                val dx=left+(p.x-minX)*scale-tap.x
                val dy=top+(mapHeight-(p.y-minY))*scale-tap.y
                dx*dx+dy*dy
            }
            val marker=nearest?.let { landmark ->
                val p=grid.project(landmark.point)
                Offset((left+(p.x-minX)*scale).toFloat(),(top+(mapHeight-(p.y-minY))*scale).toFloat())
            }
            if(nearest!=null && marker!=null && (marker-tap).getDistance()<=24.dp.toPx()) {
                landmarkAction.value?.invoke(nearest.id)
            } else {
                val x=minX+(tap.x-left)/scale
                val y=minY+mapHeight-(tap.y-top)/scale
                val cell=CellIndex(kotlin.math.floor(x/grid.sizeMeters).toInt(),kotlin.math.floor(y/grid.sizeMeters).toInt())
                if(catalog?.cells?.containsKey(cell)==true) cellAction.value?.invoke(cell)
            }
        }
    } else Modifier
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(12.dp).clipToBounds().then(taps)) {
        val scale=minOf(size.width/width,size.height/mapHeight).toFloat()
        val left=(size.width-width.toFloat()*scale)/2
        val top=(size.height-mapHeight.toFloat()*scale)/2
        fun screen(point:MeterPoint)=Offset(left+(point.x-minX).toFloat()*scale,top+(mapHeight-(point.y-minY)).toFloat()*scale)
        val outline=Path().apply { fillType=PathFillType.EvenOdd }
        boundary.polygons.forEach { polygon -> polygon.rings.forEach { ring ->
            ring.forEachIndexed { index,p -> val at=screen(grid.project(p));if(index==0) outline.moveTo(at.x,at.y) else outline.lineTo(at.x,at.y) }
            outline.close()
        } }
        clipPath(outline) {
            drawRect(Color(0xFF253A37))
            if(showDetails) catalog?.let { data ->
                data.cells.keys.forEach { cell ->
                    val at=screen(MeterPoint(cell.col*grid.sizeMeters,(cell.row+1)*grid.sizeMeters))
                    val length=(grid.sizeMeters*scale).toFloat()
                    drawRect(if(cell in session.pendingCells) Color(0xFFF4A261) else if(cell in session.unlockedCells) Color(0xFF62E4AF) else Color(0xFF55615E),at,Size(length,length))
                    if(cell==highlight) drawRect(Color.White,at,Size(length,length),style=Stroke(2.dp.toPx()))
                }
                data.roads.forEach { road ->
                    val path=Path();road.points.forEachIndexed { i,p -> val at=screen(p);if(i==0) path.moveTo(at.x,at.y) else path.lineTo(at.x,at.y) }
                    drawPath(path,Color(0xFFA8B9B2).copy(alpha=.65f),style=Stroke(.8.dp.toPx()))
                }
            }
        }
        drawPath(outline,Color(0xFF9DE4CF),style=Stroke(2.dp.toPx()))
        if(showDetails) session.feature.landmarkCollection?.catalog?.landmarks?.forEach { landmark ->
            val at=screen(grid.project(landmark.point))
            if(focus!=null) drawCircle(Color(0xFFC3A9F0).copy(alpha=.18f),(landmark.radiusMeters*scale).toFloat(),at)
            drawCircle(if(landmark.id in collected) Color(0xFFFFD56F) else Color(0xFFC3A9F0),6.dp.toPx(),at)
        }
    }
}
