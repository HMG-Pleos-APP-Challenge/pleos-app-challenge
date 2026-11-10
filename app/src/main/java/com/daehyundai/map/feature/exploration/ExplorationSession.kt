package com.daehyundai.map.feature.exploration

import android.content.Context
import androidx.compose.runtime.*
import com.daehyundai.map.core.model.*
import com.daehyundai.map.core.data.FileProgressRepository
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prototype adapter. A single repository instance feeds both map and profile summary. */
class ExplorationSession(private val userId: String, val feature: LocalExplorationFeature, val sourceType: LocationSourceType = LocationSourceType.MOCK, private val rewards: ExplorationRewards? = null, private val cloud: com.daehyundai.map.feature.exploration.cloud.CloudSync? = null) {
    var progress by mutableStateOf(UserProgress(userId,0,1))
        private set
    var summary by mutableStateOf(ExplorationSummary(userId, 0, 0, 0, 0, null))
        private set
    var status by mutableStateOf(if (sourceType == LocationSourceType.MOCK) "지역 진입을 재생해 발견과 도감 개방을 확인하세요." else "SDK 위치 입력을 기다립니다")
        private set
    var busy by mutableStateOf(false)
        private set
    var unlockedCells by mutableStateOf<Set<CellIndex>>(emptySet())
        private set
    var landmarkSummary by mutableStateOf<LandmarkCollectionSummary?>(null)
        private set
    var cloudStatus by mutableStateOf("로컬 기록 · 서버 미연결")
        private set
    private var confirmedProgress:UserProgress?=null
    suspend fun syncCloud() {
        try {
            cloud?.sync()?.let { snapshot ->
                withContext(Dispatchers.IO) { feature.mergeConfirmed(userId,snapshot.grants) }
                confirmedProgress=UserProgress(userId,snapshot.xp,(snapshot.xp/100+1).toInt())
                refresh()
            }
            cloudStatus=cloud?.message ?: "모의 기록 · 서버 전송 안 함"
        } catch(e:CancellationException) { throw e
        } catch(e:Exception) { cloudStatus="서버 기록 복원 실패 · 로컬 기록 보존" }
    }
    private var replayNumber = 0
    private var lastReplayTimestamp = 0L

    suspend fun refresh(reconcileRewards:Boolean=true) {
        if(reconcileRewards) {
            withContext(Dispatchers.IO) { rewards?.reconcile(feature,userId) }?.let { progress=it }
        }
        val loaded = withContext(Dispatchers.IO) {
            Triple(feature.getSummary(userId), feature.cellExploration?.unlockedCells(userId) ?: emptySet(),
                feature.landmarkCollection?.summary(userId))
        }
        confirmedProgress?.let { progress=it }
        summary = loaded.first
        unlockedCells = loaded.second
        landmarkSummary = loaded.third
    }

    suspend fun acceptSdk(sample: LocationSample): DiscoveryResult {
        check(sourceType == LocationSourceType.PLEOS && sample.source == sourceType)
        cloud?.enqueue(sample)
        val result = withContext(Dispatchers.IO) { feature.onLocation(sample) }
        refresh(result.newlyDiscovered || result.newlyUnlockedCells>0 || result.newlyCollectedLandmarks>0)
        val earned = buildList {
            if (result.newlyDiscovered) add("지역 최초 발견")
            if (result.newlyUnlockedCells > 0) add("도로 셀 ${result.newlyUnlockedCells}개 해금")
            if (result.newlyCollectedLandmarks > 0) add("랜드마크 도감 ${result.newlyCollectedLandmarks}개 수집")
        }
        status = when {
            earned.isNotEmpty() -> earned.joinToString(" · ")
            sample.accuracyMeters == null -> "정확도 미상 · 해금하지 않으며 연속 관측을 다시 시작합니다"
            result.status == EntryStatus.REJECTED -> "위치 정확도·관측 시각 조건 미달 · 해금 없음"
            result.status == EntryStatus.OUTSIDE -> "대상 지역 밖 · 해금 없음"
            else -> "SDK 위치 확인 · 지역·도로·체류 조건에 따라 자동 해금합니다"
        }
        return result
    }

    suspend fun replay(scenario: String) {
        check(sourceType == LocationSourceType.MOCK) { "SDK 기록에 모의 입력을 넣을 수 없습니다" }
        if (busy) return
        busy = true
        try {
            replayNumber++
            val latestCollection = landmarkSummary?.cards?.mapNotNull { it.unlockedUtcMs }?.maxOrNull() ?: 0L
            val base = maxOf(System.currentTimeMillis(), maxOf(summary.lastUpdatedUtcMs ?: 0, latestCollection) + 10_000,
                lastReplayTimestamp + 31_000)
            val roadDemo = feature.cellExploration?.catalog?.demo.orEmpty()
            val landmark = feature.landmarkCollection?.catalog?.landmarks?.firstOrNull()
            val fixes = if (scenario.startsWith("landmark-")) {
                val target = requireNotNull(landmark) { "랜드마크 데이터가 없습니다" }
                val outside = GeoPoint(target.point.latitude, target.point.longitude + .003)
                when (scenario) {
                    "landmark-dwell" -> {
                        val times = (0L until target.dwellMillis step 10_000L).toList() + target.dwellMillis
                        times.mapIndexed { index, time -> DemoRoadFix("dwell-$index",target.point,time) }
                    }
                    "landmark-short" -> (0..3).map { DemoRoadFix("short-$it",if(it==3) outside else target.point,it*10_000L) }
                    "landmark-exit" -> (0..14).map { DemoRoadFix("exit-$it",if(it==7) outside else target.point,it*10_000L) }
                    else -> error("알 수 없는 랜드마크 시나리오")
                }
            } else if (scenario == "road") {
                check(roadDemo.isNotEmpty()) { "도로 모의 입력이 없습니다" }
                roadDemo
            } else {
                val point = if (scenario == "outside") GeoPoint(37.55, 126.98) else GeoPoint(37.1995, 126.8310)
                listOf(DemoRoadFix("start", point, 0), DemoRoadFix("confirm", point, 5_000))
            }
            lastReplayTimestamp = base + fixes.last().offsetMillis
            val accuracy = if (scenario == "inaccurate") 150f else 5f
            val results = withContext(Dispatchers.IO) {
                fixes.map { fix -> feature.onLocation(LocationSample(
                    "namyang-demo-$replayNumber-${fix.id}", userId, fix.point.latitude, fix.point.longitude,
                    base + fix.offsetMillis, accuracy, LocationSourceType.MOCK,
                )) }
            }
            refresh()
            val newCells = results.sumOf { it.newlyUnlockedCells }
            val newLandmarks = results.sumOf { it.newlyCollectedLandmarks }
            status = when {
                scenario.startsWith("landmark-") && newLandmarks > 0 -> "명소 근처 연속 체류 확인 · 랜드마크 도감 ${newLandmarks}개 획득"
                scenario == "landmark-dwell" -> "체류 재생 완료 · 새 도감 없음 (기존 도감은 한 번만 획득)"
                scenario == "landmark-short" -> "짧은 방문 후 이탈 · 체류 조건 미달로 새 도감 없음"
                scenario == "landmark-exit" -> "이탈 전후 시간은 합산하지 않습니다 · 새 도감 없음"
                scenario == "road" && newCells > 0 -> "도로 이동 확인 · 새로운 도로 셀 ${newCells}개 해금"
                scenario == "road" -> "도로 이동 재생 완료 · 새로운 해금 없음 (기존 셀은 중복 집계하지 않음)"
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

@Composable fun rememberExplorationSession(userId: String, source: LocationSourceType = LocationSourceType.MOCK,
    sdkRecordPrefix: String = "pleos"): Pair<ExplorationSession?, String?> {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var session by remember(userId,source,sdkRecordPrefix) { mutableStateOf<ExplorationSession?>(null) }
    var error by remember(userId,source,sdkRecordPrefix) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId,source,sdkRecordPrefix) {
        try {
            session = withContext(Dispatchers.IO) { openExplorationSession(context, userId,source,sdkRecordPrefix) }.also { it.refresh() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "탐험 기록을 열 수 없습니다. 기존 기록을 보존했으며 파일·버전 확인이 필요합니다."
        }
    }
    return session to error
}

internal fun openExplorationSession(context: Context, userId: String,
    source: LocationSourceType = LocationSourceType.MOCK, sdkRecordPrefix: String = "pleos"): ExplorationSession {
    require(sdkRecordPrefix in setOf("pleos", "pleos-emulator"))
    val prefix = if (source == LocationSourceType.MOCK) "mock" else sdkRecordPrefix
    val boundary = context.assets.open("exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
    val store = FileDiscoveryStore(File(context.filesDir, "exploration/$prefix-discoveries.bin"),
        boundary.datasetVersion, source)
    val catalog = context.assets.open("exploration/namyang.roadcells").reader().use(RoadCellCatalogReader::read)
    val cells = LocalCellExploration(boundary, catalog, source,
        FileCellUnlockStore(File(context.filesDir, "exploration/$prefix-cells.bin"), catalog.version, source))
    val landmarks = context.assets.open("exploration/namyang.landmarks").reader().use(LandmarkCatalogReader::read)
    val collection = LocalLandmarkCollection(boundary,landmarks,source,
        FileLandmarkUnlockStore(File(context.filesDir,"exploration/$prefix-landmarks.bin"),landmarks.version,source))
    val rewards=ExplorationRewards(FileProgressRepository(File(context.filesDir,"progress/$prefix-xp.bin")),prefix)
    return ExplorationSession(userId, LocalExplorationFeature(boundary, source, store, cells, collection), source,rewards,
        if(source == LocationSourceType.PLEOS) com.daehyundai.map.feature.exploration.cloud.CloudSync(context,userId,prefix) else null)
}
