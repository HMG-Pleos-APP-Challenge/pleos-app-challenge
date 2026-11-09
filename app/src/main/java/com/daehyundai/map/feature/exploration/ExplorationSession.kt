package com.daehyundai.map.feature.exploration

import android.content.Context
import androidx.compose.runtime.*
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prototype adapter. A single repository instance feeds both map and profile summary. */
class ExplorationSession(private val userId: String, val feature: LocalExplorationFeature) {
    var summary by mutableStateOf(ExplorationSummary(userId, 0, 0, 0, 0, null))
        private set
    var status by mutableStateOf("지역 진입을 재생해 발견과 도감 개방을 확인하세요.")
        private set
    var busy by mutableStateOf(false)
        private set
    private var replayNumber = 0
    private var lastReplayTimestamp = 0L

    suspend fun refresh() { summary = withContext(Dispatchers.IO) { feature.getSummary(userId) } }

    suspend fun replay(scenario: String) {
        if (busy) return
        busy = true
        try {
            replayNumber++
            val base = maxOf(System.currentTimeMillis(), (summary.lastUpdatedUtcMs ?: 0) + 10_000, lastReplayTimestamp + 10_000)
            lastReplayTimestamp = base + 5_000L
            val point = if (scenario == "outside") GeoPoint(37.55, 126.98) else GeoPoint(37.1995, 126.8310)
            val accuracy = if (scenario == "inaccurate") 150f else 5f
            val results = withContext(Dispatchers.IO) {
                (0..1).map { index -> feature.onLocation(LocationSample(
                    "namyang-demo-$replayNumber-$index", userId, point.latitude, point.longitude,
                    base + index * 5_000L, accuracy, LocationSourceType.MOCK,
                )) }
            }
            refresh()
            status = when {
                results.any { it.newlyDiscovered } -> "남양읍 최초 발견 · 지역 도감이 열렸습니다."
                results.last().status == EntryStatus.CONFIRMED -> "이미 발견한 지역입니다 · 중복 추가되지 않았습니다."
                results.last().status == EntryStatus.OUTSIDE -> "대상 지역 밖입니다 · 발견하지 않았습니다."
                results.last().status == EntryStatus.REJECTED -> "위치 정확도가 부족합니다 · 발견하지 않았습니다."
                else -> "지역 안쪽 위치를 추가 확인하는 중입니다."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "기록을 처리하지 못했습니다. 저장 공간과 데이터 파일을 확인해 주세요."
        } finally { busy = false }
    }
}

@Composable fun rememberExplorationSession(userId: String): Pair<ExplorationSession?, String?> {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var session by remember(userId) { mutableStateOf<ExplorationSession?>(null) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        try {
            session = withContext(Dispatchers.IO) { openExplorationSession(context, userId) }.also { it.refresh() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "탐험 기록을 열 수 없습니다. 기존 기록을 보존했으며 파일·버전 확인이 필요합니다."
        }
    }
    return session to error
}

private fun openExplorationSession(context: Context, userId: String): ExplorationSession {
    val boundary = context.assets.open("exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
    val store = FileDiscoveryStore(File(context.filesDir, "exploration/mock-discoveries.bin"),
        boundary.datasetVersion, LocationSourceType.MOCK)
    return ExplorationSession(userId, LocalExplorationFeature(boundary, LocationSourceType.MOCK, store))
}
