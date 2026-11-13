package com.daehyundai.map.feature.conquest

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConquestViewState(val catalog: ConquestCatalog, val summary: ConquestSummary,
    val owners: Map<String, String?>, val teamIds: List<String>,
    val roadDetails: Map<String, ConquestRoadDetail> = emptyMap())

/** One session at the app composition root. Screens receive snapshots, not direct access to storage. */
class ConquestSession private constructor(private val userId: String, private val catalog: ConquestCatalog,
    private val feature: LocalConquestFeature, private val pipeline: ConquestLocationPipeline,
    private val teams: List<String>, val demoEnabled: Boolean, val sourceType:LocationSourceType) {
    private var savedState by mutableStateOf<ConquestViewState?>(null)
    private var demoState by mutableStateOf<ConquestViewState?>(null)
    val state get() = demoState ?: savedState
    private var demo: ConquestDemo? = null
    var demoLabel by mutableStateOf<String?>(null)
        private set
    var demoSettled by mutableStateOf(false)
        private set
    var demoCompetitionStep by mutableIntStateOf(0)
        private set
    var demoCanCompete by mutableStateOf(false)
        private set
    var demoCompetitionBusy by mutableStateOf(false)
        private set
    var demoTimeUtcMs by mutableStateOf<Long?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var visitStatus by mutableStateOf<String?>(null)
        private set
    internal val hasPendingVisits get() = pipeline.pendingCount > 0
    var pendingVisitCount by mutableIntStateOf(0)
        private set
    private val mutex = Mutex()

    private suspend fun change(action: suspend () -> Unit) = mutex.withLock {
        try {
            action()
            visitStatus = pipeline.lastResult?.result?.let { result ->
                when (result) {
                    ContributionResult.ACCEPTED -> "최근 완주 기여가 인정됐습니다."
                    ContributionResult.FUTURE -> if (hasPendingVisits) "완주 기록 시각 대기 중 · 원본 시각에 도달하면 재처리합니다."
                        else "완주 기여 미인정 · 허용 범위를 벗어난 미래 시각입니다."
                    ContributionResult.LATE -> "완주 기여 미인정 · 이미 마감된 기간의 기록입니다."
                    ContributionResult.DUPLICATE_EVENT, ContributionResult.DUPLICATE_HALF_DAY -> "이미 처리한 이벤트 또는 같은 반일의 방향 구간입니다 · 추가 기여 없음"
                    ContributionResult.NO_TEAM, ContributionResult.BEFORE_TEAM_SELECTION -> "완주 기여 미인정 · 팀 선택 전 기록입니다."
                    ContributionResult.WRONG_SOURCE -> "완주 기여 미인정 · 입력 출처가 다릅니다."
                    ContributionResult.UNKNOWN_SEGMENT -> "완주 기여 미인정 · 등록되지 않은 도로입니다."
                    ContributionResult.INVALID -> "완주 기여 미인정 · 기록 형식을 확인해 주세요."
                }
            }
            error = if (hasPendingVisits && pipeline.lastResult?.result != ContributionResult.FUTURE)
                "미처리 점령 기록 ${pipeline.pendingCount}건 · 저장 재시도 필요" else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "점령 기록을 처리하지 못했습니다. 기존 기록은 유지되며 다시 확인이 필요합니다."
        } finally {
            pendingVisitCount = pipeline.pendingCount
        }
    }

    private suspend fun update(action: () -> Unit) = change {
        savedState = withContext(Dispatchers.IO) {
            ConquestAccountRestore.withRecordAccess {
                action()
                ConquestViewState(catalog, feature.readSummary(userId), feature.owners(), teams, feature.roadDetails(userId))
            }
        }
    }

    suspend fun replayDemo(route: DemoRoute, onSample: suspend (LocationSample) -> Unit = {}): Boolean {
        var completed = false
        change {
        if (!demoEnabled) return@change
        val (nextDemo, snapshot) = withContext(Dispatchers.IO) {
            val next = ConquestDemo(userId, catalog, teams, feature.teamOf(userId) ?: teams.first())
            next to next.replay(route, onSample)
        }
        demo = nextDemo
        demoState = snapshot
        demoLabel = route.label
        demoSettled = false
        demoCompetitionStep = 0
        demoCanCompete = false
        demoTimeUtcMs = nextDemo.simulatedUtcMs
        completed = true
        }
        return completed
    }

    suspend fun settleDemo() = change {
        val current = demo ?: return@change
        demoState = withContext(Dispatchers.IO) { current.settle() }
        demoSettled = current.settled
        demoCanCompete = current.canCompete
        demoTimeUtcMs = current.simulatedUtcMs
    }

    suspend fun advanceDemoCompetition() {
        if (demoCompetitionBusy) return
        demoCompetitionBusy = true
        try {
            change {
                val current = demo ?: return@change
                demoState = withContext(Dispatchers.IO) { current.advanceCompetition() }
                demoCompetitionStep = current.competitionStep
                demoCanCompete = current.canCompete
                demoTimeUtcMs = current.simulatedUtcMs
            }
        } finally { demoCompetitionBusy = false }
    }

    suspend fun exitDemo() = change {
        demo = null
        demoState = null
        demoLabel = null
        demoSettled = false
        demoCompetitionStep = 0
        demoCanCompete = false
        demoTimeUtcMs = null
    }

    fun resetInput() { pipeline.resetObservations(userId) }
    suspend fun refresh() = update { pipeline.retryPending() }
    suspend fun selectTeam(teamId: String) = update {
        check(pipeline.selectTeam(userId, teamId)) { "Team cannot change" }
    }
    suspend fun onSample(sample: LocationSample) = update { pipeline.onSample(sample) }

    companion object {
        private val sessions=mutableMapOf<String,ConquestSession>()
        private val opening=Mutex()
        suspend fun open(context: Context, userId: String,
            teams: List<String> = listOf("team-red", "team-blue"),
            source:LocationSourceType=LocationSourceType.MOCK, recordPrefix:String="mock"): ConquestSession = opening.withLock { withContext(Dispatchers.IO) {
            require(userId.isNotBlank() && teams.isNotEmpty() && teams.distinct().size == teams.size)
            val catalog = context.assets.open("conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
            val prefix=if(source==LocationSourceType.MOCK) "mock" else recordPrefix.also { require(it in setOf("pleos","pleos-emulator")) }
            val legacy = context.assets.open("conquest/namyang-legacy.segments").reader().use(ConquestCatalogReader::read)
            val directory = ConquestCatalogMigration.userDirectory(File(context.filesDir, "conquest"), userId, source, legacy)
            val bundle = ConquestCatalogMigration.prepare(directory, prefix,
                userId, teams.toSet(), source, legacy, catalog, allowProgress = true)
            val file = bundle.record
            sessions[file.canonicalPath]?.let {
                require(it.userId==userId && it.teams==teams && it.sourceType==source) { "점령 파일 세션의 사용자·팀·출처 충돌" }
                return@withContext it
            }
            val feature = LocalConquestFeature(teams.toSet(), catalog.roads.map { it.id }.toSet(), catalog.version,
                source, FileConquestStore(file))
            ConquestSession(userId, catalog, feature,
                ConquestLocationPipeline(feature, catalog, source, pendingStore =
                    FilePendingVisitsStore(File(file.parentFile, "${file.name}.pending"), catalog.version, source, userId)), teams,
                source==LocationSourceType.MOCK && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,source)
                .also { sessions[file.canonicalPath]=it }
        } }
    }
}

/** App-owned MOCK session with an isolated, debug-only demonstration. */
@Composable fun rememberConquestSession(userId: String,source:LocationSourceType=LocationSourceType.MOCK,recordPrefix:String="mock",
    retryToken:Int=0): Pair<ConquestSession?, String?> {
    val context = LocalContext.current.applicationContext
    var session by remember(userId,source,recordPrefix) { mutableStateOf<ConquestSession?>(null) }
    var error by remember(userId,source,recordPrefix) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId,source,recordPrefix,retryToken) {
        error = null
        try {
            session = ConquestSession.open(context, userId,source=source,recordPrefix=recordPrefix).also { it.refresh() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "점령 데이터를 열 수 없습니다. 기존 파일은 보존했습니다."
        }
    }
    LaunchedEffect(session, session?.pendingVisitCount) {
        val current = session ?: return@LaunchedEffect
        while (isActive) {
            current.refresh()
            delay(if (current.hasPendingVisits) 1_000 else 30_000)
        }
    }
    return session to error
}
