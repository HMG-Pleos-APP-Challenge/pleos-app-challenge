package com.daehyundai.map.feature.conquest

import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daehyundai.map.feature.exploration.cloud.SupabaseCloud
import com.daehyundai.map.feature.exploration.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun CellConquestScreen(partition:String) {
    val context=LocalContext.current.applicationContext
    val backend=remember { SupabaseCloud(context) };val scope=rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("점령 현황 불러오는 중") };var busy by remember { mutableStateOf(false) }
    val catalog=remember { context.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read) }
    suspend fun load() { data=withContext(Dispatchers.IO) { JSONObject(backend.rpc("conquest_snapshot_v1",JSONObject().put("p_partition",partition))) };message="서버에서 확정된 이번 주 점령 현황" }
    fun refresh(team:String?=null) {
        if(busy) return
        busy=true;scope.launch {
            try {
                if(team!=null) withContext(Dispatchers.IO) { backend.rpc("choose_conquest_team_v1",JSONObject().put("p_team",team)) }
                load()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="연결 실패 또는 이번 주 팀 변경 제한 · 네트워크와 서버 설정을 확인해 주세요." }
            finally { busy=false }
        }
    }
    LaunchedEffect(partition) { refresh() }
    Text("이번 주 팀 점령",style=MaterialTheme.typography.titleLarge)
    Text(message)
    if(partition=="pleos-emulator") Text("에뮬레이터 테스트 기록 · 실제 차량 경쟁과 분리됩니다.")
    val snapshot=data
    Text("내 팀: ${snapshot?.optString("team")?.takeUnless { it=="null" || it.isEmpty() } ?: "선택 전"}")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("A","B","C").forEach { team -> OutlinedButton(enabled=!busy,onClick={refresh(team)}) { Text("$team 팀 선택") } }
    }
    Text("같은 구역은 하루 한 번 기여해요. 첫 기여 후에는 다음 주까지 팀을 바꿀 수 없어요.")
    snapshot?.let { current ->
        Text("주간 시작: ${current.getString("week")} · 내 기여 ${current.getInt("my_contributions")}회")
        val scores=current.getJSONObject("scores")
        Text(listOf("A","B","C").joinToString("   ") { "$it 팀 ${scores.getInt(it)}점" })
        val owners=current.getJSONArray("owners")
        val teams=(0 until owners.length()).associate { owners.getJSONObject(it).let { row -> row.getString("cell") to row.getString("team") } }
        val colors=mapOf("A" to Color(0xFFE7A66D),"B" to Color(0xFF76B4E8),"C" to Color(0xFF9DE4CF))
        val points=catalog.roads.flatMap { it.points }
        val minX=points.minOf { it.x };val maxX=points.maxOf { it.x };val minY=points.minOf { it.y };val maxY=points.maxOf { it.y }
        Canvas(Modifier.fillMaxWidth().height(360.dp)) {
            val scale=minOf(size.width/(maxX-minX+1000),size.height/(maxY-minY+1000))
            fun screen(x:Double,y:Double)=Offset(((x-minX+500)*scale).toFloat(),size.height-((y-minY+500)*scale).toFloat())
            catalog.cells.keys.forEach { cell ->
                val team=teams[catalog.cellId(cell)]
                if(team!=null) drawRect(colors.getValue(team).copy(alpha=.6f),screen(cell.col*500.0,(cell.row+1)*500.0),Size((500*scale).toFloat(),(500*scale).toFloat()))
            }
            catalog.roads.forEach { road -> road.points.zipWithNext().forEach { (a,b) -> drawLine(Color.LightGray,screen(a.x,a.y),screen(b.x,b.y),1f) } }
        }
        Text("주황 A · 파랑 B · 초록 C · 무색 미점령")
        Text("점령한 구역 ${owners.length()} / ${catalog.cells.size} · 동률이면 기존 소유 팀 유지")
    }
    OutlinedButton(enabled=!busy,onClick={refresh()}) { Text("점령 현황 새로고침") }
}
